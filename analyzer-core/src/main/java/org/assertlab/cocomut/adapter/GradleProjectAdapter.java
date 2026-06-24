package org.assertlab.cocomut.adapter;

import org.assertlab.cocomut.ContextRequest;
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
    private static final String SOURCE_PREFIX = "ANALYZER_SOURCE:";
    private static final String TEST_SOURCE_PREFIX = "ANALYZER_TEST_SOURCE:";
    private static final String OUTPUT_PREFIX = "ANALYZER_OUTPUT:";
    private static final String TEST_OUTPUT_PREFIX = "ANALYZER_TEST_OUTPUT:";
    private static final String JAVA_PREFIX = "ANALYZER_JAVA:";

    private final Path projectPath;

    public GradleProjectAdapter(Path projectPath) {
        this.projectPath = projectPath;
    }

    /** Matches Groovy or Kotlin DSL Gradle build files. */
    @Override
    public boolean canHandle(Path path) {
        return Files.exists(path.resolve("build.gradle"))
                || Files.exists(path.resolve("build.gradle.kts"))
                || Files.exists(path.resolve("settings.gradle"))
                || Files.exists(path.resolve("settings.gradle.kts"));
    }

    @Override
    public ProjectMetadata toMetadata() throws IOException {
        ProjectMetadata base = new ProjectAnalyzer(projectPath).analyze();
        return enrichWithGradleModel(base, false);
    }

    @Override
    public ProjectMetadata toMetadata(ContextRequest request) throws IOException {
        ProjectMetadata base = new ProjectAnalyzer(request).analyze();
        return enrichWithGradleModel(base, request.skipBuild());
    }

    private ProjectMetadata enrichWithGradleModel(ProjectMetadata base, boolean skipGradleInvocation) {
        if (skipGradleInvocation) {
            System.out.println("[GradleProjectAdapter] --skip-build active; Gradle metadata task not executed");
            return base;
        }

        GradleModel nativeModel = resolveGradleModel();
        if (nativeModel.isEmpty()) {
            System.out.println("[GradleProjectAdapter] native classpath resolution "
                    + "unavailable — using base metadata");
            return base;
        }

        // Merge: native entries first, then anything the base detected (deduped).
        Set<Path> merged = new LinkedHashSet<>(nativeModel.classpath());
        merged.addAll(base.getClasspath());
        System.out.printf("[GradleProjectAdapter] resolved %d classpath entries via Gradle%n",
                merged.size());

        Set<Path> sourceRoots = new LinkedHashSet<>(base.getSourceRoots());
        sourceRoots.addAll(nativeModel.sourceRoots());
        Set<Path> testSourceRoots = new LinkedHashSet<>(base.getTestSourceRoots());
        testSourceRoots.addAll(nativeModel.testSourceRoots());
        Set<Path> mainOutputs = new LinkedHashSet<>(base.getMainClassOutputs());
        mainOutputs.addAll(nativeModel.mainOutputs());
        Set<Path> testOutputs = new LinkedHashSet<>(base.getTestClassOutputs());
        testOutputs.addAll(nativeModel.testOutputs());
        Set<Path> dependencies = new LinkedHashSet<>(base.getDependencyClasspath());
        Set<Path> projectOutputs = new LinkedHashSet<>();
        projectOutputs.addAll(mainOutputs);
        projectOutputs.addAll(testOutputs);
        nativeModel.classpath().stream()
                .filter(path -> !projectOutputs.contains(path))
                .forEach(dependencies::add);

        return ProjectMetadata.Builder.from(base)
                .javaVersion(!"unknown".equals(nativeModel.javaVersion()) ? nativeModel.javaVersion() : base.getJavaVersion())
                .sourceRoots(new ArrayList<>(sourceRoots))
                .testSourceRoots(new ArrayList<>(testSourceRoots))
                .classpath(new ArrayList<>(merged))
                .mainClassOutputs(new ArrayList<>(mainOutputs))
                .testClassOutputs(new ArrayList<>(testOutputs))
                .dependencyClasspath(new ArrayList<>(dependencies))
                .build();
    }

    /**
     * Run Gradle with an init script to print the compile classpath.
     * Returns an empty list on any failure (Gradle missing, offline, timeout, etc.).
     */
    private GradleModel resolveGradleModel() {
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
                return GradleModel.empty();
            }
            drainer.join(1000);
            if (p.exitValue() != 0) {
                return GradleModel.empty();
            }

            List<Path> classpath = new ArrayList<>();
            List<Path> sourceRoots = new ArrayList<>();
            List<Path> testSourceRoots = new ArrayList<>();
            List<Path> mainOutputs = new ArrayList<>();
            List<Path> testOutputs = new ArrayList<>();
            String javaVersion = "unknown";
            for (String line : output.toString().split("\\R")) {
                if (line.startsWith(CP_PREFIX)) {
                    Path jar = Path.of(line.substring(CP_PREFIX.length()).trim());
                    if (Files.exists(jar)) {
                        classpath.add(jar);
                    }
                } else if (line.startsWith(SOURCE_PREFIX)) {
                    addExistingDir(sourceRoots, line.substring(SOURCE_PREFIX.length()));
                } else if (line.startsWith(TEST_SOURCE_PREFIX)) {
                    addExistingDir(testSourceRoots, line.substring(TEST_SOURCE_PREFIX.length()));
                } else if (line.startsWith(OUTPUT_PREFIX)) {
                    addExistingDir(mainOutputs, line.substring(OUTPUT_PREFIX.length()));
                } else if (line.startsWith(TEST_OUTPUT_PREFIX)) {
                    addExistingDir(testOutputs, line.substring(TEST_OUTPUT_PREFIX.length()));
                } else if (line.startsWith(JAVA_PREFIX)) {
                    String detected = line.substring(JAVA_PREFIX.length()).trim();
                    if (!detected.isBlank() && !"null".equals(detected)) {
                        javaVersion = detected;
                    }
                }
            }
            return new GradleModel(unique(sourceRoots), unique(testSourceRoots), unique(mainOutputs),
                    unique(testOutputs), unique(classpath), javaVersion);
        } catch (Exception e) {
            return GradleModel.empty();
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
     * Init script that registers a model-printing task on the root project,
     * without touching the target's build files.
     */
    private Path writeInitScript() throws IOException {
        String script =
                "gradle.projectsEvaluated {\n" +
                "    rootProject.tasks.register('analyzerPrintClasspath') {\n" +
                "        doLast {\n" +
                "            allprojects.each { p ->\n" +
                "                def names = ['runtimeClasspath', 'compileClasspath', 'testRuntimeClasspath', 'testCompileClasspath']\n" +
                "                def files = [] as LinkedHashSet\n" +
                "                names.each { n ->\n" +
                "                    def cp = p.configurations.findByName(n)\n" +
                "                    if (cp != null) { try { files.addAll(cp.resolve()) } catch (Exception ignored) { } }\n" +
                "                }\n" +
                "                files.each { println '" + CP_PREFIX + "' + it.absolutePath }\n" +
                "                def javaExt = p.extensions.findByName('java')\n" +
                "                if (javaExt != null) {\n" +
                "                    def toolchain = null\n" +
                "                    try { toolchain = javaExt.toolchain?.languageVersion?.orNull?.asInt()?.toString() } catch (Throwable ignored) { }\n" +
                "                    def sourceCompat = null\n" +
                "                    try { sourceCompat = javaExt.sourceCompatibility?.toString() } catch (Throwable ignored) { }\n" +
                "                    println '" + JAVA_PREFIX + "' + (toolchain ?: sourceCompat ?: '')\n" +
                "                    try {\n" +
                "                        javaExt.sourceSets.each { ss ->\n" +
                "                            ss.allJava.srcDirs.each { d -> if (d.exists()) println((ss.name == 'test' ? '" + TEST_SOURCE_PREFIX + "' : '" + SOURCE_PREFIX + "') + d.absolutePath) }\n" +
                "                            ss.output.classesDirs.files.each { d -> if (d.exists()) println((ss.name == 'test' ? '" + TEST_OUTPUT_PREFIX + "' : '" + OUTPUT_PREFIX + "') + d.absolutePath) }\n" +
                "                        }\n" +
                "                    } catch (Throwable ignored) { }\n" +
                "            }\n" +
                "        }\n" +
                "    }\n" +
                "}\n";
        Path tmp = Files.createTempFile("analyzer-init", ".gradle");
        Files.writeString(tmp, script, StandardCharsets.UTF_8);
        return tmp;
    }

    private static void addExistingDir(List<Path> output, String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        Path path = Path.of(raw.trim());
        if (Files.isDirectory(path)) {
            output.add(path.toAbsolutePath().normalize());
        }
    }

    private static List<Path> unique(List<Path> paths) {
        return new ArrayList<>(new LinkedHashSet<>(paths));
    }

    private record GradleModel(List<Path> sourceRoots,
                               List<Path> testSourceRoots,
                               List<Path> mainOutputs,
                               List<Path> testOutputs,
                               List<Path> classpath,
                               String javaVersion) {
        static GradleModel empty() {
            return new GradleModel(List.of(), List.of(), List.of(), List.of(), List.of(), "unknown");
        }

        boolean isEmpty() {
            return sourceRoots.isEmpty()
                    && testSourceRoots.isEmpty()
                    && mainOutputs.isEmpty()
                    && testOutputs.isEmpty()
                    && classpath.isEmpty()
                    && "unknown".equals(javaVersion);
        }
    }
}
