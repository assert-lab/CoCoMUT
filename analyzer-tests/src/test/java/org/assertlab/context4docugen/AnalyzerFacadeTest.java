package org.assertlab.context4docugen;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * End-to-end tests for {@link AnalyzerFacade} and the SELECTED-mode JSON output.
 *
 * <p>Uses the shared {@link TestFixtures} minimal Maven project (already compiled)
 * so the full 6-phase pipeline runs against real bytecode and source.
 */
@Category(FastTests.class)
public class AnalyzerFacadeTest {

    private Path fixtureRoot;

    @Before
    public void setUp() throws Exception {
        TestFixtures.ensureMinimalMavenProjectCompiled();
        fixtureRoot = TestFixtures.minimalMavenProjectRoot();
    }

    /**
     * The fixture has no {@code inputs_selected.csv}, so the facade must
     * auto-detect Maven + scan-all and run the pipeline to SUCCESS.
     */
    @Test
    public void facadeAnalyzesPlainMavenProjectEndToEnd() throws Exception {
        Map<String, Object> report = AnalyzerFacade.analyze(fixtureRoot);

        assertNotNull("Facade should return a report", report);
        assertEquals("Pipeline should succeed on the minimal fixture",
                "SUCCESS", report.get("status"));
        // Scan-all maps to FULL execution mode
        assertEquals("FULL", report.get("execution_mode"));
        assertTrue("Phase 2 should identify the fixture methods",
                report.containsKey("phase_2_methods_identified"));
    }

    /**
     * CRITICAL regression test: SELECTED mode must propagate the human-written
     * docstring and test prefix from inputs_selected.csv all the way into the JSON.
     * This guards the fix where JsonGenerator previously dropped both fields.
     */
    @Test
    public void selectedModePropagatesDocstringAndTestPrefixIntoJson() throws Exception {
        // focal_method body for Hello.greet(String) with \n escaped per CSV format
        String focal = "public String greet(String name) {\\n"
                + "        return prefix() + name;\\n"
                + "    }";
        String testPrefix = "public void test0() { new Hello().greet(\\\"x\\\"); }";
        String docstring  = "Greets a person by their name.";
        String id = "9001";

        Path tempCsv = Files.createTempFile("inputs_selected", ".csv");
        Files.writeString(tempCsv,
                "focal_method|test_prefix|docstring|id\n"
                        + focal + "|" + testPrefix + "|" + docstring + "|" + id + "\n");

        Orchestrator orch = new Orchestrator(fixtureRoot, Orchestrator.ExecutionMode.SELECTED)
                .setInputCsvPath(tempCsv)
                .setOutputMode(AnalysisOptions.OutputMode.JSON)
                .setOutputDirectory(Files.createTempDirectory("c4dg-selected-json"));
        boolean ok = orch.execute();

        assertTrue("SELECTED-mode pipeline should succeed", ok);

        Path jsonFile = findJsonForMethod(Path.of(String.valueOf(orch.getExecutionReport().get("phase_5_output_directory"))), "greet");
        assertTrue("Expected JSON output at " + jsonFile, Files.exists(jsonFile));

        JsonNode json = new ObjectMapper().readTree(jsonFile.toFile());

        assertTrue("JSON must contain original_docstring", json.has("original_docstring"));
        assertEquals("Greets a person by their name.",
                json.get("original_docstring").asText());

        assertTrue("JSON must contain test_prefix", json.has("test_prefix"));
        assertTrue("test_prefix should contain the test method",
                json.get("test_prefix").asText().contains("greet"));

        // Sanity: MUT node still present with the real method
        assertTrue("JSON must contain MUT node", json.has("MUT"));
        assertEquals("greet", json.get("MUT").get("method_name").asText());

        Files.deleteIfExists(tempCsv);
        Files.deleteIfExists(jsonFile);  // cleanup so we don't pollute the fixture
    }

    @Test
    public void entryPointStrategyProcessesOnlyPublicMethods() throws Exception {
        Map<String, Object> report = AnalyzerFacade.analyze(
                fixtureRoot, new org.assertlab.context4docugen.strategy.EntryPointScanStrategy());

        assertEquals("SUCCESS", report.get("status"));
        assertEquals("ENTRY_POINTS", report.get("phase_2_strategy"));
        // Hello: greet (public) kept; prefix (private) + main (excluded) dropped → 1 method
        assertEquals("Only the single public non-main method should be processed",
                1, ((Number) report.get("phase_2_methods_identified")).intValue());
    }

    @Test
    public void serviceApiReturnsTypedExtractionReport() throws Exception {
        ContextRequest request = ContextRequest.builder()
                .projectRoot(fixtureRoot)
                .methodSelection(MethodSelection.entryPoints())
                .callGraphAlgorithm(CallGraphGenerator.Algorithm.NONE)
                .outputMode(AnalysisOptions.OutputMode.JSONL)
                .build();

        ExtractionReport report = ContextExtractorService.createDefault().extract(request);

        assertTrue("Service API should report success", report.successful());
        assertEquals("SUCCESS", report.status());
        assertEquals(1, report.methodsIdentified());
        assertEquals(1, report.contextsExtracted());
        assertEquals(1, report.jsonlRows());
        assertNotNull("JSONL output path should be available", report.jsonlFile());
        assertTrue("Failure codes should record disabled call graph",
                report.failureCodes().contains("CALL_GRAPH_DISABLED"));
    }

    @Test
    public void autoCallGraphUsesRtaWhenCompiledClassesExist() throws Exception {
        ContextRequest request = ContextRequest.builder()
                .projectRoot(fixtureRoot)
                .methodSelection(MethodSelection.entryPoints())
                .callGraphAlgorithm(CallGraphGenerator.Algorithm.AUTO)
                .sourceResolution(AnalysisOptions.SourceResolution.AUTO)
                .outputMode(AnalysisOptions.OutputMode.JSONL)
                .maxMethods(1)
                .build();

        ExtractionReport report = ContextExtractorService.createDefault().extract(request);

        assertTrue(report.successful());
        assertEquals("AUTO", report.asMap().get("phase_3_algorithm"));
        assertEquals("RTA", report.asMap().get("phase_3_effective_algorithm"));
    }

    @Test
    public void fullModeJsonOmitsResearchFields() throws Exception {
        // FULL mode has no CSV-origin metadata → research fields must be absent
        Path output = Files.createTempDirectory("c4dg-full-json");
        AnalyzerFacade.analyze(fixtureRoot, AnalysisOptions.builder()
                .outputMode(AnalysisOptions.OutputMode.JSON)
                .outputDirectory(output)
                .build());

        Path greetJson = findJsonForMethod(output.resolve("method_context_json"), "greet");
        assertTrue("FULL-mode greet JSON should exist", Files.exists(greetJson));

        JsonNode json = new ObjectMapper().readTree(greetJson.toFile());
        assertFalse("FULL-mode JSON must NOT contain original_docstring",
                json.has("original_docstring"));
        assertFalse("FULL-mode JSON must NOT contain test_prefix",
                json.has("test_prefix"));
    }

    private Path findJsonForMethod(Path jsonDir, String methodName) throws Exception {
        try (var files = Files.list(jsonDir)) {
            for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
                JsonNode json = new ObjectMapper().readTree(file.toFile());
                if (methodName.equals(json.path("MUT").path("method_name").asText())) {
                    return file;
                }
            }
        }
        throw new AssertionError("No JSON file found for method " + methodName + " in " + jsonDir);
    }
}
