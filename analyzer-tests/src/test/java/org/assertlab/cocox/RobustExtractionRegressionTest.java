package org.assertlab.cocox;

import org.assertlab.cocox.cli.CoCoXCommand;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import picocli.CommandLine;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class RobustExtractionRegressionTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    public void maxSourceFilesLimitsParsedSourceForSmokeRuns() throws Exception {
        Path project = Files.createTempDirectory("cocox-cap-project");
        try {
            write(project.resolve("src/main/java/demo/A.java"),
                    "package demo; public class A { public void a() {} }");
            write(project.resolve("src/main/java/demo/B.java"),
                    "package demo; public class B { public void b() {} }");

            ExtractionReport report = ContextExtractorService.createDefault().extract(ContextRequest.builder()
                    .projectRoot(project)
                    .methodSelection(MethodSelection.all())
                    .callGraphAlgorithm(CallGraphGenerator.Algorithm.NONE)
                    .outputMode(AnalysisOptions.OutputMode.JSONL)
                    .maxSourceFiles(1)
                    .build());

            Map<String, Object> values = report.asMap();
            assertTrue(report.successful());
            assertEquals(1, ((Number) values.get("phase_2_methods_identified")).intValue());
            assertEquals(1, ((Number) values.get("source_max_files")).intValue());
        } finally {
            deleteRecursively(project);
        }
    }

    @Test
    public void sourceSetFilterKeepsOnlyMainMethods() throws Exception {
        Path project = Files.createTempDirectory("cocox-source-set-project");
        try {
            write(project.resolve("module-main/src/main/java/demo/MainApi.java"),
                    "package demo; public class MainApi { public void api() {} }");
            write(project.resolve("module-test/src/test/java/demo/MainApiTest.java"),
                    "package demo; public class MainApiTest { public void testApi() {} }");

            ExtractionReport all = ContextExtractorService.createDefault().extract(ContextRequest.builder()
                    .projectRoot(project)
                    .methodSelection(MethodSelection.entryPoints())
                    .callGraphAlgorithm(CallGraphGenerator.Algorithm.NONE)
                    .outputMode(AnalysisOptions.OutputMode.JSONL)
                    .build());

            ExtractionReport mainOnly = ContextExtractorService.createDefault().extract(ContextRequest.builder()
                    .projectRoot(project)
                    .methodSelection(MethodSelection.entryPoints())
                    .callGraphAlgorithm(CallGraphGenerator.Algorithm.NONE)
                    .outputMode(AnalysisOptions.OutputMode.JSONL)
                    .sourceSet("main")
                    .build());

            assertTrue(all.successful());
            assertEquals(2, all.methodsIdentified());

            assertTrue(mainOnly.successful());
            assertEquals(1, mainOnly.methodsIdentified());
            assertEquals("main", mainOnly.asMap().get("phase_2_source_set_filter"));
            assertEquals(2, ((Number) mainOnly.asMap().get("phase_2_source_set_filter_before")).intValue());
            assertEquals(1, ((Number) mainOnly.asMap().get("phase_2_source_set_filter_after")).intValue());
        } finally {
            deleteRecursively(project);
        }
    }

    @Test
    public void extractionWritesOutsideProjectAndSupportsLayeredFilters() throws Exception {
        Path project = Files.createTempDirectory("cocox-filter-project");
        Path output = Files.createTempDirectory("cocox-filter-output");
        try {
            write(project.resolve("src/main/java/demo/api/PublicApi.java"),
                    "package demo.api; public class PublicApi { public void keep() {} private void hidden() {} }");
            write(project.resolve("src/main/java/demo/internal/InternalApi.java"),
                    "package demo.internal; public class InternalApi { public void drop() {} }");

            ExtractionReport report = ContextExtractorService.createDefault().extract(ContextRequest.builder()
                    .projectRoot(project)
                    .methodSelection(MethodSelection.all())
                    .callGraphAlgorithm(CallGraphGenerator.Algorithm.NONE)
                    .outputMode(AnalysisOptions.OutputMode.JSONL)
                    .outputDirectory(output)
                    .packages(Set.of("demo.api"))
                    .classes(Set.of("PublicApi"))
                    .methods(Set.of("keep"))
                    .visibilities(Set.of("public"))
                    .includePathGlobs(Set.of("src/main/java/**/*.java"))
                    .excludePathGlobs(Set.of("**/internal/**"))
                    .build());

            Path jsonl = output.resolve("method__keep.jsonl");
            assertTrue(report.successful());
            assertTrue("Filtered JSONL should be written under explicit output dir", Files.isRegularFile(jsonl));
            assertTrue("Project root should not receive default JSONL artifact",
                    Files.notExists(project.resolve("method_contexts.jsonl")));
            List<String> rows = Files.readAllLines(jsonl);
            assertEquals(1, rows.size());
            assertTrue(rows.get(0).contains("demo.api.PublicApi.keep"));
        } finally {
            deleteRecursively(project);
            deleteRecursively(output);
        }
    }

    @Test
    public void extractionSupportsTypeAndPackageUriTargetsWithSelectionProvenance() throws Exception {
        Path project = Files.createTempDirectory("cocox-target-uri-project");
        Path output = Files.createTempDirectory("cocox-target-uri-output");
        try {
            write(project.resolve("src/main/java/demo/api/package-info.java"), """
                    /** API package docs. */
                    package demo.api;
                    """);
            write(project.resolve("src/main/java/demo/api/PublicApi.java"), """
                    package demo.api;
                    public class PublicApi {
                        public void keepOne() {}
                        public void keepTwo() {}
                    }
                    """);
            write(project.resolve("src/main/java/demo/other/OtherApi.java"), """
                    package demo.other;
                    public class OtherApi {
                        public void drop() {}
                    }
                    """);

            String typeUri = "src/main/java/demo/api/PublicApi.java#demo.api.PublicApi";
            ExtractionReport typeReport = ContextExtractorService.createDefault().extract(ContextRequest.builder()
                    .projectRoot(project)
                    .methodSelection(MethodSelection.all())
                    .callGraphAlgorithm(CallGraphGenerator.Algorithm.NONE)
                    .outputMode(AnalysisOptions.OutputMode.JSONL)
                    .outputDirectory(output.resolve("type"))
                    .typeUri(typeUri)
                    .build());

            Path typeJsonl = output.resolve("type").resolve("type_src_main_java_demo_api_PublicApi.java#demo.api.PublicApi.jsonl");
            assertTrue(typeReport.successful());
            assertTrue(Files.isRegularFile(typeJsonl));
            List<String> typeRows = Files.readAllLines(typeJsonl);
            assertEquals(2, typeRows.size());
            JsonNode typeSelection = MAPPER.readTree(typeRows.get(0)).get("selection");
            assertEquals("type", typeSelection.get("kind").asText());
            assertEquals(typeUri, typeSelection.get("uri").asText());

            String packageUri = "src/main/java/demo/api/package-info.java#demo.api";
            ExtractionReport packageReport = ContextExtractorService.createDefault().extract(ContextRequest.builder()
                    .projectRoot(project)
                    .methodSelection(MethodSelection.all())
                    .callGraphAlgorithm(CallGraphGenerator.Algorithm.NONE)
                    .outputMode(AnalysisOptions.OutputMode.JSONL)
                    .outputDirectory(output.resolve("package"))
                    .packageUri(packageUri)
                    .build());

            Path packageJsonl = output.resolve("package")
                    .resolve("package_src_main_java_demo_api_package-info.java#demo.api.jsonl");
            assertTrue(packageReport.successful());
            assertTrue(Files.isRegularFile(packageJsonl));
            List<String> packageRows = Files.readAllLines(packageJsonl);
            assertEquals(2, packageRows.size());
            JsonNode packageSelection = MAPPER.readTree(packageRows.get(0)).get("selection");
            assertEquals("package", packageSelection.get("kind").asText());
            assertEquals(packageUri, packageSelection.get("uri").asText());
        } finally {
            deleteRecursively(project);
            deleteRecursively(output);
        }
    }

    @Test
    public void cliValidateRejectsMalformedContextJson() throws Exception {
        Path file = Files.createTempFile("cocox-invalid-context", ".json");
        try {
            Files.writeString(file,
                    "{\"MUT\":{},\"metadata\":{},\"provenance\":{},\"documentation_metrics\":{},"
                            + "\"javadoc_metadata\":{},\"dynamic_features\":[]}",
                    StandardCharsets.UTF_8);

            int exit = new CommandLine(new CoCoXCommand())
                    .execute("validate", "--json", file.toString());

            assertEquals(1, exit);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static void write(Path path, String text) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, text, StandardCharsets.UTF_8);
    }

    private static void deleteRecursively(Path root) throws Exception {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            for (Path path : walk.sorted((a, b) -> b.compareTo(a)).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
