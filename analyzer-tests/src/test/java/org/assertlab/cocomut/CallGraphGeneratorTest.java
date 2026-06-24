package org.assertlab.cocomut;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
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
import sootup.core.signatures.MethodSignature;
import sootup.java.core.JavaIdentifierFactory;

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
                .methodUri("1")
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
        assertEquals("Result should have correct method URI", "1", result.getMethodUri());
        assertEquals("Result should have correct method name", "testMethod", result.getMethodName());
        assertEquals("Result should have correct classname", "com.example.MyClass", result.getClassname());
    }

    @Test
    public void testCallersAndCallees() {
        generator.initialize();
        
        MethodInfo testMethod = new MethodInfo.Builder()
                .methodUri("1")
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
                .methodUri("1")
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
        assertEquals("Results should be equal", result1.getMethodUri(), result2.getMethodUri());
        
        // Verify cache contains the result
        CallGraphResult cached = generator.getCachedResult("1");
        assertNotNull("Result should be in cache", cached);
    }

    @Test
    public void testGenerateForMultipleMethods() {
        generator.initialize();
        
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
                .methodUri("1")
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
                .methodUri("1")
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
                .methodUri("1")
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
                .generationTime(100)
                .build();
        
        assertNotNull("Should build result", result);
        assertEquals("Should have method URI", "1", result.getMethodUri());
        assertEquals("Should have 1 caller", 1, result.getCallerCount());
        assertEquals("Should have 1 callee", 1, result.getCalleeCount());
        assertEquals("Should have algorithm", "CHA", result.getAlgorithm());
    }

    @Test
    public void testCallGraphEdgeExposesBytecodeTargetUri() {
        CallGraphEdge edge = CallGraphEdge.unresolved(
                "<java.lang.Math: int max(int,int)>",
                "java.lang.Math",
                "max");

        assertEquals("method_uri remains empty for unresolved non-project edges", "", edge.methodUri());
        assertEquals("jdk_method", edge.targetKind());
        assertEquals("bytecode://java.lang.Math.max(int,int):int", edge.targetUri());
        assertEquals("jdk_or_platform_method_outside_project_source", edge.unresolvedReason());
    }

    @Test
    public void testAmbiguousCallGraphEdgeCarriesCandidates() {
        CallGraphEdge edge = CallGraphEdge.ambiguous(
                "<com.example.Foo: java.lang.Object get()>",
                "com.example.Foo",
                "get",
                List.of(
                        "src/main/java/com/example/Foo.java#com.example.Foo.get():java.lang.String",
                        "src/main/java/com/example/Foo.java#com.example.Foo.get():java.lang.Object"),
                "multiple_source_methods_match_name_and_parameters");

        assertFalse("Ambiguous edges are not resolved source methods", edge.resolved());
        assertEquals("ambiguous_project_method", edge.kind());
        assertEquals("ambiguous", edge.resolution());
        assertEquals(2, edge.candidateMethodUris().size());
        assertEquals("multiple_source_methods_match_name_and_parameters", edge.unresolvedReason());
    }

    @Test
    public void testReturnMismatchResolvesWhenClassNameAndParametersAreUnique() throws Exception {
        MethodInfo sourceMethod = new MethodInfo.Builder()
                .methodUri("src/main/java/com/example/Box.java#com.example.Box.get():java.lang.String")
                .classname("com.example.Box")
                .methodName("get")
                .methodSignature("get()")
                .returnType("java.lang.String")
                .erasedReturnType("java.lang.String")
                .sourceFile(Paths.get("Box.java"))
                .lineNumber(10)
                .build();

        CallGraphEdge edge = resolveEdgeFor(List.of(sourceMethod),
                "<com.example.Box: java.lang.Object get()>");

        assertEquals("src/main/java/com/example/Box.java#com.example.Box.get():java.lang.String",
                edge.methodUri());
        assertEquals("resolved_return_mismatch_unique", edge.resolution());
    }

    @Test
    public void testParameterNormalizationResolvesVarargsArrayDescriptor() throws Exception {
        MethodInfo sourceMethod = new MethodInfo.Builder()
                .methodUri("src/main/java/com/example/Log.java#com.example.Log.log(java.lang.String[]):void")
                .classname("com.example.Log")
                .methodName("log")
                .methodSignature("log(String... messages)")
                .returnType("void")
                .erasedReturnType("void")
                .sourceFile(Paths.get("Log.java"))
                .lineNumber(12)
                .build();

        CallGraphEdge edge = resolveEdgeFor(List.of(sourceMethod),
                "<com.example.Log: void log(java.lang.String[])>");

        assertEquals("src/main/java/com/example/Log.java#com.example.Log.log(java.lang.String[]):void",
                edge.methodUri());
        assertEquals("resolved_normalized_exact", edge.resolution());
    }

    @Test
    public void testPackageQualifiedOverloadsAreNotMatchedBySimpleName() throws Exception {
        MethodInfo first = new MethodInfo.Builder()
                .methodUri("src/main/java/com/example/Foo.java#com.example.Foo.consume(com.alpha.Token):void")
                .classname("com.example.Foo")
                .methodName("consume")
                .methodSignature("consume(com.alpha.Token token)")
                .returnType("void")
                .erasedReturnType("void")
                .sourceFile(Paths.get("Foo.java"))
                .lineNumber(20)
                .build();
        MethodInfo second = new MethodInfo.Builder()
                .methodUri("src/main/java/com/example/Foo.java#com.example.Foo.consume(com.gamma.Token):void")
                .classname("com.example.Foo")
                .methodName("consume")
                .methodSignature("consume(com.gamma.Token token)")
                .returnType("void")
                .erasedReturnType("void")
                .sourceFile(Paths.get("Foo.java"))
                .lineNumber(24)
                .build();

        CallGraphEdge edge = resolveEdgeFor(List.of(first, second),
                "<com.example.Foo: void consume(com.beta.Token)>");

        assertEquals("", edge.methodUri());
        assertEquals("unresolved", edge.resolution());
    }

    @Test
    public void testExactObjectOverloadResolvesWithoutWildcardAmbiguity() throws Exception {
        MethodInfo first = new MethodInfo.Builder()
                .methodUri("src/main/java/com/example/Foo.java#com.example.Foo.f(java.lang.String):void")
                .classname("com.example.Foo")
                .methodName("f")
                .methodSignature("f(String value)")
                .returnType("void")
                .erasedReturnType("void")
                .sourceFile(Paths.get("Foo.java"))
                .lineNumber(20)
                .build();
        MethodInfo second = new MethodInfo.Builder()
                .methodUri("src/main/java/com/example/Foo.java#com.example.Foo.f(java.lang.Object):void")
                .classname("com.example.Foo")
                .methodName("f")
                .methodSignature("f(Object value)")
                .returnType("void")
                .erasedReturnType("void")
                .sourceFile(Paths.get("Foo.java"))
                .lineNumber(24)
                .build();

        CallGraphEdge edge = resolveEdgeFor(List.of(first, second),
                "<com.example.Foo: void f(java.lang.Object)>");

        assertEquals("src/main/java/com/example/Foo.java#com.example.Foo.f(java.lang.Object):void",
                edge.methodUri());
        assertEquals("resolved_normalized_exact", edge.resolution());
    }

    @Test
    public void testProjectMethodAbsentSubdividesSyntheticCompilerMethod() throws Exception {
        MethodInfo sourceMethod = new MethodInfo.Builder()
                .methodUri("src/main/java/com/example/Widget.java#com.example.Widget.visible():void")
                .classname("com.example.Widget")
                .methodName("visible")
                .methodSignature("visible()")
                .returnType("void")
                .erasedReturnType("void")
                .sourceFile(Paths.get("Widget.java"))
                .lineNumber(10)
                .build();

        CallGraphEdge edge = resolveEdgeFor(List.of(sourceMethod),
                "<com.example.Widget: void access$000()>");

        assertEquals("", edge.methodUri());
        assertEquals("synthetic_or_compiler_method", edge.targetKind());
        assertEquals("project_class_present_method_absent_synthetic_or_compiler_method",
                edge.unresolvedReason());
    }

    @Test
    public void testProjectMethodAbsentSubdividesEnumGeneratedMethod() throws Exception {
        java.nio.file.Path source = java.nio.file.Files.createTempFile("Color", ".java");
        java.nio.file.Files.writeString(source, "package com.example; enum Color { RED, BLUE }\n");
        MethodInfo sourceMethod = new MethodInfo.Builder()
                .methodUri("src/main/java/com/example/Color.java#com.example.Color.name():java.lang.String")
                .classname("com.example.Color")
                .methodName("name")
                .methodSignature("name()")
                .returnType("java.lang.String")
                .erasedReturnType("java.lang.String")
                .sourceFile(source)
                .lineNumber(1)
                .build();

        CallGraphEdge edge = resolveEdgeFor(List.of(sourceMethod),
                "<com.example.Color: com.example.Color[] values()>");

        assertEquals("", edge.methodUri());
        assertEquals("project_class_present_method_absent_enum_generated_method",
                edge.unresolvedReason());
    }

    @Test
    public void testProjectMethodAbsentSubdividesRecordComponentAccessor() throws Exception {
        java.nio.file.Path source = java.nio.file.Files.createTempFile("Point", ".java");
        java.nio.file.Files.writeString(source, "package com.example; public record Point(int x, int y) {}\n");
        MethodInfo sourceMethod = new MethodInfo.Builder()
                .methodUri("src/main/java/com/example/Point.java#com.example.Point.distance():int")
                .classname("com.example.Point")
                .methodName("distance")
                .methodSignature("distance()")
                .returnType("int")
                .erasedReturnType("int")
                .sourceFile(source)
                .lineNumber(1)
                .build();

        CallGraphEdge edge = resolveEdgeFor(List.of(sourceMethod),
                "<com.example.Point: int x()>");

        assertEquals("", edge.methodUri());
        assertEquals("project_class_present_method_absent_record_component_accessor",
                edge.unresolvedReason());
    }

    private CallGraphEdge resolveEdgeFor(List<MethodInfo> methods, String rawSignature) throws Exception {
        CallGraphGenerator localGenerator = new CallGraphGenerator(projectMetadata);
        Field methodsByClass = CallGraphGenerator.class.getDeclaredField("methodsByClass");
        methodsByClass.setAccessible(true);
        methodsByClass.set(localGenerator, Map.of());

        Method index = CallGraphGenerator.class.getDeclaredMethod("indexMethodSignatures", List.class);
        index.setAccessible(true);
        index.invoke(localGenerator, methods);

        Method edgeFor = CallGraphGenerator.class.getDeclaredMethod("edgeFor", MethodSignature.class);
        edgeFor.setAccessible(true);
        MethodSignature signature = JavaIdentifierFactory.getInstance().parseMethodSignature(rawSignature);
        return (CallGraphEdge) edgeFor.invoke(localGenerator, signature);
    }
}
