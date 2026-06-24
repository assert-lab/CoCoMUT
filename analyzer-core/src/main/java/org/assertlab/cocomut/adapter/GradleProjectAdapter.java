package org.assertlab.cocomut.adapter;

import org.assertlab.cocomut.ProjectAnalyzer;
import org.assertlab.cocomut.ProjectMetadata;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Adapter for Gradle projects ({@code build.gradle} or {@code build.gradle.kts} present).
 *
 * <p>Resolves the <b>real</b> runtime/compile classpath by invoking Gradle with a temporary
 * init script (so the target project's build files are never modified). The init
 * script registers a task that prints runtime, compile, and test classpath entries; the
 * adapter parses those lines and merges them into the metadata.
 *
 * <p>This supplements {@link ProjectAnalyzer}'s post-build Gradle metadata with
 * the resolved Gradle dependency classpath. If Gradle is unavailable, offline, or
 * the invocation fails for any reason, the adapter falls back to the base metadata.
 */
public class GradleProjectAdapter implements ProjectAdapter {

    private static final long GRADLE_TIMEOUT_MIN = 4;
    private static final String CP_PREFIX = "ANALYZER_CP:";

    private final Path projectPath;

    public GradleProjectAdapter(Path projectPath) {
        this.projectPath = projectPath;
    }

    /** Matches Groovy or Kotlin DSL Gradle build files. */
    @Override
    public boolean canHandle(Path path) {
        return Files.exists(path.resolve("build.gradle"))
                || Files.exists(path.resolve("build.gradle.kts"));
    }

    @Override
    public ProjectMetadata toMetadata() throws IOException {
        ProjectMetadata base = new ProjectAnalyzer(projectPath).analyze();

        List<Path> nativeCp = resolveGradleClasspath();
        if (nativeCp.isEmpty()) {
            System.out.println("[GradleProjectAdapter] native classpath resolution "
                    + "unavailable — using base metadata");
            return base;
        }

        // Merge: native entries first, then anything the base detected (deduped).
        Set<Path> merged = new LinkedHashSet<>(nativeCp);
        merged.addAll(base.getClasspath());
        System.out.printf("[GradleProjectAdapter] resolved %d classpath entries via Gradle%n",
                merged.size());

        return ProjectMetadata.Builder.from(base)
                .classpath(new ArrayList<>(merged))
                .build();
    }

    /**
     * Run Gradle with an init script to print the compile classpath.
     * Returns an empty list on any failure (Gradle missing, offline, timeout, etc.).
     */
    private List<Path> resolveGradleClasspath() {
        Path initScript = null;
        try {
            initScript = writeInitScript();
            List<String> cmd = new ArrayList<>();
            cmd.add(gradleExecutable());
            cmd.add("--init-script");
            cmd.add(initScript.toString());
            cmd.add("analyzerPrintClasspath");
            cmd.add("-q");
            cmd.add("--console=plain");

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(projectPath.toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();

            StringBuilder output = new StringBuilder();
            Thread drainer = new Thread(() -> {
                try (var input = p.getInputStream()) {
                    output.append(new String(input.readAllBytes(), StandardCharsets.UTF_8));
                } catch (IOException ignored) {
                    // Gradle output is diagnostic only.
                }
            }, "cocomut-gradle-adapter-output-drainer");
            drainer.setDaemon(true);
            drainer.start();

            if (!p.waitFor(GRADLE_TIMEOUT_MIN, TimeUnit.MINUTES)) {
                p.destroyForcibly();
                drainer.join(1000);
                return List.of();
            }
            drainer.join(1000);
            if (p.exitValue() != 0) {
                return List.of();
            }

            List<Path> classpath = new ArrayList<>();
            for (String line : output.toString().split("\\R")) {
                if (line.startsWith(CP_PREFIX)) {
                    Path jar = Path.of(line.substring(CP_PREFIX.length()).trim());
                    if (Files.exists(jar)) {
                        classpath.add(jar);
                    }
                }
            }
            return classpath;
        } catch (Exception e) {
            return List.of();
        } finally {
            if (initScript != null) {
                try { Files.deleteIfExists(initScript); } catch (IOException ignored) { }
            }
        }
    }

    /**
     * Prefer the project's Gradle wrapper (pinned version, no install needed);
     * fall back to a {@code gradle} on PATH.
     */
    private String gradleExecutable() {
        boolean win = System.getProperty("os.name", "").toLowerCase().contains("win");
        Path wrapper = projectPath.resolve(win ? "gradlew.bat" : "gradlew");
        if (Files.exists(wrapper)) {
            return wrapper.toAbsolutePath().toString();
        }
        return win ? "gradle.bat" : "gradle";
    }

    /**
     * Init script that registers a classpath-printing task on every project,
     * without touching the target's build files.
     */
    private Path writeInitScript() throws IOException {
        String script =
                "allprojects {\n" +
                "    tasks.register('analyzerPrintClasspath') {\n" +
                "        doLast {\n" +
                "            def names = ['runtimeClasspath', 'compileClasspath', 'testRuntimeClasspath', 'testCompileClasspath']\n" +
                "            def files = [] as LinkedHashSet\n" +
                "            names.each { n ->\n" +
                "                def cp = configurations.findByName(n)\n" +
                "                if (cp != null) { try { files.addAll(cp.resolve()) } catch (Exception ignored) { } }\n" +
                "            }\n" +
                "            files.each { println '" + CP_PREFIX + "' + it.absolutePath }\n" +
                "        }\n" +
                "    }\n" +
                "}\n";
        Path tmp = Files.createTempFile("analyzer-init", ".gradle");
        Files.writeString(tmp, script, StandardCharsets.UTF_8);
        return tmp;
    }
}
