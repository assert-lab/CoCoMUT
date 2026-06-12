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
        } finally {
            deleteRecursively(project);
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
