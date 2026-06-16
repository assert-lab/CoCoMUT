package org.assertlab.context4docugen;

import org.assertlab.context4docugen.cli.Context4DocuGenCommand;
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

    @Test
    public void maxSourceFilesLimitsParsedSourceForSmokeRuns() throws Exception {
        Path project = Files.createTempDirectory("c4dg-cap-project");
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
        Path project = Files.createTempDirectory("c4dg-source-set-project");
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
        Path project = Files.createTempDirectory("c4dg-filter-project");
        Path output = Files.createTempDirectory("c4dg-filter-output");
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
    public void selectedModeWritesFailureArtifactForUnmatchedRows() throws Exception {
        Path project = Files.createTempDirectory("c4dg-selected-project");
        try {
            write(project.resolve("src/main/java/demo/SelectedTarget.java"),
                    "package demo; public class SelectedTarget { public String ok(String value) { return value; } }");
            ProjectMetadata metadata = new ProjectAnalyzer(project).analyze();
            String methodUri = new MethodIdentifier(metadata).identify().get(0).getId();

            Path selected = project.resolve("inputs_selected.csv");
            Files.writeString(selected,
                    "method_uri|docstring|test_prefix\n"
                            + methodUri + "|doc|test\n"
                            + "src/main/java/demo/Missing.java#demo.Missing.nope()|doc|test\n",
                    StandardCharsets.UTF_8);

            ExtractionReport report = ContextExtractorService.createDefault().extract(ContextRequest.builder()
                    .projectRoot(project)
                    .methodSelection(MethodSelection.fromCsv(selected))
                    .callGraphAlgorithm(CallGraphGenerator.Algorithm.NONE)
                    .outputMode(AnalysisOptions.OutputMode.JSONL)
                    .build());

            Path failures = Path.of(System.getProperty("user.dir"))
                    .resolve("c4dg_output")
                    .resolve(project.getFileName().toString())
                    .resolve("selected_method_failures.jsonl");
            assertTrue(report.successful());
            assertTrue(Files.isRegularFile(failures));
            List<String> lines = Files.readAllLines(failures);
            assertEquals(1, lines.size());
            assertTrue(lines.get(0).contains("SELECTED_METHOD_NOT_FOUND"));
        } finally {
            deleteRecursively(project);
        }
    }

    @Test
    public void cliValidateRejectsMalformedContextJson() throws Exception {
        Path file = Files.createTempFile("c4dg-invalid-context", ".json");
        try {
            Files.writeString(file,
                    "{\"MUT\":{},\"metadata\":{},\"provenance\":{},\"documentation_metrics\":{},"
                            + "\"javadoc_metadata\":{},\"dynamic_features\":[]}",
                    StandardCharsets.UTF_8);

            int exit = new CommandLine(new Context4DocuGenCommand())
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
