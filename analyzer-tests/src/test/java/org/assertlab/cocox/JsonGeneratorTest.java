package org.assertlab.cocox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests for the JSONL-only Phase 5 generator.
 */
public class JsonGeneratorTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Path testOutputDir;
    private JsonGenerator generator;

    @Before
    public void setUp() throws Exception {
        testOutputDir = Files.createTempDirectory("json_gen_test_");
        generator = new JsonGenerator(testOutputDir);
    }

    @After
    public void tearDown() throws Exception {
        if (Files.exists(testOutputDir)) {
            Files.walk(testOutputDir)
                    .sorted((a, b) -> b.compareTo(a))
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (Exception ignored) {
                        }
                    });
        }
    }

    @Test
    public void testJsonGeneratorCreation() {
        assertNotNull("Generator should be created", generator);
        assertEquals("Output directory should match", testOutputDir, generator.getOutputDirectory());
    }

    @Test
    public void testDefaultOutputDirectory() {
        JsonGenerator defaultGen = JsonGenerator.withDefaultDirectory();
        assertNotNull("Should create generator with default directory", defaultGen);
    }

    @Test
    public void testGenerateJsonLinesFileForSingleMethod() throws Exception {
        MethodContext context = new MethodContext.Builder()
                .methodId("1")
                .methodName("testMethod")
                .classname("com.example.MyClass")
                .methodBody("@Override\npublic void testMethod() { }")
                .javadoc("Test method")
                .classHierarchy("MyClass extends BaseClass")
                .addClassMethod("otherMethod", "void")
                .linesOfCode(2)
                .cyclomatic(1)
                .build();

        Path jsonl = testOutputDir.resolve("method_contexts.jsonl");
        int rows = generator.generateJsonLinesFile(Map.of("1", context), jsonl);

        assertEquals("Should write one JSONL row", 1, rows);
        assertTrue("JSONL file should exist", Files.exists(jsonl));

        JsonNode root = MAPPER.readTree(Files.readString(jsonl));
        assertEquals("testMethod", root.path("MUT").path("method_name").asText());
        assertEquals("Test method", root.path("MUT").path("javadoc").asText());
        assertTrue("Method code should include annotations/body", root.path("MUT").path("code").asText().contains("@Override"));
        assertTrue("Method code should include body", root.path("MUT").path("code").asText().contains("testMethod()"));
    }

    @Test
    public void testGenerateJsonLinesFileForMultipleMethods() throws Exception {
        Map<String, MethodContext> contexts = new LinkedHashMap<>();
        contexts.put("1", new MethodContext.Builder()
                .methodId("1")
                .methodName("method1")
                .classname("com.example.MyClass")
                .build());
        contexts.put("2", new MethodContext.Builder()
                .methodId("2")
                .methodName("method2")
                .classname("com.example.MyClass")
                .build());

        Path jsonl = testOutputDir.resolve("method_contexts.jsonl");
        int rows = generator.generateJsonLinesFile(contexts, jsonl);

        assertEquals("Should write one row per context", 2, rows);
        assertEquals("Statistics should show 2 generated", 2,
                generator.getGenerationStats().get("total_generated").intValue());
        assertEquals("JSONL should contain 2 physical lines", 2, Files.readAllLines(jsonl).size());
    }

    @Test
    public void testGenerationResultsPointToJsonlFile() {
        MethodContext context = new MethodContext.Builder()
                .methodId("1")
                .methodName("testMethod")
                .classname("com.example.MyClass")
                .build();

        Path jsonl = testOutputDir.resolve("method_contexts.jsonl");
        generator.generateJsonLinesFile(Map.of("1", context), jsonl);

        String result = generator.getGenerationResult("1");
        assertNotNull("Should have result", result);
        assertEquals("SUCCESS:" + jsonl, result);
    }

    @Test
    public void testVerifyGeneratedJsonlPath() {
        MethodContext context = new MethodContext.Builder()
                .methodId("1")
                .methodName("testMethod")
                .classname("com.example.MyClass")
                .build();

        generator.generateJsonLinesFile(Map.of("1", context), testOutputDir.resolve("method_contexts.jsonl"));

        Map<String, Boolean> verification = generator.verifyJsonFiles();
        assertNotNull("Should have verification results", verification);
        assertTrue("JSONL path should exist and be verified", verification.get("1"));
    }

    @Test
    public void testClearResults() {
        MethodContext context = new MethodContext.Builder()
                .methodId("1")
                .methodName("testMethod")
                .classname("com.example.MyClass")
                .build();

        generator.generateJsonLinesFile(Map.of("1", context), testOutputDir.resolve("method_contexts.jsonl"));
        assertEquals("Should have method and JSONL aggregate results before clear", 2,
                generator.getAllGenerationResults().size());

        generator.clearResults();

        assertEquals("Should have 0 results after clear", 0, generator.getAllGenerationResults().size());
    }

    @Test
    public void testJsonLinesWithCallGraph() throws Exception {
        CallGraphResult callGraph = new CallGraphResult.Builder()
                .methodId("1")
                .methodName("testMethod")
                .classname("com.example.MyClass")
                .addCaller(CallGraphEdge.resolved(
                        "src/main/java/com/example/Main.java#com.example.Main.main(java.lang.String[]):void",
                        "<com.example.Main: void main(java.lang.String[])>",
                        "com.example.Main",
                        "main"))
                .addCallee(CallGraphEdge.unresolved(
                        "<java.io.PrintStream: void println(java.lang.String)>",
                        "java.io.PrintStream",
                        "println"))
                .algorithm("CHA")
                .build();

        MethodContext context = new MethodContext.Builder()
                .methodId("1")
                .methodName("testMethod")
                .classname("com.example.MyClass")
                .callGraph(callGraph)
                .build();

        Path jsonl = testOutputDir.resolve("method_contexts.jsonl");
        generator.generateJsonLinesFile(Map.of("1", context), jsonl);

        String content = Files.readString(jsonl);
        assertTrue("Should contain callers", content.contains("\"callers\""));
        assertTrue("Should contain callees", content.contains("\"callees\""));
        assertTrue("Resolved call edge should expose method URI", content.contains("\"method_uri\""));
        assertTrue("Unresolved call edge should expose raw signature as provenance", content.contains("\"raw_signature\""));
        assertTrue("Should contain call graph algorithm", content.contains("\"CHA\""));
    }
}
