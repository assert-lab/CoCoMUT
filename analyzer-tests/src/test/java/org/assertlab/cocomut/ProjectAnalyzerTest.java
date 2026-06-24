package org.assertlab.cocomut;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;

/**
 * Test suite for {@link ProjectAnalyzer} (Phase 1).
 *
 * <p>Uses {@code analyzer-core} as the subject project: it has its own
 * {@code pom.xml} (with explicit {@code maven.compiler.source}), real source
 * under {@code src/main/java}, and compiled classes under {@code target/classes}
 * — everything {@link ProjectAnalyzer} needs to exercise all code paths.
 *
 * <p>The path is derived from the test module's working directory
 * ({@code analyzer-tests/}) by stepping up one level to the repo root and then
 * resolving the {@code analyzer-core} sibling module.
 */
public class ProjectAnalyzerTest {

    /** Absolute path to the analyzer-core sibling module. */
    private static final Path CORE_MODULE =
            Paths.get(System.getProperty("user.dir")).getParent().resolve("analyzer-core");

    private ProjectAnalyzer analyzer;

    @Before
    public void setUp() {
        analyzer = new ProjectAnalyzer(CORE_MODULE);
    }

    @Test
    public void testBuildSystemDetection() throws IOException {
        ProjectMetadata metadata = analyzer.analyze();
        assertNotNull("Metadata should not be null", metadata);
        assertEquals("Should detect Maven build system", "maven", metadata.getBuildSystem());
    }

    @Test
    public void testJavaVersionDetection() throws IOException {
        ProjectMetadata metadata = analyzer.analyze();
        assertNotNull("Java version should be detected", metadata.getJavaVersion());
        assertNotEquals("Java version should not be unknown", "unknown", metadata.getJavaVersion());
        // analyzer-core/pom.xml declares maven.compiler.source=17 as the product baseline.
        assertEquals("Should detect Java version from pom.xml", "17", metadata.getJavaVersion());
    }

    @Test
    public void detectsMavenReleaseThroughJavaVersionProperty() throws IOException {
        Path project = Files.createTempDirectory("cocomut-maven-release-version-");
        try {
            Files.writeString(project.resolve("pom.xml"), """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <project xmlns="http://maven.apache.org/POM/4.0.0"
                             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                             xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                      <modelVersion>4.0.0</modelVersion>
                      <groupId>demo</groupId>
                      <artifactId>release-version</artifactId>
                      <version>1.0-SNAPSHOT</version>
                      <properties>
                        <java.version>21</java.version>
                        <maven.compiler.release>${java.version}</maven.compiler.release>
                      </properties>
                    </project>
                    """);
            Files.createDirectories(project.resolve("target/classes"));
            Files.write(project.resolve("target/classes/Marker.class"), new byte[] {0, 0, 0, 0});

            ProjectMetadata metadata = new ProjectAnalyzer(project).analyze();

            assertEquals("21", metadata.getJavaVersion());
        } finally {
            deleteRecursively(project);
        }
    }

    @Test
    public void testSourceRootDetection() throws IOException {
        ProjectMetadata metadata = analyzer.analyze();
        assertNotNull("Source root should be detected", metadata.getSourceRoot());
        assertTrue("Source root should exist", java.nio.file.Files.exists(metadata.getSourceRoot()));
        assertTrue("Source root should be directory", java.nio.file.Files.isDirectory(metadata.getSourceRoot()));
    }

    @Test
    public void testClasspathBuild() throws IOException {
        ProjectMetadata metadata = analyzer.analyze();
        assertNotNull("Classpath should be built", metadata.getClasspath());
        assertTrue("Classpath should not be empty", metadata.getClasspath().size() > 0);
        assertTrue("Classpath should contain source root",
                metadata.getClasspath().contains(metadata.getSourceRoot()));
    }

    @Test
    public void testProjectNameExtraction() throws IOException {
        ProjectMetadata metadata = analyzer.analyze();
        assertEquals("Should extract project name from path", "analyzer-core", metadata.getProjectName());
    }

    @Test
    public void testProjectPathSet() throws IOException {
        ProjectMetadata metadata = analyzer.analyze();
        assertNotNull("Project path should be set", metadata.getProjectPath());
        assertEquals("Project path should match input", CORE_MODULE, metadata.getProjectPath());
    }

    @Test
    public void testCompilationValidation() throws IOException {
        ProjectMetadata metadata = analyzer.analyze();
        assertNotNull("Compile status should be set", metadata.getCompileStatus());
        assertFalse("Direct ProjectAnalyzer use must not execute builds by default", metadata.isBuildAttempted());
        assertFalse("compiles means the build command succeeded, not that bytecode exists", metadata.isCompiles());
    }

    @Test
    public void testMetadataToString() throws IOException {
        ProjectMetadata metadata = analyzer.analyze();
        String str = metadata.toString();
        assertNotNull("toString should not be null", str);
        assertTrue("toString should contain project name", str.contains("analyzer-core"));
        assertTrue("toString should contain build system", str.contains("maven"));
        assertTrue("toString should contain Java version", str.contains("17"));
    }

    @Test
    public void testConvenienceMethodStaticAnalyze() throws IOException {
        ProjectMetadata metadata = ProjectAnalyzer.analyze(CORE_MODULE.toString());
        assertNotNull("Static analyze method should return metadata", metadata);
        assertEquals("Should detect Maven", "maven", metadata.getBuildSystem());
    }

    @Test
    public void testConvenienceMethodWithBuildSystem() throws IOException {
        ProjectMetadata metadata = ProjectAnalyzer.analyze(CORE_MODULE.toString(), "maven");
        assertNotNull("Static analyze with build system should work", metadata);
        assertEquals("Should use specified build system", "maven", metadata.getBuildSystem());
    }

    @Test
    public void testInvalidProjectPath() {
        assertThrows("Should throw for invalid project path",
                IllegalArgumentException.class,
                () -> new ProjectAnalyzer(Paths.get("/nonexistent/path")));
    }

    @Test
    public void testAnalyzerWithCustomConfig() throws IOException {
        ProjectAnalyzer customAnalyzer = new ProjectAnalyzer(CORE_MODULE, true, "maven");
        ProjectMetadata metadata = customAnalyzer.analyze();
        assertNotNull("Custom analyzer should work", metadata);
        assertEquals("Should use specified Maven system", "maven", metadata.getBuildSystem());
    }

    @Test
    public void classpathFileDependencyDirectoryDoesNotSatisfyProjectBytecode() throws IOException {
        Path project = Files.createTempDirectory("cocomut-classpath-file-project-");
        Path dependencyDir = Files.createTempDirectory("cocomut-exploded-dependency-");
        Path classpathFile = Files.createTempFile("cocomut-classpath", ".txt");
        try {
            Files.createDirectories(project.resolve("src/main/java/demo"));
            Files.writeString(project.resolve("src/main/java/demo/App.java"), "package demo; class App {}\n");
            Files.createDirectories(dependencyDir.resolve("lib"));
            Files.write(dependencyDir.resolve("lib/Dependency.class"), new byte[] {0, 0, 0, 0});
            Files.writeString(classpathFile, dependencyDir.toString());

            ProjectMetadata metadata = new ProjectAnalyzer(ContextRequest.builder()
                    .projectRoot(project)
                    .skipBuild(true)
                    .classpathFile(classpathFile)
                    .build()).analyze();

            assertTrue("Classpath-file directory should be dependency classpath",
                    metadata.getDependencyClasspath().contains(dependencyDir.toAbsolutePath().normalize()));
            assertEquals("Classpath-file directory must not become project bytecode",
                    0, metadata.getMainClassOutputs().size());
            assertTrue("No project bytecode means analysis cannot proceed",
                    !metadata.isAnalysisCanProceed());
        } finally {
            deleteRecursively(project);
            deleteRecursively(dependencyDir);
            Files.deleteIfExists(classpathFile);
        }
    }

    @Test
    public void explicitProjectArtifactsDoNotMergeStaleConventionalOutputs() throws IOException {
        Path project = Files.createTempDirectory("cocomut-explicit-artifact-project-");
        Path explicitOutput = Files.createTempDirectory("cocomut-explicit-output-");
        try {
            Files.createDirectories(project.resolve("target/classes/stale"));
            Files.write(project.resolve("target/classes/stale/Stale.class"), new byte[] {0, 0, 0, 0});
            Files.createDirectories(explicitOutput.resolve("demo"));
            Files.write(explicitOutput.resolve("demo/App.class"), new byte[] {1, 2, 3, 4});

            ProjectMetadata metadata = new ProjectAnalyzer(ContextRequest.builder()
                    .projectRoot(project)
                    .skipBuild(true)
                    .classOutputDir(explicitOutput)
                    .build()).analyze();

            assertEquals("Explicit bytecode mode should use only the supplied project output",
                    List.of(explicitOutput.toAbsolutePath().normalize()), metadata.getMainClassOutputs());
            assertEquals("explicit", metadata.getBytecodeOrigin());
        } finally {
            deleteRecursively(project);
            deleteRecursively(explicitOutput);
        }
    }

    @Test
    public void manifestBytecodeHashIsStableAcrossCheckoutPaths() throws Exception {
        Path rootA = Files.createTempDirectory("cocomut-hash-a-");
        Path rootB = Files.createTempDirectory("cocomut-hash-b-");
        Path outA = Files.createTempDirectory("cocomut-manifest-a-");
        Path outB = Files.createTempDirectory("cocomut-manifest-b-");
        try {
            Path classesA = rootA.resolve("target/classes/demo");
            Path classesB = rootB.resolve("target/classes/demo");
            Files.createDirectories(classesA);
            Files.createDirectories(classesB);
            Files.write(classesA.resolve("Same.class"), new byte[] {1, 2, 3});
            Files.write(classesB.resolve("Same.class"), new byte[] {1, 2, 3});

            String validHashA = "a".repeat(64);
            String validHashB = "b".repeat(64);
            Path manifestA = ExtractionManifest.write(outA, manifestMetadata(rootA, rootA.resolve("target/classes")),
                    null, java.util.Map.of(), validHashA, null, java.util.Map.of("status", "FAILED"));
            Path manifestB = ExtractionManifest.write(outB, manifestMetadata(rootB, rootB.resolve("target/classes")),
                    null, java.util.Map.of(), validHashB, null, java.util.Map.of("status", "FAILED"));

            ObjectMapper mapper = new ObjectMapper();
            JsonNode hashA = mapper.readTree(manifestA.toFile()).path("hashes").path("combined_project_bytecode").path("sha256");
            JsonNode hashB = mapper.readTree(manifestB.toFile()).path("hashes").path("combined_project_bytecode").path("sha256");
            assertEquals("Identical bytecode content should hash identically across checkout paths",
                    hashA.asText(), hashB.asText());
        } finally {
            deleteRecursively(rootA);
            deleteRecursively(rootB);
            deleteRecursively(outA);
            deleteRecursively(outB);
        }
    }

    @Test
    public void gitDirtyManifestStateTracksCleanAndModifiedRepositories() throws Exception {
        Path project = Files.createTempDirectory("cocomut-git-dirty-");
        Path output = Files.createTempDirectory("cocomut-git-dirty-out-");
        try {
            run(project, "git", "init");
            run(project, "git", "config", "user.email", "test@example.com");
            run(project, "git", "config", "user.name", "CoCoMUT Test");
            Files.writeString(project.resolve("Tracked.java"), "class Tracked {}\n");
            run(project, "git", "add", ".");
            run(project, "git", "commit", "-m", "initial");

            Path cleanManifest = ExtractionManifest.write(output, manifestMetadata(project, project),
                    null, java.util.Map.of(), "c".repeat(64), null, java.util.Map.of("status", "FAILED"));
            ObjectMapper mapper = new ObjectMapper();
            assertFalse("Clean repositories must not be dirty",
                    mapper.readTree(cleanManifest.toFile()).path("project").path("git").path("dirty").asBoolean());

            Files.writeString(project.resolve("Tracked.java"), "class Tracked { int changed; }\n");
            Path dirtyManifest = ExtractionManifest.write(output, manifestMetadata(project, project),
                    null, java.util.Map.of(), "d".repeat(64), null, java.util.Map.of("status", "FAILED"));
            assertTrue("Modified repositories must be dirty",
                    mapper.readTree(dirtyManifest.toFile()).path("project").path("git").path("dirty").asBoolean());
        } finally {
            deleteRecursively(project);
            deleteRecursively(output);
        }
    }

    @Test
    public void failedBuildWithStaleBytecodeDoesNotProceedByDefault() throws IOException {
        Path project = Files.createTempDirectory("cocomut-stale-bytecode-");
        try {
            createBrokenMavenProjectWithStaleBytecode(project);

            ProjectMetadata metadata = new ProjectAnalyzer(ContextRequest.builder()
                    .projectRoot(project)
                    .allowUnsandboxedBuild()
                    .build()).analyze();

            assertTrue(metadata.isBuildAttempted());
            assertFalse(metadata.isBuildSucceeded());
            assertTrue(metadata.isBytecodeAvailable());
            assertEquals("preexisting", metadata.getBytecodeOrigin());
            assertFalse("Failed attempted builds must not proceed with stale bytecode by default",
                    metadata.isAnalysisCanProceed());

            ProjectMetadata explicitRisk = new ProjectAnalyzer(ContextRequest.builder()
                    .projectRoot(project)
                    .allowPreexistingBytecodeAfterBuildFailure()
                    .build()).analyze();
            assertTrue("The risky stale-bytecode path requires an explicit policy",
                    explicitRisk.isAnalysisCanProceed());
        } finally {
            deleteRecursively(project);
        }
    }

    @Test
    public void requestHashIncludesExplicitProjectArtifacts() throws Exception {
        Path project = Files.createTempDirectory("cocomut-request-hash-");
        Path artifactA = project.resolve("artifact-a/classes");
        Path artifactB = project.resolve("artifact-b/classes");
        try {
            Files.createDirectories(artifactA);
            Files.createDirectories(artifactB);
            Files.write(artifactA.resolve("A.class"), new byte[] {1});
            Files.write(artifactB.resolve("A.class"), new byte[] {2});

            String hashA = requestHash(ContextRequest.builder()
                    .projectRoot(project)
                    .skipBuild(true)
                    .classOutputDir(project.relativize(artifactA))
                    .build());
            String hashB = requestHash(ContextRequest.builder()
                    .projectRoot(project)
                    .skipBuild(true)
                    .classOutputDir(project.relativize(artifactB))
                    .build());

            assertNotEquals("Different explicit project artifacts must produce different request hashes",
                    hashA, hashB);
        } finally {
            deleteRecursively(project);
        }
    }

    @Test
    public void dependencyClassDirectoriesReachProjectModel() throws IOException {
        Path project = Files.createTempDirectory("cocomut-dep-dir-model-");
        Path dependency = Files.createTempDirectory("cocomut-dep-dir-");
        try {
            Files.createDirectories(dependency.resolve("api"));
            Files.write(dependency.resolve("api/GeneratedApi.class"), new byte[] {1, 2, 3});
            ProjectMetadata metadata = new ProjectMetadata.Builder()
                    .projectName("dep-dir")
                    .projectPath(project)
                    .buildSystem("none")
                    .javaVersion("17")
                    .sourceRoot(project)
                    .sourceRoots(List.of(project))
                    .testSourceRoots(List.of())
                    .classpath(List.of(dependency))
                    .mainClassOutputs(List.of())
                    .testClassOutputs(List.of())
                    .projectArtifactJars(List.of())
                    .dependencyClasspath(List.of(dependency))
                    .compileStatus("BUILD DENIED; NO PROJECT BYTECODE")
                    .buildPolicy(ContextRequest.BuildPolicy.DENY_BUILD)
                    .build();

            org.assertlab.cocomut.source.ProjectModel model =
                    org.assertlab.cocomut.source.ProjectModel.from(metadata);

            assertTrue("Dependency class directories must be passed to the source backend classpath",
                    model.dependencyClasspath().contains(dependency.toAbsolutePath().normalize()));
        } finally {
            deleteRecursively(project);
            deleteRecursively(dependency);
        }
    }

    @Test
    public void projectArtifactJarsAreNotReportedAsDependencyJars() throws IOException {
        Path project = Files.createTempDirectory("cocomut-project-jar-model-");
        try {
            Path projectJar = project.resolve("app.jar");
            Path dependencyJar = project.resolve("dependency.jar");
            Files.write(projectJar, new byte[] {1, 2, 3});
            Files.write(dependencyJar, new byte[] {4, 5, 6});
            ProjectMetadata metadata = new ProjectMetadata.Builder()
                    .projectName("project-jar")
                    .projectPath(project)
                    .buildSystem("none")
                    .javaVersion("17")
                    .sourceRoot(project)
                    .sourceRoots(List.of(project))
                    .testSourceRoots(List.of())
                    .classpath(List.of(projectJar, dependencyJar))
                    .mainClassOutputs(List.of())
                    .testClassOutputs(List.of())
                    .projectArtifactJars(List.of(projectJar))
                    .dependencyClasspath(List.of(dependencyJar))
                    .compileStatus("BUILD DENIED; PROJECT BYTECODE AVAILABLE")
                    .buildPolicy(ContextRequest.BuildPolicy.DENY_BUILD)
                    .build();

            org.assertlab.cocomut.source.ProjectModel model =
                    org.assertlab.cocomut.source.ProjectModel.from(metadata);

            assertTrue(model.projectArtifactJars().contains(projectJar.toAbsolutePath().normalize()));
            assertFalse("Project artifact JARs must not inflate the dependency JAR count",
                    model.dependencyJars().contains(projectJar.toAbsolutePath().normalize()));
            assertTrue(model.dependencyJars().contains(dependencyJar.toAbsolutePath().normalize()));
        } finally {
            deleteRecursively(project);
        }
    }

    @Test
    public void dependencyClasspathHashPreservesOrderSeparatelyFromContentSet() throws Exception {
        Path project = Files.createTempDirectory("cocomut-classpath-order-");
        Path outputA = Files.createTempDirectory("cocomut-classpath-order-a-");
        Path outputB = Files.createTempDirectory("cocomut-classpath-order-b-");
        try {
            Path first = project.resolve("first.jar");
            Path second = project.resolve("second.jar");
            Files.write(first, new byte[] {1});
            Files.write(second, new byte[] {2});

            ObjectMapper mapper = new ObjectMapper();
            Path manifestA = ExtractionManifest.write(outputA, manifestMetadataWithDependencies(project, List.of(first, second)),
                    null, java.util.Map.of(), "e".repeat(64), null, java.util.Map.of("status", "FAILED"));
            Path manifestB = ExtractionManifest.write(outputB, manifestMetadataWithDependencies(project, List.of(second, first)),
                    null, java.util.Map.of(), "f".repeat(64), null, java.util.Map.of("status", "FAILED"));

            JsonNode hashesA = mapper.readTree(manifestA.toFile()).path("hashes");
            JsonNode hashesB = mapper.readTree(manifestB.toFile()).path("hashes");
            assertNotEquals("Ordered classpath hash must distinguish classpath order",
                    hashesA.path("dependency_classpath").path("sha256").asText(),
                    hashesB.path("dependency_classpath").path("sha256").asText());
            assertEquals("Content-set hash should remain stable for the same dependency set",
                    hashesA.path("dependency_classpath_content_set").path("sha256").asText(),
                    hashesB.path("dependency_classpath_content_set").path("sha256").asText());
        } finally {
            deleteRecursively(project);
            deleteRecursively(outputA);
            deleteRecursively(outputB);
        }
    }

    private static ProjectMetadata manifestMetadata(Path project, Path classOutput) {
        return new ProjectMetadata.Builder()
                .projectName(project.getFileName().toString())
                .projectPath(project)
                .buildSystem("none")
                .javaVersion("unknown")
                .sourceRoot(project)
                .sourceRoots(List.of())
                .testSourceRoots(List.of())
                .classpath(List.of(classOutput))
                .mainClassOutputs(List.of(classOutput))
                .testClassOutputs(List.of())
                .projectArtifactJars(List.of())
                .dependencyClasspath(List.of())
                .compiles(true)
                .compileStatus("BUILD DENIED; PROJECT BYTECODE AVAILABLE")
                .buildPolicy(ContextRequest.BuildPolicy.DENY_BUILD)
                .bytecodeAvailable(true)
                .bytecodeOrigin("preexisting")
                .analysisCanProceed(true)
                .build();
    }

    private static ProjectMetadata manifestMetadataWithDependencies(Path project, List<Path> dependencyClasspath) {
        return new ProjectMetadata.Builder()
                .projectName(project.getFileName().toString())
                .projectPath(project)
                .buildSystem("none")
                .javaVersion("unknown")
                .sourceRoot(project)
                .sourceRoots(List.of())
                .testSourceRoots(List.of())
                .classpath(dependencyClasspath)
                .mainClassOutputs(List.of())
                .testClassOutputs(List.of())
                .projectArtifactJars(List.of())
                .dependencyClasspath(dependencyClasspath)
                .compileStatus("BUILD DENIED; NO PROJECT BYTECODE")
                .buildPolicy(ContextRequest.BuildPolicy.DENY_BUILD)
                .bytecodeAvailable(false)
                .bytecodeOrigin("none")
                .analysisCanProceed(false)
                .build();
    }

    private static void createBrokenMavenProjectWithStaleBytecode(Path project) throws IOException {
        Files.writeString(project.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>demo</groupId>
                  <artifactId>broken</artifactId>
                  <version>1.0-SNAPSHOT</version>
                  <properties>
                    <maven.compiler.release>17</maven.compiler.release>
                  </properties>
                </project>
                """);
        Files.createDirectories(project.resolve("src/main/java/demo"));
        Files.writeString(project.resolve("src/main/java/demo/Broken.java"),
                "package demo; public class Broken { syntax error }\n");
        Files.createDirectories(project.resolve("target/classes/demo"));
        Files.write(project.resolve("target/classes/demo/Broken.class"), new byte[] {0, 1, 2, 3});
    }

    private static String requestHash(ContextRequest request) throws Exception {
        Orchestrator orchestrator = new Orchestrator(request);
        Method method = Orchestrator.class.getDeclaredMethod("requestHash");
        method.setAccessible(true);
        return String.valueOf(method.invoke(orchestrator));
    }

    private static void run(Path cwd, String... command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command)
                .directory(cwd.toFile())
                .redirectErrorStream(true)
                .start();
        if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new IOException("Command failed: " + String.join(" ", command));
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            for (Path path : walk.sorted((a, b) -> b.compareTo(a)).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
