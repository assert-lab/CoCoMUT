package org.assertlab.cocomut;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.assertlab.cocomut.source.SourceBackends;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * End-to-end tests for {@link AnalyzerFacade}.
 *
 * <p>Uses the shared {@link TestFixtures} minimal Maven project (already compiled)
 * so the pipeline runs against real bytecode and source.
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
     * The facade must auto-detect Maven + scan-all and run the pipeline to
     * SUCCESS.
     */
    @Test
    public void facadeAnalyzesPlainMavenProjectEndToEnd() throws Exception {
        Map<String, Object> report = AnalyzerFacade.analyze(fixtureRoot);

        assertNotNull("Facade should return a report", report);
        assertEquals("Pipeline should succeed on the minimal fixture",
                "SUCCESS", report.get("status"));
        assertEquals("Pipeline should have five phases", 5, report.get("pipeline_phases"));
        assertTrue("Phase 2 should identify the fixture methods",
                report.containsKey("phase_2_methods_identified"));
    }

    @Test
    public void entryPointScopeProcessesOnlyPublicMethods() throws Exception {
        Map<String, Object> report = AnalyzerFacade.analyze(ContextRequest.builder()
                .projectRoot(fixtureRoot)
                .scope(ContextRequest.Scope.ENTRY_POINTS)
                .build());

        assertEquals("SUCCESS", report.get("status"));
        assertEquals("ENTRY_POINTS", report.get("phase_2_strategy"));
        // Hello: greet (public) kept; prefix (private) + main (excluded) dropped → 1 method
        assertEquals("Only the single public non-main method should be processed",
                1, ((Number) report.get("phase_2_methods_identified")).intValue());
    }

    @Test
    public void entryPointEdgesResolveAgainstFullAnalysisUniverse() throws Exception {
        Map<String, Object> report = AnalyzerFacade.analyze(ContextRequest.builder()
                .projectRoot(fixtureRoot)
                .scope(ContextRequest.Scope.ENTRY_POINTS)
                .sourceSets(java.util.Set.of("main"))
                .build());

        assertEquals("SUCCESS", report.get("status"));
        JsonNode row = findJsonlRowForMethod(Path.of(String.valueOf(report.get("phase_5_jsonl_file"))), "greet");
        boolean privateHelperResolvedOutsideOutput = false;
        for (JsonNode edge : row.path("callees")) {
            if ("prefix".equals(edge.path("method_name").asText())) {
                privateHelperResolvedOutsideOutput = edge.path("method_uri").asText().contains("prefix()")
                        && !edge.path("context_in_output").asBoolean(true)
                        && "project_method".equals(edge.path("target_kind").asText());
            }
        }
        assertTrue("Filtered private helper should resolve but not embed context",
                privateHelperResolvedOutsideOutput);
    }

    @Test
    public void facadeUsesOneRequestScopedSpoonParse() throws Exception {
        SourceBackends.resetParseCount();

        Map<String, Object> report = AnalyzerFacade.analyze(ContextRequest.builder()
                .projectRoot(fixtureRoot)
                .scope(ContextRequest.Scope.ALL)
                .sourceSet("main")
                .build());

        assertEquals("SUCCESS", report.get("status"));
        assertTrue("Fixture should provide multiple focal methods",
                ((Number) report.get("phase_2_methods_identified")).intValue() > 1);
        assertEquals("One extraction request should construct one Spoon model",
                1, SourceBackends.parseCount());
    }

    @Test
    public void serviceApiReturnsTypedExtractionReport() throws Exception {
        ContextRequest request = ContextRequest.builder()
                .projectRoot(fixtureRoot)
                .scope(ContextRequest.Scope.ENTRY_POINTS)
                .build();

        ExtractionReport report = ContextExtractorService.createDefault().extract(request);

        assertTrue("Service API should report success", report.successful());
        assertEquals("SUCCESS", report.status());
        assertEquals(1, report.methodsIdentified());
        assertEquals(1, report.contextsExtracted());
        assertEquals(1, report.jsonlRows());
        assertNotNull("JSONL output path should be available", report.jsonlFile());
        assertTrue("Static bytecode analysis should be available",
                Boolean.TRUE.equals(report.asMap().get("phase_3_available")));
    }

    @Test
    public void autoCallGraphUsesRtaWhenCompiledClassesExist() throws Exception {
        ContextRequest request = ContextRequest.builder()
                .projectRoot(fixtureRoot)
                .scope(ContextRequest.Scope.ENTRY_POINTS)
                .maxMethods(1)
                .build();

        ExtractionReport report = ContextExtractorService.createDefault().extract(request);

        assertTrue(report.successful());
        assertEquals("RTA", report.asMap().get("phase_3_algorithm"));
        assertEquals("RTA", report.asMap().get("phase_3_effective_algorithm"));
    }

    @Test
    public void skipBuildUsesExplicitClassOutputAndWritesManifest() throws Exception {
        Path output = Files.createTempDirectory("cocomut-skip-build-output");
        ContextRequest request = ContextRequest.builder()
                .projectRoot(fixtureRoot)
                .scope(ContextRequest.Scope.ENTRY_POINTS)
                .sourceSet("main")
                .skipBuild(true)
                .classOutputDir(fixtureRoot.resolve("target/classes"))
                .outputDirectory(output)
                .build();

        ExtractionReport report = ContextExtractorService.createDefault().extract(request);

        assertTrue("Precompiled fixture should extract without running Maven", report.successful());
        assertEquals(Boolean.TRUE, report.asMap().get("phase_1_build_skipped"));
        assertEquals(Boolean.FALSE, report.asMap().get("phase_1_build_attempted"));
        assertEquals(1, ((Number) report.asMap().get("phase_1_explicit_class_outputs")).intValue());
        Path manifest = Path.of(String.valueOf(report.asMap().get("extraction_manifest_file")));
        assertTrue("Manifest should be written", Files.isRegularFile(manifest));
        JsonNode manifestJson = new ObjectMapper().readTree(manifest.toFile());
        assertTrue("Manifest should record skipped build policy",
                manifestJson.path("build").path("skipped").asBoolean());
        assertEquals("skip", manifestJson.path("build").path("policy").asText());
        assertFalse("Manifest should include project bytecode hash",
                manifestJson.path("hashes").path("project_bytecode_sha256").asText().isBlank());
    }

    private JsonNode findJsonlRowForMethod(Path jsonl, String methodName) throws Exception {
        assertTrue("Expected JSONL output at " + jsonl, Files.exists(jsonl));
        ObjectMapper mapper = new ObjectMapper();
        for (String line : Files.readAllLines(jsonl)) {
            JsonNode json = mapper.readTree(line);
            if (methodName.equals(json.path("MUT").path("method_name").asText())) {
                return json;
            }
        }
        throw new AssertionError("No JSONL row found for method " + methodName + " in " + jsonl);
    }
}
