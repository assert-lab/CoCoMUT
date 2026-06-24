package org.assertlab.cocomut;

import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Comparator;

import static org.junit.Assert.*;

/**
 * Tests for {@link Orchestrator}. Pipeline tests use {@link TestFixtures} so they
 * do not scan the entire workspace.
 */
@Category(FastTests.class)
public class OrchestratorTest {
    private Path testProjectPath;
    private Orchestrator orchestrator;

    @Before
    public void setUp() throws Exception {
        TestFixtures.ensureMinimalMavenProjectCompiled();
        testProjectPath = TestFixtures.minimalMavenProjectRoot();
        orchestrator = new Orchestrator(testProjectPath);
    }

    @Test
    public void testOrchestratorCreation() {
        assertNotNull("Orchestrator should be created", orchestrator);

        assertNotNull("Should be able to get execution report", orchestrator.getExecutionReport());
    }

    @Test
    public void testValidateConfiguration() {
        boolean valid = orchestrator.validateConfiguration();
        assertTrue("Should validate configuration for existing project", valid);
    }

    @Test
    public void testOrchestratorExecute() {
        orchestrator.execute();

        Map<String, Object> report = orchestrator.getExecutionReport();

        assertNotNull("Should have execution report", report);
        assertTrue("Report should contain status", report.containsKey("status"));
        assertEquals("Pipeline should have five phases", 5, report.get("pipeline_phases"));
    }

    @Test
    public void testExecutionReport() {
        orchestrator.execute();

        Map<String, Object> report = orchestrator.getExecutionReport();

        assertNotNull("Should have report", report);
        assertNotNull("Should have status", report.get("status"));
        assertNotNull("Should have pipeline phase count", report.get("pipeline_phases"));
    }

    @Test
    public void testProjectMetadataExtraction() {
        orchestrator.execute();

        ProjectMetadata metadata = orchestrator.getProjectMetadata();

        assertNotNull("Metadata should be present for fixture", metadata);
        assertNotNull("Should have project name", metadata.getProjectName());
        assertNotNull("Should have build system", metadata.getBuildSystem());
    }

    @Test
    public void testMethodInfosRetrieval() {
        orchestrator.execute();

        assertNotNull("Should have methods list", orchestrator.getMethodInfos());
        assertFalse("Fixture should yield methods", orchestrator.getMethodInfos().isEmpty());
    }

    @Test
    public void testExecutionReportHasAllKeys() {
        orchestrator.execute();

        Map<String, Object> report = orchestrator.getExecutionReport();

        assertTrue("Report should have status", report.containsKey("status"));
        assertTrue("Report should have execution data", report.size() > 1);
    }

    @Test
    public void testInvalidProjectPath() {
        Orchestrator invalidOrch = new Orchestrator(Path.of("/nonexistent/path"));

        boolean valid = invalidOrch.validateConfiguration();

        assertFalse("Should invalidate non-existent path", valid);
    }

    @Test
    public void testExecutionReportPrint() {
        orchestrator.execute();

        orchestrator.printReport();

        assertTrue("Should complete print without error", true);
    }

    @Test
    public void testExecutionReportContainsPhaseInfo() {
        orchestrator.execute();

        Map<String, Object> report = orchestrator.getExecutionReport();

        assertTrue("Report should contain execution information",
                report.size() > 0);
    }

    @Test
    public void testOrchestratorNullProjectPath() {
        try {
            new Orchestrator((Path) null);
            fail("Should throw NullPointerException");
        } catch (NullPointerException e) {
            assertTrue("Should catch null project path", true);
        }
    }

    @Test
    public void testExecutionReportImmutability() {
        orchestrator.execute();

        Map<String, Object> report1 = orchestrator.getExecutionReport();
        Map<String, Object> report2 = orchestrator.getExecutionReport();

        assertEquals("Reports should be equivalent", report1.keySet(), report2.keySet());
    }

    @Test
    public void testUncompiledProjectFailsWithoutBytecode() throws Exception {
        Path project = Files.createTempDirectory("cocomut-uncompiled-");
        try {
            Path sourceDir = project.resolve("src/main/java/example");
            Files.createDirectories(sourceDir);
            Files.writeString(sourceDir.resolve("SourceOnly.java"), """
                    package example;

                    public class SourceOnly {
                        /**
                         * Greets a person.
                         *
                         * @param name person name
                         * @return greeting text
                         */
                        public String greet(String name) {
                            return "Hello " + name;
                        }
                    }
                    """);

            Orchestrator sourceOnly = new Orchestrator(project);
            assertFalse("Source-only project should fail without compiled bytecode", sourceOnly.execute());

            Map<String, Object> report = sourceOnly.getExecutionReport();
            assertEquals("FAILED", report.get("status"));
            assertEquals(1, report.get("failed_at_phase"));
            assertEquals(false, report.get("phase_1_compiles"));
            assertEquals(true, report.get("phase_1_source_available"));
            assertTrue(String.valueOf(report.get("phase_1_error")).contains("compiled project bytecode"));
            assertTrue(Files.isRegularFile(Path.of(String.valueOf(report.get("extraction_report_file")))));
        } finally {
            deleteRecursively(project);
        }
    }

    private static void deleteRecursively(Path root) throws Exception {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
