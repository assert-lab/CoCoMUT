package org.assertlab.cocomut;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class RobustExtractionRegressionTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    public void maxSourceFilesLimitsParsedSourceForSmokeRuns() throws Exception {
        Path project = Files.createTempDirectory("cocomut-cap-project");
        try {
            write(project.resolve("src/main/java/demo/A.java"),
                    "package demo; public class A { public void a() {} }");
            write(project.resolve("src/main/java/demo/B.java"),
                    "package demo; public class B { public void b() {} }");
            compileProject(project);

            ExtractionReport report = ContextExtractorService.createDefault().extract(ContextRequest.builder()
                    .projectRoot(project)
                    .scope(ContextRequest.Scope.ALL)
                    .maxSourceFiles(1)
                    .build());

            Map<String, Object> values = report.asMap();
            assertCompleted(report);
            assertEquals(1, ((Number) values.get("phase_2_methods_identified")).intValue());
            assertEquals(1, ((Number) values.get("source_max_files")).intValue());
        } finally {
            deleteRecursively(project);
        }
    }

    @Test
    public void sourceSetFilterKeepsOnlyMainMethods() throws Exception {
        Path project = Files.createTempDirectory("cocomut-source-set-project");
        try {
            write(project.resolve("module-main/src/main/java/demo/MainApi.java"),
                    "package demo; public class MainApi { public void api() {} }");
            write(project.resolve("module-test/src/test/java/demo/MainApiTest.java"),
                    "package demo; public class MainApiTest { public void testApi() {} }");
            compileProject(project);

            ExtractionReport all = ContextExtractorService.createDefault().extract(ContextRequest.builder()
                    .projectRoot(project)
                    .scope(ContextRequest.Scope.ENTRY_POINTS)
                    .build());

            ExtractionReport mainOnly = ContextExtractorService.createDefault().extract(ContextRequest.builder()
                    .projectRoot(project)
                    .scope(ContextRequest.Scope.ENTRY_POINTS)
                    .sourceSet("main")
                    .build());

            assertCompleted(all);
            assertEquals(2, all.methodsIdentified());

            assertCompleted(mainOnly);
            assertEquals(1, mainOnly.methodsIdentified());
            assertEquals("main", mainOnly.asMap().get("phase_2_source_set_filter"));
            assertEquals(1, ((Number) mainOnly.asMap().get("phase_2_source_set_filter_before")).intValue());
            assertEquals(1, ((Number) mainOnly.asMap().get("phase_2_source_set_filter_after")).intValue());
        } finally {
            deleteRecursively(project);
        }
    }

    @Test
    public void extractionWritesOutsideProjectAndSupportsLayeredFilters() throws Exception {
        Path project = Files.createTempDirectory("cocomut-filter-project");
        Path output = Files.createTempDirectory("cocomut-filter-output");
        try {
            write(project.resolve("src/main/java/demo/api/PublicApi.java"),
                    "package demo.api; public class PublicApi { public void keep() {} private void hidden() {} }");
            write(project.resolve("src/main/java/demo/internal/InternalApi.java"),
                    "package demo.internal; public class InternalApi { public void drop() {} }");
            compileProject(project);

            ExtractionReport report = ContextExtractorService.createDefault().extract(ContextRequest.builder()
                    .projectRoot(project)
                    .scope(ContextRequest.Scope.ALL)
                    .outputDirectory(output)
                    .packages(Set.of("demo.api"))
                    .classes(Set.of("PublicApi"))
                    .methods(Set.of("keep"))
                    .visibilities(Set.of("public"))
                    .includePathGlobs(Set.of("src/main/java/**/*.java"))
                    .excludePathGlobs(Set.of("**/internal/**"))
                    .build());

            assertCompleted(report);
            Path jsonl = singleJsonl(output, "method__keep__*.jsonl");
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
        Path project = Files.createTempDirectory("cocomut-target-uri-project");
        Path output = Files.createTempDirectory("cocomut-target-uri-output");
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
            compileProject(project);

            String typeUri = "src/main/java/demo/api/PublicApi.java#demo.api.PublicApi";
            ExtractionReport typeReport = ContextExtractorService.createDefault().extract(ContextRequest.builder()
                    .projectRoot(project)
                    .scope(ContextRequest.Scope.ALL)
                    .outputDirectory(output.resolve("type"))
                    .typeUri(typeUri)
                    .build());

            assertCompleted(typeReport);
            Path typeJsonl = singleJsonl(output.resolve("type"),
                    "type_src_main_java_demo_api_PublicApi.java#demo.api.PublicApi__*.jsonl");
            assertTrue(Files.isRegularFile(typeJsonl));
            List<String> typeRows = Files.readAllLines(typeJsonl);
            assertEquals(2, typeRows.size());
            JsonNode typeSelection = MAPPER.readTree(typeRows.get(0)).get("selection");
            assertEquals("type", typeSelection.get("kind").asText());
            assertEquals(typeUri, typeSelection.get("uri").asText());

            String packageUri = "src/main/java/demo/api/package-info.java#demo.api";
            ExtractionReport packageReport = ContextExtractorService.createDefault().extract(ContextRequest.builder()
                    .projectRoot(project)
                    .scope(ContextRequest.Scope.ALL)
                    .outputDirectory(output.resolve("package"))
                    .packageUri(packageUri)
                    .build());

            assertCompleted(packageReport);
            Path packageJsonl = singleJsonl(output.resolve("package"),
                    "package_src_main_java_demo_api_package-info.java#demo.api__*.jsonl");
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
    public void unsupportedInlineJavadocTagDoesNotAbortExtraction() throws Exception {
        Path project = Files.createTempDirectory("cocomut-unsupported-javadoc-tag");
        try {
            write(project.resolve("src/main/java/demo/JavadocEdge.java"), """
                    package demo;
                    public class JavadocEdge {
                        /** Parser-hostile inline standard tag: {@return not valid inline usage}. */
                        public String value() { return "ok"; }
                    }
                    """);
            compileProject(project);

            ExtractionReport report = ContextExtractorService.createDefault().extract(ContextRequest.builder()
                    .projectRoot(project)
                    .scope(ContextRequest.Scope.ALL)
                    .build());

            assertCompleted(report);
            assertEquals(1, report.methodsIdentified());
            assertEquals(1, report.jsonlRows());
        } finally {
            deleteRecursively(project);
        }
    }

    @Test
    public void newerDependencyClassfileDoesNotAbortSourceParsing() throws Exception {
        Path project = Files.createTempDirectory("cocomut-newer-bytecode-project");
        Path dependency = Files.createTempDirectory("cocomut-newer-bytecode-dep");
        try {
            write(project.resolve("src/main/java/demo/UsesFuture.java"), """
                    package demo;
                    public class UsesFuture {
                        public String value() { return "ok"; }
                    }
                    """);
            compileProject(project);

            write(dependency.resolve("src/future/FutureThing.java"), """
                    package future;
                    public class FutureThing {}
                    """);
            Path dependencyClasses = dependency.resolve("classes");
            compileJavaFiles(dependency.resolve("src"), dependencyClasses);
            makeClassfileTooNew(dependencyClasses.resolve("future/FutureThing.class"));

            write(project.resolve("src/main/java/demo/UsesFuture.java"), """
                    package demo;
                    import future.FutureThing;
                    public class UsesFuture {
                        private FutureThing future;
                        public String value() { return "ok"; }
                    }
                    """);
            Path classpathFile = project.resolve("classpath.txt");
            write(classpathFile, dependencyClasses.toString() + System.lineSeparator());

            ExtractionReport report = ContextExtractorService.createDefault().extract(ContextRequest.builder()
                    .projectRoot(project)
                    .scope(ContextRequest.Scope.ALL)
                    .classpathFile(classpathFile)
                    .build());

            assertCompleted(report);
            assertEquals(1, report.methodsIdentified());
            assertEquals(1, report.jsonlRows());
        } finally {
            deleteRecursively(project);
            deleteRecursively(dependency);
        }
    }

    private static void write(Path path, String text) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, text, StandardCharsets.UTF_8);
    }

    private static void assertCompleted(ExtractionReport report) {
        assertTrue("Expected completed extraction, got " + report.status(),
                "SUCCESS".equals(report.status()) || "PARTIAL".equals(report.status()));
    }

    private static void compileProject(Path project) throws Exception {
        Path classes = project.resolve("target/classes");
        compileJavaFiles(project, classes);
    }

    private static void compileJavaFiles(Path sourceRoot, Path classes) throws Exception {
        Files.createDirectories(classes);
        List<String> command = new ArrayList<>();
        command.add(javac());
        command.add("-d");
        command.add(classes.toString());
        try (var walk = Files.walk(sourceRoot)) {
            walk.filter(path -> path.toString().endsWith(".java"))
                    .sorted(Comparator.comparing(Path::toString))
                    .map(Path::toString)
                    .forEach(command::add);
        }
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        if (!process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("javac timed out for " + sourceRoot);
        }
        if (process.exitValue() != 0) {
            throw new AssertionError("javac failed for " + sourceRoot);
        }
    }

    private static void makeClassfileTooNew(Path classFile) throws Exception {
        byte[] bytes = Files.readAllBytes(classFile);
        bytes[6] = 0;
        bytes[7] = 70;
        Files.write(classFile, bytes);
    }

    private static String javac() {
        Path javac = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").toLowerCase().contains("win") ? "javac.exe" : "javac");
        return Files.isRegularFile(javac) ? javac.toString() : "javac";
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

    private static Path singleJsonl(Path directory, String glob) throws Exception {
        try (var stream = Files.newDirectoryStream(directory, glob)) {
            List<Path> matches = new ArrayList<>();
            for (Path path : stream) {
                matches.add(path);
            }
            assertEquals("Expected one JSONL file matching " + glob, 1, matches.size());
            return matches.get(0);
        }
    }
}
