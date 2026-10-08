package org.assertlab.cocomut;

import org.assertlab.cocomut.source.ProjectModel;
import org.assertlab.cocomut.source.SourceBackends;
import org.junit.Test;
import java.nio.file.*;
import java.util.*;
import javax.tools.ToolProvider;
import static org.junit.Assert.*;

public class ExternalNestedJavadocTest {
    @Test
    public void inheritedNestedDeclarationsHideAncestorsBeforeAmbiguityCheck() throws Exception {
        Path root = Files.createTempDirectory("inherited-nested-hiding");
        try {
            Path dependency = root.resolve("Child.java");
            Files.writeString(dependency, """
                    package dep;
                    class Grand { public static class Inner {} }
                    class Parent extends Grand { public static class Inner {} }
                    public class Child extends Parent {
                        public interface Common { class Shared {} }
                        public interface Left extends Common {}
                        public interface Right extends Common {}
                        public interface Other { class Shared {} }
                        public static class Diamond implements Left, Right {}
                        public static class Conflict implements Left, Other {}
                    }
                    """);
            Path classes = root.resolve("dependency-classes");
            Files.createDirectories(classes);
            assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                    "-d", classes.toString(), dependency.toString()));
            Path source = root.resolve("src/main/java/Example.java");
            Files.createDirectories(source.getParent());
            Files.writeString(source, """
                    import dep.Child;
                    class Example {
                        Child.Inner proof;
                        /** {@link Child.Inner} {@link Child.Diamond.Shared}
                         * {@link Child.Conflict.Shared} */ public void focal() {}
                    }
                    """);
            assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                    "-cp", classes.toString(), "-d", root.resolve("proof").toString(), source.toString()));
            try (var session = SourceBackends.spoon().open(ProjectModel.from(metadata(root, source, classes)))) {
                var method = session.methods().stream().filter(m -> m.methodName().equals("focal")).findFirst().orElseThrow();
                @SuppressWarnings("unchecked")
                var refs = (List<Map<String,Object>>) session.extractContext(method.methodUri()).orElseThrow()
                        .javadocMetadata().get("javadoc_references");
                assertEquals(3, refs.size());
                assertEquals("external_symbol", refs.get(0).get("resolution"));
                assertEquals("dep.Parent$Inner", refs.get(0).get("external_type"));
                assertEquals("external_symbol", refs.get(1).get("resolution"));
                assertEquals("dep.Child$Common$Shared", refs.get(1).get("external_type"));
                assertEquals("unresolved", refs.get(2).get("resolution"));
            }
        } finally { deleteTree(root); }
    }

    @Test
    public void missingNestedSuperclassPreservesRowsAndRecordsLinkageFailure() throws Exception {
        Path root = Files.createTempDirectory("broken-nested-dependency");
        try {
            Path dependency = root.resolve("BrokenOuter.java");
            Files.writeString(dependency, """
                    package dep;
                    class Missing {}
                    public class BrokenOuter { public static class Inner extends Missing {} }
                    """);
            Path classes = root.resolve("dependency-classes");
            Files.createDirectories(classes);
            assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                    "-d", classes.toString(), dependency.toString()));
            Files.delete(classes.resolve("dep/Missing.class"));
            Path source = root.resolve("src/main/java/Example.java");
            Files.createDirectories(source.getParent());
            Files.writeString(source, """
                    import dep.BrokenOuter;
                    class Example {
                        /** {@link BrokenOuter.Inner} */ public void imported() {}
                        /** {@link dep.BrokenOuter.Inner} */ public void qualified() {}
                        /** Healthy method. */ public void healthy() {}
                    }
                    """);
            var metadata = metadata(root, source, classes);
            try (var session = SourceBackends.spoon().open(ProjectModel.from(metadata))) {
                var contexts = new LinkedHashMap<String, MethodContext>();
                for (var method : new MethodIdentifier(metadata).identify(session)) {
                    var context = new ContextExtractor(metadata, null, session).extractContext(method);
                    contexts.put(method.getMethodUri(), context);
                    if (!method.getMethodName().equals("healthy")) {
                        var sourceContext = session.extractContext(method.getMethodUri()).orElseThrow();
                        assertTrue(sourceContext.enrichmentDiagnostics().toString(),
                                sourceContext.enrichmentDiagnostics().stream().anyMatch(d ->
                                        d.exceptionClass().equals("java.lang.NoClassDefFoundError")));
                    }
                }
                assertEquals(3, contexts.size());
                Path output = root.resolve("rows.jsonl");
                new JsonGenerator(root).generateJsonLinesFile(contexts, output);
                assertEquals(3, Files.readAllLines(output).size());
            }
            Path projectClasses = Files.createDirectories(root.resolve("target/classes"));
            assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                    "-cp", classes.toString(), "-d", projectClasses.toString(), source.toString()));
            var pipelineMetadata = new ProjectMetadata.Builder().projectName("fixture").projectPath(root)
                    .sourceRoot(source.getParent()).sourceRoots(List.of(source.getParent()))
                    .mainClassOutputs(List.of(projectClasses)).dependencyClasspath(List.of(classes))
                    .buildSystem("generic").javaVersion("17").compiles(true).compileStatus("BUILD SUCCESS")
                    .bytecodeAvailable(true).analysisCanProceed(true).build();
            var pipeline = new Orchestrator(ContextRequest.builder().projectRoot(root)
                    .outputDirectory(root.resolve("pipeline-output")).build(), pipelineMetadata);
            assertFalse(pipeline.execute());
            var report = pipeline.getExecutionReport();
            assertEquals(report.toString(), "PARTIAL", report.get("status"));
            assertTrue(report.get("failure_codes").toString().contains("CONTEXT_EXTRACTION_FAILED"));
            assertEquals(3, Files.readAllLines(Path.of(report.get("phase_5_jsonl_file").toString())).size());
        } finally { deleteTree(root); }
    }

    private static ProjectMetadata metadata(Path root, Path source, Path classes) {
        return new ProjectMetadata.Builder().projectName("fixture").projectPath(root)
                .sourceRoot(source.getParent()).sourceRoots(List.of(source.getParent()))
                .buildSystem("unknown").dependencyClasspath(List.of(classes)).javaVersion("17").build();
    }

    private static void deleteTree(Path root) throws Exception {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }

    @Test
    public void externalNestedLinksRespectVisibilityAndAmbiguity() throws Exception {
        Path root = Files.createTempDirectory("external-nested-javadoc");
        try {
            Path dependency = root.resolve("dependency");
            Files.createDirectories(dependency);
            Path outer = dependency.resolve("Outer.java");
            Files.writeString(outer, "package dep; public class Outer extends Base { public static class Inner { public static class Deep {} } } class Base { public static class Inherited {} }");
            Path depClasses = root.resolve("dep-classes"); Files.createDirectories(depClasses);
            assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                    "-d", depClasses.toString(), outer.toString()));
            Path sources = root.resolve("src/main/java"); Files.createDirectories(sources);
            Map<String,String> fixtures = new LinkedHashMap<>();
            fixtures.put("Qualified", "/** {@link java.util.Map.Entry} {@link java.util.Map.Missing} */");
            fixtures.put("Imported", "/* import missing.Map; */ import java.util.Map; /** {@link Map.Entry} */");
            fixtures.put("Direct", "import java.util.Map.Entry; /** {@link Entry} */");
            fixtures.put("Dependency", "import dep.Outer; /** {@link Outer.Inner.Deep} {@link Outer.Inherited} */");
            fixtures.put("Ambiguous", "import java.util.*; import java.sql.*; /** {@link Date} */");
            fixtures.put("Shadowed", "import java.util.*; /** {@link Map.Entry} */");
            fixtures.put("Parameter", "import java.util.Map; /** {@link Map.Entry} */");
            for (var entry : fixtures.entrySet()) {
                String text = entry.getValue(); int doc = text.indexOf("/**");
                String generic = entry.getKey().equals("Parameter") ? "<Map>" : "";
                String nested = entry.getKey().equals("Shadowed") ? "static class Map {}" : "";
                Files.writeString(sources.resolve(entry.getKey()+".java"), "package demo; "
                        + text.substring(0,doc) + "public class " + entry.getKey() + generic + " { "
                        + nested + text.substring(doc) + " public void focal() {} }");
            }
            ProjectMetadata metadata = new ProjectMetadata.Builder().projectName("fixture").projectPath(root).sourceRoot(sources).sourceRoots(List.of(sources))
                    .buildSystem("unknown").dependencyClasspath(List.of(depClasses)).javaVersion("17").build();
            try (var session = SourceBackends.spoon().open(ProjectModel.from(metadata))) {
                assertEquals(7, session.methods().stream().filter(m -> m.methodName().equals("focal")).count());
                for (var method : session.methods()) {
                    if (!method.methodName().equals("focal")) continue;
                    @SuppressWarnings("unchecked")
                    var refs = (List<Map<String,Object>>) session.extractContext(method.methodUri()).orElseThrow()
                            .javadocMetadata().get("javadoc_references");
                    assertFalse(method.typeName(), refs.isEmpty());
                    for (var ref : refs) {
                        String target = ref.get("target").toString();
                        boolean unresolved = target.endsWith("Missing") || method.typeName().endsWith("Ambiguous")
                                || method.typeName().endsWith("Shadowed") || method.typeName().endsWith("Parameter");
                        assertEquals(method.typeName()+" "+target, unresolved ? "unresolved" : "external_symbol", ref.get("resolution"));
                        assertFalse(ref.containsKey("type_uri"));
                        if (!unresolved) {
                            boolean dep = method.typeName().endsWith("Dependency");
                            assertEquals(dep ? (target.endsWith("Inherited") ? "dep.Base$Inherited" : "dep.Outer$Inner$Deep")
                                    : "java.util.Map$Entry", ref.get("external_type"));
                            assertEquals(dep ? "external_library" : "external_jdk", ref.get("reference_domain"));
                        }
                    }
                }
            }
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
