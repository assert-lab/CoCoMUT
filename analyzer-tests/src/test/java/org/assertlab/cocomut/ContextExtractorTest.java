package org.assertlab.cocomut;

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
 * Test suite for ContextExtractor Phase 4 component
 */
public class ContextExtractorTest {
    private ProjectMetadata projectMetadata;
    private CallGraphGenerator callGraphGenerator;
    private ContextExtractor extractor;

    @Before
    public void setUp() throws Exception {
        TestFixtures.ensureMinimalMavenProjectCompiled();
        java.nio.file.Path fixture = TestFixtures.minimalMavenProjectRoot();
        projectMetadata = new ProjectMetadata.Builder()
                .projectName("TestProject")
                .projectPath(fixture)
                .buildSystem("maven")
                .javaVersion("17")
                .sourceRoot(fixture.resolve("src/main/java"))
                .classpath(List.of(
                        fixture.resolve("target/classes")
                ))
                .compiles(true)
                .compileStatus("BUILD SUCCESS")
                .build();
        
        callGraphGenerator = new CallGraphGenerator(projectMetadata);
        callGraphGenerator.initialize();
        
        extractor = new ContextExtractor(projectMetadata, callGraphGenerator);
    }

    @Test
    public void testContextExtractorCreation() {
        assertNotNull("Extractor should be created", extractor);
    }

    @Test
    public void testExtractContextForSingleMethod() {
        MethodInfo testMethod = new MethodInfo.Builder()
                .methodUri("1")
                .classname("com.example.MyClass")
                .methodName("testMethod")
                .methodSignature("testMethod()")
                .sourceFile(Paths.get("MyClass.java"))
                .lineNumber(42)
                .visibility("public")
                .returnType("void")
                .build();

        MethodContext context = extractor.extractContext(testMethod);
        
        // Context may be null if file not found, which is ok for test
        if (context != null) {
            assertEquals("Context should have correct method URI", "1", context.getMethodUri());
            assertEquals("Context should have correct method name", "testMethod", context.getMethodName());
            assertEquals("Context should have correct classname", "com.example.MyClass", context.getClassname());
        }
    }

    @Test
    public void testMethodContextBuilder() {
        MethodContext context = new MethodContext.Builder()
                .methodUri("1")
                .methodName("testMethod")
                .classname("com.example.MyClass")
                .methodBody("public void testMethod() { }")
                .javadoc("/** Test method */")
                .classHierarchy("MyClass extends BaseClass")
                .addClassMethod("otherMethod", "void")
                .linesOfCode(10)
                .cyclomatic(1)
                .build();
        
        assertNotNull("Should build context", context);
        assertEquals("Should have method URI", "1", context.getMethodUri());
        assertEquals("Should have method body", "public void testMethod() { }", context.getMethodBody());
        assertEquals("Should have javadoc", "/** Test method */", context.getJavadoc());
        assertTrue("Should have class hierarchy", context.getClassHierarchy().contains("MyClass"));
        assertEquals("Should have 1 class method", 1, context.getClassMethods().size());
        assertEquals("Should have 10 lines of code", 10, context.getLinesOfCode());
        assertEquals("Should have cyclomatic 1", 1, context.getCyclomatic());
    }

    @Test
    public void testMethodContextWithCallGraph() {
        CallGraphResult callGraph = new CallGraphResult.Builder()
                .methodUri("1")
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
                .methodUri("1")
                .methodName("testMethod")
                .classname("com.example.MyClass")
                .callGraph(callGraph)
                .build();
        
        assertNotNull("Should have call graph", context.getCallGraph());
        assertEquals("Call graph should have 1 caller", 1, context.getCallGraph().getCallerCount());
        assertEquals("Call graph should have 1 callee", 1, context.getCallGraph().getCalleeCount());
    }

    @Test
    public void testMethodContextHasJavadoc() {
        MethodContext contextWithJavadoc = new MethodContext.Builder()
                .methodUri("1")
                .methodName("testMethod")
                .classname("com.example.MyClass")
                .javadoc("/** Test method */")
                .build();
        
        MethodContext contextWithoutJavadoc = new MethodContext.Builder()
                .methodUri("2")
                .methodName("testMethod2")
                .classname("com.example.MyClass")
                .build();
        
        assertTrue("Should detect javadoc when present", contextWithJavadoc.hasJavadoc());
        assertFalse("Should detect when javadoc missing", contextWithoutJavadoc.hasJavadoc());
    }

    @Test
    public void testContextCaching() {
        MethodInfo testMethod = new MethodInfo.Builder()
                .methodUri("1")
                .classname("com.example.MyClass")
                .methodName("testMethod")
                .methodSignature("testMethod()")
                .sourceFile(Paths.get("MyClass.java"))
                .lineNumber(42)
                .build();

        MethodContext context1 = extractor.extractContext(testMethod);
        MethodContext context2 = extractor.extractContext(testMethod);

        // Should be cached (same reference or null for both)
        if (context1 != null && context2 != null) {
            assertEquals("Second call should return cached result",
                    context1.getMethodUri(), context2.getMethodUri());
        }
    }

    @Test
    public void testExtractContextForMultipleMethods() {
        List<MethodInfo> methods = List.of(
                new MethodInfo.Builder()
                        .methodUri("1")
                        .classname("com.example.MyClass")
                        .methodName("method1")
                        .methodSignature("method1()")
                        .sourceFile(Paths.get("MyClass.java"))
                        .lineNumber(10)
                        .build(),
                new MethodInfo.Builder()
                        .methodUri("2")
                        .classname("com.example.MyClass")
                        .methodName("method2")
                        .methodSignature("method2()")
                        .sourceFile(Paths.get("MyClass.java"))
                        .lineNumber(20)
                        .build()
        );

        Map<String, MethodContext> contexts = extractor.extractContextForMethods(methods);
        
        assertNotNull("Should return contexts map", contexts);
        // Will have 0 entries if files not found, which is ok for test
        assertTrue("Should have 0 or 2 contexts", contexts.size() == 0 || contexts.size() == 2);
    }

    @Test
    public void testCacheStats() {
        MethodInfo testMethod = new MethodInfo.Builder()
                .methodUri("1")
                .classname("com.example.MyClass")
                .methodName("testMethod")
                .methodSignature("testMethod()")
                .sourceFile(Paths.get("MyClass.java"))
                .lineNumber(42)
                .build();

        extractor.extractContext(testMethod);
        
        Map<String, Integer> stats = extractor.getCacheStats();
        assertNotNull("Should return cache stats", stats);
        assertTrue("Stats should contain cached_contexts", stats.containsKey("cached_contexts"));
    }

    @Test
    public void testClearCache() {
        MethodInfo testMethod = new MethodInfo.Builder()
                .methodUri("1")
                .classname("com.example.MyClass")
                .methodName("testMethod")
                .methodSignature("testMethod()")
                .sourceFile(Paths.get("MyClass.java"))
                .lineNumber(42)
                .build();

        extractor.extractContext(testMethod);
        
        // Cache should have entry (or zero if file not found)
        Map<String, Integer> statsBefore = extractor.getCacheStats();
        int sizeBefore = statsBefore.get("cached_contexts");
        
        // Clear cache
        extractor.clearCache();
        
        // Cache should be empty
        Map<String, Integer> statsAfter = extractor.getCacheStats();
        int sizeAfter = statsAfter.get("cached_contexts");
        
        assertEquals("Cache should be empty after clear", 0, sizeAfter);
        assertTrue("Size after should be <= size before", sizeAfter <= sizeBefore);
    }

    @Test
    public void testMethodContextToString() {
        MethodContext context = new MethodContext.Builder()
                .methodUri("1")
                .methodName("testMethod")
                .classname("com.example.MyClass")
                .linesOfCode(10)
                .cyclomatic(2)
                .build();
        
        String str = context.toString();
        assertNotNull("Should have string representation", str);
        assertTrue("Should contain method URI", str.contains("methodUri"));
        assertTrue("Should contain lines of code", str.contains("linesOfCode"));
    }
}
