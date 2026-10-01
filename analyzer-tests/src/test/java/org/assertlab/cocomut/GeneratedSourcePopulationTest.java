package org.assertlab.cocomut;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.assertlab.cocomut.source.SourceRootPolicy;
import org.junit.Test;
import static org.junit.Assert.*;

public class GeneratedSourcePopulationTest {
    @Test
    public void generatedInputsResolveDocumentationButAreNotDefaultFocalRows() throws Exception {
        Path root = Files.createTempDirectory("cocomut-generated-population-");
        try {
            Path original = Files.createDirectories(root.resolve("src/main/java/demo"));
            Path generated = Files.createDirectories(root.resolve("target/generated-sources/javacc/demo"));
            Files.writeString(generated.resolve("GeneratedParent.java"), """
                    package demo;
                    public interface GeneratedParent {
                        /** Returns the generated protocol value. */
                        int value();
                    }
                    """);
            Files.writeString(original.resolve("Original.java"), """
                    package demo;
                    public class Original implements GeneratedParent {
                        /** {@inheritDoc} */
                        @Override public int value() { return 1; }
                    }
                    """);
            Path classes = Files.createDirectories(root.resolve("target/classes"));
            int compiled = javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null,
                    "--release", "17", "-d", classes.toString(),
                    generated.resolve("GeneratedParent.java").toString(), original.resolve("Original.java").toString());
            assertEquals(0, compiled);
            ProjectMetadata metadata = new ProjectAnalyzer(ContextRequest.builder().projectRoot(root)
                    .sourceRoot(root.resolve("src/main/java"))
                    .sourceRoot(root.resolve("target/generated-sources/javacc"))
                    .classOutputDir(classes).skipBuild(true).build()).analyze();

            Orchestrator defaults = pipeline(root, metadata, "default", Set.of(), List.of());
            String rows = rows(defaults);
            assertTrue(rows.contains("demo.Original"));
            assertFalse(focalNames(rows).stream().anyMatch(name -> name.startsWith("demo.GeneratedParent.")));
            assertTrue(defaults.getAnalysisUniverseMethods().stream().anyMatch(m -> m.getTypeName().equals("demo.GeneratedParent")));
            assertTrue("Generated source documentation remains resolvable", rows.contains("generated protocol value"));
            assertTrue(((Number) defaults.getExecutionReport().get("phase_2_generated_methods_excluded")).intValue() > 0);
            var manifest = new ObjectMapper().readTree(root.resolve("default/extraction_manifest.json").toFile());
            assertEquals("generated", manifest.path("artifacts").path("source_root_roles")
                    .path("target/generated-sources/javacc").asText());
            assertEquals("original", manifest.path("artifacts").path("source_root_roles").path("src/main/java").asText());

            Orchestrator generatedOnly = pipeline(root, metadata, "generated", Set.of("generated"), List.of());
            String selected = rows(generatedOnly);
            assertTrue(focalNames(selected).stream().anyMatch(name -> name.startsWith("demo.GeneratedParent.")));
            assertFalse(focalNames(selected).stream().anyMatch(name -> name.startsWith("demo.Original.")));
            assertEquals(0, ((Number) generatedOnly.getExecutionReport().get("phase_2_generated_methods_excluded")).intValue());

            Orchestrator explicit = pipeline(root, metadata, "explicit", Set.of(), List.of(root.resolve("target/generated-sources/javacc")));
            assertTrue(rows(explicit).contains("demo.GeneratedParent"));
        } finally { remove(root); }
    }

    @Test
    public void successfulMavenBuildKeepsExplicitOriginalRootBoundary() throws Exception {
        org.junit.Assume.assumeFalse(System.getProperty("os.name", "").toLowerCase().contains("win"));
        Path root = Files.createTempDirectory("cocomut-explicit-built-root-");
        try {
            Path chosen = Files.createDirectories(root.resolve("chosen/java"));
            Path conventional = Files.createDirectories(root.resolve("src/main/java"));
            Files.writeString(chosen.resolve("Chosen.java"), "class Chosen { int value(){return 1;} }\n");
            Files.writeString(conventional.resolve("Excluded.java"), "class Excluded { int value(){return 2;} }\n");
            Path classes = Files.createDirectories(root.resolve("target/classes"));
            Files.write(classes.resolve("Compiled.class"), new byte[] {0});
            Files.writeString(root.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion><groupId>demo</groupId><artifactId>root</artifactId><version>1</version></project>\n");
            Files.createDirectories(root.resolve(".mvn/wrapper"));
            Files.writeString(root.resolve(".mvn/wrapper/maven-wrapper.properties"), "distributionUrl=unused\n");
            Path wrapper = root.resolve("mvnw");
            Files.writeString(wrapper, "#!/bin/sh\nexit 0\n");
            assertTrue(wrapper.toFile().setExecutable(true));
            ProjectMetadata metadata = new ProjectAnalyzer(ContextRequest.builder().projectRoot(root)
                    .sourceRoot(chosen).classOutputDir(classes).allowUnsandboxedBuild().build()).analyze();
            assertTrue(metadata.isBuildSucceeded());
            assertEquals(List.of(chosen), metadata.getSourceRoots());
        } finally { remove(root); }
    }

    @Test
    public void provenanceUsesBuildDirectoriesInsteadOfNamesContainingGenerated() {
        assertTrue(SourceRootPolicy.isGenerated(Path.of("module/target/generated-sources/javacc/Foo.java")));
        assertTrue(SourceRootPolicy.isGenerated(Path.of("module/target/generated-test-sources/test/Foo.java")));
        assertTrue(SourceRootPolicy.isGenerated(Path.of("module/build/generated/source/buildConfig/Foo.java")));
        assertFalse(SourceRootPolicy.isGenerated(Path.of("src/main/java/demo/GeneratedHelper.java")));
        assertFalse(SourceRootPolicy.isGenerated(Path.of("generated-api/src/main/java/demo/Foo.java")));
    }

    private static Orchestrator pipeline(Path root, ProjectMetadata metadata, String output,
                                         Set<String> sets, List<Path> roots) {
        Orchestrator pipeline = new Orchestrator(ContextRequest.builder().projectRoot(root)
                .sourceSets(sets).sourceRoots(roots).scope(ContextRequest.Scope.ALL)
                .outputDirectory(root.resolve(output)).build(), metadata);
        pipeline.execute();
        assertNotEquals(pipeline.getExecutionReport().toString(), "FAILED", pipeline.getExecutionReport().get("status"));
        return pipeline;
    }

    private static Set<String> focalNames(String rows) throws Exception {
        Set<String> names = new java.util.LinkedHashSet<>();
        for (String row : rows.lines().toList()) {
            var focal = new ObjectMapper().readTree(row).path("MUT");
            assertTrue(focal.isObject());
            names.add(focal.path("qualified_name").asText());
        }
        return names;
    }

    private static String rows(Orchestrator pipeline) throws Exception {
        return Files.readString(Path.of(pipeline.getExecutionReport().get("phase_5_jsonl_file").toString()));
    }

    private static void remove(Path root) throws Exception {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
