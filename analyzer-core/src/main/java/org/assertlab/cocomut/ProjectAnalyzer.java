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
    private final boolean attemptCompile;

    /**
     * Create a ProjectAnalyzer for the given project path
     * @param projectPath Absolute path to project root
     */
    public ProjectAnalyzer(Path projectPath) {
        this(projectPath, true, "auto", false);
    }

    /**
     * Create a ProjectAnalyzer with custom configuration
     * @param projectPath Absolute path to project root
     * @param autoDetectJavaVersion Whether to auto-detect Java version
     * @param buildSystem Build system override ("auto", "maven", or "gradle")
     */
    public ProjectAnalyzer(Path projectPath, boolean autoDetectJavaVersion, String buildSystem) {
        this(projectPath, autoDetectJavaVersion, buildSystem, false);
    }

    public ProjectAnalyzer(Path projectPath, boolean autoDetectJavaVersion, String buildSystem, boolean attemptCompile) {
        this.projectPath = Objects.requireNonNull(projectPath, "projectPath cannot be null");
        this.autoDetectJavaVersion = autoDetectJavaVersion;
        this.buildSystem = buildSystem;
        this.attemptCompile = attemptCompile;

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
        List<Path> classpath = buildClasspath(detectedBuildSystem);
        boolean compiles = validateProjectCompiles();

        String projectName = projectPath.getFileName().toString();

        return new ProjectMetadata.Builder()
                .projectName(projectName)
                .projectPath(projectPath)
                .buildSystem(detectedBuildSystem)
                .javaVersion(javaVersion)
                .sourceRoot(sourceRoot)
                .classpath(classpath)
                .compiles(compiles)
                .compileStatus(compiles ? "BUILD SUCCESS" : (attemptCompile ? "BUILD FAILED" : "BUILD NOT ATTEMPTED"))
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
            Files.exists(projectPath.resolve("build.gradle.kts"))) {
            return "gradle";
        }

        // No recognized build descriptor: a plain Java directory or pre-compiled
        // project. Return "none" so the GenericJavaAdapter path can still analyze
        // it (scanning sources / decompiling bytecode) instead of failing outright.
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
     * Looks for: maven.compiler.source or properties.source.version
     */
    private String detectJavaVersionFromMaven() throws IOException {
        Path pomPath = projectPath.resolve("pom.xml");
        if (!Files.exists(pomPath)) {
            return "unknown";
        }

        String pomContent = Files.readString(pomPath);

        // Look for <maven.compiler.source>
        Pattern sourcePattern = Pattern.compile("<maven\\.compiler\\.source>([^<]+)</maven\\.compiler\\.source>");
        Matcher matcher = sourcePattern.matcher(pomContent);
        if (matcher.find()) {
            String version = matcher.group(1).strip();
            // Convert "1.8" to "8", "11" stays "11"
            return version.replace("1.", "");
        }

        // Fallback: look for <source> tag in maven-compiler-plugin
        Pattern sourcePattern2 = Pattern.compile("<source>([^<]+)</source>");
        matcher = sourcePattern2.matcher(pomContent);
        if (matcher.find()) {
            String version = matcher.group(1).strip();
            return version.replace("1.", "");
        }

        return "unknown";
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

        // Look for sourceCompatibility or java.sourceCompatibility
        Pattern pattern = Pattern.compile("(?:sourceCompatibility|java\\.sourceCompatibility)\\s*=\\s*['\"]?([^'\"\\s,;]+)['\"]?");
        Matcher matcher = pattern.matcher(buildContent);
        if (matcher.find()) {
            String version = matcher.group(1).strip();
            return version.replace("1.", "");
        }

        return "unknown";
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

        // Add target/classes for compiled classes
        Path targetClasses = projectPath.resolve("target/classes");
        if (Files.exists(targetClasses)) {
            classpath.add(targetClasses);
        }

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
            }
        }

        // Add dependencies from Maven local repository
        if ("maven".equals(buildSystem)) {
            classpath.addAll(buildMavenClasspath());
        } else if ("gradle".equals(buildSystem)) {
            classpath.addAll(buildGradleClasspath());
        }

        // Remove duplicates while maintaining order
        List<Path> uniqueClasspath = new ArrayList<>(new LinkedHashSet<>(classpath));

        return uniqueClasspath;
    }

    /**
     * Extract classpath from Maven repository (~/.m2/repository)
     */
    private List<Path> buildMavenClasspath() throws IOException {
        List<Path> jars = new ArrayList<>();
        
        Path mavenRepo = Paths.get(System.getProperty("user.home"), ".m2", "repository");
        if (!Files.exists(mavenRepo)) {
            return jars;
        }

        // Find all JAR files in Maven repository
        try (var stream = Files.walk(mavenRepo, 5)) {
            stream.filter(path -> path.toString().endsWith(".jar"))
                    .limit(100)  // Limit to avoid excessive classpaths
                    .forEach(jars::add);
        } catch (IOException e) {
            // If walk fails, continue with what we have
        }

        return jars;
    }

    /**
     * Extract classpath from Gradle (simplified - looks for build/libs and .gradle cache)
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

        // Check Gradle cache (simplified - just look for common location)
        Path gradleCache = Paths.get(System.getProperty("user.home"), ".gradle", "caches");
        if (Files.exists(gradleCache)) {
            try (var stream = Files.walk(gradleCache, 3)) {
                stream.filter(p -> p.toString().endsWith(".jar"))
                        .limit(50)
                        .forEach(jars::add);
            } catch (IOException e) {
                // Continue if walk fails
            }
        }

        return jars;
    }

    /**
     * Validate that the project compiles successfully.
     *
     * Strategy:
     *   1. Fast-path: if compiled output already exists (target/classes for Maven,
     *      build/classes for Gradle) and contains at least one .class file, treat
     *      the project as compiled. This avoids re-running a slow build on every
     *      analysis call and works regardless of OS shell quirks.
     *   2. Otherwise, invoke the build tool. On Windows the launcher is named
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

        if (hasExistingCompiledArtifacts(buildSystem)) {
            return true;
        }

        if (!attemptCompile) {
            return false;
        }

        try {
            ProcessBuilder pb;
            boolean isWindows = System.getProperty("os.name", "")
                    .toLowerCase()
                    .contains("win");

            if ("maven".equals(buildSystem)) {
                String mvn = executableWithWrapper("mvn", isWindows);
                pb = new ProcessBuilder(mvn, "-q", "-DskipTests", "-Dmaven.test.skip=true", "compile");
            } else if ("gradle".equals(buildSystem)) {
                String gradle = executableWithWrapper("gradle", isWindows);
                pb = new ProcessBuilder(gradle, "--no-daemon", "classes", "-x", "test", "--build-cache", "-q");
            } else {
                return false;
            }

            pb.directory(projectPath.toFile());
            pb.redirectErrorStream(true);
            Process process = pb.start();

            boolean completed = process.waitFor(compileTimeoutSeconds(), java.util.concurrent.TimeUnit.SECONDS);

            if (!completed) {
                process.destroyForcibly();
                return false;
            }

            return process.exitValue() == 0;
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
        String relative;
        if ("maven".equals(buildSystem)) {
            relative = "target/classes";
        } else if ("gradle".equals(buildSystem)) {
            relative = "build/classes";
        } else {
            // "none": plain/pre-compiled project — scan common output dirs for .class.
            for (String candidate : List.of("target/classes", "build/classes",
                    "out/production", "bin", "classes")) {
                Path dir = projectPath.resolve(candidate);
                if (Files.isDirectory(dir) && containsClassFile(dir)) {
                    return true;
                }
            }
            return false;
        }

        // Check root output dir first.
        Path rootOutput = projectPath.resolve(relative);
        if (Files.isDirectory(rootOutput) && containsClassFile(rootOutput)) {
            return true;
        }

        // Multi-module fallback: only check directories declared as <modules> in the
        // root pom. Avoids treating unrelated nested projects as submodules.
        if ("maven".equals(buildSystem)) {
            for (Path module : collectMavenModuleDirs(projectPath)) {
                Path moduleClasses = module.resolve("target/classes");
                if (Files.isDirectory(moduleClasses) && containsClassFile(moduleClasses)) {
                    return true;
                }
            }
        }
        return false;
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
