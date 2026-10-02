package org.assertlab.cocomut;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.assertlab.cocomut.source.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class EnrichmentRowPreservationTest {
    @Test
    public void malformedInlineParamPreservesRowsAndReportsParserAssertion() throws Exception {
        Path root = Files.createTempDirectory("cocomut-malformed-javadoc");
        try {
            Path source = Files.createDirectories(root.resolve("src/main/java"));
            Path file = source.resolve("Sample.java");
            Files.writeString(file, """
                    public class Sample {
                        /** {@param count description} */
                        public int broken(int count) { return count; }
                        public int healthy() { return 1; }
                    }
                    """);
            Path classes = Files.createDirectories(root.resolve("target/classes"));
            assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null,
                    "-d", classes.toString(), file.toString()));
            ProjectMetadata metadata = new ProjectMetadata.Builder().projectName("malformed-javadoc")
                    .projectPath(root).buildSystem("generic").javaVersion("17")
                    .sourceRoot(source).sourceRoots(List.of(source)).mainClassOutputs(List.of(classes))
                    .classpath(List.of(classes)).compiles(true).compileStatus("BUILD SUCCESS")
                    .bytecodeAvailable(true).analysisCanProceed(true).build();
            Path output = root.resolve("output");
            Orchestrator pipeline = new Orchestrator(ContextRequest.builder().projectRoot(root)
                    .outputDirectory(output).build(), metadata);
            assertFalse("Malformed optional documentation produces PARTIAL", pipeline.execute());
            Map<String, Object> report = pipeline.getExecutionReport();
            assertEquals("PARTIAL", report.get("status"));
            assertEquals(report.get("phase_2_methods_identified"), report.get("phase_5_jsonl_rows"));
            assertTrue(report.get("failure_codes").toString().contains("CONTEXT_EXTRACTION_FAILED"));
            ObjectMapper mapper = new ObjectMapper();
            Path jsonl = Path.of(report.get("phase_5_jsonl_file").toString());
            JsonNode row = Files.readAllLines(jsonl).stream().map(line -> {
                try { return mapper.readTree(line); }
                catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
            }).filter(node -> node.path("MUT").path("method_name").asText().equals("broken"))
                    .findFirst().orElseThrow();
            assertTrue(row.path("MUT").path("code").asText().contains("return count"));
            assertTrue(row.path("MUT").path("javadoc").asText().contains("{@param count description}"));
            assertEquals("partial", row.path("MUT").path("enrichment_status").asText());
            assertEquals("unavailable", row.path("javadoc_metadata").path("availability").asText());
            assertTrue(row.path("MUT").path("enrichment_diagnostics").toString().contains("java.lang.AssertionError"));
            JsonNode failures = mapper.readTree(Files.readString(output.resolve("method_context_failures.jsonl")));
            assertTrue(failures.path("row_preserved").asBoolean());
            assertTrue(failures.path("enrichment_diagnostics").toString().contains("java.lang.AssertionError"));
            var schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(
                    mapper.readTree(Path.of(System.getProperty("user.dir")).getParent()
                            .resolve("schemas/method-context.schema.json").toFile()));
            assertTrue(schema.validate(row).toString(), schema.validate(row).isEmpty());
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    @Test
    public void contextRecoveryPropagatesDirectAndWrappedResourceFailuresAtEveryBoundary() throws Exception {
        Path root = Files.createTempDirectory("cocomut-context-resource-failure");
        try {
            Path source = Files.createDirectories(root.resolve("src/main/java"));
            Files.writeString(source.resolve("Sample.java"), "public class Sample { public int value() { return 1; } }");
            ProjectMetadata metadata = new ProjectMetadata.Builder().projectName("resource-failure")
                    .projectPath(root).buildSystem("generic").javaVersion("17")
                    .sourceRoot(source).sourceRoots(List.of(source)).build();
            try (SourceAnalysisSession delegate = SourceBackends.spoon().open(ProjectModel.from(metadata))) {
                MethodInfo method = new MethodIdentifier(metadata).identify(delegate).get(0);
                for (String boundary : List.of("source_enrichment", "target_declaration", "call_graph")) {
                    for (Error resource : List.of(new OutOfMemoryError("synthetic OOM"),
                            new StackOverflowError("synthetic stack overflow"))) {
                        for (boolean wrapped : List.of(false, true)) {
                            Runnable fail = () -> {
                                if (wrapped) throw new IllegalStateException("wrapper", resource);
                                throw resource;
                            };
                            SourceAnalysisSession failing = new SourceAnalysisSession() {
                                public List<SourceMethod> methods() throws java.io.IOException { return delegate.methods(); }
                                public Optional<SourceContext> extractContext(String uri) throws java.io.IOException {
                                    if (boundary.equals("source_enrichment")) fail.run();
                                    if (boundary.equals("target_declaration")) throw new IllegalStateException("Recoverable enrichment failure");
                                    return delegate.extractContext(uri);
                                }
                                public Optional<SourceContext> extractDeclarationContext(String uri) {
                                    fail.run();
                                    throw new AssertionError("unreachable");
                                }
                            };
                            CallGraphGenerator graph = boundary.equals("call_graph") ? new CallGraphGenerator(metadata) {
                                @Override public CallGraphResult getCachedResult(String uri) {
                                    fail.run();
                                    throw new AssertionError("unreachable");
                                }
                            } : null;
                            ContextExtractor extractor = new ContextExtractor(metadata, graph, failing);
                            try {
                                extractor.extractContext(method);
                                fail("Resource failure at " + boundary + " must be terminal");
                            } catch (Error caught) {
                                assertSame(resource, caught);
                                assertNull(extractor.getCachedContext(method.getMethodUri()));
                            }
                        }
                    }
                }
            }
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    @Test
    public void failedEnrichmentPreservesDeclarationAndOtherRowsAndFailureArtifact() throws Exception {
        Path root = Files.createTempDirectory("cocomut-enrichment-rows");
        Path source = Files.createDirectories(root.resolve("src/main/java"));
        Files.writeString(source.resolve("Sample.java"), """
                public class Sample {
                    /** Available focal documentation. */
                    public static final int broken(final int count) {
                        if (count > 0) return count;
                        return 0;
                    }
                    public int healthy() { return 1; }
                }
                """);
        ProjectMetadata metadata = new ProjectMetadata.Builder().projectName("enrichment-rows")
                .projectPath(root).buildSystem("generic").javaVersion("17")
                .sourceRoot(source).sourceRoots(List.of(source)).build();
        try (SourceAnalysisSession delegate = SourceBackends.spoon().open(ProjectModel.from(metadata))) {
            List<MethodInfo> methods = new MethodIdentifier(metadata).identify(delegate);
            SourceAnalysisSession throwing = new SourceAnalysisSession() {
                public List<SourceMethod> methods() throws java.io.IOException { return delegate.methods(); }
                public Optional<SourceContext> extractContext(String uri) throws java.io.IOException {
                    if (delegate.findMethod(uri).orElseThrow().methodName().equals("broken")) {
                        throw new IllegalStateException("ExternalBase cannot be found");
                    }
                    return delegate.extractContext(uri);
                }
                public Optional<SourceContext> extractDeclarationContext(String uri) throws java.io.IOException {
                    return delegate.extractDeclarationContext(uri);
                }
            };
            ContextExtractor extractor = new ContextExtractor(metadata, null, throwing);
            Map<String, MethodContext> contexts = extractor.extractContextForMethods(methods);
            assertEquals(methods.size(), contexts.size());
            MethodContext broken = contexts.values().stream().filter(c -> c.getMethodName().equals("broken"))
                    .findFirst().orElseThrow();
            assertTrue(broken.getMethodBody().contains("if (count > 0)"));
            assertTrue(broken.getJavadoc().contains("Available focal documentation"));
            assertEquals(2, broken.getCyclomatic());
            assertTrue(broken.getLinesOfCode() > 0);
            assertEquals(List.of("final", "public", "static"), broken.getModifiers());
            assertEquals("int", broken.getParameterDetails().get(0).get("type"));
            assertEquals("unavailable", broken.getHierarchyResolution());
            assertEquals(IllegalStateException.class.getName(), broken.getEnrichmentDiagnostics().get(0).exceptionClass());
            assertEquals("ExternalBase cannot be found", broken.getEnrichmentDiagnostics().get(0).message());
            assertEquals(1, extractor.getExtractionFailures().size());
            assertTrue(contexts.values().stream().filter(c -> c.getMethodName().equals("healthy"))
                    .allMatch(c -> c.getEnrichmentDiagnostics().isEmpty()));

            // Exercise the real phase 4/5 reporting and JSON paths with a request-owned session.
            Path output = root.resolve("output");
            Orchestrator orchestrator = new Orchestrator(ContextRequest.builder().projectRoot(root)
                    .outputDirectory(output).build(), metadata);
            set(orchestrator, "projectMetadata", metadata);
            set(orchestrator, "methodInfos", methods);
            set(orchestrator, "sourceSession", throwing);
            assertEquals(true, invoke(orchestrator, "executePhase4"));
            assertEquals(true, invoke(orchestrator, "executePhase5"));
            assertEquals(methods.size(), orchestrator.getExecutionReport().get("phase_5_jsonl_rows"));
            assertEquals(1, orchestrator.getExecutionReport().get("phase_4_context_failures"));
            java.lang.reflect.Field codes = Orchestrator.class.getDeclaredField("failureCodes");
            codes.setAccessible(true);
            assertTrue(((java.util.Set<?>) codes.get(orchestrator)).contains(FailureCode.CONTEXT_EXTRACTION_FAILED));
            assertFalse(((java.util.Set<?>) codes.get(orchestrator)).contains(FailureCode.JSON_GENERATION_FAILED));
            ObjectMapper mapper = new ObjectMapper();
            JsonNode failure = mapper.readTree(Files.readString(output.resolve("method_context_failures.jsonl")));
            assertTrue(failure.path("row_preserved").asBoolean());
            assertEquals("ExternalBase cannot be found", failure.path("enrichment_diagnostics").get(0).path("message").asText());
            assertEquals(IllegalStateException.class.getName(), failure.path("enrichment_diagnostics").get(0).path("exception_class").asText());
            Path jsonl = Path.of(orchestrator.getExecutionReport().get("phase_5_jsonl_file").toString());
            var schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(
                    mapper.readTree(Path.of(System.getProperty("user.dir")).getParent()
                            .resolve("schemas/method-context.schema.json").toFile()));
            for (String line : Files.readAllLines(jsonl)) {
                JsonNode row = mapper.readTree(line);
                assertTrue(schema.validate(row).toString(), schema.validate(row).isEmpty());
                if (row.path("MUT").path("method_name").asText().equals("broken")) {
                    assertEquals(broken.getMethodUri(), row.path("MUT").path("method_uri").asText());
                    assertEquals("partial", row.path("MUT").path("enrichment_status").asText());
                    assertEquals("source_enrichment", row.path("MUT").path("enrichment_diagnostics").get(0).path("component").asText());
                }
            }
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void set(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
    private static Object invoke(Object target, String name) throws Exception {
        var method = target.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(target);
    }
}
