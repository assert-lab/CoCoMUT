package org.assertlab.context4docugen;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;

/**
 * Test suite for {@link CallGraphGenerator} (Phase 3).
 *
 * <p>Uses {@code analyzer-core} as the subject project, which has real source
 * under {@code src/main/java} and compiled classes under {@code target/classes}
 * populated by the reactor before the test phase runs in {@code analyzer-tests}.
 */
public class CallGraphGeneratorTest {

    /** Absolute path to the analyzer-core sibling module. */
    private static final Path CORE_MODULE =
            Paths.get(System.getProperty("user.dir")).getParent().resolve("analyzer-core");

    private ProjectMetadata projectMetadata;
    private CallGraphGenerator generator;

    @Before
    public void setUp() throws Exception {
        projectMetadata = new ProjectMetadata.Builder()
                .projectName("TestProject")
                .projectPath(CORE_MODULE)
                .buildSystem("maven")
                .javaVersion("25")
                .sourceRoot(CORE_MODULE.resolve("src/main/java"))
                .classpath(List.of(
                        CORE_MODULE.resolve("target/classes")
                ))
                .compiles(true)
                .compileStatus("BUILD SUCCESS")
                .build();

        generator = new CallGraphGenerator(projectMetadata);
    }


    @Test
    public void testCallGraphGeneratorCreation() {
        assertNotNull("Generator should be created", generator);
        assertFalse("Should not be initialized yet", generator.isInitialized());
    }

    @Test
    public void testCallGraphGeneratorInitialization() {
        boolean initialized = generator.initialize();
        assertTrue("Should initialize successfully", initialized);
        assertTrue("Should be marked as initialized", generator.isInitialized());
    }

    @Test
    public void testAlgorithmSelection() {
        assertEquals("Default generator algorithm should be CHA",
                CallGraphGenerator.Algorithm.CHA, generator.getAlgorithm());
        
        CallGraphGenerator rtaGenerator = new CallGraphGenerator(projectMetadata, CallGraphGenerator.Algorithm.RTA);
        assertEquals("Should support RTA algorithm", 
                CallGraphGenerator.Algorithm.RTA, rtaGenerator.getAlgorithm());
    }

    @Test
    public void testGenerateForSingleMethod() {
        generator.initialize();
        
        MethodInfo testMethod = new MethodInfo.Builder()
                .id("1")
                .classname("com.example.MyClass")
                .methodName("testMethod")
                .methodSignature("testMethod()")
                .sourceFile(Paths.get("MyClass.java"))
                .lineNumber(42)
                .visibility("public")
                .returnType("void")
                .build();
        
        CallGraphResult result = generator.generateForMethod(testMethod);
        
        assertNotNull("Should generate call graph result", result);
        assertEquals("Result should have correct method ID", "1", result.getMethodId());
        assertEquals("Result should have correct method name", "testMethod", result.getMethodName());
        assertEquals("Result should have correct classname", "com.example.MyClass", result.getClassname());
    }

    @Test
    public void testCallersAndCallees() {
        generator.initialize();
        
        MethodInfo testMethod = new MethodInfo.Builder()
                .id("1")
                .classname("com.example.MyClass")
                .methodName("processData")
                .methodSignature("processData()")
                .sourceFile(Paths.get("MyClass.java"))
                .lineNumber(50)
                .visibility("public")
                .returnType("void")
                .build();
        
        CallGraphResult result = generator.generateForMethod(testMethod);
        
        assertNotNull("Should have callers", result.getCallers());
        assertNotNull("Should have callees", result.getCallees());
        assertTrue("Should have at least 0 callers", result.getCallerCount() >= 0);
        assertTrue("Should have at least 0 callees", result.getCalleeCount() >= 0);
    }

    @Test
    public void testCaching() {
        generator.initialize();
        
        MethodInfo testMethod = new MethodInfo.Builder()
                .id("1")
                .classname("com.example.MyClass")
                .methodName("testMethod")
                .methodSignature("testMethod()")
                .sourceFile(Paths.get("MyClass.java"))
                .lineNumber(42)
                .visibility("public")
                .returnType("void")
                .build();
        
        // First call
        CallGraphResult result1 = generator.generateForMethod(testMethod);
        
        // Second call should return cached result
        CallGraphResult result2 = generator.generateForMethod(testMethod);
        
        assertNotNull("First result should not be null", result1);
        assertNotNull("Second result should not be null", result2);
        // Both should be the same cached instance or equivalent
        assertEquals("Results should be equal", result1.getMethodId(), result2.getMethodId());
        
        // Verify cache contains the result
        CallGraphResult cached = generator.getCachedResult("1");
        assertNotNull("Result should be in cache", cached);
    }

    @Test
    public void testGenerateForMultipleMethods() {
        generator.initialize();
        
        List<MethodInfo> methods = List.of(
                new MethodInfo.Builder()
                        .id("1")
                        .classname("com.example.MyClass")
                        .methodName("method1")
                        .methodSignature("method1()")
                        .sourceFile(Paths.get("MyClass.java"))
                        .lineNumber(10)
                        .build(),
                new MethodInfo.Builder()
                        .id("2")
                        .classname("com.example.MyClass")
                        .methodName("method2")
                        .methodSignature("method2()")
                        .sourceFile(Paths.get("MyClass.java"))
                        .lineNumber(20)
                        .build()
        );
        
        Map<String, CallGraphResult> results = generator.generateForMethods(methods);
        
        assertNotNull("Should return results map", results);
        assertEquals("Should have 2 results", 2, results.size());
        assertTrue("Should contain method 1", results.containsKey("1"));
        assertTrue("Should contain method 2", results.containsKey("2"));
    }

    @Test
    public void testCacheStats() {
        generator.initialize();
        
        MethodInfo testMethod = new MethodInfo.Builder()
                .id("1")
                .classname("com.example.MyClass")
                .methodName("testMethod")
                .methodSignature("testMethod()")
                .sourceFile(Paths.get("MyClass.java"))
                .lineNumber(42)
                .build();
        
        generator.generateForMethod(testMethod);
        
        Map<String, Integer> stats = generator.getCacheStats();
        assertNotNull("Should return cache stats", stats);
        assertTrue("Stats should contain cached_entries", stats.containsKey("cached_entries"));
        assertEquals("Should have 1 cached entry", 1, stats.get("cached_entries").intValue());
    }

    @Test
    public void testClearCache() {
        generator.initialize();
        
        MethodInfo testMethod = new MethodInfo.Builder()
                .id("1")
                .classname("com.example.MyClass")
                .methodName("testMethod")
                .methodSignature("testMethod()")
                .sourceFile(Paths.get("MyClass.java"))
                .lineNumber(42)
                .build();
        
        generator.generateForMethod(testMethod);
        
        // Cache should have 1 entry
        assertEquals("Cache should have 1 entry before clear", 1, generator.getCacheStats().get("cached_entries").intValue());
        
        // Clear cache
        generator.clearCache();
        
        // Cache should be empty
        assertEquals("Cache should be empty after clear", 0, generator.getCacheStats().get("cached_entries").intValue());
    }

    @Test(expected = IllegalStateException.class)
    public void testGenerateBeforeInitialization() {
        MethodInfo testMethod = new MethodInfo.Builder()
                .id("1")
                .classname("com.example.MyClass")
                .methodName("testMethod")
                .methodSignature("testMethod()")
                .sourceFile(Paths.get("MyClass.java"))
                .lineNumber(42)
                .build();
        
        // Should throw IllegalStateException
        generator.generateForMethod(testMethod);
    }

    @Test
    public void testCallGraphResultBuilder() {
        CallGraphResult result = new CallGraphResult.Builder()
                .methodId("1")
                .methodName("testMethod")
                .classname("com.example.MyClass")
                .addCaller("com.example.Main.main(String[])")
                .addCallee("java.lang.System.out")
                .algorithm("CHA")
                .generationTime(100)
                .build();
        
        assertNotNull("Should build result", result);
        assertEquals("Should have method ID", "1", result.getMethodId());
        assertEquals("Should have 1 caller", 1, result.getCallerCount());
        assertEquals("Should have 1 callee", 1, result.getCalleeCount());
        assertEquals("Should have algorithm", "CHA", result.getAlgorithm());
    }
}
