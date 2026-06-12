package org.assertlab.context4docugen;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.junit.After;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;

/**
 * Test suite for JsonGenerator Phase 5 component
 */
public class JsonGeneratorTest {
    private Path testOutputDir;
    private JsonGenerator generator;

    @Before
    public void setUp() throws Exception {
        // Create temporary test directory
        testOutputDir = Files.createTempDirectory("json_gen_test_");
        generator = new JsonGenerator(testOutputDir);
    }

    @After
    public void tearDown() throws Exception {
        // Clean up test directory
        if (Files.exists(testOutputDir)) {
            Files.walk(testOutputDir)
                    .sorted((a, b) -> b.compareTo(a))
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (Exception e) {
                            // Ignore
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
    public void testGenerateJsonFileForSingleMethod() throws Exception {
        MethodContext context = new MethodContext.Builder()
                .methodId("1")
                .methodName("testMethod")
                .classname("com.example.MyClass")
                .methodBody("public void testMethod() { }")
                .javadoc("/** Test method */")
                .classHierarchy("MyClass extends BaseClass")
                .addClassMethod("otherMethod", "void")
                .linesOfCode(10)
                .cyclomatic(1)
                .build();

        boolean success = generator.generateJsonFile(context);
        
        assertTrue("Should generate JSON file successfully", success);
        
        // Verify file was created
        Path expectedFile = onlyJsonFile();
        assertTrue("JSON file should exist", Files.exists(expectedFile));
    }

    @Test
    public void testJsonFileNameGeneration() throws Exception {
        MethodContext context1 = new MethodContext.Builder()
                .methodId("1")
                .methodName("myMethod")
                .classname("com.example.MyClass")
                .build();

        MethodContext context2 = new MethodContext.Builder()
                .methodId("2")
                .methodName("get<T>Value")
                .classname("com.example.MyClass")
                .build();

        generator.generateJsonFile(context1);
        generator.generateJsonFile(context2);

        try (var files = Files.list(testOutputDir)) {
            assertEquals("Should create two JSON files", 2,
                    files.filter(p -> p.toString().endsWith(".json")).count());
        }
        try (var files = Files.list(testOutputDir)) {
            assertTrue("Should sanitize special characters in filename",
                    files.anyMatch(p -> p.getFileName().toString().endsWith("__get_T_Value.json")));
        }
    }

    @Test
    public void testGenerateJsonFilesForMultipleMethods() {
        Map<String, MethodContext> contexts = new HashMap<>();
        
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

        int generated = generator.generateJsonFiles(contexts);
        
        assertEquals("Should generate 2 files", 2, generated);
        assertEquals("Statistics should show 2 generated", 2, generator.getGenerationStats().get("total_generated").intValue());
    }

    @Test
    public void testJsonFileContent() throws Exception {
        MethodContext context = new MethodContext.Builder()
                .methodId("1")
                .methodName("testMethod")
                .classname("com.example.MyClass")
                .methodBody("public void testMethod() { System.out.println(\"test\"); }")
                .javadoc("/** Test method */")
                .classHierarchy("MyClass extends BaseClass")
                .linesOfCode(2)
                .cyclomatic(1)
                .build();

        generator.generateJsonFile(context);

        // Read and verify file content
        Path jsonFile = onlyJsonFile();
        String content = new String(Files.readAllBytes(jsonFile));
        
        // JSON schema wraps method info inside an "MUT" object with snake_case keys
        assertTrue("Should contain method URI/key", content.contains("\"method_uri\""));
        assertTrue("Should contain method name", content.contains("\"method_name\" : \"testMethod\""));
        assertTrue("Should contain class name (via qualified_name)",
                content.contains("\"qualified_name\" : \"com.example.MyClass.testMethod\""));
        assertTrue("Should contain method body", content.contains("System.out.println"));
        assertTrue("Should contain javadoc", content.contains("Test method"));
    }

    @Test
    public void testGenerationStats() {
        Map<String, MethodContext> contexts = new HashMap<>();
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

        generator.generateJsonFiles(contexts);

        Map<String, Integer> stats = generator.getGenerationStats();
        assertEquals("Should have 2 total", 2, stats.get("total_generated").intValue());
        assertEquals("Should have 2 successful", 2, stats.get("successful").intValue());
        assertEquals("Should have 0 failed", 0, stats.get("failed").intValue());
    }

    @Test
    public void testGenerationResults() {
        MethodContext context = new MethodContext.Builder()
                .methodId("1")
                .methodName("testMethod")
                .classname("com.example.MyClass")
                .build();

        generator.generateJsonFile(context);

        String result = generator.getGenerationResult("1");
        assertNotNull("Should have result", result);
        assertTrue("Result should indicate success", result.startsWith("SUCCESS:"));
        assertTrue("Result should contain URI-hash JSON file path", result.contains("method_"));
        assertTrue("Result should contain method name in file path", result.contains("__testMethod.json"));
    }

    @Test
    public void testVerifyJsonFiles() {
        MethodContext context = new MethodContext.Builder()
                .methodId("1")
                .methodName("testMethod")
                .classname("com.example.MyClass")
                .build();

        generator.generateJsonFile(context);

        Map<String, Boolean> verification = generator.verifyJsonFiles();
        assertNotNull("Should have verification results", verification);
        assertTrue("File should exist and be verified", verification.get("1"));
    }

    @Test
    public void testGetAllGenerationResults() {
        Map<String, MethodContext> contexts = new HashMap<>();
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

        generator.generateJsonFiles(contexts);

        Map<String, String> allResults = generator.getAllGenerationResults();
        assertEquals("Should have 2 results", 2, allResults.size());
        assertTrue("Should contain result for method 1", allResults.containsKey("1"));
        assertTrue("Should contain result for method 2", allResults.containsKey("2"));
    }

    @Test
    public void testClearResults() {
        MethodContext context = new MethodContext.Builder()
                .methodId("1")
                .methodName("testMethod")
                .classname("com.example.MyClass")
                .build();

        generator.generateJsonFile(context);
        
        assertEquals("Should have 1 result before clear", 1, generator.getAllGenerationResults().size());
        
        generator.clearResults();
        
        assertEquals("Should have 0 results after clear", 0, generator.getAllGenerationResults().size());
    }

    @Test
    public void testJsonFileWithCallGraph() throws Exception {
        CallGraphResult callGraph = new CallGraphResult.Builder()
                .methodId("1")
                .methodName("testMethod")
                .classname("com.example.MyClass")
                .addCaller("com.example.Main.main(String[])")
                .addCallee("java.lang.System.out")
                .algorithm("CHA")
                .build();

        MethodContext context = new MethodContext.Builder()
                .methodId("1")
                .methodName("testMethod")
                .classname("com.example.MyClass")
                .callGraph(callGraph)
                .build();

        generator.generateJsonFile(context);

        Path jsonFile = onlyJsonFile();
        String content = new String(Files.readAllBytes(jsonFile));
        
        // New schema: callers and callees are top-level arrays; algorithm captured in metadata
        assertTrue("Should contain callers", content.contains("\"callers\""));
        assertTrue("Should contain callees", content.contains("\"callees\""));
    }

    @Test
    public void testJsonFileWithoutOptionalFields() throws Exception {
        MethodContext context = new MethodContext.Builder()
                .methodId("1")
                .methodName("simpleMethod")
                .classname("com.example.MyClass")
                .build();

        generator.generateJsonFile(context);

        Path jsonFile = onlyJsonFile();
        String content = new String(Files.readAllBytes(jsonFile));
        
        // New schema wraps fields in an "MUT" object; required fields use snake_case
        assertTrue("Should contain MUT root", content.contains("\"MUT\""));
        assertTrue("Should contain method name", content.contains("\"method_name\""));
        assertTrue("Should contain qualified name (carries class)",
                content.contains("\"qualified_name\""));

        // Optional fields like javadoc shouldn't appear if empty
        // This is fine as-is; JSON will just not have the field
    }

    private Path onlyJsonFile() throws Exception {
        try (var files = Files.list(testOutputDir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("No JSON file generated in " + testOutputDir));
        }
    }
}
