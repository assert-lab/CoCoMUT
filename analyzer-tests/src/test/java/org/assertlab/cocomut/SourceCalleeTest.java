package org.assertlab.cocomut;

import org.assertlab.cocomut.source.*;
import org.junit.Test;
import java.nio.file.*;
import java.util.*;
import javax.tools.ToolProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.junit.Assert.*;

public class SourceCalleeTest {
    @Test
    public void anonymousAllocationsResolveSelectedSuperclassConstructor() throws Exception {
        Path root = Files.createTempDirectory("anonymous-source-callees");
        try {
            Path source = root.resolve("src/main/java/demo/Example.java");
            Files.createDirectories(source.getParent());
            Files.writeString(source, """
                    package demo;
                    public class Example {
                        public static class Base {
                            public Base() {}
                            public Base(String value) {}
                            public Base(int value) {}
                        }
                        static void hidden() {}
                        public Object ordinary() { return new Base(); }
                        public Object anonymous() { return new Base() { void ignored() { hidden(); } }; }
                        public Object overloaded() { return new Base("value") { void ignored() { hidden(); } }; }
                        public Object external() { return new java.util.ArrayList<String>() {}; }
                        public Object externalOverloaded() { return new java.util.ArrayList<String>(4) {}; }
                    }
                    """);
            Path classes = Files.createDirectories(root.resolve("target/classes"));
            assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                    "-d", classes.toString(), source.toString()));
            var metadata = new ProjectMetadata.Builder().projectName("fixture").projectPath(root).buildSystem("unknown")
                    .javaVersion("17").sourceRoot(source.getParent()).sourceRoots(List.of(source.getParent()))
                    .mainClassOutputs(List.of(classes)).build();
            try (var session = SourceBackends.spoon().open(ProjectModel.from(metadata))) {
                for (String name : List.of("ordinary", "anonymous", "overloaded", "external", "externalOverloaded")) {
                    var method = session.methods().stream().filter(m -> m.methodName().equals(name)).findFirst().orElseThrow();
                    var callees = session.extractContext(method.methodUri()).orElseThrow().callees();
                    assertEquals(name + ": " + callees, 1, callees.size());
                    var callee = callees.get(0);
                    boolean external = name.startsWith("external");
                    assertEquals(name + ": " + callee, external ? "resolved_external" : "resolved", callee.resolution());
                    assertEquals(external ? "java.util.ArrayList" : "demo.Example$Base", callee.declaringType());
                    String parameters = name.equals("overloaded") ? "java.lang.String"
                            : name.equals("externalOverloaded") ? "int" : "";
                    assertEquals((external ? "ArrayList" : "Base") + "(" + parameters + "):void", callee.signature());
                    assertEquals(external ? "" : "src/main/java/demo/Example.java#demo.Example$Base."
                            + callee.signature(), callee.methodUri());
                    assertEquals(external ? "java:java.util.ArrayList#" + callee.signature()
                            : callee.methodUri(), callee.targetUri());
                }
            }
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    @Test
    public void sourceDeclarationsAreDeduplicatedAndIndependentOfDispatch() throws Exception {
        Path root = Files.createTempDirectory("source-callees");
        try {
            Path source = root.resolve("src/main/java/demo/Example.java");
            Files.createDirectories(source.getParent());
            Files.writeString(source, """
                    package demo;
                    public class Example {
                        public abstract static class Field { public abstract int get(long value); }
                        public static final class YearField extends Field { public int get(long v) { return 2026; } }
                        public static final class MonthField extends Field { public int get(long v) { return 10; } }
                        public static Field year() { return new YearField(); }
                        public static Field month() { return new MonthField(); }
                        public static int mut() { return year().get(0L); }
                        public static int repeated() { return year().get(0L) + year().get(1L); }
                        public static String external() { return "x".trim().trim(); }
                        public static Object construct() { return new Object(); }
                        public static int nested() { class Local { int ignored(){ return month().get(0); } } return year().get(0); }
                    }
                    """);
            Path classes = root.resolve("target/classes"); Files.createDirectories(classes);
            assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null,null,null,"--release","8","-d",classes.toString(),source.toString()));
            var metadata = new ProjectMetadata.Builder().projectName("fixture").projectPath(root).buildSystem("unknown")
                    .javaVersion("8").sourceRoot(root.resolve("src/main/java")).sourceRoots(List.of(root.resolve("src/main/java")))
                    .mainClassOutputs(List.of(classes)).build();
            try (var session = SourceBackends.spoon().open(ProjectModel.from(metadata))) {
                var methods = new MethodIdentifier(metadata).identify(session);
                var focal = methods.stream().filter(m -> m.getMethodName().equals("mut")).findFirst().orElseThrow();
                var graph = new CallGraphGenerator(metadata, CallGraphGenerator.Algorithm.RTA);
                assertTrue(graph.initialize());
                graph.generateForMethods(methods, List.of(focal));
                assertEquals(3, graph.getCachedResult(focal.getMethodUri()).getCalleeCount());
                var context = new ContextExtractor(metadata, graph, session).extractContext(focal);
                var withoutGraph = new ContextExtractor(metadata, null, session).extractContext(focal);
                assertEquals(2, context.getCallees().size());
                assertEquals(context.getCallees(), withoutGraph.getCallees());
                assertTrue(context.getCallees().stream().anyMatch(c -> c.declaringType().equals("demo.Example$Field") && c.methodName().equals("get")));
                assertTrue(context.getCallees().stream().allMatch(c -> c.resolution().equals("resolved")));
                for (String name : List.of("repeated", "nested")) {
                    var method = methods.stream().filter(m -> m.getMethodName().equals(name)).findFirst().orElseThrow();
                    assertEquals(name, 2, session.extractContext(method.getMethodUri()).orElseThrow().callees().size());
                }
                for (String name : List.of("external", "construct")) {
                    var method = methods.stream().filter(m -> m.getMethodName().equals(name)).findFirst().orElseThrow();
                    var callees = session.extractContext(method.getMethodUri()).orElseThrow().callees();
                    assertEquals(name, 1, callees.size());
                    assertEquals(name, "resolved_external", callees.get(0).resolution());
                }
                Path output = root.resolve("contexts.jsonl");
                new JsonGenerator(root).generateJsonLinesFile(Map.of(context.getMethodUri(), context), output);
                var mapper = new ObjectMapper(); var json = mapper.readTree(Files.readString(output));
                assertEquals(2, json.path("callees").size());
                assertEquals(2, json.path("metadata").path("callee_count").asInt());
                assertEquals(3, json.path("metadata").path("call_graph").path("callee_count").asInt());
                var schema = com.networknt.schema.JsonSchemaFactory.getInstance(com.networknt.schema.SpecVersion.VersionFlag.V202012)
                        .getSchema(mapper.readTree(Path.of(System.getProperty("user.dir")).getParent().resolve("schemas/method-context.schema.json").toFile()));
                assertTrue(schema.validate(json).toString(), schema.validate(json).isEmpty());
            }
            // Missing source evidence must not be replaced with guessed declarations.
            Files.writeString(source, "package demo; class Example { void unresolved(Missing value) { value.call(); } }");
            try (var session = SourceBackends.spoon().open(ProjectModel.from(metadata))) {
                var method = session.methods().stream().filter(m -> m.methodName().equals("unresolved")).findFirst().orElseThrow();
                var callees = session.extractContext(method.methodUri()).orElseThrow().callees();
                assertEquals(1, callees.size()); assertEquals("unresolved", callees.get(0).resolution());
                assertEquals("", callees.get(0).methodUri()); assertEquals("", callees.get(0).targetUri());
            }
        } finally {
            try(var paths=Files.walk(root)){ for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p); }
        }
    }
}
