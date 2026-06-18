package org.assertlab.cocox;

import org.assertlab.cocox.source.ProjectModel;
import org.assertlab.cocox.source.SourceBackends;
import org.assertlab.cocox.source.SourceContext;
import org.assertlab.cocox.source.SourceMethod;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SourceModelEdgeCaseTest {

    @Test
    public void spoonBackendExtractsModernJavaSourceContext() throws Exception {
        Path project = Files.createTempDirectory("cocox-source-edge-cases");
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

                        /** {@inheritDoc}
                         * @see #transform(String, int)
                         */
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
            assertEquals("java.lang.String", focal.erasedReturnType());
            assertTrue("Method URI should include erased return type",
                    focal.methodUri().endsWith("):java.lang.String"));
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
            assertTrue("Javadoc @see should resolve to a project method URI",
                    context.javadocMetadata().get("javadoc_references").toString()
                            .contains("#demo.EdgeCase.transform(java.lang.String,int):java.lang.String"));
            assertFalse(context.methodBody().isBlank());
            assertTrue("Method source should keep annotations", context.methodBody().startsWith("@Deprecated"));
            assertFalse("Method source must not include leading Javadoc",
                    context.methodBody().contains("/**"));
        } finally {
            deleteRecursively(project);
        }
    }

    @Test
    public void spoonBackendDeduplicatesOverlappingSourceRoots() throws Exception {
        Path project = Files.createTempDirectory("cocox-overlapping-source-roots");
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
        Path project = Files.createTempDirectory("cocox-generic-erasure-overloads");
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
            assertTrue(methods.stream().anyMatch(method -> method.methodUri().endsWith("):java.lang.Object")));
            assertTrue(methods.stream().anyMatch(method -> method.methodUri().endsWith("):java.lang.Throwable")));
            assertTrue(methods.stream().anyMatch(method -> method.parameters().get(0).erasedType().equals("java.lang.Object")));
            assertTrue(methods.stream().anyMatch(method -> method.parameters().get(0).erasedType().equals("java.lang.Throwable")));
            assertTrue(methods.stream().anyMatch(method -> method.erasedReturnType().equals("java.lang.Object")));
            assertTrue(methods.stream().anyMatch(method -> method.erasedReturnType().equals("java.lang.Throwable")));
        } finally {
            deleteRecursively(project);
        }
    }

    @Test
    public void methodSourceDropsJavadocEvenWhenSpoonSliceStartsAtPreviousComment() throws Exception {
        Path project = Files.createTempDirectory("cocox-comment-before-javadoc");
        try {
            write(project.resolve("src/main/java/demo/CommentBeforeJavadoc.java"), """
                    package demo;

                    public class CommentBeforeJavadoc {
                        // @formatter:on

                        /**
                         * Returns a {@code value()} value.
                         *
                         * @return value
                         */
                        @Deprecated
                        public String value() {
                            return "x";
                        }
                    }
                    """);

            ProjectModel model = ProjectModel.from(new ProjectAnalyzer(project).analyze());
            SourceMethod focal = SourceBackends.spoon().findMethods(model).stream()
                    .filter(method -> method.methodName().equals("value"))
                    .findFirst()
                    .orElseThrow();
            SourceContext context = SourceBackends.spoon()
                    .extractContext(model, focal.methodUri())
                    .orElseThrow();

            assertEquals("Returns a {@code value()} value.\n\n@return value", context.javadoc());
            assertTrue("Method source should keep annotations", context.methodBody().startsWith("@Deprecated"));
            assertFalse("Method source must not include previous formatter comments",
                    context.methodBody().contains("@formatter:on"));
            assertFalse("Method source must not include leading Javadoc",
                    context.methodBody().contains("/**"));
        } finally {
            deleteRecursively(project);
        }
    }

    @Test
    public void javadocReferencesResolveAmbiguousFieldsTypesInheritedAndExternalSymbols() throws Exception {
        Path project = Files.createTempDirectory("cocox-javadoc-reference-resolution");
        try {
            write(project.resolve("src/main/java/demo/Base.java"), """
                    package demo;

                    /** Base docs. */
                    public class Base {
                        /** Token field docs. */
                        protected static final String TOKEN = "x";

                        /** Parent method docs.
                         * @param input input text
                         * @return parent result
                         */
                        public CharSequence inherited(CharSequence input) {
                            return input;
                        }
                    }
                    """);
            write(project.resolve("src/main/java/demo/Helper.java"), """
                    package demo;

                    /** Helper type docs. */
                    public class Helper {
                    }
                    """);
            write(project.resolve("src/main/java/demo/Child.java"), """
                    package demo;

                    import java.util.Arrays;
                    import java.util.regex.*;

                    /** Child docs. */
                    public class Child extends Base {
                        /** Exercises Javadoc reference resolution.
                         * @see #sameName
                         * @see #sameName(String)
                         * @see #TOKEN
                         * @see Helper
                         * @see #inherited(CharSequence)
                         * @see java.util.List#add(Object)
                         * @see java.base/java.util.List#remove(Object)
                         * @see Arrays#sort(byte[])
                         * @see Long#MIN_VALUE
                         * @see Pattern#DOTALL
                         * @see "Reference text without a generated link"
                         */
                        public void focal() {
                        }

                        /** No-arg overload. */
                        public void sameName() {
                        }

                        /** String overload. */
                        public void sameName(String value) {
                        }
                    }
                    """);

            ProjectModel model = ProjectModel.from(new ProjectAnalyzer(project).analyze());
            SourceMethod focal = SourceBackends.spoon().findMethods(model).stream()
                    .filter(method -> method.className().equals("demo.Child"))
                    .filter(method -> method.methodName().equals("focal"))
                    .findFirst()
                    .orElseThrow();
            SourceContext context = SourceBackends.spoon()
                    .extractContext(model, focal.methodUri())
                    .orElseThrow();

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> refs = (List<Map<String, Object>>) context.javadocMetadata()
                    .get("javadoc_references");

            Map<String, Object> ambiguous = referenceByTarget(refs, "#sameName");
            assertEquals("overload_ambiguous", ambiguous.get("resolution"));
            assertEquals("target_omits_parameter_types", ambiguous.get("ambiguity_reason"));
            assertTrue(ambiguous.get("candidate_method_uris").toString().contains("sameName():void"));
            assertTrue(ambiguous.get("candidate_method_uris").toString()
                    .contains("sameName(java.lang.String):void"));

            Map<String, Object> exactOverload = referenceByTarget(refs, "#sameName(String)");
            assertEquals("resolved_method", exactOverload.get("resolution"));
            assertTrue(exactOverload.get("method_uri").toString()
                    .contains("sameName(java.lang.String):void"));
            @SuppressWarnings("unchecked")
            Map<String, Object> exactOverloadContext = (Map<String, Object>) exactOverload.get("referenced_method");
            assertTrue(exactOverloadContext.get("code").toString().contains("public void sameName(String value)"));
            assertTrue(exactOverloadContext.get("javadoc").toString().contains("String overload."));

            Map<String, Object> inheritedField = referenceByTarget(refs, "#TOKEN");
            assertEquals("field_reference", inheritedField.get("kind"));
            assertEquals("resolved_inherited_field", inheritedField.get("resolution"));
            assertEquals("java.lang.String", inheritedField.get("field_erased_type"));
            assertTrue(inheritedField.get("field_uri").toString().contains("Base.TOKEN:java.lang.String"));
            assertTrue(inheritedField.get("field_javadoc").toString().contains("Token field docs"));

            Map<String, Object> type = referenceByTarget(refs, "Helper");
            assertEquals("resolved_type", type.get("resolution"));
            assertTrue(type.get("type_uri").toString().contains("Helper.java#demo.Helper"));
            assertTrue(type.get("class_javadoc").toString().contains("Helper type docs"));
            assertTrue(type.containsKey("class_hierarchy"));

            Map<String, Object> inheritedMethod = referenceByTarget(refs, "#inherited(CharSequence)");
            assertEquals("resolved_inherited_method", inheritedMethod.get("resolution"));
            assertEquals("demo.Base", inheritedMethod.get("inherited_from"));
            assertTrue(inheritedMethod.get("method_uri").toString()
                    .contains("Base.inherited(java.lang.CharSequence):java.lang.CharSequence"));
            @SuppressWarnings("unchecked")
            Map<String, Object> inheritedMethodContext = (Map<String, Object>) inheritedMethod.get("referenced_method");
            assertTrue(inheritedMethodContext.get("code").toString().contains("public CharSequence inherited"));
            assertTrue(inheritedMethodContext.get("javadoc").toString().contains("Parent method docs"));

            Map<String, Object> external = referenceByTarget(refs, "java.util.List#add(Object)");
            assertEquals("external_symbol", external.get("resolution"));
            assertEquals("java.util.List", external.get("external_class"));
            assertEquals("add(Object)", external.get("external_member"));
            assertEquals("method", external.get("external_member_kind"));

            Map<String, Object> modulePrefixed = referenceByTarget(refs, "java.base/java.util.List#remove(Object)");
            assertEquals("external_symbol", modulePrefixed.get("resolution"));
            assertEquals("java.util.List", modulePrefixed.get("external_class"));
            assertEquals("remove(Object)", modulePrefixed.get("external_member"));
            assertEquals("method", modulePrefixed.get("external_member_kind"));

            Map<String, Object> imported = referenceByTarget(refs, "Arrays#sort(byte[])");
            assertEquals("external_symbol", imported.get("resolution"));
            assertEquals("java.util.Arrays", imported.get("external_class"));
            assertEquals("explicit_import", imported.get("external_resolution"));
            assertEquals("method", imported.get("external_member_kind"));

            Map<String, Object> javaLangField = referenceByTarget(refs, "Long#MIN_VALUE");
            assertEquals("field_reference", javaLangField.get("kind"));
            assertEquals("external_symbol", javaLangField.get("resolution"));
            assertEquals("java.lang.Long", javaLangField.get("external_class"));
            assertEquals("implicit_java_lang", javaLangField.get("external_resolution"));
            assertEquals("field", javaLangField.get("external_member_kind"));

            Map<String, Object> wildcardField = referenceByTarget(refs, "Pattern#DOTALL");
            assertEquals("field_reference", wildcardField.get("kind"));
            assertEquals("external_symbol", wildcardField.get("resolution"));
            assertEquals("java.util.regex.Pattern", wildcardField.get("external_class"));
            assertEquals("wildcard_import_symbol", wildcardField.get("external_resolution"));
            assertEquals("field", wildcardField.get("external_member_kind"));

            Map<String, Object> textReference = referenceByTarget(refs, "\"Reference text without a generated link\"");
            assertEquals("text_reference", textReference.get("kind"));
            assertEquals("text", textReference.get("resolution"));
            assertEquals("Reference text without a generated link", textReference.get("text"));
        } finally {
            deleteRecursively(project);
        }
    }

    @Test
    public void spoonAutoResolutionUsesClasspathWhenCompiledClassesExist() throws Exception {
        TestFixtures.ensureMinimalMavenProjectCompiled();
        ProjectMetadata metadata = new ProjectAnalyzer(TestFixtures.minimalMavenProjectRoot()).analyze();
        ProjectModel model = ProjectModel.from(metadata);

        SourceMethod focal = SourceBackends.spoon(ContextRequest.SourceResolution.AUTO)
                .findMethods(model)
                .stream()
                .filter(method -> method.methodName().equals("greet"))
                .findFirst()
                .orElseThrow();

        SourceContext context = SourceBackends.spoon(ContextRequest.SourceResolution.AUTO)
                .extractContext(model, focal.methodUri())
                .orElseThrow();

        assertEquals("classpath", context.sourceBackendMode());
    }

    private static void write(Path path, String text) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, text, StandardCharsets.UTF_8);
    }

    private static Map<String, Object> referenceByTarget(List<Map<String, Object>> references, String target) {
        return references.stream()
                .filter(reference -> target.equals(reference.get("target")))
                .findFirst()
                .orElseThrow();
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
