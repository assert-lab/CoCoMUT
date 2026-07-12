package org.assertlab.cocomut;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;
import org.w3c.dom.Node;

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

    private static final String NO_ROOT_BUILD_DESCRIPTOR =
            "NO MAVEN OR GRADLE BUILD DESCRIPTOR AT PROJECT ROOT";
    private static final long DEFAULT_COMPILE_TIMEOUT_SECONDS = 120;
    private static final int BUILD_OUTPUT_TAIL_CHARS = 12_000;
    private static final Pattern ANSI_ESCAPE = Pattern.compile("\\u001B\\[[;\\d]*[ -/]*[@-~]");
    private final Path projectPath;
    private Path effectiveBuildRoot;
    private final boolean autoDetectJavaVersion;
    private final String buildSystem;
    private final boolean includeTests;
    private final ContextRequest.BuildPolicy buildPolicy;
    private final List<Path> explicitClassOutputDirs;
    private final List<Path> explicitTestClassOutputDirs;
    private final List<Path> explicitProjectJars;
    private final List<Path> explicitDependencyJars;
    private final List<Path> explicitClasspathFiles;
    private final List<Path> explicitSourceRoots;
    private final List<Path> explicitTestSourceRoots;
    private BuildResult lastBuildResult = BuildResult.notAttempted("BUILD DENIED");
    private BuildJavaSelection buildJavaSelection = new BuildJavaSelection(null, "inherited", "inherited_environment");
    private final List<BuildAttempt> buildAttempts = new ArrayList<>();
    private List<Path> buildRootCandidates = List.of();
    private String finalBuildOutput = "";

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
        this(projectPath, autoDetectJavaVersion, buildSystem, includeTests,
                ContextRequest.BuildPolicy.DENY_BUILD,
                List.of(), List.of(), List.of(), List.of(), List.of());
    }

    public ProjectAnalyzer(ContextRequest request) {
        this(request.projectRoot(), true, "auto", includeTestBytecode(request),
                request.buildPolicy(),
                request.classOutputDirs(),
                request.testClassOutputDirs(),
                request.projectJars(),
                request.dependencyJars(),
                request.classpathFiles(),
                request.sourceRoots(),
                request.testSourceRoots());
    }

    public ProjectAnalyzer(Path projectPath,
                           boolean autoDetectJavaVersion,
                           String buildSystem,
                           boolean includeTests,
                           ContextRequest.BuildPolicy buildPolicy,
                           List<Path> explicitClassOutputDirs,
                           List<Path> explicitTestClassOutputDirs,
                           List<Path> explicitProjectJars,
                           List<Path> explicitDependencyJars,
                           List<Path> explicitClasspathFiles) {
        this(projectPath, autoDetectJavaVersion, buildSystem, includeTests, buildPolicy,
                explicitClassOutputDirs, explicitTestClassOutputDirs, explicitProjectJars,
                explicitDependencyJars, explicitClasspathFiles, List.of(), List.of());
    }

    public ProjectAnalyzer(Path projectPath,
                           boolean autoDetectJavaVersion,
                           String buildSystem,
                           boolean includeTests,
                           ContextRequest.BuildPolicy buildPolicy,
                           List<Path> explicitClassOutputDirs,
                           List<Path> explicitTestClassOutputDirs,
                           List<Path> explicitProjectJars,
                           List<Path> explicitDependencyJars,
                           List<Path> explicitClasspathFiles,
                           List<Path> explicitSourceRoots,
                           List<Path> explicitTestSourceRoots) {
        this.projectPath = Objects.requireNonNull(projectPath, "projectPath cannot be null");
        this.autoDetectJavaVersion = autoDetectJavaVersion;
        this.buildSystem = buildSystem;
        this.includeTests = includeTests;
        this.buildPolicy = buildPolicy == null ? ContextRequest.BuildPolicy.DENY_BUILD : buildPolicy;
        this.explicitClassOutputDirs = explicitClassOutputDirs == null ? List.of() : List.copyOf(explicitClassOutputDirs);
        this.explicitTestClassOutputDirs = explicitTestClassOutputDirs == null ? List.of() : List.copyOf(explicitTestClassOutputDirs);
        this.explicitProjectJars = explicitProjectJars == null ? List.of() : List.copyOf(explicitProjectJars);
        this.explicitDependencyJars = explicitDependencyJars == null ? List.of() : List.copyOf(explicitDependencyJars);
        this.explicitClasspathFiles = explicitClasspathFiles == null ? List.of() : List.copyOf(explicitClasspathFiles);
        this.explicitSourceRoots = explicitSourceRoots == null ? List.of() : List.copyOf(explicitSourceRoots);
        this.explicitTestSourceRoots = explicitTestSourceRoots == null ? List.of() : List.copyOf(explicitTestSourceRoots);
        this.effectiveBuildRoot = this.projectPath;

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
        buildJavaSelection = BuildJavaSelection.select(projectPath, detectedBuildSystem, javaVersion);
        List<Path> sourceRoots = !explicitSourceRoots.isEmpty()
                ? existingDirs(explicitSourceRoots)
                : findSourceRoots();
        List<Path> testSourceRoots = includeTests
                ? (!explicitTestSourceRoots.isEmpty() ? existingDirs(explicitTestSourceRoots) : findTestSourceRoots())
                : List.of();
        Path sourceRoot = !sourceRoots.isEmpty() ? sourceRoots.get(0) : findSourceRoot();
        List<Path> classpathFileEntries = readClasspathFiles();
        boolean explicitProjectBytecode = !explicitClassOutputDirs.isEmpty()
                || !explicitTestClassOutputDirs.isEmpty()
                || !explicitProjectJars.isEmpty();
        BuildResult buildResult = runBuildIfAllowed(detectedBuildSystem);
        if (buildResult.succeeded()) {
            if ("maven".equals(detectedBuildSystem)) {
                sourceRoots = mergePaths(sourceRoots, findBuiltMavenSourceRoots(false));
                if (includeTests) testSourceRoots = mergePaths(testSourceRoots, findBuiltMavenSourceRoots(true));
            }
            sourceRoots = mergePaths(sourceRoots, findGeneratedSourceRoots(false));
            if (includeTests) testSourceRoots = mergePaths(testSourceRoots, findGeneratedSourceRoots(true));
            sourceRoot = !sourceRoots.isEmpty() ? sourceRoots.get(0) : sourceRoot;
        }
        List<Path> discoveredMainOutputs = explicitMode(explicitProjectBytecode)
                ? List.of()
                : existingMainClassOutputDirs(detectedBuildSystem);
        List<Path> discoveredTestOutputs = includeTests && !explicitMode(explicitProjectBytecode)
                ? existingTestClassOutputDirs(detectedBuildSystem)
                : List.of();
        List<Path> discoveredProjectJars = explicitMode(explicitProjectBytecode) || buildResult.succeeded()
                ? List.of()
                : existingProjectArtifactJars(detectedBuildSystem);
        List<Path> mainClassOutputs = mergePaths(discoveredMainOutputs,
                existingClassDirs(explicitClassOutputDirs));
        List<Path> testClassOutputs = mergePaths(discoveredTestOutputs,
                existingClassDirs(explicitTestClassOutputDirs));
        List<Path> dependencyClasspath = mergePaths(buildDependencyClasspath(detectedBuildSystem),
                existingJars(explicitDependencyJars),
                existingJarsFromClasspathFile(classpathFileEntries),
                existingClassDirsFromClasspathFile(classpathFileEntries));
        List<Path> projectArtifactJars = mergePaths(discoveredProjectJars,
                existingJars(explicitProjectJars));
        dependencyClasspath = withoutProjectArtifacts(dependencyClasspath,
                mainClassOutputs, testClassOutputs, projectArtifactJars);
        List<Path> classpath = combinedClasspath(sourceRoot, mainClassOutputs, testClassOutputs,
                projectArtifactJars, dependencyClasspath);
        java.util.Map<String, String> artifactOrigins = artifactOrigins(buildResult,
                mainClassOutputs, testClassOutputs, projectArtifactJars, dependencyClasspath);
        boolean bytecodeAvailable = !mainClassOutputs.isEmpty()
                || !testClassOutputs.isEmpty()
                || !projectArtifactJars.isEmpty();
        String bytecodeOrigin = bytecodeOrigin(explicitProjectBytecode, buildResult, bytecodeAvailable);
        boolean analysisCanProceed = bytecodeAvailable && canTrustBytecodeForAnalysis(buildResult);

        String projectName = projectPath.getFileName().toString();

        return new ProjectMetadata.Builder()
                .projectName(projectName)
                .projectPath(projectPath)
                .buildRoot(effectiveBuildRoot)
                .buildRootCandidates(buildRootCandidates)
                .buildSystem(detectedBuildSystem)
                .javaVersion(javaVersion)
                .sourceRoot(sourceRoot)
                .sourceRoots(sourceRoots)
                .testSourceRoots(testSourceRoots)
                .classpath(classpath)
                .mainClassOutputs(mainClassOutputs)
                .testClassOutputs(testClassOutputs)
                .projectArtifactJars(projectArtifactJars)
                .dependencyClasspath(dependencyClasspath)
                .compiles(buildResult.succeeded())
                .compileStatus(compileStatus(buildResult, bytecodeAvailable))
                .buildAttempted(buildResult.attempted())
                .buildExitCode(buildResult.exitCode())
                .buildSucceeded(buildResult.succeeded())
                .buildTimedOut(buildResult.timedOut())
                .buildOutputTail(buildResult.outputTail())
                .buildFailureReason(buildResult.failureReason())
                .buildJavaHome(buildJavaSelection.javaHome() == null ? "" : buildJavaSelection.javaHome().toString())
                .buildJavaVersion(buildJavaSelection.version())
                .buildJavaEvidence(buildJavaSelection.evidence())
                .buildAttempts(buildAttempts)
                .buildSkipped(buildPolicy == ContextRequest.BuildPolicy.DENY_BUILD)
                .buildSandboxed(buildPolicy == ContextRequest.BuildPolicy.EXTERNALLY_SANDBOXED_BUILD)
                .buildPolicy(buildPolicy)
                .bytecodeAvailable(bytecodeAvailable)
                .bytecodeOrigin(bytecodeOrigin)
                .analysisCanProceed(analysisCanProceed)
                .explicitClassOutputDirs(new ArrayList<>(explicitClassOutputDirs))
                .explicitTestClassOutputDirs(new ArrayList<>(explicitTestClassOutputDirs))
                .explicitProjectJars(new ArrayList<>(explicitProjectJars))
                .explicitDependencyJars(new ArrayList<>(explicitDependencyJars))
                .explicitClasspathFiles(new ArrayList<>(explicitClasspathFiles))
                .explicitSourceRoots(new ArrayList<>(explicitSourceRoots))
                .explicitTestSourceRoots(new ArrayList<>(explicitTestSourceRoots))
                .artifactOrigins(artifactOrigins)
                .build();
    }

    private boolean explicitMode(boolean explicitProjectBytecode) {
        return buildPolicy == ContextRequest.BuildPolicy.DENY_BUILD && explicitProjectBytecode;
    }

    private boolean canTrustBytecodeForAnalysis(BuildResult buildResult) {
        if (!buildResult.attempted()) {
            return true;
        }
        if (buildResult.succeeded()) {
            return true;
        }
        return false;
    }

    private static boolean includeTestBytecode(ContextRequest request) {
        Set<String> sourceSets = request.sourceSets();
        return sourceSets == null || sourceSets.isEmpty()
                || sourceSets.stream().anyMatch(set -> !"main".equals(set));
    }

    private String compileStatus(BuildResult buildResult, boolean bytecodeAvailable) {
        if (!buildResult.attempted()) {
            return bytecodeAvailable ? buildResult.status() + "; PROJECT BYTECODE AVAILABLE"
                    : buildResult.status() + "; NO PROJECT BYTECODE";
        }
        if (buildResult.succeeded()) {
            return bytecodeAvailable ? "BUILD SUCCESS" : "BUILD SUCCESS; NO PROJECT BYTECODE";
        }
        if (bytecodeAvailable) {
            return "BUILD FAILED; PREEXISTING PROJECT BYTECODE AVAILABLE";
        }
        return "BUILD FAILED; NO PROJECT BYTECODE";
    }

    private static String bytecodeOrigin(boolean explicitProjectBytecode, BuildResult buildResult, boolean available) {
        if (!available) {
            return "none";
        }
        if (explicitProjectBytecode) {
            return "explicit";
        }
        if (buildResult.succeeded()) {
            return "generated_this_run";
        }
        return "preexisting";
    }

    private static List<Path> withoutProjectArtifacts(List<Path> dependencies,
                                                      List<Path> mainOutputs,
                                                      List<Path> testOutputs,
                                                      List<Path> projectJars) {
        Set<Path> projectArtifacts = new LinkedHashSet<>();
        projectArtifacts.addAll(normalized(mainOutputs));
        projectArtifacts.addAll(normalized(testOutputs));
        projectArtifacts.addAll(normalized(projectJars));
        List<Path> filtered = new ArrayList<>();
        for (Path dependency : dependencies == null ? List.<Path>of() : dependencies) {
            Path normalized = dependency.toAbsolutePath().normalize();
            if (!projectArtifacts.contains(normalized)) {
                filtered.add(normalized);
            }
        }
        return new ArrayList<>(new LinkedHashSet<>(filtered));
    }

    private static List<Path> normalized(List<Path> paths) {
        return (paths == null ? List.<Path>of() : paths).stream()
                .filter(Objects::nonNull)
                .map(path -> path.toAbsolutePath().normalize())
                .toList();
    }

    private java.util.Map<String, String> artifactOrigins(BuildResult buildResult,
                                                          List<Path> mainOutputs,
                                                          List<Path> testOutputs,
                                                          List<Path> projectJars,
                                                          List<Path> dependencyClasspath) {
        java.util.LinkedHashMap<String, String> origins = new java.util.LinkedHashMap<>();
        addOrigins(origins, mainOutputs, explicitClassOutputDirs, buildResult);
        addOrigins(origins, testOutputs, explicitTestClassOutputDirs, buildResult);
        addOrigins(origins, projectJars, explicitProjectJars, buildResult);
        for (Path dependency : dependencyClasspath == null ? List.<Path>of() : dependencyClasspath) {
            origins.put(dependency.toAbsolutePath().normalize().toString(), "dependency");
        }
        return origins;
    }

    private static void addOrigins(java.util.Map<String, String> origins,
                                   List<Path> artifacts,
                                   List<Path> explicit,
                                   BuildResult buildResult) {
        Set<Path> explicitSet = new LinkedHashSet<>(normalized(explicit));
        for (Path artifact : artifacts == null ? List.<Path>of() : artifacts) {
            Path normalized = artifact.toAbsolutePath().normalize();
            String origin = explicitSet.contains(normalized)
                    ? "explicit"
                    : (buildResult.succeeded() ? "generated_this_run" : "preexisting");
            origins.put(normalized.toString(), origin);
        }
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

        if (Files.exists(projectPath.resolve("build.gradle")) ||
            Files.exists(projectPath.resolve("build.gradle.kts")) ||
            Files.exists(projectPath.resolve("settings.gradle")) ||
            Files.exists(projectPath.resolve("settings.gradle.kts"))) {
            return "gradle";
        }
        if (Files.exists(projectPath.resolve("pom.xml"))) {
            return "maven";
        }

        String unsupportedBuildSystem = unsupportedRootBuildSystem();
        if (unsupportedBuildSystem != null) {
            buildRootCandidates = List.of(projectPath);
            return unsupportedBuildSystem;
        }

        List<Path> nestedRoots = nestedBuildRoots();
        buildRootCandidates = nestedRoots;
        if (nestedRoots.size() == 1) {
            effectiveBuildRoot = nestedRoots.get(0);
            return Files.isRegularFile(effectiveBuildRoot.resolve("pom.xml")) ? "maven" : "gradle";
        }

        // No recognized build descriptor: a plain Java directory or pre-compiled
        // project. Return "none" so conventional source and bytecode layouts can
        // still be analyzed.
        return "none";
    }

    private String unsupportedRootBuildSystem() {
        if (Files.isRegularFile(projectPath.resolve("build.xml"))) return "ant";
        if (Files.isRegularFile(projectPath.resolve("MODULE.bazel"))
                || Files.isRegularFile(projectPath.resolve("WORKSPACE"))
                || Files.isRegularFile(projectPath.resolve("WORKSPACE.bazel"))) return "bazel";
        if (Files.isRegularFile(projectPath.resolve("BUCK"))) return "buck";
        if (Files.isRegularFile(projectPath.resolve("build.sbt"))) return "sbt";
        return null;
    }

    private List<Path> nestedBuildRoots() {
        Set<Path> roots = new LinkedHashSet<>();
        try (var walk = Files.walk(projectPath, 3)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                String name = file.getFileName().toString();
                if (name.equals("pom.xml") || name.equals("settings.gradle") || name.equals("settings.gradle.kts")
                        || name.equals("build.gradle") || name.equals("build.gradle.kts")) {
                    Path parent = file.getParent();
                    String relative = projectPath.relativize(parent).toString().replace('\\', '/');
                    if (!relative.contains("build/") && !relative.contains("target/")) roots.add(parent);
                }
            }
        } catch (IOException ignored) {
            return List.of();
        }
        List<Path> topLevelRoots = roots.stream()
                .filter(root -> roots.stream().noneMatch(other -> !other.equals(root) && root.startsWith(other)))
                .toList();
        List<Path> productionRoots = topLevelRoots.stream()
                .filter(root -> !isAuxiliaryBuildRoot(root))
                .toList();
        return productionRoots.isEmpty() ? topLevelRoots : productionRoots;
    }

    private boolean isAuxiliaryBuildRoot(Path root) {
        Set<String> auxiliarySegments = Set.of(
                "example", "examples", "demo", "demos", "sample", "samples",
                "benchmark", "benchmarks");
        Path relative = projectPath.relativize(root);
        for (Path segment : relative) {
            if (auxiliarySegments.contains(segment.toString().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
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
        Path pomPath = effectiveBuildRoot.resolve("pom.xml");
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
        Path buildGradle = effectiveBuildRoot.resolve("build.gradle");
        if (!Files.exists(buildGradle)) {
            buildGradle = effectiveBuildRoot.resolve("build.gradle.kts");
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
        Path pomXml = effectiveBuildRoot.resolve("pom.xml");
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

    private List<Path> findSourceRoots() throws IOException {
        LinkedHashSet<Path> roots = new LinkedHashSet<>();
        addIfDirectory(roots, projectPath.resolve("src/main/java"));
        for (Path module : collectMavenModuleDirs(effectiveBuildRoot)) {
            addIfDirectory(roots, module.resolve("src/main/java"));
        }
        addDeclaredMavenSourceRoots(roots, false);
        if (roots.isEmpty()) {
            addConventionSourceRoots(roots, "src/main/java");
        }
        if (roots.isEmpty()) {
            addIfDirectory(roots, findSourceRoot());
        }
        return new ArrayList<>(roots);
    }

    private List<Path> findTestSourceRoots() throws IOException {
        LinkedHashSet<Path> roots = new LinkedHashSet<>();
        addIfDirectory(roots, projectPath.resolve("src/test/java"));
        for (Path module : collectMavenModuleDirs(effectiveBuildRoot)) {
            addIfDirectory(roots, module.resolve("src/test/java"));
        }
        addDeclaredMavenSourceRoots(roots, true);
        if (roots.isEmpty()) {
            addConventionSourceRoots(roots, "src/test/java");
        }
        return new ArrayList<>(roots);
    }

    private void addDeclaredMavenSourceRoots(Set<Path> roots, boolean tests) {
        if (!Files.isRegularFile(effectiveBuildRoot.resolve("pom.xml"))) return;
        String element = tests ? "testSourceDirectory" : "sourceDirectory";
        for (Path module : mergePaths(List.of(effectiveBuildRoot), collectMavenModuleDirs(effectiveBuildRoot))) {
            String configured = inheritedMavenBuildPath(module, element);
            Path resolved = resolveMavenProjectPath(module, configured);
            addIfDirectory(roots, resolved);
        }
    }

    private String inheritedMavenBuildPath(Path module, String element) {
        Path root = effectiveBuildRoot.toAbsolutePath().normalize();
        Path current = module.toAbsolutePath().normalize();
        while (current.startsWith(root)) {
            String value = directMavenBuildPath(current.resolve("pom.xml"), element);
            if (value != null && !value.isBlank()) return value;
            if (current.equals(root)) break;
            current = current.getParent();
        }
        return null;
    }

    private static String directMavenBuildPath(Path pom, String elementName) {
        if (!Files.isRegularFile(pom)) return null;
        try {
            Element project = parseXml(pom);
            for (Node child = project.getFirstChild(); child != null; child = child.getNextSibling()) {
                if (!(child instanceof Element build) || !"build".equals(elementName(build))) continue;
                for (Node entry = build.getFirstChild(); entry != null; entry = entry.getNextSibling()) {
                    if (entry instanceof Element value && elementName.equals(elementName(value))) {
                        return value.getTextContent().trim();
                    }
                }
            }
        } catch (Exception ignored) {
            // Invalid POMs are classified by the build itself.
        }
        return null;
    }

    private static Path resolveMavenProjectPath(Path module, String configured) {
        if (configured == null || configured.isBlank()) return null;
        String resolved = configured
                .replace("${project.basedir}", module.toAbsolutePath().normalize().toString())
                .replace("${basedir}", module.toAbsolutePath().normalize().toString());
        if (resolved.contains("${")) return null;
        Path path = Path.of(resolved);
        return (path.isAbsolute() ? path : module.resolve(path)).toAbsolutePath().normalize();
    }

    private List<Path> findGeneratedSourceRoots(boolean tests) {
        Set<Path> roots = new LinkedHashSet<>();
        try (var walk = Files.walk(projectPath, 10)) {
            for (Path dir : walk.filter(Files::isDirectory).toList()) {
                String normalized = projectPath.relativize(dir).toString().replace('\\', '/');
                boolean generated = normalized.contains("/target/generated-")
                        || normalized.contains("/build/generated/sources/")
                        || normalized.startsWith("target/generated-")
                        || normalized.startsWith("build/generated/sources/");
                boolean testRoot = normalized.contains("generated-test-sources")
                        || normalized.contains("/test/") || normalized.endsWith("/test");
                if (generated && testRoot == tests && containsJavaFiles(dir)) roots.add(dir);
            }
        } catch (IOException ignored) {
            // Generated sources are optional enrichment.
        }
        return new ArrayList<>(roots);
    }

    private List<Path> findBuiltMavenSourceRoots(boolean tests) {
        Set<Path> roots = new LinkedHashSet<>();
        for (Path output : builtMavenClassOutputs(effectiveBuildRoot, tests)) {
            Path target = output.getParent();
            Path module = target == null ? null : target.getParent();
            if (module == null || !Files.isRegularFile(module.resolve("pom.xml"))) continue;
            String element = tests ? "testSourceDirectory" : "sourceDirectory";
            Path configured = resolveMavenProjectPath(module, inheritedMavenBuildPath(module, element));
            Path conventional = module.resolve(tests ? "src/test/java" : "src/main/java");
            addIfDirectory(roots, configured != null ? configured : conventional);
        }
        return new ArrayList<>(roots);
    }

    private static boolean containsJavaFiles(Path dir) {
        try (var files = Files.walk(dir, 5)) {
            return files.anyMatch(path -> Files.isRegularFile(path) && path.toString().endsWith(".java"));
        } catch (IOException ignored) {
            return false;
        }
    }

    private void addConventionSourceRoots(Set<Path> roots, String suffix) {
        try (var walk = Files.walk(projectPath, 7)) {
            for (Path dir : walk.filter(Files::isDirectory)
                    .filter(path -> path.endsWith(Path.of(suffix)))
                    .toList()) {
                addIfDirectory(roots, dir);
            }
        } catch (IOException ignored) {
            // Source-root discovery is best-effort; parsing reports exact failures.
        }
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
            for (Path module : collectMavenModuleDirs(effectiveBuildRoot)) {
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
            // Gradle metadata is resolved by GradleProjectAdapter so the build is
            // evaluated once through a request-aware path. Keep this legacy
            // analyzer from running a second Gradle classpath task.
        }

        // Remove duplicates while maintaining order
        List<Path> uniqueClasspath = new ArrayList<>(new LinkedHashSet<>(classpath));

        return uniqueClasspath;
    }

    private List<Path> buildDependencyClasspath(String buildSystem) throws IOException {
        if ("none".equals(buildSystem)) {
            return plainProjectDependencyClasspath();
        }
        if (buildPolicy == ContextRequest.BuildPolicy.DENY_BUILD) {
            return List.of();
        }
        if ("maven".equals(buildSystem)) {
            return buildMavenClasspath();
        }
        if ("gradle".equals(buildSystem)) {
            // GradleProjectAdapter owns Gradle dependency discovery. Running the
            // older helper here would evaluate untrusted Gradle configuration a
            // second time and would not be represented as a separate model step
            // in provenance.
            return List.of();
        }
        return List.of();
    }

    private List<Path> plainProjectDependencyClasspath() throws IOException {
        List<Path> entries = new ArrayList<>();
        addJarsFromDirectory(entries, projectPath.resolve("lib"));
        addJarsFromDirectory(entries, projectPath);
        return new ArrayList<>(new LinkedHashSet<>(entries));
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
                        "dependency:build-classpath"), false);
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
    private BuildResult runBuildIfAllowed(String buildSystem) {
        if (buildPolicy == ContextRequest.BuildPolicy.DENY_BUILD) {
            lastBuildResult = BuildResult.notAttempted("BUILD DENIED");
            return lastBuildResult;
        }

        if (!"maven".equals(buildSystem) && !"gradle".equals(buildSystem)) {
            lastBuildResult = BuildResult.notAttempted("none".equals(buildSystem)
                    ? NO_ROOT_BUILD_DESCRIPTOR
                    : "UNSUPPORTED BUILD SYSTEM: " + buildSystem.toUpperCase(Locale.ROOT));
            return lastBuildResult;
        }

        try {
            boolean isWindows = System.getProperty("os.name", "")
                    .toLowerCase()
                    .contains("win");
            List<String> command;
            AndroidSdkSupport.Preparation androidPreparation = "gradle".equals(buildSystem)
                    ? AndroidSdkSupport.prepare(effectiveBuildRoot) : AndroidSdkSupport.Preparation.notAndroid();

            if ("maven".equals(buildSystem)) {
                String mvn = executableWithWrapper("mvn", isWindows);
                List<String> mavenCommand = new ArrayList<>(List.of(mvn, "-q", "-DskipTests"));
                Path toolchains = isolatedMavenToolchainsFile();
                if (toolchains != null) {
                    mavenCommand.add("-t");
                    mavenCommand.add(toolchains.toString());
                }
                mavenCommand.add(includeTests ? "test-compile" : "compile");
                command = mavenCommand;
            } else if ("gradle".equals(buildSystem)) {
                String gradle = executableWithWrapper("gradle", isWindows);
                command = List.of(gradle, "--no-daemon",
                        gradleBuildTask(androidPreparation.androidProject(), includeTests),
                        "-x", "test", "--build-cache", "-q");
            } else {
                lastBuildResult = BuildResult.notAttempted(NO_ROOT_BUILD_DESCRIPTOR);
                return lastBuildResult;
            }

            CommandResult result = runWithTransientRetries(command);
            if (androidPreparation.androidProject() && !androidPreparation.diagnostic().isBlank()) {
                result = new CommandResult(result.exitCode(),
                        "[CoCoMUT Android SDK preparation]\n" + androidPreparation.diagnostic() + "\n"
                                + result.output(), result.timedOut());
            }
            result = retryForRequestedJava(command, result);
            if ("maven".equals(buildSystem) && result.exitCode() != 0 && !result.timedOut()
                    && missingSameReactorArtifacts(result.output())) {
                List<String> packageCommand = new ArrayList<>(command);
                packageCommand.set(packageCommand.size() - 1, "package");
                CommandResult packaged = retryForRequestedJava(packageCommand,
                        runWithTransientRetries(packageCommand));
                result = new CommandResult(packaged.exitCode(),
                        result.output() + "\n[CoCoMUT retried Maven package because only declared reactor artifacts were missing]\n"
                                + packaged.output(), packaged.timedOut());
            }
            if ("gradle".equals(buildSystem) && !includeTests && result.exitCode() != 0
                    && !result.timedOut() && result.output().contains("Task 'classes' not found")) {
                List<String> assembleCommand = new ArrayList<>(command);
                assembleCommand.set(assembleCommand.indexOf("classes"), "assemble");
                CommandResult assembled = retryForRequestedJava(assembleCommand,
                        runWithTransientRetries(assembleCommand));
                result = new CommandResult(assembled.exitCode(),
                        result.output()
                                + "\n[CoCoMUT retried Gradle assemble because the aggregator has no classes task]\n"
                                + assembled.output(),
                        assembled.timedOut());
            }
            lastBuildResult = new BuildResult(true, result.exitCode(), result.exitCode() == 0,
                    result.timedOut(), result.timedOut() ? "BUILD TIMED OUT" : (result.exitCode() == 0 ? "BUILD SUCCESS" : "BUILD FAILED"),
                    diagnosticTail(result.output()),
                    classifiedBuildFailure(result));
            return lastBuildResult;
        } catch (Exception e) {
            lastBuildResult = new BuildResult(true, -1, false, false,
                    "BUILD FAILED: " + e.getClass().getSimpleName(), e.getMessage() == null ? "" : e.getMessage(),
                    BuildFailureReason.BUILD_FAILED_UNKNOWN_ERROR);
            return lastBuildResult;
        }
    }

    private CommandResult retryForRequestedJava(List<String> command, CommandResult initial)
            throws IOException, InterruptedException {
        if ("COCOMUT_BUILD_JAVA_HOME".equals(buildJavaSelection.evidence())) return initial;
        CommandResult result = initial;
        StringBuilder attempts = new StringBuilder(result.output());
        Set<Path> attemptedJavaHomes = new HashSet<>();
        if (buildJavaSelection.javaHome() != null) {
            attemptedJavaHomes.add(buildJavaSelection.javaHome().toAbsolutePath().normalize());
        }
        for (int retryCount = 0; retryCount < 4 && result.exitCode() != 0 && !result.timedOut(); retryCount++) {
            int requiredVersion = requiredJavaVersion(result.output());
            BuildJavaSelection retrySelection = BuildJavaSelection.forRequiredVersion(requiredVersion,
                    "compiler requested Java " + requiredVersion + " after build failure");
            Path retryHome = retrySelection == null || retrySelection.javaHome() == null
                    ? null : retrySelection.javaHome().toAbsolutePath().normalize();
            boolean exactRequirement = exactJavaVersionRequired(result.output());
            boolean downgradeRequired = obsoleteJavaSourceLevel(result.output());
            if (retrySelection == null
                    || retrySelection.javaHome().equals(buildJavaSelection.javaHome())
                    || attemptedJavaHomes.contains(retryHome)
                    || (!exactRequirement && !downgradeRequired && buildJavaSelection.majorVersion() > 0
                            && retrySelection.majorVersion() <= buildJavaSelection.majorVersion())) {
                break;
            }
            BuildJavaSelection previousSelection = buildJavaSelection;
            buildJavaSelection = retrySelection;
            attemptedJavaHomes.add(retryHome);
            System.err.println("[ProjectAnalyzer] Retrying build with JDK " + retrySelection.version()
                    + " because the compiler requested Java " + requiredVersion);
            result = runWithTransientRetries(command);
            attempts.append("\n[CoCoMUT retried after ")
                    .append(previousSelection.evidence())
                    .append(" using ")
                    .append(retrySelection.javaHome())
                    .append("]\n")
                    .append(result.output());
        }
        return new CommandResult(result.exitCode(), attempts.toString(), result.timedOut());
    }

    static String gradleBuildTask(boolean androidProject, boolean includeTests) {
        if (includeTests) return "testClasses";
        return androidProject ? "assemble" : "classes";
    }

    private static String diagnosticTail(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String normalized = ANSI_ESCAPE.matcher(raw)
                .replaceAll("")
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .stripTrailing();
        if (normalized.length() <= BUILD_OUTPUT_TAIL_CHARS) {
            return normalized;
        }
        return "[CoCoMUT kept the last " + BUILD_OUTPUT_TAIL_CHARS + " characters of build output]\n"
                + normalized.substring(normalized.length() - BUILD_OUTPUT_TAIL_CHARS);
    }

    private BuildFailureReason classifiedBuildFailure(CommandResult result) {
        BuildFailureReason reason = BuildFailureReason.classify(
                finalBuildOutput, result.timedOut(), result.exitCode() == 0);
        if (reason == BuildFailureReason.BUILD_FAILED_REACTOR_ARTIFACT_MISSING
                && finalBuildOutput.contains("Could not find artifact")
                && !missingSameReactorArtifacts(finalBuildOutput)) {
            return BuildFailureReason.BUILD_FAILED_DEPENDENCY_UNAVAILABLE;
        }
        return reason;
    }

    static int requiredJavaVersion(String output) {
        if (output == null || output.isBlank()) {
            return -1;
        }
        if (Pattern.compile("invalid flag:\\s*--release", Pattern.CASE_INSENSITIVE).matcher(output).find()) {
            return 11;
        }
        if (Pattern.compile("unrecognized option:\\s*--add-(?:opens|exports)", Pattern.CASE_INSENSITIVE)
                .matcher(output).find()) {
            return 11;
        }
        Matcher classVersion = Pattern.compile(
                "compiled by a more recent version of the java runtime.*?class file version\\s+(\\d+)(?:\\.\\d+)?",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(output);
        boolean classVersionFound = classVersion.find();
        if (!classVersionFound) {
            classVersion = Pattern.compile("class file has wrong version\\s+(\\d+)(?:\\.\\d+)?",
                    Pattern.CASE_INSENSITIVE).matcher(output);
            classVersionFound = classVersion.find();
        }
        if (classVersionFound) {
            int major = Integer.parseInt(classVersion.group(1));
            if (major >= 45) {
                return major - 44;
            }
        }
        for (Pattern pattern : List.of(
                Pattern.compile("languageVersion=(\\d+)", Pattern.CASE_INSENSITIVE),
                Pattern.compile("(?:release version|invalid target release:)\\s*(\\d+)\\s*(?:not supported)?", Pattern.CASE_INSENSITIVE),
                Pattern.compile("invalid source release:\\s*(\\d+)", Pattern.CASE_INSENSITIVE),
                Pattern.compile("requires at least jvm runtime version\\s*(\\d+)", Pattern.CASE_INSENSITIVE),
                Pattern.compile("requires at least jdk\\s*(\\d+)", Pattern.CASE_INSENSITIVE),
                Pattern.compile("only compatible with jvm runtime version\\s*(\\d+)\\s+or newer",
                        Pattern.CASE_INSENSITIVE),
                Pattern.compile("(?:this )?build requires java\\s*(\\d+)(?:\\s+or\\s+\\d+)?",
                        Pattern.CASE_INSENSITIVE),
                Pattern.compile("jdk\\s*(\\d+)\\+?\\s+is required", Pattern.CASE_INSENSITIVE),
                Pattern.compile("java\\s+(?:1\\.)?(\\d+)\\s+is required", Pattern.CASE_INSENSITIVE),
                Pattern.compile("only builds on jdk\\s*(\\d+)\\s+or higher", Pattern.CASE_INSENSITIVE),
                Pattern.compile("built with java\\s*(\\d+)\\s+or (?:above|higher|later)", Pattern.CASE_INSENSITIVE),
                Pattern.compile("requires (?:a )?jvm\\s*(\\d+)\\s*(?:or later|\\+)", Pattern.CASE_INSENSITIVE),
                Pattern.compile("not in the allowed range\\s*\\[(\\d+)\\s*,", Pattern.CASE_INSENSITIVE),
                Pattern.compile("(?:source|target) option\\s+(\\d+)\\s+is no longer supported", Pattern.CASE_INSENSITIVE))) {
            Matcher matcher = pattern.matcher(output);
            if (matcher.find()) {
                return Integer.parseInt(matcher.group(1));
            }
        }
        return -1;
    }

    static boolean exactJavaVersionRequired(String output) {
        if (output == null || output.isBlank()) {
            return false;
        }
        return Pattern.compile("java\\s+(?:1\\.)?\\d+\\s+is required", Pattern.CASE_INSENSITIVE)
                .matcher(output)
                .find();
    }

    static boolean obsoleteJavaSourceLevel(String output) {
        if (output == null || output.isBlank()) return false;
        return Pattern.compile("(?:source|target) option\\s+\\d+\\s+is no longer supported",
                Pattern.CASE_INSENSITIVE).matcher(output).find();
    }

    private CommandResult runWithTransientRetries(List<String> command) throws IOException, InterruptedException {
        CommandResult result = runCommand(command, true);
        StringBuilder attempts = new StringBuilder(result.output());
        for (int retry = 1; retry <= 2 && result.exitCode() != 0 && !result.timedOut()
                && BuildFailureReason.isTransientNetworkFailure(result.output()); retry++) {
            Thread.sleep(500L * retry);
            result = runCommand(command, true);
            attempts.append("\n[CoCoMUT transient network retry ").append(retry).append("/2]\n")
                    .append(result.output());
        }
        return new CommandResult(result.exitCode(), attempts.toString(), result.timedOut());
    }

    boolean missingSameReactorArtifacts(String output) {
        if (output == null) return false;
        String lower = output.toLowerCase(Locale.ROOT);
        if (lower.contains("artifact has not been packaged yet")
                && lower.contains("when used on reactor artifact")) return true;
        Set<MavenCoordinate> reactorArtifacts = new HashSet<>();
        for (Path dir : mergePaths(List.of(effectiveBuildRoot), collectMavenModuleDirs(effectiveBuildRoot))) {
            MavenCoordinate coordinate = readMavenCoordinate(dir.resolve("pom.xml"));
            if (coordinate != null) reactorArtifacts.add(coordinate);
        }
        Matcher pluginDescriptor = Pattern.compile(
                "plugin descriptor for\\s+([^\\s]+)\\s+\\(([^)]+)\\).*?no plugin descriptor found",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(output);
        if (pluginDescriptor.find()) {
            String[] parts = pluginDescriptor.group(1).split(":");
            Path pluginPath;
            try {
                pluginPath = Path.of(pluginDescriptor.group(2)).toAbsolutePath().normalize();
            } catch (Exception ignored) {
                return false;
            }
            return parts.length == 3
                    && pluginPath.startsWith(effectiveBuildRoot.toAbsolutePath().normalize())
                    && reactorArtifacts.contains(new MavenCoordinate(parts[0], parts[1], parts[2]));
        }
        if (!output.contains("Could not find artifact")) return false;
        Matcher missing = Pattern.compile("Could not find artifact\\s+([^\\s]+)", Pattern.CASE_INSENSITIVE)
                .matcher(output);
        boolean found = false;
        while (missing.find()) {
            found = true;
            String[] parts = missing.group(1).replaceAll("[.,;]+$", "").split(":");
            if (parts.length < 4
                    || !reactorArtifacts.contains(new MavenCoordinate(parts[0], parts[1], parts[parts.length - 1]))) {
                return false;
            }
        }
        return found;
    }

    private static MavenCoordinate readMavenCoordinate(Path pom) {
        if (!Files.isRegularFile(pom)) return null;
        try {
            Element project = parseXml(pom);
            Map<String, String> properties = new java.util.LinkedHashMap<>();
            Element propertiesElement = directChild(project, "properties");
            if (propertiesElement != null) {
                for (Node child = propertiesElement.getFirstChild(); child != null; child = child.getNextSibling()) {
                    if (child instanceof Element element) {
                        properties.put(elementName(element), element.getTextContent().trim());
                    }
                }
            }
            Element parent = directChild(project, "parent");
            String groupId = directChildText(project, "groupId");
            String artifactId = directChildText(project, "artifactId");
            String version = directChildText(project, "version");
            if (groupId.isBlank() && parent != null) groupId = directChildText(parent, "groupId");
            if (version.isBlank() && parent != null) version = directChildText(parent, "version");
            properties.putIfAbsent("project.groupId", groupId);
            properties.putIfAbsent("project.artifactId", artifactId);
            properties.putIfAbsent("project.version", version);
            properties.putIfAbsent("pom.groupId", groupId);
            properties.putIfAbsent("pom.artifactId", artifactId);
            properties.putIfAbsent("pom.version", version);
            groupId = resolveMavenProperties(groupId, properties);
            artifactId = resolveMavenProperties(artifactId, properties);
            version = resolveMavenProperties(version, properties);
            if (groupId.isBlank() || artifactId.isBlank() || version.isBlank()
                    || groupId.contains("${") || artifactId.contains("${") || version.contains("${")) return null;
            return new MavenCoordinate(groupId, artifactId, version);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String resolveMavenProperties(String value, Map<String, String> properties) {
        String resolved = value == null ? "" : value.trim();
        for (int pass = 0; pass < 5; pass++) {
            Matcher matcher = Pattern.compile("\\$\\{([^}]+)}").matcher(resolved);
            StringBuffer next = new StringBuffer();
            boolean changed = false;
            while (matcher.find()) {
                String replacement = properties.get(matcher.group(1));
                if (replacement == null) continue;
                matcher.appendReplacement(next, Matcher.quoteReplacement(replacement));
                changed = true;
            }
            matcher.appendTail(next);
            resolved = next.toString();
            if (!changed) break;
        }
        return resolved;
    }

    private record MavenCoordinate(String groupId, String artifactId, String version) {}

    private static String readQuietly(Path path) {
        try {
            return Files.isRegularFile(path) ? Files.readString(path) : "";
        } catch (IOException ignored) {
            return "";
        }
    }

    private String executableWithWrapper(String tool, boolean isWindows) {
        return BuildToolExecutable.resolve(effectiveBuildRoot, tool, isWindows);
    }

    private Path isolatedMavenToolchainsFile() throws IOException {
        String poms = readQuietly(effectiveBuildRoot.resolve("pom.xml"));
        if (!poms.contains("maven-toolchains-plugin") && !poms.contains("jdkToolchain")
                && !Files.isRegularFile(effectiveBuildRoot.resolve(".mvn/toolchains.xml"))) return null;
        Map<Integer, Path> homes = BuildJavaSelection.installedJdkHomes();
        if (homes.isEmpty()) return null;
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<toolchains>\n");
        for (Map.Entry<Integer, Path> entry : homes.entrySet()) {
            xml.append("  <toolchain><type>jdk</type><provides><version>")
                    .append(entry.getKey()).append("</version></provides><configuration><jdkHome>")
                    .append(escapeXml(entry.getValue().toString()))
                    .append("</jdkHome></configuration></toolchain>\n");
        }
        xml.append("</toolchains>\n");
        Path file = Files.createTempFile("cocomut-maven-toolchains-", ".xml");
        Files.writeString(file, xml.toString());
        file.toFile().deleteOnExit();
        return file;
    }

    private static String escapeXml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    private CommandResult runCommand(List<String> command, boolean recordAttempt)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(effectiveBuildRoot.toFile());
        pb.redirectErrorStream(true);
        buildJavaSelection.apply(pb);
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
            CommandResult result = new CommandResult(-1, output.toString(), true);
            if (recordAttempt) recordBuildAttempt(command, result);
            return result;
        }
        drainer.join(1000);
        CommandResult result = new CommandResult(process.exitValue(), output.toString(), false);
        if (recordAttempt) recordBuildAttempt(command, result);
        return result;
    }

    private void recordBuildAttempt(List<String> command, CommandResult result) {
        finalBuildOutput = result.output();
        buildAttempts.add(new BuildAttempt(
                command,
                buildJavaSelection.javaHome() == null ? "" : buildJavaSelection.javaHome().toString(),
                buildJavaSelection.version(),
                buildJavaSelection.evidence(),
                result.exitCode(),
                result.timedOut(),
                BuildFailureReason.classify(result.output(), result.timedOut(), result.exitCode() == 0)));
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

    private record CommandResult(int exitCode, String output, boolean timedOut) {}

    private record BuildResult(boolean attempted, int exitCode, boolean succeeded, boolean timedOut,
                               String status, String outputTail, BuildFailureReason failureReason) {
        static BuildResult notAttempted(String status) {
            return new BuildResult(false, -1, false, false, status, "", BuildFailureReason.NONE);
        }
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
        try {
            return !existingMainClassOutputDirs(buildSystem).isEmpty()
                    || !existingTestClassOutputDirs(buildSystem).isEmpty()
                    || !existingProjectArtifactJars(buildSystem).isEmpty()
                    || !existingClassDirs(explicitClassOutputDirs).isEmpty()
                    || !existingClassDirs(explicitTestClassOutputDirs).isEmpty()
                    || !existingJars(explicitProjectJars).isEmpty();
        } catch (IOException e) {
            return !existingMainClassOutputDirs(buildSystem).isEmpty()
                    || !existingClassDirs(explicitClassOutputDirs).isEmpty()
                    || !existingClassDirs(explicitTestClassOutputDirs).isEmpty()
                    || !existingJars(explicitProjectJars).isEmpty();
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
            for (Path module : collectMavenModuleDirs(effectiveBuildRoot)) {
                addClassDir(dirs, module.resolve("target/classes"));
            }
            if (lastBuildResult != null && lastBuildResult.succeeded()) {
                dirs.addAll(builtMavenClassOutputs(effectiveBuildRoot, false));
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
            for (Path module : collectMavenModuleDirs(effectiveBuildRoot)) {
                addClassDir(dirs, module.resolve("target/test-classes"));
            }
            if (lastBuildResult != null && lastBuildResult.succeeded()) {
                dirs.addAll(builtMavenClassOutputs(effectiveBuildRoot, true));
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

    static List<Path> builtMavenClassOutputs(Path root, boolean tests) {
        if (root == null || !Files.isDirectory(root)) return List.of();
        Path suffix = Path.of("target", tests ? "test-classes" : "classes");
        List<Path> outputs = new ArrayList<>();
        try (var walk = Files.walk(root, 12)) {
            for (Path dir : walk.filter(Files::isDirectory)
                    .filter(path -> path.endsWith(suffix))
                    .toList()) {
                Path target = dir.getParent();
                Path module = target == null ? null : target.getParent();
                if (module != null && Files.isRegularFile(module.resolve("pom.xml"))
                        && containsClassFile(dir)) {
                    outputs.add(dir.toAbsolutePath().normalize());
                }
            }
        } catch (IOException ignored) {
            // Conventional and explicitly supplied outputs remain available.
        }
        return new ArrayList<>(new LinkedHashSet<>(outputs));
    }

    private List<Path> existingProjectArtifactJars(String buildSystem) throws IOException {
        List<Path> jars = new ArrayList<>();
        if ("maven".equals(buildSystem)) {
            addJarsFromDirectory(jars, projectPath.resolve("target"));
            for (Path module : collectMavenModuleDirs(effectiveBuildRoot)) {
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
            // Plain Java projects do not have a reliable convention for
            // project-owned JARs. Root/lib JARs are dependencies unless the
            // caller supplies them explicitly with --project-jar.
        }
        return new ArrayList<>(new LinkedHashSet<>(jars));
    }

    private void addGradleOutputDirs(List<Path> dirs, String sourceSet) {
        try (var walk = Files.walk(projectPath, 10)) {
            for (Path dir : walk.filter(Files::isDirectory)
                    .filter(path -> path.endsWith(Path.of("build/classes/java/" + sourceSet))
                            || path.endsWith(Path.of("build/classes/kotlin/" + sourceSet))
                            || isAndroidClassOutput(path, sourceSet))
                    .toList()) {
                addClassDir(dirs, dir);
            }
        } catch (IOException ignored) {
            // Missing output dirs are reported through phase-1 bytecode counts.
        }
    }

    private static boolean isAndroidClassOutput(Path path, String sourceSet) {
        String normalized = path.toString().replace('\\', '/');
        boolean testOutput = normalized.contains("/androidTest/") || normalized.contains("/test/")
                || normalized.contains("/testDebug/") || normalized.contains("/testRelease/");
        if ("test".equals(sourceSet) != testOutput) return false;
        return (normalized.contains("/build/intermediates/javac/") && normalized.endsWith("/classes"))
                || normalized.contains("/build/tmp/kotlin-classes/")
                || normalized.contains("/build/intermediates/classes/");
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

    private static boolean containsClassFile(Path dir) {
        try (java.util.stream.Stream<Path> stream = Files.walk(dir)) {
            return stream.anyMatch(p -> p.toString().endsWith(".class"));
        } catch (IOException e) {
            return false;
        }
    }

    private static List<Path> mergePaths(List<Path>... lists) {
        LinkedHashSet<Path> merged = new LinkedHashSet<>();
        for (List<Path> list : lists) {
            if (list == null) {
                continue;
            }
            for (Path path : list) {
                if (path != null) {
                    merged.add(path.toAbsolutePath().normalize());
                }
            }
        }
        return new ArrayList<>(merged);
    }

    private List<Path> readClasspathFiles() throws IOException {
        List<Path> entries = new ArrayList<>();
        for (Path file : explicitClasspathFiles) {
            if (!Files.isRegularFile(file)) {
                continue;
            }
            for (String line : Files.readString(file, StandardCharsets.UTF_8).split("\\R")) {
                String trimmed = line.strip();
                if (trimmed.isBlank() || trimmed.startsWith("#")) {
                    continue;
                }
                entries.addAll(parsePathList(trimmed, file.getParent()));
            }
        }
        return new ArrayList<>(new LinkedHashSet<>(entries));
    }

    private static List<Path> parsePathList(String raw, Path baseDir) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<Path> paths = new ArrayList<>();
        for (String item : raw.strip().split(Pattern.quote(java.io.File.pathSeparator))) {
            if (!item.isBlank()) {
                Path path = Path.of(item.strip());
                if (!path.isAbsolute() && baseDir != null) {
                    path = baseDir.resolve(path).normalize();
                }
                if (Files.exists(path)) {
                    paths.add(path.toAbsolutePath().normalize());
                }
            }
        }
        return paths;
    }

    private List<Path> existingClassDirs(List<Path> dirs) {
        List<Path> existing = new ArrayList<>();
        for (Path dir : dirs) {
            addClassDir(existing, dir);
        }
        return new ArrayList<>(new LinkedHashSet<>(existing));
    }

    private static List<Path> existingDirs(List<Path> dirs) {
        List<Path> existing = new ArrayList<>();
        for (Path dir : dirs == null ? List.<Path>of() : dirs) {
            if (dir != null && Files.isDirectory(dir)) {
                existing.add(dir.toAbsolutePath().normalize());
            }
        }
        return new ArrayList<>(new LinkedHashSet<>(existing));
    }

    private List<Path> existingClassDirsFromClasspathFile(List<Path> entries) {
        List<Path> existing = new ArrayList<>();
        for (Path entry : entries) {
            if (Files.isDirectory(entry)) {
                addClassDir(existing, entry);
            }
        }
        return new ArrayList<>(new LinkedHashSet<>(existing));
    }

    private static List<Path> existingJars(List<Path> jars) {
        List<Path> existing = new ArrayList<>();
        for (Path jar : jars) {
            if (jar != null && jar.toString().endsWith(".jar") && Files.isRegularFile(jar)) {
                existing.add(jar.toAbsolutePath().normalize());
            }
        }
        return new ArrayList<>(new LinkedHashSet<>(existing));
    }

    private static List<Path> existingJarsFromClasspathFile(List<Path> entries) {
        List<Path> existing = new ArrayList<>();
        for (Path entry : entries) {
            if (entry.toString().endsWith(".jar") && Files.isRegularFile(entry)) {
                existing.add(entry.toAbsolutePath().normalize());
            }
        }
        return new ArrayList<>(new LinkedHashSet<>(existing));
    }

    private static void addIfDirectory(Set<Path> dirs, Path path) {
        if (path != null && Files.isDirectory(path)) {
            dirs.add(path.toAbsolutePath().normalize());
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
        while (!queue.isEmpty()) {
            Path dir = queue.poll().toAbsolutePath().normalize();
            if (!seen.add(dir)) continue;
            Path pom = dir.resolve("pom.xml");
            if (!Files.isRegularFile(pom)) continue;
            for (String module : directMavenModules(pom)) {
                Path child = dir.resolve(module).normalize();
                if (Files.isDirectory(child)) {
                    modules.add(child);
                    queue.add(child);
                }
            }
        }
        return modules;
    }

    private static List<String> directMavenModules(Path pom) {
        try {
            Element project = parseXml(pom);
            for (Node child = project.getFirstChild(); child != null; child = child.getNextSibling()) {
                if (!(child instanceof Element element) || !"modules".equals(elementName(element))) {
                    continue;
                }
                List<String> modules = new ArrayList<>();
                for (Node module = element.getFirstChild(); module != null; module = module.getNextSibling()) {
                    if (module instanceof Element moduleElement && "module".equals(elementName(moduleElement))) {
                        String value = moduleElement.getTextContent().trim();
                        if (!value.isBlank()) {
                            modules.add(value);
                        }
                    }
                }
                return modules;
            }
        } catch (Exception ignored) {
            // Malformed or unsupported POMs are handled as projects without declared modules.
        }
        return List.of();
    }

    private static Element parseXml(Path path) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(path.toFile()).getDocumentElement();
    }

    private static Element directChild(Element parent, String name) {
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && name.equals(elementName(element))) return element;
        }
        return null;
    }

    private static String directChildText(Element parent, String name) {
        Element child = directChild(parent, name);
        return child == null ? "" : child.getTextContent().trim();
    }

    private static String elementName(Element element) {
        return element.getLocalName() != null ? element.getLocalName() : element.getTagName();
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
