package org.assertlab.cocomut;

import org.assertlab.cocomut.source.ProjectModel;
import org.assertlab.cocomut.source.SourceBackends;
import org.assertlab.cocomut.source.SourceContext;
import org.assertlab.cocomut.source.SourceMethod;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SourceModelEdgeCaseTest {

    @Test
    public void spoonBackendExtractsModernJavaSourceContext() throws Exception {
        Path project = Files.createTempDirectory("cocomut-source-edge-cases");
        try {
            write(project.resolve("src/main/java/demo/Parent.java"), """
                    package demo;
                    import java.io.IOException;
                    public interface Parent<T> {
                        /** Converts the input value.
                         * @param input input value, may be {@code null}
                         * @return converted value
                         * @throws IOException
                         *         if conversion fails
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
                         * @param input input value, may be {@code null}
                         * @return converted value
                         * @throws IOException
                         *         if conversion fails
                         * @see #transform(String, int)
                         */
                        @Deprecated
                        @Override
                        public String transform(final String input) throws IOException {
                            this.last = input;
                            try {
                                Class.forName("demo.Generated");
                            } catch (ClassNotFoundException ignored) {
                            }
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

            compileProject(project);
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
            assertEquals("spoon-javadoc", context.documentationMetrics().get("parser"));
            assertEquals("high", context.documentationMetrics().get("parse_confidence"));
            assertTrue((Boolean) context.documentationMetrics().get("has_param_tags"));
            assertTrue((Boolean) context.documentationMetrics().get("has_return_tag"));
            assertTrue((Boolean) context.documentationMetrics().get("has_throws_tag"));
            assertTrue((Boolean) context.documentationMetrics().get("uses_inheritdoc"));
            assertEquals("resolved_candidate", context.javadocMetadata().get("inheritdoc_resolution"));
            assertTrue(context.javadocMetadata().containsKey("inherited_javadoc_candidates"));
            @SuppressWarnings("unchecked")
            Map<String, Object> structuredTags = (Map<String, Object>) context.javadocMetadata().get("structured_tags");
            assertEquals("spoon-javadoc", structuredTags.get("parser"));
            assertEquals("high", structuredTags.get("parse_confidence"));
            assertTrue(structuredTags.get("params").toString().contains("input value, may be null"));
            assertTrue(structuredTags.get("return").toString().contains("converted value"));
            assertTrue(structuredTags.get("throws").toString().contains("type=java.io.IOException"));
            assertTrue(structuredTags.get("throws").toString().contains("if conversion fails"));
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
        Path project = Files.createTempDirectory("cocomut-overlapping-source-roots");
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
                    .classpath(List.of(project.resolve("classes")))
                    .compiles(true)
                    .compileStatus("BUILD SUCCESS")
                    .build();

            compileProject(project);
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
        Path project = Files.createTempDirectory("cocomut-generic-erasure-overloads");
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

            compileProject(project);
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
        Path project = Files.createTempDirectory("cocomut-comment-before-javadoc");
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

            compileProject(project);
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
        Path project = Files.createTempDirectory("cocomut-javadoc-reference-resolution");
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
            write(project.resolve("src/main/java/demo/other/OtherHelper.java"), """
                    package demo.other;

                    /** Other package helper docs. */
                    public class OtherHelper {
                    }
                    """);
            write(project.resolve("src/main/java/demo/nested/Outer.java"), """
                    package demo.nested;

                    /** Outer docs. */
                    public class Outer {
                        /** Inner docs. */
                        public static class Inner {
                            /** Run docs. */
                            public void run() {
                            }
                        }
                    }
                    """);
            write(project.resolve("src/main/java/demo/Child.java"), """
                    package demo;

                    import demo.nested.Outer.Inner;
                    import java.util.Arrays;
                    import java.util.Map;
                    import java.util.regex.*;

                    /** Child docs. */
                    public class Child extends Base {
                        /** Exercises Javadoc reference resolution.
                         * Inline reference: {@link #sameName(String) same-name link}.
                         * Spaced inline reference: {@linkplain Map#put(Object, Object) map put}.
                         * Invalid external member: {@link java.util.List#add(Integer) invalid add}.
                         * @see #sameName
                         * @see #sameName(String)
                         * @see #sameName(String,
                         *     int) multi-line overload
                         * @see #TOKEN
                         * @see Helper
                         * @see demo.other.OtherHelper
                         * @see Inner#run()
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

                        /** String and mode overload. */
                        public void sameName(String value, int mode) {
                        }
                    }
                    """);

            compileProject(project);
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
            assertTrue(ambiguous.get("candidate_method_uris").toString()
                    .contains("sameName(java.lang.String,int):void"));

            Map<String, Object> exactOverload = referenceByTarget(refs, "#sameName(String)");
            assertEquals("resolved_method", exactOverload.get("resolution"));
            assertEquals("spoon-javadoc", exactOverload.get("parser"));
            assertEquals("high", exactOverload.get("parse_confidence"));
            assertEquals("method", exactOverload.get("reference_target_kind"));
            assertEquals("project", exactOverload.get("reference_domain"));
            assertEquals("same_type", exactOverload.get("reference_scope"));
            assertTrue(exactOverload.get("method_uri").toString()
                    .contains("sameName(java.lang.String):void"));
            @SuppressWarnings("unchecked")
            Map<String, Object> exactOverloadContext = (Map<String, Object>) exactOverload.get("referenced_method");
            assertTrue(exactOverloadContext.get("code").toString().contains("public void sameName(String value)"));
            assertTrue(exactOverloadContext.get("javadoc").toString().contains("String overload."));

            Map<String, Object> multilineOverload = referenceByTarget(refs, "#sameName(String, int)");
            assertEquals("resolved_method", multilineOverload.get("resolution"));
            assertEquals("multi-line overload", multilineOverload.get("label"));
            assertEquals("spoon-javadoc", multilineOverload.get("parser"));
            assertEquals("high", multilineOverload.get("parse_confidence"));
            assertTrue(multilineOverload.get("method_uri").toString()
                    .contains("sameName(java.lang.String,int):void"));

            Map<String, Object> inheritedField = referenceByTarget(refs, "#TOKEN");
            assertEquals("field_reference", inheritedField.get("kind"));
            assertEquals("resolved_inherited_field", inheritedField.get("resolution"));
            assertEquals("field", inheritedField.get("reference_target_kind"));
            assertEquals("project", inheritedField.get("reference_domain"));
            assertEquals("same_package", inheritedField.get("reference_scope"));
            assertEquals("java.lang.String", inheritedField.get("field_erased_type"));
            assertTrue(inheritedField.get("field_uri").toString().contains("Base.TOKEN:java.lang.String"));
            assertTrue(inheritedField.get("field_javadoc").toString().contains("Token field docs"));

            Map<String, Object> type = referenceByTarget(refs, "Helper");
            assertEquals("resolved_type", type.get("resolution"));
            assertEquals("type", type.get("reference_target_kind"));
            assertEquals("project", type.get("reference_domain"));
            assertEquals("same_package", type.get("reference_scope"));
            assertTrue(type.get("type_uri").toString().contains("Helper.java#demo.Helper"));
            assertTrue(type.get("class_javadoc").toString().contains("Helper type docs"));
            assertTrue(type.containsKey("class_hierarchy"));

            Map<String, Object> otherPackageType = referenceByTarget(refs, "demo.other.OtherHelper");
            assertEquals("resolved_type", otherPackageType.get("resolution"));
            assertEquals("type", otherPackageType.get("reference_target_kind"));
            assertEquals("project", otherPackageType.get("reference_domain"));
            assertEquals("same_module", otherPackageType.get("reference_scope"));

            Map<String, Object> importedNestedMethod = referenceByTarget(refs, "Inner#run()");
            assertEquals("resolved_method", importedNestedMethod.get("resolution"));
            assertEquals("spoon-javadoc", importedNestedMethod.get("parser"));
            assertEquals("high", importedNestedMethod.get("parse_confidence"));
            assertTrue(importedNestedMethod.get("canonical_target").toString().contains("demo.nested.Outer"));
            assertTrue(importedNestedMethod.get("method_uri").toString()
                    .contains("Outer$Inner.run():void"));

            Map<String, Object> inheritedMethod = referenceByTarget(refs, "#inherited(CharSequence)");
            assertEquals("resolved_inherited_method", inheritedMethod.get("resolution"));
            assertEquals("demo.Base", inheritedMethod.get("inherited_from"));
            assertEquals("method", inheritedMethod.get("reference_target_kind"));
            assertEquals("project", inheritedMethod.get("reference_domain"));
            assertEquals("same_package", inheritedMethod.get("reference_scope"));
            assertTrue(inheritedMethod.get("method_uri").toString()
                    .contains("Base.inherited(java.lang.CharSequence):java.lang.CharSequence"));
            @SuppressWarnings("unchecked")
            Map<String, Object> inheritedMethodContext = (Map<String, Object>) inheritedMethod.get("referenced_method");
            assertTrue(inheritedMethodContext.get("code").toString().contains("public CharSequence inherited"));
            assertTrue(inheritedMethodContext.get("javadoc").toString().contains("Parent method docs"));

            Map<String, Object> external = referenceByTarget(refs, "java.util.List#add(Object)");
            assertEquals("external_symbol", external.get("resolution"));
            assertEquals("method", external.get("reference_target_kind"));
            assertEquals("external_jdk", external.get("reference_domain"));
            assertEquals("external", external.get("reference_scope"));
            assertEquals("java.util.List", external.get("external_class"));
            assertEquals("add(java.lang.Object)", external.get("external_member"));
            assertEquals("method", external.get("external_member_kind"));

            Map<String, Object> invalidExternal = referenceByTarget(refs, "java.util.List#add(Integer)");
            assertEquals("unresolved", invalidExternal.get("resolution"));
            assertEquals("unknown", invalidExternal.get("external_member_kind"));
            assertEquals("symbol_only", invalidExternal.get("external_member_resolution"));

            Map<String, Object> inlineExact = referenceByTargetAndTag(refs, "#sameName(String)", "link");
            assertEquals("link", inlineExact.get("tag"));
            assertEquals("same-name link", inlineExact.get("label"));
            assertEquals("resolved_method", inlineExact.get("resolution"));

            Map<String, Object> spacedInline = referenceByTarget(refs, "Map#put(Object, Object)");
            assertEquals("linkplain", spacedInline.get("tag"));
            assertEquals("map put", spacedInline.get("label"));
            assertEquals("spoon-javadoc", spacedInline.get("parser"));
            assertEquals("high", spacedInline.get("parse_confidence"));
            assertEquals("external_symbol", spacedInline.get("resolution"));
            assertEquals("java.util.Map", spacedInline.get("external_class"));
            assertEquals("method", spacedInline.get("external_member_kind"));

            Map<String, Object> modulePrefixed = referenceByTarget(refs, "java.base/java.util.List#remove(Object)");
            assertEquals("external_symbol", modulePrefixed.get("resolution"));
            assertEquals("java.util.List", modulePrefixed.get("external_class"));
            assertEquals("remove(java.lang.Object)", modulePrefixed.get("external_member"));
            assertEquals("method", modulePrefixed.get("external_member_kind"));

            Map<String, Object> imported = referenceByTarget(refs, "Arrays#sort(byte[])");
            assertEquals("external_symbol", imported.get("resolution"));
            assertEquals("java.util.Arrays", imported.get("external_class"));
            assertEquals("qualified_symbol", imported.get("external_resolution"));
            assertEquals("method", imported.get("external_member_kind"));

            Map<String, Object> javaLangField = referenceByTarget(refs, "Long#MIN_VALUE");
            assertEquals("field_reference", javaLangField.get("kind"));
            assertEquals("external_symbol", javaLangField.get("resolution"));
            assertEquals("java.lang.Long", javaLangField.get("external_class"));
            assertEquals("qualified_symbol", javaLangField.get("external_resolution"));
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
            assertEquals("text", textReference.get("reference_target_kind"));
            assertEquals("text", textReference.get("reference_domain"));
            assertEquals("text", textReference.get("reference_scope"));
            assertEquals("Reference text without a generated link", textReference.get("text"));
        } finally {
            deleteRecursively(project);
        }
    }

    @Test
    public void spoonBackendUsesClasspathWhenCompiledClassesExist() throws Exception {
        TestFixtures.ensureMinimalMavenProjectCompiled();
        ProjectMetadata metadata = new ProjectAnalyzer(TestFixtures.minimalMavenProjectRoot()).analyze();
        ProjectModel model = ProjectModel.from(metadata);

        SourceMethod focal = SourceBackends.spoon()
                .findMethods(model)
                .stream()
                .filter(method -> method.methodName().equals("greet"))
                .findFirst()
                .orElseThrow();

        SourceContext context = SourceBackends.spoon()
                .extractContext(model, focal.methodUri())
                .orElseThrow();

        assertEquals("classpath", context.sourceBackendMode());
    }

    private static void write(Path path, String text) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, text, StandardCharsets.UTF_8);
    }

    private static void compileProject(Path project) throws Exception {
        Path classes = project.resolve("classes");
        Files.createDirectories(classes);
        List<String> command = new ArrayList<>();
        command.add(javac());
        command.add("-d");
        command.add(classes.toString());
        try (var walk = Files.walk(project.resolve("src/main/java"))) {
            walk.filter(path -> path.toString().endsWith(".java"))
                    .sorted(Comparator.comparing(Path::toString))
                    .forEach(path -> command.add(path.toString()));
        }
        Process process = new ProcessBuilder(command)
                .directory(project.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = process.waitFor();
        if (exit != 0) {
            throw new AssertionError("javac failed:\n" + output);
        }
    }

    private static String javac() {
        Path javaHome = Path.of(System.getProperty("java.home"));
        Path javac = javaHome.resolve("bin").resolve("javac");
        if (Files.isRegularFile(javac)) {
            return javac.toString();
        }
        Path parentJavac = javaHome.getParent() == null
                ? null
                : javaHome.getParent().resolve("bin").resolve("javac");
        if (parentJavac != null && Files.isRegularFile(parentJavac)) {
            return parentJavac.toString();
        }
        return "javac";
    }

    private static Map<String, Object> referenceByTarget(List<Map<String, Object>> references, String target) {
        return references.stream()
                .filter(reference -> target.equals(reference.get("target")))
                .findFirst()
                .orElseThrow();
    }

    private static Map<String, Object> referenceByTargetAndTag(List<Map<String, Object>> references,
                                                               String target,
                                                               String tag) {
        return references.stream()
                .filter(reference -> target.equals(reference.get("target")))
                .filter(reference -> tag.equals(reference.get("tag")))
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
