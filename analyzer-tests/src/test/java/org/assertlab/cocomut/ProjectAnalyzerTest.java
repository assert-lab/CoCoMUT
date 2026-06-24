package org.assertlab.cocomut;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
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
        // analyzer-core/target/classes is populated by the reactor before tests run.
        ProjectMetadata metadata = analyzer.analyze();
        assertNotNull("Compile status should be set", metadata.getCompileStatus());
        assertTrue("Project should compile successfully", metadata.isCompiles());
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

            Path manifestA = ExtractionManifest.write(outA, manifestMetadata(rootA, rootA.resolve("target/classes")),
                    null, java.util.Map.of(), "hash-a", null, java.util.Map.of("status", "FAILED"));
            Path manifestB = ExtractionManifest.write(outB, manifestMetadata(rootB, rootB.resolve("target/classes")),
                    null, java.util.Map.of(), "hash-b", null, java.util.Map.of("status", "FAILED"));

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
