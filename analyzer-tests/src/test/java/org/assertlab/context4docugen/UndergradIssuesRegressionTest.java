package org.assertlab.context4docugen;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Regression tests for undergrad-scoped issues U1, U2, and U3.
 */
@Category(FastTests.class)
public class UndergradIssuesRegressionTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Path fixture(String fixtureName) {
        Path fixture = Path.of("src/test/resources/fixtures")
                .resolve(fixtureName)
                .toAbsolutePath()
                .normalize();
        if (Files.exists(fixture.resolve("inputs_selected.csv"))) {
            return fixture;
        }
        throw new AssertionError("Could not find fixture project at " + fixture);
    }

    private Orchestrator runSelectedFixture(Path fixtureProject) throws Exception {
        cleanGeneratedOutputs(fixtureProject);
        Path output = Files.createTempDirectory("c4dg-undergrad-output");
        Orchestrator orchestrator = new Orchestrator(
                fixtureProject,
                Orchestrator.ExecutionMode.SELECTED)
                .setOutputMode(AnalysisOptions.OutputMode.JSONL)
                .setOutputDirectory(output);
        orchestrator.execute();
        return orchestrator;
    }

    private void cleanGeneratedOutputs(Path fixtureProject) throws IOException {
        Files.deleteIfExists(fixtureProject.resolve("methods.csv"));
    }

    @Test
    public void u1ParametersIncludeNamesTypesAndModifiers() throws Exception {
        Path project = fixture("parameter-provenance-project");
        Orchestrator orchestrator = runSelectedFixture(project);

        assertEquals("The fixture has one selected method", 1,
                ((Number) orchestrator.getExecutionReport().get("phase_2_methods_loaded")).intValue());

        JsonNode root = findJsonForMethod(orchestrator, "choose");
        JsonNode params = root.path("MUT").path("parameters");

        assertTrue("MUT.parameters should be an array", params.isArray());
        assertEquals("choose(final T first, T second) should have exactly two parameters", 2, params.size());

        assertTrue(
                "Each parameter should be a structured object with name/type/modifiers, not a raw string. Actual parameters: " + params,
                params.get(0).isObject());

        assertEquals("first", params.get(0).path("name").asText());
        assertEquals("T", params.get(0).path("type").asText());
        assertTrue(params.get(0).path("modifiers").isArray());
        assertEquals("final", params.get(0).path("modifiers").get(0).asText());

        assertEquals("second", params.get(1).path("name").asText());
        assertEquals("T", params.get(1).path("type").asText());
        assertTrue(params.get(1).path("modifiers").isArray());
        assertEquals("second should not be final", 0, params.get(1).path("modifiers").size());
    }

    @Test
    public void u2EveryJsonContainsProvenanceMetadata() throws Exception {
        Path project = fixture("parameter-provenance-project");
        Orchestrator orchestrator = runSelectedFixture(project);

        JsonNode root = findJsonForMethod(orchestrator, "choose");
        JsonNode provenance = root.path("provenance");

        assertTrue(
                "Every generated JSON should contain a top-level provenance object explaining source/matching/extraction confidence. Actual top-level fields: " +
                        Stream.of(root.fieldNames()).flatMap(it -> {
                            java.util.List<String> names = new java.util.ArrayList<>();
                            it.forEachRemaining(names::add);
                            return names.stream();
                        }).collect(Collectors.joining(", ")),
                provenance.isObject());

        assertEquals("selected_csv", provenance.path("method_source").asText());
        assertTrue("provenance.method_matching should be present", provenance.hasNonNull("method_matching"));
        assertTrue("provenance.javadoc_extraction should be present", provenance.hasNonNull("javadoc_extraction"));
        assertTrue("provenance.call_graph should be present", provenance.hasNonNull("call_graph"));
        assertTrue("provenance.compiled_project should be present", provenance.has("compiled_project"));
        assertTrue("provenance.context_confidence should be an object", provenance.path("context_confidence").isObject());
    }

    @Test
    public void u3UnsafeIdsDoNotSilentlyBreakJsonGeneration() throws Exception {
        Path project = fixture("unsafe-id-project");
        Orchestrator orchestrator = runSelectedFixture(project);
        Map<String, Object> report = orchestrator.getExecutionReport();

        int filesGenerated = ((Number) report.getOrDefault("phase_5_files_generated", -1)).intValue();
        String status = String.valueOf(report.get("status"));

        boolean sanitizedAndGenerated = filesGenerated == 1
                && Files.exists(Path.of(String.valueOf(report.get("phase_5_jsonl_file"))));
        boolean clearlyReportedFailure = !"SUCCESS".equals(status)
                || report.containsKey("phase_5_files_failed")
                || report.containsKey("phase_5_generation_failures");

        assertTrue(
                "Unsafe selected IDs must either be sanitized into a valid filename or clearly reported as JSON-generation failures. " +
                        "Current report was: " + report,
                sanitizedAndGenerated || clearlyReportedFailure);
    }

    private JsonNode findJsonForMethod(Orchestrator orchestrator, String methodName) throws IOException {
        Path jsonl = Path.of(String.valueOf(orchestrator.getExecutionReport().get("phase_5_jsonl_file")));
        if (!Files.exists(jsonl)) {
            throw new AssertionError("No JSONL output generated at " + jsonl);
        }
        for (String line : Files.readAllLines(jsonl)) {
            JsonNode json = MAPPER.readTree(line);
            if (methodName.equals(json.path("MUT").path("method_name").asText())) {
                return json;
            }
        }
        throw new AssertionError("No JSONL row found for method " + methodName + " in " + jsonl);
    }
}
