package org.assertlab.cocomut.adapter;

import org.assertlab.cocomut.ContextRequest;
import org.assertlab.cocomut.GradleModelReport;
import org.assertlab.cocomut.ModuleSourceSet;
import org.assertlab.cocomut.ProjectAnalyzer;
import org.assertlab.cocomut.ProjectMetadata;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
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
    private static final String DIAGNOSTIC_PREFIX = "ANALYZER_DIAGNOSTIC:";
    private static final String PROJECT_PREFIX = "ANALYZER_PROJECT:";
    private static final String SOURCESET_PREFIX = "ANALYZER_SOURCESET:";
    private static final String SOURCESET_SOURCE_PREFIX = "ANALYZER_SS_SOURCE:";
    private static final String SOURCESET_OUTPUT_PREFIX = "ANALYZER_SS_OUTPUT:";
    private static final String SOURCESET_CP_PREFIX = "ANALYZER_SS_CP:";

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
        return new ProjectAnalyzer(projectPath).analyze();
    }

    @Override
    public ProjectMetadata toMetadata(ContextRequest request) throws IOException {
        ProjectMetadata base = new ProjectAnalyzer(request).analyze();
        return enrichWithGradleModel(base, request);
    }

    private ProjectMetadata enrichWithGradleModel(ProjectMetadata base, ContextRequest request) {
        if (request.skipBuild()) {
            System.out.println("[GradleProjectAdapter] --skip-build active; Gradle metadata task not executed");
            return ProjectMetadata.Builder.from(base)
                    .gradleModelReport(GradleModelReport.skipped("--skip-build"))
                    .build();
        }

        boolean includeTests = includeTests(request);
        GradleModel nativeModel = resolveGradleModel(includeTests);
        if (!nativeModel.report().succeeded()) {
            System.out.println("[GradleProjectAdapter] native classpath resolution "
                    + "unavailable — using base metadata");
            return ProjectMetadata.Builder.from(base)
                    .gradleModelReport(nativeModel.report())
                    .moduleSourceSets(nativeModel.moduleSourceSets())
                    .build();
        }

        // Merge: native entries first, then anything the base detected (deduped).
        Set<Path> merged = new LinkedHashSet<>(nativeModel.classpath());
        merged.addAll(base.getClasspath());
        System.out.printf("[GradleProjectAdapter] resolved %d classpath entries via Gradle%n",
                merged.size());

        Set<Path> sourceRoots = new LinkedHashSet<>(nativeModel.sourceRoots());
        Set<Path> testSourceRoots = new LinkedHashSet<>(nativeModel.testSourceRoots());
        Set<Path> mainOutputs = new LinkedHashSet<>(nativeModel.mainOutputs());
        Set<Path> testOutputs = new LinkedHashSet<>(nativeModel.testOutputs());
        Set<Path> dependencies = new LinkedHashSet<>(base.getDependencyClasspath());
        Set<Path> projectOutputs = new LinkedHashSet<>();
        projectOutputs.addAll(mainOutputs);
        projectOutputs.addAll(testOutputs);
        nativeModel.classpath().stream()
                .filter(path -> !projectOutputs.contains(path))
                .forEach(dependencies::add);

        boolean bytecodeAvailable = !mainOutputs.isEmpty()
                || !testOutputs.isEmpty()
                || !base.getProjectArtifactJars().isEmpty();
        boolean analysisCanProceed = bytecodeAvailable && canTrustBytecodeForAnalysis(base);
        boolean explicitProjectBytecode = !base.getExplicitClassOutputDirs().isEmpty()
                || !base.getExplicitTestClassOutputDirs().isEmpty()
                || !base.getExplicitProjectJars().isEmpty();
        Path authoritativeRoot = !sourceRoots.isEmpty()
                ? sourceRoots.iterator().next()
                : (!testSourceRoots.isEmpty() ? testSourceRoots.iterator().next() : base.getSourceRoot());

        return ProjectMetadata.Builder.from(base)
                .javaVersion(!"unknown".equals(nativeModel.javaVersion()) ? nativeModel.javaVersion() : base.getJavaVersion())
                .sourceRoot(authoritativeRoot)
                .sourceRoots(new ArrayList<>(sourceRoots))
                .testSourceRoots(new ArrayList<>(testSourceRoots))
                .classpath(new ArrayList<>(merged))
                .mainClassOutputs(new ArrayList<>(mainOutputs))
                .testClassOutputs(new ArrayList<>(testOutputs))
                .dependencyClasspath(new ArrayList<>(dependencies))
                .gradleModelReport(nativeModel.report())
                .moduleSourceSets(nativeModel.moduleSourceSets())
                .compiles(base.isBuildSucceeded())
                .compileStatus(compileStatus(base, bytecodeAvailable))
                .bytecodeAvailable(bytecodeAvailable)
                .bytecodeOrigin(bytecodeOrigin(explicitProjectBytecode, base, bytecodeAvailable))
                .analysisCanProceed(analysisCanProceed)
                .artifactOrigins(artifactOrigins(base, mainOutputs, testOutputs, base.getProjectArtifactJars(), dependencies))
                .build();
    }

    private static java.util.Map<String, String> artifactOrigins(ProjectMetadata base,
                                                                  Set<Path> mainOutputs,
                                                                  Set<Path> testOutputs,
                                                                  List<Path> projectJars,
                                                                  Set<Path> dependencies) {
        java.util.LinkedHashMap<String, String> origins = new java.util.LinkedHashMap<>(base.getArtifactOrigins());
        String projectOrigin = base.isBuildSucceeded() ? "build_model_discovered" : "preexisting";
        mainOutputs.forEach(path -> origins.put(path.toAbsolutePath().normalize().toString(), projectOrigin));
        testOutputs.forEach(path -> origins.put(path.toAbsolutePath().normalize().toString(), projectOrigin));
        projectJars.forEach(path -> origins.put(path.toAbsolutePath().normalize().toString(), projectOrigin));
        dependencies.forEach(path -> origins.put(path.toAbsolutePath().normalize().toString(), "dependency"));
        return origins;
    }

    private static boolean includeTests(ContextRequest request) {
        return request.sourceSets() == null
                || request.sourceSets().isEmpty()
                || request.sourceSets().stream().anyMatch(set -> !"main".equals(set));
    }

    private static boolean canTrustBytecodeForAnalysis(ProjectMetadata base) {
        if (!base.isBuildAttempted()) {
            return true;
        }
        if (base.isBuildSucceeded()) {
            return true;
        }
        return base.isAllowPreexistingBytecodeAfterBuildFailure();
    }

    private static String compileStatus(ProjectMetadata base, boolean bytecodeAvailable) {
        if (!base.isBuildAttempted()) {
            return bytecodeAvailable ? base.getCompileStatus().replace("; NO PROJECT BYTECODE", "; PROJECT BYTECODE AVAILABLE")
                    : base.getCompileStatus();
        }
        if (base.isBuildSucceeded()) {
            return bytecodeAvailable ? "BUILD SUCCESS" : "BUILD SUCCESS; NO PROJECT BYTECODE";
        }
        return bytecodeAvailable ? "BUILD FAILED; PREEXISTING PROJECT BYTECODE AVAILABLE"
                : "BUILD FAILED; NO PROJECT BYTECODE";
    }

    private static String bytecodeOrigin(boolean explicitProjectBytecode, ProjectMetadata base, boolean available) {
        if (!available) {
            return "none";
        }
        if (explicitProjectBytecode) {
            return "explicit";
        }
        if (base.isBuildSucceeded()) {
            return "generated_this_run";
        }
        return "preexisting";
    }

    /**
     * Run Gradle with an init script to print the compile classpath.
     * Returns an empty list on any failure (Gradle missing, offline, timeout, etc.).
     */
    private GradleModel resolveGradleModel(boolean includeTests) {
        Path initScript = null;
        try {
            initScript = writeInitScript(includeTests);
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
                return GradleModel.failed(true, "Gradle model task timed out");
            }
            drainer.join(1000);
            if (p.exitValue() != 0) {
                return GradleModel.failed(false, firstLines(output.toString()));
            }

            List<Path> classpath = new ArrayList<>();
            List<Path> sourceRoots = new ArrayList<>();
            List<Path> testSourceRoots = new ArrayList<>();
            List<Path> mainOutputs = new ArrayList<>();
            List<Path> testOutputs = new ArrayList<>();
            String javaVersion = "unknown";
            List<String> diagnostics = new ArrayList<>();
            Map<String, ModuleSourceSetBuilder> sourceSetBuilders = new LinkedHashMap<>();
            int resolvedProjects = 0;
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
                } else if (line.startsWith(PROJECT_PREFIX)) {
                    resolvedProjects++;
                } else if (line.startsWith(DIAGNOSTIC_PREFIX)) {
                    diagnostics.add(line.substring(DIAGNOSTIC_PREFIX.length()).trim());
                } else if (line.startsWith(SOURCESET_PREFIX)) {
                    String[] parts = line.substring(SOURCESET_PREFIX.length()).split("\\t", 3);
                    if (parts.length == 3) {
                        builder(sourceSetBuilders, parts[0], parts[1]).javaVersion(parts[2]);
                    }
                } else if (line.startsWith(SOURCESET_SOURCE_PREFIX)) {
                    addSourceSetPath(sourceSetBuilders, line.substring(SOURCESET_SOURCE_PREFIX.length()), SourceSetPathRole.SOURCE);
                } else if (line.startsWith(SOURCESET_OUTPUT_PREFIX)) {
                    addSourceSetPath(sourceSetBuilders, line.substring(SOURCESET_OUTPUT_PREFIX.length()), SourceSetPathRole.OUTPUT);
                } else if (line.startsWith(SOURCESET_CP_PREFIX)) {
                    addSourceSetPath(sourceSetBuilders, line.substring(SOURCESET_CP_PREFIX.length()), SourceSetPathRole.CLASSPATH);
                }
            }
            GradleModelReport report = new GradleModelReport(true, true, false, !diagnostics.isEmpty(),
                    resolvedProjects, diagnostics,
                    diagnostics.stream().filter(value -> value.startsWith("classpath:")).toList());
            List<ModuleSourceSet> sourceSets = sourceSetBuilders.values().stream()
                    .map(ModuleSourceSetBuilder::build)
                    .toList();
            if (sourceSets.isEmpty()) {
                sourceSets = List.of(new ModuleSourceSet(":", includeTests ? "main+test" : "main",
                        unique(sourceRoots), concat(unique(mainOutputs), unique(testOutputs)), unique(classpath), javaVersion));
            }
            return new GradleModel(unique(sourceRoots), unique(testSourceRoots), unique(mainOutputs),
                    unique(testOutputs), unique(classpath), javaVersion, report, sourceSets);
        } catch (Exception e) {
            return GradleModel.failed(false, e.getClass().getSimpleName());
        } finally {
            if (initScript != null) {
                try { Files.deleteIfExists(initScript); } catch (IOException ignored) { }
            }
        }
    }

    private static String firstLines(String output) {
        if (output == null || output.isBlank()) {
            return "Gradle model task failed";
        }
        return output.lines().limit(5).collect(java.util.stream.Collectors.joining(" | "));
    }

    private static void addSourceSetPath(Map<String, ModuleSourceSetBuilder> builders,
                                         String raw,
                                         SourceSetPathRole role) {
        String[] parts = raw.split("\\t", 3);
        if (parts.length != 3) {
            return;
        }
        Path path = Path.of(parts[2].trim());
        if (!Files.exists(path)) {
            return;
        }
        ModuleSourceSetBuilder builder = builder(builders, parts[0], parts[1]);
        switch (role) {
            case SOURCE -> builder.sources.add(path.toAbsolutePath().normalize());
            case OUTPUT -> builder.outputs.add(path.toAbsolutePath().normalize());
            case CLASSPATH -> builder.classpath.add(path.toAbsolutePath().normalize());
        }
    }

    private static ModuleSourceSetBuilder builder(Map<String, ModuleSourceSetBuilder> builders,
                                                  String projectPath,
                                                  String sourceSet) {
        String key = projectPath + "\t" + sourceSet;
        return builders.computeIfAbsent(key, ignored -> new ModuleSourceSetBuilder(projectPath, sourceSet));
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
    private Path writeInitScript(boolean includeTests) throws IOException {
        String configurationNames = includeTests
                ? "['runtimeClasspath', 'compileClasspath', 'testRuntimeClasspath', 'testCompileClasspath']"
                : "['runtimeClasspath', 'compileClasspath']";
        String sourceSetNames = includeTests ? "['main', 'test']" : "['main']";
        String script =
                "gradle.projectsEvaluated {\n" +
                "    rootProject.tasks.register('analyzerPrintClasspath') {\n" +
                "        doLast {\n" +
                "            rootProject.allprojects.each { p ->\n" +
                "                println '" + PROJECT_PREFIX + "' + p.path\n" +
                "                def names = " + configurationNames + "\n" +
                "                def sourceSetNames = " + sourceSetNames + "\n" +
                "                def files = [] as LinkedHashSet\n" +
                "                names.each { n ->\n" +
                "                    def cp = p.configurations.findByName(n)\n" +
                "                    if (cp != null) { try { files.addAll(cp.resolve()) } catch (Exception e) { println '" + DIAGNOSTIC_PREFIX + "classpath:' + p.path + ':' + n + ':' + e.class.simpleName } }\n" +
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
                "                            if (sourceSetNames.contains(ss.name)) {\n" +
                "                                def javaLevel = (toolchain ?: sourceCompat ?: '')\n" +
                "                                println '" + SOURCESET_PREFIX + "' + p.path + '\\t' + ss.name + '\\t' + javaLevel\n" +
                "                                ss.allJava.srcDirs.each { d -> if (d.exists()) println((ss.name == 'test' ? '" + TEST_SOURCE_PREFIX + "' : '" + SOURCE_PREFIX + "') + d.absolutePath) }\n" +
                "                                ss.allJava.srcDirs.each { d -> if (d.exists()) println '" + SOURCESET_SOURCE_PREFIX + "' + p.path + '\\t' + ss.name + '\\t' + d.absolutePath }\n" +
                "                                ss.output.classesDirs.files.each { d -> if (d.exists()) println((ss.name == 'test' ? '" + TEST_OUTPUT_PREFIX + "' : '" + OUTPUT_PREFIX + "') + d.absolutePath) }\n" +
                "                                ss.output.classesDirs.files.each { d -> if (d.exists()) println '" + SOURCESET_OUTPUT_PREFIX + "' + p.path + '\\t' + ss.name + '\\t' + d.absolutePath }\n" +
                "                                def ssCp = [] as LinkedHashSet\n" +
                "                                try { ssCp.addAll(ss.compileClasspath.files) } catch (Throwable e) { println '" + DIAGNOSTIC_PREFIX + "sourceSetClasspath:' + p.path + ':' + ss.name + ':compile:' + e.class.simpleName }\n" +
                "                                try { ssCp.addAll(ss.runtimeClasspath.files) } catch (Throwable e) { println '" + DIAGNOSTIC_PREFIX + "sourceSetClasspath:' + p.path + ':' + ss.name + ':runtime:' + e.class.simpleName }\n" +
                "                                ssCp.each { f -> if (f.exists()) println '" + SOURCESET_CP_PREFIX + "' + p.path + '\\t' + ss.name + '\\t' + f.absolutePath }\n" +
                "                            }\n" +
                "                        }\n" +
                "                    } catch (Throwable ignored) { }\n" +
                "                }\n" +
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

    private static List<Path> concat(List<Path> first, List<Path> second) {
        List<Path> paths = new ArrayList<>();
        paths.addAll(first == null ? List.of() : first);
        paths.addAll(second == null ? List.of() : second);
        return unique(paths);
    }

    private enum SourceSetPathRole {
        SOURCE,
        OUTPUT,
        CLASSPATH
    }

    private static final class ModuleSourceSetBuilder {
        private final String projectPath;
        private final String sourceSet;
        private final List<Path> sources = new ArrayList<>();
        private final List<Path> outputs = new ArrayList<>();
        private final List<Path> classpath = new ArrayList<>();
        private String javaVersion = "unknown";

        ModuleSourceSetBuilder(String projectPath, String sourceSet) {
            this.projectPath = projectPath;
            this.sourceSet = sourceSet;
        }

        void javaVersion(String value) {
            if (value != null && !value.isBlank() && !"null".equals(value)) {
                this.javaVersion = value;
            }
        }

        ModuleSourceSet build() {
            return new ModuleSourceSet(projectPath, sourceSet,
                    unique(sources), unique(outputs), unique(classpath), javaVersion);
        }
    }

    private record GradleModel(List<Path> sourceRoots,
                               List<Path> testSourceRoots,
                               List<Path> mainOutputs,
                               List<Path> testOutputs,
                               List<Path> classpath,
                               String javaVersion,
                               GradleModelReport report,
                               List<ModuleSourceSet> moduleSourceSets) {
        static GradleModel failed(boolean timedOut, String diagnostic) {
            return new GradleModel(List.of(), List.of(), List.of(), List.of(), List.of(), "unknown",
                    new GradleModelReport(true, false, timedOut, true, 0,
                            diagnostic == null || diagnostic.isBlank() ? List.of() : List.of(diagnostic),
                            List.of()),
                    List.of());
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
