package org.assertlab.cocomut;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase 1 of the method context extraction pipeline.
 * 
 * Analyzes a Java project to detect:
 * - Build system (Maven or Gradle)
 * - Java version
 * - Source root directory
 * - Complete classpath from dependencies
 * - Whether project compiles successfully
 * 
 * Returns ProjectMetadata object containing all detected information for use by
 * downstream components (MethodIdentifier, CallGraphGenerator, etc.)
 */
public class ProjectAnalyzer {
    private static final long DEFAULT_COMPILE_TIMEOUT_SECONDS = 120;
    private final Path projectPath;
    private final boolean autoDetectJavaVersion;
    private final String buildSystem;
    private final boolean includeTests;

    /**
     * Create a ProjectAnalyzer for the given project path
     * @param projectPath Absolute path to project root
     */
    public ProjectAnalyzer(Path projectPath) {
        this(projectPath, true, "auto", true);
    }

    /**
     * Create a ProjectAnalyzer with custom configuration
     * @param projectPath Absolute path to project root
     * @param autoDetectJavaVersion Whether to auto-detect Java version
     * @param buildSystem Build system override ("auto", "maven", or "gradle")
     */
    public ProjectAnalyzer(Path projectPath, boolean autoDetectJavaVersion, String buildSystem) {
        this(projectPath, autoDetectJavaVersion, buildSystem, true);
    }

    public ProjectAnalyzer(Path projectPath, boolean autoDetectJavaVersion, String buildSystem, boolean includeTests) {
        this.projectPath = Objects.requireNonNull(projectPath, "projectPath cannot be null");
        this.autoDetectJavaVersion = autoDetectJavaVersion;
        this.buildSystem = buildSystem;
        this.includeTests = includeTests;

        if (!Files.isDirectory(projectPath)) {
            throw new IllegalArgumentException("Project path must be a directory: " + projectPath);
        }
    }

    /**
     * Analyze the project and return metadata
     * @return ProjectMetadata with all detected information
     * @throws IOException if analysis fails
     */
    public ProjectMetadata analyze() throws IOException {
        String detectedBuildSystem = detectBuildSystem();
        String javaVersion = detectJavaVersion(detectedBuildSystem);
        Path sourceRoot = findSourceRoot();
        boolean compiles = validateProjectCompiles();
        List<Path> mainClassOutputs = existingMainClassOutputDirs(detectedBuildSystem);
        List<Path> testClassOutputs = includeTests ? existingTestClassOutputDirs(detectedBuildSystem) : List.of();
        List<Path> dependencyClasspath = buildDependencyClasspath(detectedBuildSystem);
        List<Path> projectArtifactJars = existingProjectArtifactJars(detectedBuildSystem);
        List<Path> classpath = combinedClasspath(sourceRoot, mainClassOutputs, testClassOutputs,
                projectArtifactJars, dependencyClasspath);

        String projectName = projectPath.getFileName().toString();

        return new ProjectMetadata.Builder()
                .projectName(projectName)
                .projectPath(projectPath)
                .buildSystem(detectedBuildSystem)
                .javaVersion(javaVersion)
                .sourceRoot(sourceRoot)
                .classpath(classpath)
                .mainClassOutputs(mainClassOutputs)
                .testClassOutputs(testClassOutputs)
                .projectArtifactJars(projectArtifactJars)
                .dependencyClasspath(dependencyClasspath)
                .compiles(compiles)
                .compileStatus(compiles ? "BUILD SUCCESS" : "BUILD FAILED")
                .build();
    }

    /**
     * Detect whether project uses Maven or Gradle
     * @return "maven", "gradle", or "none"
     */
    private String detectBuildSystem() throws IOException {
        if ("maven".equalsIgnoreCase(buildSystem)) {
            return "maven";
        }
        if ("gradle".equalsIgnoreCase(buildSystem)) {
            return "gradle";
        }

        // Auto-detect: check for pom.xml first, then build.gradle
        if (Files.exists(projectPath.resolve("pom.xml"))) {
            return "maven";
        }
        if (Files.exists(projectPath.resolve("build.gradle")) ||
            Files.exists(projectPath.resolve("build.gradle.kts")) ||
            Files.exists(projectPath.resolve("settings.gradle")) ||
            Files.exists(projectPath.resolve("settings.gradle.kts"))) {
            return "gradle";
        }

        // No recognized build descriptor: a plain Java directory or pre-compiled
        // project. Return "none" so conventional source and bytecode layouts can
        // still be analyzed.
        return "none";
    }

    /**
     * Detect Java version from project configuration
     * @param buildSystem "maven" or "gradle"
     * @return Java version string (e.g., "17", "11", "8")
     */
    private String detectJavaVersion(String buildSystem) throws IOException {
        if (!autoDetectJavaVersion) {
            return "unknown";
        }

        if ("maven".equals(buildSystem)) {
            return detectJavaVersionFromMaven();
        } else if ("gradle".equals(buildSystem)) {
            return detectJavaVersionFromGradle();
        }

        return "unknown";
    }

    /**
     * Extract Java version from Maven pom.xml
     * Looks for common compiler properties and direct compiler-plugin settings.
     */
    private String detectJavaVersionFromMaven() throws IOException {
        Path pomPath = projectPath.resolve("pom.xml");
        if (!Files.exists(pomPath)) {
            return "unknown";
        }

        String pomContent = Files.readString(pomPath);
        java.util.Map<String, String> properties = mavenProperties(pomContent);

        for (String tag : List.of("maven.compiler.release", "maven.compiler.source",
                "maven.compiler.target", "java.version")) {
            String version = resolveMavenProperty(properties.get(tag), properties);
            if (!version.isBlank()) {
                return normalizeJavaVersion(version);
            }
        }

        for (String tag : List.of("release", "source", "target")) {
            Matcher matcher = Pattern.compile("<" + tag + ">([^<]+)</" + tag + ">").matcher(pomContent);
            if (matcher.find()) {
                String version = resolveMavenProperty(matcher.group(1).strip(), properties);
                if (!version.isBlank()) {
                    return normalizeJavaVersion(version);
                }
            }
        }

        return "unknown";
    }

    private static java.util.Map<String, String> mavenProperties(String pomContent) {
        java.util.Map<String, String> properties = new java.util.LinkedHashMap<>();
        Matcher matcher = Pattern.compile("<([A-Za-z0-9_.-]+)>\\s*([^<]+?)\\s*</\\1>").matcher(pomContent);
        while (matcher.find()) {
            properties.put(matcher.group(1), matcher.group(2).strip());
        }
        return properties;
    }

    private static String resolveMavenProperty(String value, java.util.Map<String, String> properties) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String trimmed = value.strip();
        Matcher matcher = Pattern.compile("^\\$\\{([^}]+)}$").matcher(trimmed);
        if (matcher.find()) {
            return properties.getOrDefault(matcher.group(1), "").strip();
        }
        return trimmed;
    }

    /**
     * Extract Java version from Gradle build.gradle or build.gradle.kts
     */
    private String detectJavaVersionFromGradle() throws IOException {
        Path buildGradle = projectPath.resolve("build.gradle");
        if (!Files.exists(buildGradle)) {
            buildGradle = projectPath.resolve("build.gradle.kts");
        }

        if (!Files.exists(buildGradle)) {
            return "unknown";
        }

        String buildContent = Files.readString(buildGradle);

        // Look for sourceCompatibility, targetCompatibility, or Java toolchain languageVersion.
        Pattern pattern = Pattern.compile(
                "(?:sourceCompatibility|targetCompatibility|java\\.sourceCompatibility)\\s*=\\s*(?:JavaVersion\\.)?['\"]?([^'\"\\s,;)]+)['\"]?");
        Matcher matcher = pattern.matcher(buildContent);
        if (matcher.find()) {
            return normalizeJavaVersion(matcher.group(1).strip());
        }

        matcher = Pattern.compile("languageVersion\\s*=\\s*JavaLanguageVersion\\.of\\((\\d+)\\)").matcher(buildContent);
        if (matcher.find()) {
            return normalizeJavaVersion(matcher.group(1));
        }

        return "unknown";
    }

    private static String normalizeJavaVersion(String raw) {
        if (raw == null || raw.isBlank()) {
            return "unknown";
        }
        String value = raw.strip()
                .replace("VERSION_", "")
                .replace("_", ".")
                .replaceAll("[^0-9.]", "");
        return value.startsWith("1.") ? value.substring(2) : value;
    }

    /**
     * Find the source root directory (typically src/main/java)
     * For multi-module projects, returns a collection of all source directories
     */
    private Path findSourceRoot() throws IOException {
        // First check for standard single-module structure
        Path standardSourceRoot = projectPath.resolve("src/main/java");
        if (Files.isDirectory(standardSourceRoot)) {
            return standardSourceRoot;
        }

        // Check for multi-module Maven structure
        Path pomXml = projectPath.resolve("pom.xml");
        if (Files.exists(pomXml)) {
            // Look for modules with src/main/java directories
            try (var stream = Files.list(projectPath)) {
                List<Path> moduleDirs = stream
                    .filter(Files::isDirectory)
                    .filter(dir -> !dir.getFileName().toString().startsWith("."))
                    .filter(dir -> {
                        Path moduleSource = dir.resolve("src/main/java");
                        return Files.exists(moduleSource) && Files.isDirectory(moduleSource);
                    })
                    .toList();
                
                if (!moduleDirs.isEmpty()) {
                    // For multi-module projects, return the first module's source root
                    // MethodIdentifier will need to be updated to handle multiple source roots
                    return moduleDirs.get(0).resolve("src/main/java");
                }
            }
        }

        // Fallback: look for any "src" directory with Java files
        Path srcDir = projectPath.resolve("src");
        if (Files.isDirectory(srcDir)) {
            return srcDir;
        }

        // Last resort: use project root
        return projectPath;
    }

    /**
     * Build classpath from Maven dependencies or Gradle configuration
     * @param buildSystem "maven" or "gradle"
     * @return List of JAR file paths
     */
    private List<Path> buildClasspath(String buildSystem) throws IOException {
        List<Path> classpath = new ArrayList<>();

        // Add source root
        Path sourceRoot = findSourceRoot();
        if (Files.exists(sourceRoot)) {
            classpath.add(sourceRoot);
        }

        classpath.addAll(existingClassOutputDirs(buildSystem));

        // Multi-module fallback: only descend into directories that the root pom
        // declares as <modules>. This avoids accidentally picking up unrelated nested
        // projects (e.g. when running pipeline tests from a workspace that contains
        // multiple sibling projects on disk).
        if ("maven".equals(buildSystem)) {
            for (Path module : collectMavenModuleDirs(projectPath)) {
                Path moduleClasses = module.resolve("target/classes");
                if (Files.exists(moduleClasses)) {
                    classpath.add(moduleClasses);
                }
                Path moduleTestClasses = module.resolve("target/test-classes");
                if (Files.exists(moduleTestClasses)) {
                    classpath.add(moduleTestClasses);
                }
            }
        }

        // Add build-tool-resolved dependency artifacts after compilation.
        if ("maven".equals(buildSystem)) {
            classpath.addAll(buildMavenClasspath());
        } else if ("gradle".equals(buildSystem)) {
            classpath.addAll(buildGradleClasspath());
        }

        // Remove duplicates while maintaining order
        List<Path> uniqueClasspath = new ArrayList<>(new LinkedHashSet<>(classpath));

        return uniqueClasspath;
    }

    private List<Path> buildDependencyClasspath(String buildSystem) throws IOException {
        if ("maven".equals(buildSystem)) {
            return buildMavenClasspath();
        }
        if ("gradle".equals(buildSystem)) {
            return buildGradleClasspath();
        }
        return List.of();
    }

    private static List<Path> combinedClasspath(Path sourceRoot,
                                                List<Path> mainClassOutputs,
                                                List<Path> testClassOutputs,
                                                List<Path> projectArtifactJars,
                                                List<Path> dependencyClasspath) {
        List<Path> classpath = new ArrayList<>();
        if (sourceRoot != null && Files.exists(sourceRoot)) {
            classpath.add(sourceRoot);
        }
        classpath.addAll(mainClassOutputs);
        classpath.addAll(testClassOutputs);
        classpath.addAll(projectArtifactJars);
        classpath.addAll(dependencyClasspath);
        return new ArrayList<>(new LinkedHashSet<>(classpath));
    }

    /**
     * Extract the exact Maven compile classpath for the analyzed project.
     */
    private List<Path> buildMavenClasspath() throws IOException {
        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
        String mvn = executableWithWrapper("mvn", isWindows);
        Path output = Files.createTempFile("cocomut-maven-classpath", ".txt");
        try {
            CommandResult result;
            try {
                result = runCommand(List.of(mvn, "-q", "-DincludeScope=" + (includeTests ? "test" : "compile"),
                        "-Dmdep.outputFile=" + output.toAbsolutePath(),
                        "dependency:build-classpath"));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return List.of();
            }
            if (result.exitCode() != 0 || !Files.isRegularFile(output)) {
                return List.of();
            }
            return parsePathList(Files.readString(output, StandardCharsets.UTF_8));
        } finally {
            Files.deleteIfExists(output);
        }
    }

    /**
     * Extract the exact Gradle runtime/compile classpath for the analyzed project.
     */
    private List<Path> buildGradleClasspath() throws IOException {
        List<Path> jars = new ArrayList<>();

        // Check build/libs directory
        Path buildLibs = projectPath.resolve("build/libs");
        if (Files.exists(buildLibs)) {
            try (var stream = Files.list(buildLibs)) {
                stream.filter(p -> p.toString().endsWith(".jar"))
                        .forEach(jars::add);
            }
        }

        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
        String gradle = executableWithWrapper("gradle", isWindows);
        Path initScript = Files.createTempFile("cocomut-gradle-classpath", ".gradle");
        Files.writeString(initScript, """
                allprojects {
                    tasks.register("printCocomutClasspath") {
                        doLast {
                            def names = %s
                            def files = [] as LinkedHashSet
                            names.each { n ->
                                if (configurations.findByName(n) != null) {
                                    try { files.addAll(configurations.getByName(n).resolve()) } catch (Throwable ignored) {}
                                }
                            }
                            println("COCOMUT_CLASSPATH=" + files.collect { it.absolutePath }.join(File.pathSeparator))
                        }
                    }
                }
                """.formatted(includeTests
                ? "[\"runtimeClasspath\", \"compileClasspath\", \"testRuntimeClasspath\", \"testCompileClasspath\"]"
                : "[\"runtimeClasspath\", \"compileClasspath\"]"), StandardCharsets.UTF_8);
        try {
            CommandResult result;
            try {
                result = runCommand(List.of(gradle, "--no-daemon", "-q",
                        "--init-script", initScript.toAbsolutePath().toString(), "printCocomutClasspath"));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new ArrayList<>(new LinkedHashSet<>(jars));
            }
            if (result.exitCode() == 0) {
                for (String line : result.output().split("\\R")) {
                    if (line.startsWith("COCOMUT_CLASSPATH=")) {
                        jars.addAll(parsePathList(line.substring("COCOMUT_CLASSPATH=".length())));
                    }
                }
            }
        } finally {
            Files.deleteIfExists(initScript);
        }
        return new ArrayList<>(new LinkedHashSet<>(jars));
    }

    /**
     * Validate that the project compiles successfully.
     *
     * Strategy:
     *   1. Invoke the build tool for Maven/Gradle projects so metadata reflects
     *      the current source tree instead of stale class files.
     *   2. On Windows the launcher is named
     *      "mvn.cmd" / "gradle.bat"; everywhere else the bare command name is
     *      used. The timeout is raised to 5 minutes so first-time compiles of
     *      medium-sized projects don't spuriously fail.
     *
     * @return true if compiled artifacts exist or the build succeeds
     */
    private boolean validateProjectCompiles() {
        String buildSystem;
        try {
            buildSystem = detectBuildSystem();
        } catch (IOException e) {
            return false;
        }

        if (!"maven".equals(buildSystem) && !"gradle".equals(buildSystem)) {
            return hasExistingCompiledArtifacts(buildSystem);
        }

        try {
            boolean isWindows = System.getProperty("os.name", "")
                    .toLowerCase()
                    .contains("win");
            List<String> command;

            if ("maven".equals(buildSystem)) {
                String mvn = executableWithWrapper("mvn", isWindows);
                command = List.of(mvn, "-q", "-DskipTests", "clean", includeTests ? "test-compile" : "compile");
            } else if ("gradle".equals(buildSystem)) {
                String gradle = executableWithWrapper("gradle", isWindows);
                command = List.of(gradle, "--no-daemon", "clean", includeTests ? "testClasses" : "classes",
                        "-x", "test", "--build-cache", "-q");
            } else {
                return false;
            }

            return runCommand(command).exitCode() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private String executableWithWrapper(String tool, boolean isWindows) {
        String wrapperName = switch (tool) {
            case "mvn" -> isWindows ? "mvnw.cmd" : "mvnw";
            case "gradle" -> isWindows ? "gradlew.bat" : "gradlew";
            default -> tool;
        };
        Path wrapper = projectPath.resolve(wrapperName);
        if (Files.isRegularFile(wrapper) && Files.isExecutable(wrapper)) {
            return wrapper.toAbsolutePath().toString();
        }
        return isWindows ? tool + ".cmd" : tool;
    }

    private CommandResult runCommand(List<String> command) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(projectPath.toFile());
        pb.redirectErrorStream(true);
        Process process = pb.start();
        StringBuilder output = new StringBuilder();
        Thread drainer = new Thread(() -> {
            try (var input = process.getInputStream()) {
                byte[] buffer = new byte[8192];
                int read;
                int remaining = 1_000_000;
                while ((read = input.read(buffer)) >= 0) {
                    if (remaining > 0) {
                        int keep = Math.min(read, remaining);
                        output.append(new String(buffer, 0, keep, StandardCharsets.UTF_8));
                        remaining -= keep;
                        if (remaining == 0) {
                            output.append("\n[CoCoMUT build log truncated after 1000000 bytes]\n");
                        }
                    }
                }
            } catch (IOException ignored) {
                // Build output is diagnostic only.
            }
        }, "cocomut-build-output-drainer");
        drainer.setDaemon(true);
        drainer.start();
        boolean completed = process.waitFor(compileTimeoutSeconds(), java.util.concurrent.TimeUnit.SECONDS);
        if (!completed) {
            process.destroyForcibly();
            drainer.join(1000);
            return new CommandResult(-1, output.toString());
        }
        drainer.join(1000);
        return new CommandResult(process.exitValue(), output.toString());
    }

    private static List<Path> parsePathList(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<Path> paths = new ArrayList<>();
        for (String item : raw.strip().split(Pattern.quote(java.io.File.pathSeparator))) {
            if (!item.isBlank()) {
                Path path = Path.of(item.strip());
                if (Files.exists(path)) {
                    paths.add(path);
                }
            }
        }
        return paths;
    }

    private record CommandResult(int exitCode, String output) {}

    private static long compileTimeoutSeconds() {
        String configured = System.getProperty("cocomut.compileTimeoutSeconds");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("COCOMUT_COMPILE_TIMEOUT_SECONDS");
        }
        if (configured == null || configured.isBlank()) {
            return DEFAULT_COMPILE_TIMEOUT_SECONDS;
        }
        try {
            long value = Long.parseLong(configured.trim());
            return value > 0 ? value : DEFAULT_COMPILE_TIMEOUT_SECONDS;
        } catch (NumberFormatException e) {
            return DEFAULT_COMPILE_TIMEOUT_SECONDS;
        }
    }

    /**
     * Check whether the project already has compiled .class files on disk.
     * Returns true if at least one .class file lives under the build system's
     * conventional output directory.
     */
    private boolean hasExistingCompiledArtifacts(String buildSystem) {
        try {
            return !existingMainClassOutputDirs(buildSystem).isEmpty()
                    || !existingProjectArtifactJars(buildSystem).isEmpty();
        } catch (IOException e) {
            return !existingMainClassOutputDirs(buildSystem).isEmpty();
        }
    }

    private List<Path> existingClassOutputDirs(String buildSystem) {
        List<Path> dirs = new ArrayList<>();
        dirs.addAll(existingMainClassOutputDirs(buildSystem));
        dirs.addAll(existingTestClassOutputDirs(buildSystem));
        return new ArrayList<>(new LinkedHashSet<>(dirs));
    }

    private List<Path> existingMainClassOutputDirs(String buildSystem) {
        List<Path> dirs = new ArrayList<>();
        if ("maven".equals(buildSystem)) {
            addClassDir(dirs, projectPath.resolve("target/classes"));
            for (Path module : collectMavenModuleDirs(projectPath)) {
                addClassDir(dirs, module.resolve("target/classes"));
            }
            return new ArrayList<>(new LinkedHashSet<>(dirs));
        } else if ("gradle".equals(buildSystem)) {
            for (Path candidate : List.of(
                    projectPath.resolve("build/classes/java/main"),
                    projectPath.resolve("build/classes/kotlin/main"),
                    projectPath.resolve("build/classes"))) {
                addClassDir(dirs, candidate);
            }
            addGradleOutputDirs(dirs, "main");
            return new ArrayList<>(new LinkedHashSet<>(dirs));
        } else {
            // "none": plain/pre-compiled project. Accept common bytecode output
            // folders, but only when they already contain .class files.
            for (String candidate : List.of("target/classes", "build/classes",
                    "out/production", "bin", "classes")) {
                addClassDir(dirs, projectPath.resolve(candidate));
            }
            return new ArrayList<>(new LinkedHashSet<>(dirs));
        }
    }

    private List<Path> existingTestClassOutputDirs(String buildSystem) {
        List<Path> dirs = new ArrayList<>();
        if ("maven".equals(buildSystem)) {
            addClassDir(dirs, projectPath.resolve("target/test-classes"));
            for (Path module : collectMavenModuleDirs(projectPath)) {
                addClassDir(dirs, module.resolve("target/test-classes"));
            }
        } else if ("gradle".equals(buildSystem)) {
            for (Path candidate : List.of(
                    projectPath.resolve("build/classes/java/test"),
                    projectPath.resolve("build/classes/kotlin/test"))) {
                addClassDir(dirs, candidate);
            }
            addGradleOutputDirs(dirs, "test");
        }
        return new ArrayList<>(new LinkedHashSet<>(dirs));
    }

    private List<Path> existingProjectArtifactJars(String buildSystem) throws IOException {
        List<Path> jars = new ArrayList<>();
        if ("maven".equals(buildSystem)) {
            addJarsFromDirectory(jars, projectPath.resolve("target"));
            for (Path module : collectMavenModuleDirs(projectPath)) {
                addJarsFromDirectory(jars, module.resolve("target"));
            }
        } else if ("gradle".equals(buildSystem)) {
            addJarsFromDirectory(jars, projectPath.resolve("build/libs"));
            try (var walk = Files.walk(projectPath, 4)) {
                for (Path dir : walk.filter(Files::isDirectory)
                        .filter(path -> path.endsWith(Path.of("build/libs")))
                        .toList()) {
                    addJarsFromDirectory(jars, dir);
                }
            } catch (IOException ignored) {
                // Class directories remain the primary project bytecode artifact.
            }
        } else {
            addJarsFromDirectory(jars, projectPath.resolve("lib"));
            addJarsFromDirectory(jars, projectPath);
        }
        return new ArrayList<>(new LinkedHashSet<>(jars));
    }

    private void addGradleOutputDirs(List<Path> dirs, String sourceSet) {
        try (var walk = Files.walk(projectPath, 6)) {
            for (Path dir : walk.filter(Files::isDirectory)
                    .filter(path -> path.endsWith(Path.of("build/classes/java/" + sourceSet))
                            || path.endsWith(Path.of("build/classes/kotlin/" + sourceSet)))
                    .toList()) {
                addClassDir(dirs, dir);
            }
        } catch (IOException ignored) {
            // Missing output dirs are reported through phase-1 bytecode counts.
        }
    }

    private void addClassDir(List<Path> dirs, Path dir) {
        if (Files.isDirectory(dir) && containsClassFile(dir)) {
            dirs.add(dir.toAbsolutePath().normalize());
        }
    }

    private static void addJarsFromDirectory(List<Path> jars, Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var stream = Files.list(dir)) {
            stream.filter(path -> path.toString().endsWith(".jar"))
                    .filter(path -> !path.getFileName().toString().endsWith("-sources.jar"))
                    .filter(path -> !path.getFileName().toString().endsWith("-javadoc.jar"))
                    .map(path -> path.toAbsolutePath().normalize())
                    .forEach(jars::add);
        }
    }

    private boolean containsClassFile(Path dir) {
        try (java.util.stream.Stream<Path> stream = Files.walk(dir)) {
            return stream.anyMatch(p -> p.toString().endsWith(".class"));
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Collect every Maven module directory reachable from the given project root by
     * parsing its pom.xml for <module>...</module> entries (transitively). Returns
     * an empty list if the root has no pom or no declared modules.
     */
    private static List<Path> collectMavenModuleDirs(Path root) {
        List<Path> modules = new ArrayList<>();
        java.util.ArrayDeque<Path> queue = new java.util.ArrayDeque<>();
        java.util.Set<Path> seen = new java.util.HashSet<>();
        queue.add(root);
        Pattern modulePattern = Pattern.compile("<module>\\s*([^<]+?)\\s*</module>");
        while (!queue.isEmpty()) {
            Path dir = queue.poll().toAbsolutePath().normalize();
            if (!seen.add(dir)) continue;
            Path pom = dir.resolve("pom.xml");
            if (!Files.isRegularFile(pom)) continue;
            String content;
            try {
                content = Files.readString(pom, StandardCharsets.UTF_8);
            } catch (IOException e) {
                continue;
            }
            Matcher m = modulePattern.matcher(content);
            while (m.find()) {
                Path child = dir.resolve(m.group(1)).normalize();
                if (Files.isDirectory(child)) {
                    modules.add(child);
                    queue.add(child);
                }
            }
        }
        return modules;
    }

    /**
     * Convenience method for analyzing a project given a string path
     */
    public static ProjectMetadata analyze(String projectPath) throws IOException {
        return new ProjectAnalyzer(Paths.get(projectPath)).analyze();
    }

    /**
     * Convenience method for analyzing with specific build system
     */
    public static ProjectMetadata analyze(String projectPath, String buildSystem) throws IOException {
        return new ProjectAnalyzer(Paths.get(projectPath), true, buildSystem).analyze();
    }
}
