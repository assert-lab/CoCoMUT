package org.assertlab.context4docugen;

import org.assertlab.context4docugen.source.ProjectModel;
import org.assertlab.context4docugen.source.SourceBackends;
import org.assertlab.context4docugen.source.SourceContext;
import org.assertlab.context4docugen.source.SourceMethod;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SourceModelEdgeCaseTest {

    @Test
    public void spoonBackendExtractsModernJavaSourceContext() throws Exception {
        Path project = Files.createTempDirectory("c4dg-source-edge-cases");
        try {
            write(project.resolve("src/main/java/demo/Parent.java"), """
                    package demo;
                    import java.io.IOException;
                    public interface Parent<T> {
                        /** Converts the input value.
                         * @param input input value
                         * @return converted value
                         * @throws IOException if conversion fails
                         */
                        T transform(T input) throws IOException;
                    }
                    """);
            write(project.resolve("src/main/java/demo/EdgeCase.java"), """
                    package demo;
                    import java.io.IOException;

                    /** Class documentation. */
                    public class EdgeCase implements Parent<String> {
                        private String last;

                        /** {@inheritDoc} */
                        @Deprecated
                        @Override
                        public String transform(final String input) throws IOException {
                            this.last = input;
                            Class.forName("demo.Generated");
                            return input.trim();
                        }

                        /** Repeats input.
                         * @param input value
                         * @param times repeat count
                         * @return repeated value
                         */
                        public String transform(String input, int times) {
                            return input.repeat(times);
                        }

                        class Inner {
                            int value() { return 1; }
                        }

                        record Point(int x, int y) {
                            public Point {
                                if (x < 0) {
                                    throw new IllegalArgumentException();
                                }
                            }
                        }
                    }
                    """);

            ProjectModel model = ProjectModel.from(new ProjectAnalyzer(project).analyze());
            List<SourceMethod> methods = SourceBackends.spoon().findMethods(model);

            assertTrue(methods.stream().anyMatch(m -> m.className().equals("demo.EdgeCase$Inner")
                    && m.methodName().equals("value")));
            assertTrue(methods.stream().anyMatch(m -> m.constructor() && m.className().equals("demo.EdgeCase$Point")));

            SourceMethod focal = methods.stream()
                    .filter(m -> m.className().equals("demo.EdgeCase"))
                    .filter(m -> m.methodName().equals("transform"))
                    .filter(m -> m.parameters().size() == 1)
                    .findFirst()
                    .orElseThrow();

            SourceContext context = SourceBackends.spoon()
                    .extractContext(model, focal.methodUri())
                    .orElseThrow();

            assertEquals("public", focal.visibility());
            assertEquals("java.lang.String", focal.returnType());
            assertEquals("input", focal.parameters().get(0).name());
            assertEquals("java.lang.String", focal.parameters().get(0).type());
            assertTrue(focal.parameters().get(0).modifiers().contains("final"));
            assertTrue(focal.annotations().contains("java.lang.Deprecated"));
            assertTrue(focal.thrownExceptions().contains("java.io.IOException"));
            assertTrue(context.fieldWrites().contains("last"));
            assertTrue(context.dynamicFeatures().contains("reflection"));
            assertEquals(2, context.overloadGroup().size());
            assertTrue((Boolean) context.documentationMetrics().get("uses_inheritdoc"));
            assertEquals("resolved_candidate", context.javadocMetadata().get("inheritdoc_resolution"));
            assertTrue(context.javadocMetadata().containsKey("inherited_javadoc_candidates"));
            assertFalse(context.methodBody().isBlank());
            assertEquals("{@inheritDoc}", context.javadoc().replaceAll("\\s+", ""));
            assertTrue("Method source should keep annotations", context.methodBody().startsWith("@Deprecated"));
            assertFalse("Method source must not include leading Javadoc",
                    context.methodBody().contains("/**"));
        } finally {
            deleteRecursively(project);
        }
    }

    @Test
    public void spoonBackendDeduplicatesOverlappingSourceRoots() throws Exception {
        Path project = Files.createTempDirectory("c4dg-overlapping-source-roots");
        try {
            write(project.resolve("src/main/java/demo/DuplicateRoot.java"), """
                    package demo;

                    public class DuplicateRoot {
                        public String value(String input) {
                            return input;
                        }
                    }
                    """);

            ProjectMetadata metadata = new ProjectMetadata.Builder()
                    .projectName("overlapping-source-roots")
                    .projectPath(project)
                    .buildSystem("none")
                    .javaVersion("17")
                    .sourceRoot(project)
                    .classpath(List.of())
                    .compiles(false)
                    .compileStatus("BUILD NOT ATTEMPTED")
                    .build();

            ProjectModel model = ProjectModel.from(metadata);
            List<SourceMethod> methods = SourceBackends.spoon().findMethods(model);
            long matching = methods.stream()
                    .filter(m -> m.className().equals("demo.DuplicateRoot"))
                    .filter(m -> m.methodName().equals("value"))
                    .count();

            assertEquals("Overlapping project and src/main/java roots should not duplicate method URIs",
                    1, matching);
        } finally {
            deleteRecursively(project);
        }
    }

    @Test
    public void methodUriUsesErasedSignatureForGenericOverloadIdentity() throws Exception {
        Path project = Files.createTempDirectory("c4dg-generic-erasure-overloads");
        try {
            write(project.resolve("src/main/java/demo/GenericOverloads.java"), """
                    package demo;

                    public class GenericOverloads {
                        /** Object-bounded overload. */
                        public static <T> T throwUnchecked(final T throwable) {
                            return throwable;
                        }

                        /** Throwable-bounded overload. */
                        public static <T extends Throwable> T throwUnchecked(final T throwable) {
                            return throwable;
                        }
                    }
                    """);

            ProjectModel model = ProjectModel.from(new ProjectAnalyzer(project).analyze());
            List<SourceMethod> methods = SourceBackends.spoon().findMethods(model).stream()
                    .filter(method -> method.className().equals("demo.GenericOverloads"))
                    .filter(method -> method.methodName().equals("throwUnchecked"))
                    .toList();

            assertEquals("Both generic overloads should be discoverable", 2, methods.size());
            assertEquals("Their URI identities should remain distinct", 2,
                    methods.stream().map(SourceMethod::methodUri).distinct().count());
            assertTrue(methods.stream().anyMatch(method -> method.methodUri().contains("java.lang.Object")));
            assertTrue(methods.stream().anyMatch(method -> method.methodUri().contains("java.lang.Throwable")));
            assertTrue(methods.stream().anyMatch(method -> method.parameters().get(0).erasedType().equals("java.lang.Object")));
            assertTrue(methods.stream().anyMatch(method -> method.parameters().get(0).erasedType().equals("java.lang.Throwable")));
        } finally {
            deleteRecursively(project);
        }
    }

    @Test
    public void spoonAutoResolutionUsesClasspathWhenCompiledClassesExist() throws Exception {
        TestFixtures.ensureMinimalMavenProjectCompiled();
        ProjectMetadata metadata = new ProjectAnalyzer(TestFixtures.minimalMavenProjectRoot()).analyze();
        ProjectModel model = ProjectModel.from(metadata);

        SourceMethod focal = SourceBackends.spoon(AnalysisOptions.SourceResolution.AUTO)
                .findMethods(model)
                .stream()
                .filter(method -> method.methodName().equals("greet"))
                .findFirst()
                .orElseThrow();

        SourceContext context = SourceBackends.spoon(AnalysisOptions.SourceResolution.AUTO)
                .extractContext(model, focal.methodUri())
                .orElseThrow();

        assertEquals("classpath", context.sourceBackendMode());
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
