package org.assertlab.context4docugen;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Integration tests for the complete pipeline.
 * Uses {@link TestFixtures#minimalMavenProjectRoot()} so runs stay fast and do not
 * traverse bundled commons-* trees under the workspace root.
 */
@Category(FastTests.class)
public class IntegrationTest {
    private Path testProjectPath;
    private Orchestrator orchestrator;

    @Before
    public void setUp() throws Exception {
        TestFixtures.ensureMinimalMavenProjectCompiled();
        testProjectPath = TestFixtures.minimalMavenProjectRoot();
        orchestrator = new Orchestrator(testProjectPath, Orchestrator.ExecutionMode.FULL);
    }

    @After
    public void tearDown() {
        Path jsonDir = testProjectPath.resolve("method_context_json");
        Path csv = testProjectPath.resolve("methods.csv");
        Path csvBackup = testProjectPath.resolve("methods.csv.backup");
        Path callGraphTxt = testProjectPath.resolve("Output_CallGraph_CHA.txt");

        try {
            if (Files.exists(jsonDir)) {
                Files.walk(jsonDir)
                        .sorted((a, b) -> b.compareTo(a))
                        .forEach(path -> {
                            try {
                                Files.delete(path);
                            } catch (Exception e) {
                                // Ignore
                            }
                        });
            }
            if (Files.exists(csv)) {
                Files.delete(csv);
            }
            if (Files.exists(csvBackup)) {
                Files.delete(csvBackup);
            }
            if (Files.exists(callGraphTxt)) {
                Files.delete(callGraphTxt);
            }
        } catch (Exception e) {
            // Ignore cleanup errors
        }
    }

    @Test
    public void testFullPipelineExecution() {
        orchestrator.execute();

        Map<String, Object> report = orchestrator.getExecutionReport();

        assertNotNull("Should have execution report", report);
        assertNotNull("Should have status", report.get("status"));
    }

    @Test
    public void testPhase1ProjectAnalysis() {
        orchestrator.execute();

        ProjectMetadata metadata = orchestrator.getProjectMetadata();

        assertNotNull("Project metadata should be present for fixture", metadata);
        assertNotNull("Project name should not be null", metadata.getProjectName());
        assertNotNull("Build system should be detected", metadata.getBuildSystem());
        assertNotNull("Java version should be detected", metadata.getJavaVersion());
    }

    @Test
    public void testPhase2MethodIdentification() {
        orchestrator.execute();

        List<MethodInfo> methods = orchestrator.getMethodInfos();

        assertNotNull("Should have methods list", methods);
        assertFalse("Fixture should expose at least one method", methods.isEmpty());
    }

    @Test
    public void testExecutionReportCompleteness() {
        orchestrator.execute();

        Map<String, Object> report = orchestrator.getExecutionReport();

        assertTrue("Report should have start_time", report.containsKey("start_time"));
        assertTrue("Report should have end_time", report.containsKey("end_time"));
        assertTrue("Report should have execution_mode", report.containsKey("execution_mode"));
        assertTrue("Report should have status", report.containsKey("status"));
    }

    @Test
    public void testSelectedMode() {
        Orchestrator selectedOrch = new Orchestrator(testProjectPath, Orchestrator.ExecutionMode.SELECTED);

        selectedOrch.execute();
        Map<String, Object> report = selectedOrch.getExecutionReport();

        assertNotNull("Should have report in selected mode", report);
        assertEquals("Execution mode should be SELECTED", "SELECTED", report.get("execution_mode"));
    }

    @Test
    public void testFullModeReport() {
        Orchestrator fullOrch = new Orchestrator(testProjectPath, Orchestrator.ExecutionMode.FULL);

        fullOrch.execute();
        Map<String, Object> report = fullOrch.getExecutionReport();

        assertNotNull("Should have report in full mode", report);
        assertEquals("Execution mode should be FULL", "FULL", report.get("execution_mode"));
    }

    @Test
    public void testDataFlowAcrossPhases() {
        orchestrator.execute();

        ProjectMetadata metadata = orchestrator.getProjectMetadata();
        List<MethodInfo> methods = orchestrator.getMethodInfos();
        Map<String, Object> report = orchestrator.getExecutionReport();

        assertNotNull("Should have metadata", metadata);
        assertNotNull("Should have methods list", methods);
        assertNotNull("Should have execution report", report);
    }

    @Test
    public void testConfigurationValidation() {
        Orchestrator validOrch = new Orchestrator(testProjectPath, Orchestrator.ExecutionMode.FULL);
        boolean valid = validOrch.validateConfiguration();

        assertTrue("Should validate existing project", valid);
    }

    @Test
    public void testInvalidProjectPath() {
        Orchestrator invalidOrch = new Orchestrator(
                Paths.get("/nonexistent/path/to/project"),
                Orchestrator.ExecutionMode.FULL
        );

        boolean valid = invalidOrch.validateConfiguration();

        assertFalse("Should reject non-existent project path", valid);
    }

    @Test
    public void testExecutionDuration() {
        orchestrator.execute();

        Map<String, Object> report = orchestrator.getExecutionReport();

        Object duration = report.get("duration_ms");
        assertNotNull("Should have duration", duration);

        long durationMs = ((Number) duration).longValue();
        assertTrue("Duration should be non-negative", durationMs >= 0);
    }

    @Test
    public void testReportPrinting() {
        orchestrator.execute();

        orchestrator.printReport();

        assertTrue("Print should complete without error", true);
    }

    @Test
    public void testExecutionModesExist() {
        assertTrue("Should have FULL mode", Orchestrator.ExecutionMode.FULL != null);
        assertTrue("Should have SELECTED mode", Orchestrator.ExecutionMode.SELECTED != null);
    }

    @Test
    public void testErrorHandling() {
        Orchestrator errorOrch = new Orchestrator(
                Paths.get("/nonexistent/path"),
                Orchestrator.ExecutionMode.FULL
        );

        errorOrch.execute();
        Map<String, Object> report = errorOrch.getExecutionReport();

        assertNotNull("Should have report even on error", report);
        assertTrue("Report should have status", report.containsKey("status"));
    }

    @Test
    public void testPhaseSequencing() {
        orchestrator.execute();

        Map<String, Object> report = orchestrator.getExecutionReport();

        if ("FAILED".equals(report.get("status"))) {
            assertTrue("Should indicate failed phase", report.containsKey("failed_at_phase"));
        } else {
            assertTrue("Should indicate completed phases", report.containsKey("completed_phases"));
        }
    }
}
