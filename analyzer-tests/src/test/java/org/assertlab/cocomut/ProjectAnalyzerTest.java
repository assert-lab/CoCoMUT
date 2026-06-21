package org.assertlab.cocomut;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

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
}
