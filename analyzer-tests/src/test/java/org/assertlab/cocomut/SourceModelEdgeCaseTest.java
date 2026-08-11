package org.assertlab.cocomut;

import org.assertlab.cocomut.source.ProjectModel;
import org.assertlab.cocomut.source.SourceAnalysisSession;
import org.assertlab.cocomut.source.SourceBackends;
import org.assertlab.cocomut.source.SourceContext;
import org.assertlab.cocomut.source.SourceMethod;
import org.junit.Assume;
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
    public void spoonBackendFallsBackToNoClasspathAfterRuntimeFailure() throws Exception {
        Path project = Files.createTempDirectory("cocomut-no-classpath-fallback");
        try {
            Path sourceRoot = project.resolve("src/main/java");
            write(sourceRoot.resolve("demo/Fallback.java"), """
                    package demo;
                    public class Fallback {
                        public String value() { return "ok"; }
                    }
                    """);
            Path malformedJar = project.resolve("malformed.jar");
            Files.writeString(malformedJar, "not a jar");
            ProjectMetadata metadata = new ProjectMetadata.Builder()
                    .projectName("no-classpath-fallback")
                    .projectPath(project)
                    .buildSystem("none")
                    .javaVersion("17")
                    .sourceRoot(sourceRoot)
                    .sourceRoots(List.of(sourceRoot))
                    .dependencyClasspath(List.of(malformedJar))
                    .build();

            try (SourceAnalysisSession session = SourceBackends.spoon().open(ProjectModel.from(metadata))) {
                assertEquals(1, session.parseStats().discovered());
                assertEquals(1, session.parseStats().parsed());
                assertTrue(session.methods().stream().anyMatch(method -> method.methodName().equals("value")));
            }
        } finally {
            deleteRecursively(project);
        }
    }

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
                         * @since 2.0
                         * @deprecated Use {@link Helper} instead.
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

            @SuppressWarnings("unchecked")
            List<String> seeTargets = (List<String>) context.javadocMetadata().get("see");
            assertTrue("Legacy see metadata should be Spoon-derived and include complete multiline signatures",
                    seeTargets.toString().contains("#sameName(String, int)"));
            assertFalse("Legacy see metadata should not keep line-truncated target fragments",
                    seeTargets.contains("#sameName(String,"));
            assertTrue(context.javadocMetadata().get("since").toString().contains("2.0"));
            assertTrue("Nested inline links inside block tags should appear in inline_links",
                    context.javadocMetadata().get("inline_links").toString().contains("Helper"));

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
    public void inheritDocKeepsDeclaredTagsAndAddsEffectiveTags() throws Exception {
        Path project = Files.createTempDirectory("cocomut-inheritdoc-policy");
        try {
            write(project.resolve("src/main/java/demo/ParentDocs.java"), """
                    package demo;

                    public interface ParentDocs {
                        /**
                         * Parses input.
                         * @param text input text
                         * @return parsed value
                         */
                        String parse(String text);
                    }
                    """);
            write(project.resolve("src/main/java/demo/ChildDocs.java"), """
                    package demo;

                    public class ChildDocs implements ParentDocs {
                        /**
                         * {@inheritDoc}
                         * @param text {@inheritDoc}
                         */
                        @Override
                        public String parse(String text) {
                            return text;
                        }
                    }
                    """);

            compileProject(project);
            ProjectModel model = ProjectModel.from(new ProjectAnalyzer(project).analyze());
            SourceMethod focal = SourceBackends.spoon().findMethods(model).stream()
                    .filter(method -> method.className().equals("demo.ChildDocs"))
                    .filter(method -> method.methodName().equals("parse"))
                    .findFirst()
                    .orElseThrow();

            SourceContext context = SourceBackends.spoon()
                    .extractContext(model, focal.methodUri())
                    .orElseThrow();

            assertEquals(true, context.javadocMetadata().get("uses_inheritdoc"));
            assertEquals("jdk25-standard-doclet", context.javadocMetadata().get("inheritdoc_policy"));
            assertEquals("resolved_candidate", context.javadocMetadata().get("inheritdoc_resolution"));
            assertTrue(context.javadocMetadata().get("inherited_javadoc_candidates").toString()
                    .contains("input text"));

            @SuppressWarnings("unchecked")
            Map<String, Object> structuredTags = (Map<String, Object>) context.javadocMetadata()
                    .get("structured_tags");
            assertTrue("Child structured tags preserve local inheritDoc markers; inherited docs are candidates",
                    structuredTags.get("params").toString().contains("{@inheritDoc}"));
            assertFalse("Declared tags must not be overwritten by inherited param text",
                    structuredTags.get("params").toString().contains("input text"));

            @SuppressWarnings("unchecked")
            Map<String, Object> effectiveTags = (Map<String, Object>) context.javadocMetadata()
                    .get("effective_structured_tags");
            assertTrue(effectiveTags.get("params").toString().contains("input text"));
            assertTrue(effectiveTags.get("return").toString().contains("parsed value"));
            assertEquals("complete", effectiveTags.get("resolution"));
            assertEquals("jdk25-standard-doclet",
                    ((Map<?, ?>) context.javadocMetadata().get("javadoc_inheritance")).get("policy_id"));
        } finally {
            deleteRecursively(project);
        }
    }

    @Test
    public void inheritDocPrefersNearestDocumentedOverrideOverTopDefinition() throws Exception {
        Path project = Files.createTempDirectory("cocomut-inheritdoc-nearest");
        try {
            write(project.resolve("src/main/java/demo/Root.java"), """
                    package demo;
                    public class Root {
                        /**
                         * Root contract.
                         * @return root result
                         */
                        public Root copy() { return this; }
                    }
                    """);
            write(project.resolve("src/main/java/demo/Parent.java"), """
                    package demo;
                    public class Parent extends Root {
                        /**
                         * Nearest contract.
                         * @return nearest result
                         */
                        @Override public Parent copy() { return this; }
                    }
                    """);
            write(project.resolve("src/main/java/demo/Child.java"), """
                    package demo;
                    public class Child extends Parent {
                        /** {@inheritDoc } Child details. */
                        @Override public Child copy() { return this; }
                    }
                    """);

            compileProject(project);
            ProjectModel model = ProjectModel.from(new ProjectAnalyzer(project).analyze());
            SourceMethod focal = SourceBackends.spoon().findMethods(model).stream()
                    .filter(method -> method.className().equals("demo.Child"))
                    .filter(method -> method.methodName().equals("copy"))
                    .findFirst()
                    .orElseThrow();
            SourceContext context = SourceBackends.spoon().extractContext(model, focal.methodUri()).orElseThrow();

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> candidates = (List<Map<String, Object>>) context.javadocMetadata()
                    .get("inherited_javadoc_candidates");
            assertFalse(candidates.isEmpty());
            assertEquals("demo.Parent", candidates.get(0).get("declaring_type"));
            assertEquals("present", candidates.get(0).get("javadoc_availability"));
            assertTrue(candidates.get(0).get("method_uri").toString().contains("Parent.java#demo.Parent.copy"));
            assertTrue(candidates.get(0).get("structured_tags").toString().contains("nearest result"));
            assertEquals(candidates.size(), context.javadocMetadata().get("inheritdoc_candidate_count"));
            assertEquals(2, context.javadocMetadata().get("inheritdoc_documented_candidate_count"));
            assertEquals("resolved_candidate", context.javadocMetadata().get("inheritdoc_resolution"));

            @SuppressWarnings("unchecked")
            Map<String, Object> effective = (Map<String, Object>) context.javadocMetadata()
                    .get("effective_structured_tags");
            assertTrue(effective.get("description").toString().contains("Nearest contract"));
            assertTrue(effective.get("return").toString().contains("nearest result"));
            assertFalse(effective.get("return").toString().contains("root result"));
        } finally {
            deleteRecursively(project);
        }
    }

    @Test
    public void explicitInheritDocSupertypeSelectsRequestedAncestor() throws Exception {
        Path project = Files.createTempDirectory("cocomut-inheritdoc-explicit-supertype");
        try {
            write(project.resolve("src/main/java/demo/Root.java"), """
                    package demo;
                    public interface Root {
                        /** Root contract. */
                        String value();
                    }
                    """);
            write(project.resolve("src/main/java/demo/Near.java"), """
                    package demo;
                    public interface Near extends Root {
                        /** Near contract. */
                        @Override String value();
                    }
                    """);
            write(project.resolve("src/main/java/demo/Child.java"), """
                    package demo;
                    public class Child implements Near {
                        /** {@inheritDoc demo.Root} Child details. */
                        @Override public String value() { return "value"; }
                    }
                    """);

            compileProject(project);
            ProjectModel model = ProjectModel.from(new ProjectAnalyzer(project).analyze());
            SourceMethod focal = SourceBackends.spoon().findMethods(model).stream()
                    .filter(method -> method.className().equals("demo.Child"))
                    .filter(method -> method.methodName().equals("value"))
                    .findFirst()
                    .orElseThrow();
            SourceContext context = SourceBackends.spoon().extractContext(model, focal.methodUri()).orElseThrow();

            @SuppressWarnings("unchecked")
            Map<String, Object> effective = (Map<String, Object>) context.javadocMetadata()
                    .get("effective_structured_tags");
            assertTrue("Explicit supertype should select Root: " + effective,
                    effective.get("description").toString().contains("Root contract"));
            assertFalse(effective.get("description").toString().contains("Near contract"));
            assertTrue(effective.get("description").toString().contains("Child details"));
        } finally {
            deleteRecursively(project);
        }
    }

    @Test
    public void earlierUnavailableOverrideMakesLaterDocumentationIndeterminate() throws Exception {
        Path project = Files.createTempDirectory("cocomut-inheritdoc-search-gap");
        try {
            write(project.resolve("src/main/java/demo/Sized.java"), """
                    package demo;
                    public interface Sized {
                        /**
                         * Local size contract.
                         * @return local size
                         */
                        int size();
                    }
                    """);
            write(project.resolve("src/main/java/demo/Child.java"), """
                    package demo;
                    public class Child extends java.util.ArrayList<String> implements Sized {
                        /** {@inheritDoc} */
                        @Override public int size() { return super.size(); }
                    }
                    """);

            compileProject(project);
            ProjectModel model = ProjectModel.from(new ProjectAnalyzer(project).analyze());
            SourceMethod focal = SourceBackends.spoon().findMethods(model).stream()
                    .filter(method -> method.className().equals("demo.Child"))
                    .filter(method -> method.methodName().equals("size"))
                    .findFirst()
                    .orElseThrow();
            SourceContext context = SourceBackends.spoon().extractContext(model, focal.methodUri()).orElseThrow();

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> candidates = (List<Map<String, Object>>) context.javadocMetadata()
                    .get("inherited_javadoc_candidates");
            assertEquals("java.util.ArrayList", candidates.get(0).get("declaring_type"));
            assertEquals("source_unavailable", candidates.get(0).get("javadoc_availability"));
            assertTrue(candidates.stream().anyMatch(candidate ->
                    "demo.Sized".equals(candidate.get("declaring_type"))
                            && "present".equals(candidate.get("javadoc_availability"))));

            @SuppressWarnings("unchecked")
            Map<String, Object> effective = (Map<String, Object>) context.javadocMetadata()
                    .get("effective_structured_tags");
            assertEquals("partial", effective.get("resolution"));
            assertTrue(effective.get("description").toString().contains("indeterminate"));
            assertFalse("Later candidates remain evidence but are not asserted as effective",
                    effective.get("description").toString().contains("Local size contract"));
        } finally {
            deleteRecursively(project);
        }
    }

    @Test
    public void missingItemsInheritWithoutExplicitInheritDoc() throws Exception {
        Path project = Files.createTempDirectory("cocomut-inheritdoc-implicit");
        try {
            write(project.resolve("src/main/java/demo/Contract.java"), """
                    package demo;
                    public interface Contract {
                        /**
                         * Tests a value.
                         * @param value value to test
                         * @return true when accepted
                         * @see java.lang.Object
                         */
                        boolean accepts(Object value);
                    }
                    """);
            write(project.resolve("src/main/java/demo/Implementation.java"), """
                    package demo;
                    public class Implementation implements Contract {
                        /** Local implementation note. @since 1.0 */
                        @Override public boolean accepts(Object candidate) { return candidate != null; }
                    }
                    """);

            compileProject(project);
            ProjectModel model = ProjectModel.from(new ProjectAnalyzer(project).analyze());
            SourceMethod focal = SourceBackends.spoon().findMethods(model).stream()
                    .filter(method -> method.className().equals("demo.Implementation"))
                    .filter(method -> method.methodName().equals("accepts"))
                    .findFirst()
                    .orElseThrow();
            SourceContext context = SourceBackends.spoon().extractContext(model, focal.methodUri()).orElseThrow();

            assertEquals(false, context.javadocMetadata().get("uses_inheritdoc"));
            assertEquals("jdk25-standard-doclet", context.javadocMetadata().get("inheritdoc_policy"));
            @SuppressWarnings("unchecked")
            Map<String, Object> declared = (Map<String, Object>) context.javadocMetadata()
                    .get("declared_structured_tags");
            assertEquals(List.of(), declared.get("params"));
            assertEquals(List.of(), declared.get("return"));

            @SuppressWarnings("unchecked")
            Map<String, Object> effective = (Map<String, Object>) context.javadocMetadata()
                    .get("effective_structured_tags");
            assertTrue(effective.get("params").toString().contains("value to test"));
            assertTrue(effective.get("params").toString().contains("candidate"));
            assertTrue(effective.get("return").toString().contains("true when accepted"));
            assertTrue(effective.get("params").toString().contains("implicit_missing_item"));
            assertEquals("Non-inheritable tags must remain declared-only", List.of(),
                    context.javadocMetadata().get("see"));
            assertFalse(effective.containsKey("see"));
        } finally {
            deleteRecursively(project);
        }
    }

    @Test
    public void missingItemDoesNotFallThroughToSiblingInterface() throws Exception {
        Path project = Files.createTempDirectory("cocomut-inheritdoc-interface-order");
        try {
            write(project.resolve("src/main/java/demo/First.java"), """
                    package demo;
                    public interface First {
                        /** First interface contract. */
                        int value();
                    }
                    """);
            write(project.resolve("src/main/java/demo/Second.java"), """
                    package demo;
                    public interface Second {
                        /**
                         * Second interface contract.
                         * @return documentation from the second branch
                         */
                        int value();
                    }
                    """);
            write(project.resolve("src/main/java/demo/Implementation.java"), """
                    package demo;
                    public class Implementation implements First, Second {
                        /** Local implementation. */
                        @Override public int value() { return 1; }
                    }
                    """);

            compileProject(project);
            ProjectModel model = ProjectModel.from(new ProjectAnalyzer(project).analyze());
            SourceMethod focal = SourceBackends.spoon().findMethods(model).stream()
                    .filter(method -> method.className().equals("demo.Implementation"))
                    .filter(method -> method.methodName().equals("value"))
                    .findFirst()
                    .orElseThrow();
            SourceContext context = SourceBackends.spoon().extractContext(model, focal.methodUri()).orElseThrow();

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> candidates = (List<Map<String, Object>>) context.javadocMetadata()
                    .get("inherited_javadoc_candidates");
            assertEquals("demo.First", candidates.get(0).get("declaring_type"));
            assertTrue("The second branch remains available as audit evidence", candidates.stream().anyMatch(candidate ->
                    "demo.Second".equals(candidate.get("declaring_type"))));

            @SuppressWarnings("unchecked")
            Map<String, Object> effective = (Map<String, Object>) context.javadocMetadata()
                    .get("effective_structured_tags");
            assertTrue("The first overriding declaration selects the inheritance branch",
                    effective.get("return").toString().contains("source=missing"));
            assertFalse("A missing item must not fall through to a sibling interface",
                    effective.get("return").toString().contains("documentation from the second branch"));
        } finally {
            deleteRecursively(project);
        }
    }

    @Test
    public void missingTypeParameterAndThrowsDocsInheritByPositionAndType() throws Exception {
        Path project = Files.createTempDirectory("cocomut-inheritdoc-generic-throws");
        try {
            write(project.resolve("src/main/java/demo/Contract.java"), """
                    package demo;
                    public interface Contract {
                        /**
                         * Converts a value.
                         * @param <T> result type
                         * @param input value to convert
                         * @return converted value
                         * @throws java.io.IOException when conversion fails
                         */
                        <T> T convert(T input) throws java.io.IOException;
                    }
                    """);
            write(project.resolve("src/main/java/demo/Implementation.java"), """
                    package demo;
                    public class Implementation implements Contract {
                        /** Local conversion note. */
                        @Override
                        public <R> R convert(R candidate) throws java.io.IOException {
                            return candidate;
                        }
                    }
                    """);

            compileProject(project);
            ProjectModel model = ProjectModel.from(new ProjectAnalyzer(project).analyze());
            SourceMethod focal = SourceBackends.spoon().findMethods(model).stream()
                    .filter(method -> method.className().equals("demo.Implementation"))
                    .filter(method -> method.methodName().equals("convert"))
                    .findFirst()
                    .orElseThrow();
            SourceContext context = SourceBackends.spoon().extractContext(model, focal.methodUri()).orElseThrow();

            @SuppressWarnings("unchecked")
            Map<String, Object> effective = (Map<String, Object>) context.javadocMetadata()
                    .get("effective_structured_tags");
            assertTrue(effective.get("type_params").toString().contains("result type"));
            assertTrue("Focal type-parameter name should be retained after positional matching",
                    effective.get("type_params").toString().contains("name=R"));
            assertTrue(effective.get("params").toString().contains("value to convert"));
            assertTrue(effective.get("return").toString().contains("converted value"));
            assertTrue(effective.get("throws").toString().contains("when conversion fails"));
            assertTrue(effective.get("throws").toString().contains("java.io.IOException"));
        } finally {
            deleteRecursively(project);
        }
    }

    @Test
    public void unavailableAncestorSourceIsIndeterminateRatherThanAbsent() throws Exception {
        Path project = Files.createTempDirectory("cocomut-inheritdoc-unavailable");
        try {
            write(project.resolve("src/main/java/demo/Child.java"), """
                    package demo;
                    public class Child {
                        /** {@inheritDoc} */
                        @Override public String toString() { return "child"; }
                    }
                    """);

            compileProject(project);
            ProjectModel model = ProjectModel.from(new ProjectAnalyzer(project).analyze());
            SourceMethod focal = SourceBackends.spoon().findMethods(model).stream()
                    .filter(method -> method.className().equals("demo.Child"))
                    .filter(method -> method.methodName().equals("toString"))
                    .findFirst()
                    .orElseThrow();
            SourceContext context = SourceBackends.spoon().extractContext(model, focal.methodUri()).orElseThrow();

            assertEquals("indeterminate", context.javadocMetadata().get("inheritdoc_resolution"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> candidates = (List<Map<String, Object>>) context.javadocMetadata()
                    .get("inherited_javadoc_candidates");
            assertFalse(candidates.isEmpty());
            assertEquals("java.lang.Object", candidates.get(0).get("declaring_type"));
            assertEquals("source_unavailable", candidates.get(0).get("javadoc_availability"));
            assertEquals(0, context.javadocMetadata().get("inheritdoc_documented_candidate_count"));
        } finally {
            deleteRecursively(project);
        }
    }

    @Test
    public void inaccessibleAncestorMethodsAreNotInheritanceCandidates() throws Exception {
        Path project = Files.createTempDirectory("cocomut-inheritdoc-access");
        try {
            write(project.resolve("src/main/java/parent/Base.java"), """
                    package parent;
                    public class Base {
                        /** Private documentation. */
                        private void privateMethod(String value) {}
                        /** Package documentation. */
                        void packageMethod(String value) {}
                    }
                    """);
            write(project.resolve("src/main/java/child/Child.java"), """
                    package child;
                    public class Child extends parent.Base {
                        /** {@inheritDoc} */
                        public void privateMethod(String value) {}
                        /** {@inheritDoc} */
                        public void packageMethod(String value) {}
                    }
                    """);

            compileProject(project);
            ProjectModel model = ProjectModel.from(new ProjectAnalyzer(project).analyze());
            for (String methodName : List.of("privateMethod", "packageMethod")) {
                SourceMethod focal = SourceBackends.spoon().findMethods(model).stream()
                        .filter(method -> method.className().equals("child.Child"))
                        .filter(method -> method.methodName().equals(methodName))
                        .findFirst().orElseThrow();
                SourceContext context = SourceBackends.spoon().extractContext(model, focal.methodUri()).orElseThrow();
                assertEquals("unresolved", context.javadocMetadata().get("inheritdoc_resolution"));
                assertEquals(List.of(), context.javadocMetadata().get("inherited_javadoc_candidates"));
            }
        } finally {
            deleteRecursively(project);
        }
    }

    @Test
    public void accessiblePackageMethodRemainsAnInheritanceCandidate() throws Exception {
        Path project = Files.createTempDirectory("cocomut-inheritdoc-package-access");
        try {
            write(project.resolve("src/main/java/demo/Base.java"), """
                    package demo;
                    public class Base {
                        /** Package contract. */
                        void process(String value) {}
                    }
                    """);
            write(project.resolve("src/main/java/demo/Child.java"), """
                    package demo;
                    public class Child extends Base {
                        /** {@inheritDoc} */
                        @Override void process(String value) {}
                    }
                    """);

            compileProject(project);
            String effective = contextFor(project, "demo.Child", "process")
                    .javadocMetadata().get("effective_structured_tags").toString();
            assertTrue(effective.contains("Package contract"));
        } finally {
            deleteRecursively(project);
        }
    }

    @Test
    public void interfaceDoesNotInheritProtectedObjectMethod() throws Exception {
        Path project = Files.createTempDirectory("cocomut-inheritdoc-object-public");
        try {
            write(project.resolve("src/main/java/demo/CloneContract.java"), """
                    package demo;
                    public interface CloneContract {
                        /** {@inheritDoc} */
                        Object clone();
                    }
                    """);

            compileProject(project);
            ProjectModel model = ProjectModel.from(new ProjectAnalyzer(project).analyze());
            SourceMethod focal = SourceBackends.spoon().findMethods(model).stream()
                    .filter(method -> method.className().equals("demo.CloneContract"))
                    .filter(method -> method.methodName().equals("clone"))
                    .findFirst().orElseThrow();
            SourceContext context = SourceBackends.spoon().extractContext(model, focal.methodUri()).orElseThrow();

            assertEquals(List.of(), context.javadocMetadata().get("inherited_javadoc_candidates"));
            @SuppressWarnings("unchecked")
            Map<String, Object> effective = (Map<String, Object>) context.javadocMetadata()
                    .get("effective_structured_tags");
            assertTrue(effective.get("description").toString().contains("resolution=invalid"));
        } finally {
            deleteRecursively(project);
        }
    }

    @Test
    public void eachExplicitInheritDocTargetIsResolvedIndependently() throws Exception {
        Path project = Files.createTempDirectory("cocomut-inheritdoc-multiple-targets");
        try {
            write(project.resolve("src/main/java/demo/Left.java"), """
                    package demo;
                    public interface Left { /** Left contract. */ String value(); }
                    """);
            write(project.resolve("src/main/java/demo/Right.java"), """
                    package demo;
                    public interface Right { /** Right contract. */ String value(); }
                    """);
            write(project.resolve("src/main/java/demo/Child.java"), """
                    package demo;
                    public class Child implements Left, Right {
                        /** {@inheritDoc Left} Combined with {@inheritDoc Right}. */
                        @Override public String value() { return "value"; }
                    }
                    """);

            compileProject(project);
            SourceContext context = contextFor(project, "demo.Child", "value");
            @SuppressWarnings("unchecked")
            Map<String, Object> effective = (Map<String, Object>) context.javadocMetadata()
                    .get("effective_structured_tags");
            String description = effective.get("description").toString();
            assertTrue(description.contains("Left contract"));
            assertTrue(description.contains("Right contract"));
            assertTrue(description.contains("source=composed"));
            assertTrue(description.contains("demo.Left"));
            assertTrue(description.contains("demo.Right"));
        } finally {
            deleteRecursively(project);
        }
    }

    @Test
    public void nestedInheritancePreservesEveryContributingSegment() throws Exception {
        Path project = Files.createTempDirectory("cocomut-inheritdoc-segment-chain");
        try {
            write(project.resolve("src/main/java/demo/Root.java"), """
                    package demo;
                    public interface Root { /** Root contract. */ String value(); }
                    """);
            write(project.resolve("src/main/java/demo/Parent.java"), """
                    package demo;
                    public class Parent implements Root {
                        /** {@inheritDoc} Parent refinement. */
                        @Override public String value() { return "parent"; }
                    }
                    """);
            write(project.resolve("src/main/java/demo/Child.java"), """
                    package demo;
                    public class Child extends Parent {
                        /** {@inheritDoc} Child refinement. */
                        @Override public String value() { return "child"; }
                    }
                    """);

            compileProject(project);
            SourceContext context = contextFor(project, "demo.Child", "value");
            @SuppressWarnings("unchecked")
            Map<String, Object> effective = (Map<String, Object>) context.javadocMetadata()
                    .get("effective_structured_tags");
            @SuppressWarnings("unchecked")
            Map<String, Object> description = (Map<String, Object>) effective.get("description");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> segments = (List<Map<String, Object>>) description.get("segments");

            assertEquals("composed", description.get("source"));
            assertEquals(3, segments.size());
            assertTrue(description.get("text").toString().contains("Root contract"));
            assertTrue(description.get("text").toString().contains("Parent refinement"));
            assertTrue(description.get("text").toString().contains("Child refinement"));
            assertTrue(description.get("source_chain").toString().contains("demo.Root.value"));
            assertTrue(description.get("source_chain").toString().contains("demo.Parent.value"));
            assertTrue(description.get("source_chain").toString().contains("demo.Child.value"));
        } finally {
            deleteRecursively(project);
        }
    }

    @Test
    public void explicitSupertypeUsesImportsAndRejectsAmbiguousSimpleNames() throws Exception {
        Path project = Files.createTempDirectory("cocomut-inheritdoc-target-scope");
        try {
            write(project.resolve("src/main/java/left/Contract.java"), """
                    package left;
                    public interface Contract { /** Left contract. */ String value(); }
                    """);
            write(project.resolve("src/main/java/right/Contract.java"), """
                    package right;
                    public interface Contract { /** Right contract. */ String value(); }
                    """);
            write(project.resolve("src/main/java/demo/Imported.java"), """
                    package demo;
                    import left.Contract;
                    public class Imported implements Contract, right.Contract {
                        /** {@inheritDoc Contract} */
                        @Override public String value() { return "value"; }
                    }
                    """);
            write(project.resolve("src/main/java/demo/Ambiguous.java"), """
                    package demo;
                    public class Ambiguous implements left.Contract, right.Contract {
                        /** {@inheritDoc Contract} */
                        @Override public String value() { return "value"; }
                    }
                    """);

            compileProject(project);
            SourceContext imported = contextFor(project, "demo.Imported", "value");
            SourceContext ambiguous = contextFor(project, "demo.Ambiguous", "value");
            assertEquals("Left contract.", effectiveDescription(imported).get("text"));
            assertEquals("resolved", effectiveDescription(imported).get("resolution"));
            assertEquals("invalid", effectiveDescription(ambiguous).get("resolution"));
            assertEquals("inheritdoc_target_ambiguous",
                    effectiveDescription(ambiguous).get("diagnostic_code"));
        } finally {
            deleteRecursively(project);
        }
    }

    @Test
    public void explicitSupertypeCanNameAnIntermediateInterface() throws Exception {
        Path project = Files.createTempDirectory("cocomut-inheritdoc-intermediate-target");
        try {
            write(project.resolve("src/main/java/demo/Root.java"), """
                    package demo;
                    public interface Root { /** Root documentation. */ String value(); }
                    """);
            write(project.resolve("src/main/java/demo/Mid.java"), """
                    package demo;
                    public interface Mid extends Root {}
                    """);
            write(project.resolve("src/main/java/demo/Child.java"), """
                    package demo;
                    public class Child implements Mid {
                        /** {@inheritDoc Mid} */
                        @Override public String value() { return ""; }
                    }
                    """);

            compileProject(project);
            String effective = contextFor(project, "demo.Child", "value")
                    .javadocMetadata().get("effective_structured_tags").toString();
            assertTrue(effective, effective.contains("Root documentation"));
            assertFalse(effective.contains("inheritdoc_target_not_overridden"));
        } finally {
            deleteRecursively(project);
        }
    }

    @Test
    public void qualifiedExplicitSupertypeIsNeverRepairedBySimpleName() throws Exception {
        Path project = Files.createTempDirectory("cocomut-inheritdoc-qualified-target");
        try {
            write(project.resolve("src/main/java/demo/Contract.java"), """
                    package demo;
                    public interface Contract { /** Contract documentation. */ String value(); }
                    """);
            write(project.resolve("src/main/java/demo/Child.java"), """
                    package demo;
                    public class Child implements Contract {
                        /** {@inheritDoc wrong.Contract} */
                        @Override public String value() { return ""; }
                    }
                    """);

            compileProject(project);
            String effective = contextFor(project, "demo.Child", "value")
                    .javadocMetadata().get("effective_structured_tags").toString();
            assertTrue(effective.contains("resolution=invalid"));
            assertTrue(effective.contains("inheritdoc_target_not_overridden"));
            assertFalse(effective.contains("Contract documentation"));
        } finally {
            deleteRecursively(project);
        }
    }

    @Test
    public void inlineReturnIsStructuredAndSupportsInheritance() throws Exception {
        Path project = Files.createTempDirectory("cocomut-inline-return");
        try {
            write(project.resolve("src/main/java/demo/Parent.java"), """
                    package demo;
                    public interface Parent {
                        /** Computes the configured value.
                         * @return the result contract
                         */
                        String value();
                    }
                    """);
            write(project.resolve("src/main/java/demo/ImplicitChild.java"), """
                    package demo;
                    public class ImplicitChild implements Parent {
                        /** Local implementation note. */
                        @Override public String value() { return ""; }
                    }
                    """);
            write(project.resolve("src/main/java/demo/ExplicitChild.java"), """
                    package demo;
                    public class ExplicitChild implements Parent {
                        /** {@return {@inheritDoc Parent}} */
                        @Override public String value() { return ""; }
                    }
                    """);

            compileProject(project);
            SourceContext parent = contextFor(project, "demo.Parent", "value");
            assertEquals("the result contract", firstEffectiveItem(parent, "return").get("text"));
            for (String child : List.of("demo.ImplicitChild", "demo.ExplicitChild")) {
                SourceContext context = contextFor(project, child, "value");
                assertEquals("the result contract", firstEffectiveItem(context, "return").get("text"));
                if ("demo.ExplicitChild".equals(child)) {
                    assertEquals("Returns the result contract.", effectiveDescription(context).get("text"));
                    assertFalse(effectiveDescription(context).get("text").toString()
                            .contains("Computes the configured value"));
                }
            }
        } finally {
            deleteRecursively(project);
        }
    }

    @Test
    public void codeAndLiteralNeverTriggerInheritance() throws Exception {
        Path project = Files.createTempDirectory("cocomut-inheritdoc-literal");
        try {
            write(project.resolve("src/main/java/demo/Parent.java"), """
                    package demo;
                    public interface Parent {
                        /** Parent description.
                         * @param value parent parameter
                         * @return parent return
                         * @throws java.lang.IllegalStateException parent failure
                         */
                        String value(String value) throws IllegalStateException;
                    }
                    """);
            write(project.resolve("src/main/java/demo/Child.java"), """
                    package demo;
                    public class Child implements Parent {
                        /** Literal {@code {@inheritDoc}} and {@literal {@inheritDoc}}.
                         * @param value {@code {@inheritDoc}}
                         * @return {@literal {@inheritDoc}}
                         * @throws java.lang.IllegalStateException {@code {@inheritDoc}}
                         */
                        @Override public String value(String value) throws IllegalStateException { return value; }
                    }
                    """);

            compileProject(project);
            SourceContext context = contextFor(project, "demo.Child", "value");
            String effective = context.javadocMetadata().get("effective_structured_tags").toString();
            assertTrue(effective.contains("{@inheritDoc}"));
            assertFalse(effective.contains("Parent description"));
            assertFalse(effective.contains("parent parameter"));
            assertFalse(effective.contains("parent return"));
            assertFalse(effective, effective.contains("parent failure"));
            assertEquals(false, context.javadocMetadata().get("uses_inheritdoc"));
            assertEquals(false, context.documentationMetrics().get("uses_inheritdoc"));
        } finally {
            deleteRecursively(project);
        }
    }

    @Test
    public void codeAndLiteralProtectMethodTypeParameterDocumentation() throws Exception {
        Path project = Files.createTempDirectory("cocomut-inheritdoc-literal-type-param");
        try {
            write(project.resolve("src/main/java/demo/Parent.java"), """
                    package demo;
                    public interface Parent {
                        /** @param <T> inherited type documentation */
                        <T> void process();
                    }
                    """);
            write(project.resolve("src/main/java/demo/Child.java"), """
                    package demo;
                    public class Child implements Parent {
                        /** @param <U> {@code {@inheritDoc}} */
                        @Override public <U> void process() {}
                    }
                    """);

            compileProject(project);
            Map<String, Object> typeParameter = firstEffectiveItem(
                    contextFor(project, "demo.Child", "process"), "type_params");
            assertEquals("U", typeParameter.get("name"));
            assertTrue(typeParameter.get("text").toString().contains("{@inheritDoc}"));
            assertFalse(typeParameter.get("text").toString().contains("inherited type documentation"));
            assertEquals("declared", typeParameter.get("source"));
        } finally {
            deleteRecursively(project);
        }
    }

    @Test
    public void rawFallbackDoesNotInventReturnTagsInsideLiteralContent() throws Exception {
        Path project = Files.createTempDirectory("cocomut-inheritdoc-literal-return");
        try {
            write(project.resolve("src/main/java/demo/Parent.java"), """
                    package demo;
                    public interface Parent {
                        /** Parent description.
                         * @return actual parent return
                         */
                        String value();
                    }
                    """);
            write(project.resolve("src/main/java/demo/Child.java"), """
                    package demo;
                    public class Child implements Parent {
                        /** {@inheritDoc Parent}
                         * Example: {@code {@return fake return}}
                         */
                        @Override public String value() { return ""; }
                    }
                    """);

            compileProject(project);
            SourceContext context = contextFor(project, "demo.Child", "value");
            assertEquals("actual parent return", firstEffectiveItem(context, "return").get("text"));
            assertEquals(1, ((List<?>) effectiveTags(context).get("return")).size());
        } finally {
            deleteRecursively(project);
        }
    }

    @Test
    public void rawFallbackPreservesLeadingInlineReturnWhenLiteralContentNeedsProtection() throws Exception {
        Path project = Files.createTempDirectory("cocomut-inline-return-protected-fallback");
        try {
            write(project.resolve("src/main/java/demo/Parent.java"), """
                    package demo;
                    public interface Parent {
                        /** @param value parent parameter
                         * @return parent return
                         */
                        String value(String value);
                    }
                    """);
            write(project.resolve("src/main/java/demo/Child.java"), """
                    package demo;
                    public class Child implements Parent {
                        /** {@return child return}
                         * Example: {@code @Override}
                         * @param value {@inheritDoc Parent}
                         */
                        @Override public String value(String value) { return value; }
                    }
                    """);

            compileProject(project);
            SourceContext context = contextFor(project, "demo.Child", "value");
            assertTrue(effectiveDescription(context).get("text").toString()
                    .startsWith("Returns child return."));
            assertFalse(effectiveDescription(context).get("text").toString().contains("parent return"));
            assertEquals("child return", firstEffectiveItem(context, "return").get("text"));
            assertEquals("declared", firstEffectiveItem(context, "return").get("source"));
            assertEquals("parent parameter", firstEffectiveItem(context, "params").get("text"));
        } finally {
            deleteRecursively(project);
        }
    }

    @Test
    public void rawFallbackUsesExactBlockIdentifiersAndAllBlockBoundaries() throws Exception {
        Path project = Files.createTempDirectory("cocomut-block-tag-grammar");
        try {
            write(project.resolve("src/main/java/demo/Parent.java"), """
                    package demo;
                    public interface Parent {
                        /** Parent description.
                         * @return parent return
                         */
                        String value();
                    }
                    """);
            write(project.resolve("src/main/java/demo/PrefixChild.java"), """
                    package demo;
                    public class PrefixChild implements Parent {
                        /** {@inheritDoc Parent}
                         * @returnValue not a return contract
                         * @RETURN not an uppercase return contract
                         */
                        @Override public String value() { return ""; }
                    }
                    """);
            write(project.resolve("src/main/java/demo/BoundaryChild.java"), """
                    package demo;
                    public class BoundaryChild implements Parent {
                        /** Local {@inheritDoc Parent}
                         * @custom custom information
                         * @return local return
                         */
                        @Override public String value() { return ""; }
                    }
                    """);

            compileProject(project);
            SourceContext prefix = contextFor(project, "demo.PrefixChild", "value");
            assertEquals("parent return", firstEffectiveItem(prefix, "return").get("text"));
            assertFalse(firstEffectiveItem(prefix, "return").get("text").toString()
                    .contains("not a return contract"));

            SourceContext boundary = contextFor(project, "demo.BoundaryChild", "value");
            assertEquals("Local Parent description.", effectiveDescription(boundary).get("text"));
            assertFalse(effectiveDescription(boundary).get("text").toString()
                    .contains("custom information"));
            assertEquals("local return", firstEffectiveItem(boundary, "return").get("text"));
        } finally {
            deleteRecursively(project);
        }
    }

    @Test
    public void snippetContentNeverBecomesInheritanceOrBlockDocumentation() throws Exception {
        Path project = Files.createTempDirectory("cocomut-snippet-javadoc-grammar");
        try {
            write(project.resolve("src/main/java/demo/Parent.java"), """
                    package demo;
                    public interface Parent {
                        /** Parent description.
                         * @return actual parent return
                         */
                        String value();
                    }
                    """);
            write(project.resolve("src/main/java/demo/Child.java"), """
                    package demo;
                    public class Child implements Parent {
                        /** {@inheritDoc Parent}
                         * {@snippet :
                         * {@inheritDoc}
                         * @return fake return
                         * }
                         */
                        @Override public String value() { return ""; }

                        /** {@snippet :
                         * {@inheritDoc}
                         * @return fake standalone return
                         * @see fake.Reference
                         * @deprecated fake deprecation
                         * }
                         */
                        public String standalone() { return ""; }
                    }
                    """);

            compileProject(project);
            SourceContext child = contextFor(project, "demo.Child", "value");
            assertEquals(true, child.javadocMetadata().get("uses_inheritdoc"));
            assertEquals("actual parent return", firstEffectiveItem(child, "return").get("text"));
            assertFalse(firstEffectiveItem(child, "return").get("text").toString().contains("fake"));

            SourceContext standalone = contextFor(project, "demo.Child", "standalone");
            assertEquals(false, standalone.javadocMetadata().get("uses_inheritdoc"));
            assertEquals(false, standalone.documentationMetrics().get("uses_inheritdoc"));
            assertEquals(false, standalone.documentationMetrics().get("has_see_tag"));
            assertEquals(false, standalone.javadocMetadata().get("deprecated"));
            assertEquals("missing", firstEffectiveItem(standalone, "return").get("source"));

            if (Runtime.version().feature() >= 25) {
                Path docs = project.resolve("javadoc");
                Process process = new ProcessBuilder(javadoc(), "-quiet", "-d", docs.toString(),
                        "-sourcepath", project.resolve("src/main/java").toString(), "demo")
                        .redirectErrorStream(true).start();
                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                assertEquals("JDK 25 javadoc failed: " + output, 0, process.waitFor());
            }
        } finally {
            deleteRecursively(project);
        }
    }

    @Test
    public void explicitSupertypeUsesWildcardAndEnclosingTypeScope() throws Exception {
        Path project = Files.createTempDirectory("cocomut-inheritdoc-target-scope");
        try {
            write(project.resolve("src/main/java/api/Outer.java"), """
                    package api;
                    public class Outer {
                        public interface Contract {
                            /** Wildcard contract. */ String value();
                        }
                    }
                    """);
            write(project.resolve("src/main/java/other/Contract.java"), """
                    package other;
                    public interface Contract { /** Other contract. */ String value(); }
                    """);
            write(project.resolve("src/main/java/demo/WildcardChild.java"), """
                    package demo;
                    import api.*;
                    public class WildcardChild implements Outer.Contract {
                        /** {@inheritDoc Outer.Contract} */
                        @Override public String value() { return ""; }
                    }
                    """);
            write(project.resolve("src/main/java/demo/Host.java"), """
                    package demo;
                    public class Host {
                        interface Contract { /** Enclosing contract. */ String value(); }
                        class Child implements Contract, other.Contract {
                            /** {@inheritDoc Contract} */
                            @Override public String value() { return ""; }
                        }
                    }
                    """);
            write(project.resolve("src/main/java/demo/UnimportedChild.java"), """
                    package demo;
                    public class UnimportedChild implements api.Outer.Contract {
                        /** {@inheritDoc Contract} */
                        @Override public String value() { return ""; }
                    }
                    """);

            compileProject(project);
            assertEquals("Wildcard contract.", effectiveDescription(
                    contextFor(project, "demo.WildcardChild", "value")).get("text"));
            assertEquals("Enclosing contract.", effectiveDescription(
                    contextFor(project, "demo.Host$Child", "value")).get("text"));
            Map<String, Object> unimported = effectiveDescription(
                    contextFor(project, "demo.UnimportedChild", "value"));
            assertEquals("invalid", unimported.get("resolution"));
            assertEquals("inheritdoc_target_not_overridden", unimported.get("diagnostic_code"));
        } finally {
            deleteRecursively(project);
        }
    }

    @Test
    public void explicitSupertypeUsesJavaTypeNamePrecedence() throws Exception {
        Path project = Files.createTempDirectory("cocomut-inheritdoc-java-scope");
        try {
            write(project.resolve("src/main/java/local/Contract.java"), """
                    package local;
                    public interface Contract { /** Local contract. */ String value(); }
                    """);
            write(project.resolve("src/main/java/external/Contract.java"), """
                    package external;
                    public interface Contract { /** External contract. */ String value(); }
                    """);
            write(project.resolve("src/main/java/local/SamePackageChild.java"), """
                    package local;
                    import external.*;
                    public class SamePackageChild implements Contract, external.Contract {
                        /** {@inheritDoc Contract} */
                        @Override public String value() { return ""; }
                    }
                    """);
            write(project.resolve("src/main/java/imported/Contract.java"), """
                    package imported;
                    public interface Contract { /** Explicit contract. */ String value(); }
                    """);
            write(project.resolve("src/main/java/consumer/ExplicitImportChild.java"), """
                    package consumer;
                    import imported.Contract;
                    import external.*;
                    public class ExplicitImportChild implements Contract, external.Contract {
                        /** {@inheritDoc Contract} */
                        @Override public String value() { return ""; }
                    }
                    """);
            write(project.resolve("src/main/java/alpha/Contract.java"), """
                    package alpha;
                    public interface Contract { /** Alpha contract. */ String value(); }
                    """);
            write(project.resolve("src/main/java/beta/Contract.java"), """
                    package beta;
                    public interface Contract { /** Beta contract. */ String value(); }
                    """);
            write(project.resolve("src/main/java/consumer/AmbiguousChild.java"), """
                    package consumer;
                    import alpha.*;
                    import beta.*;
                    public class AmbiguousChild implements alpha.Contract, beta.Contract {
                        /** {@inheritDoc Contract} */
                        @Override public String value() { return ""; }
                    }
                    """);
            write(project.resolve("src/main/java/consumer/ComparableItem.java"), """
                    package consumer;
                    public class ComparableItem implements Comparable<ComparableItem> {
                        /** {@inheritDoc Comparable} */
                        @Override public int compareTo(ComparableItem other) { return 0; }
                    }
                    """);

            compileProject(project);
            assertEquals("Local contract.", effectiveDescription(
                    contextFor(project, "local.SamePackageChild", "value")).get("text"));
            assertEquals("Explicit contract.", effectiveDescription(
                    contextFor(project, "consumer.ExplicitImportChild", "value")).get("text"));

            Map<String, Object> ambiguous = effectiveDescription(
                    contextFor(project, "consumer.AmbiguousChild", "value"));
            assertEquals("invalid", ambiguous.get("resolution"));
            assertEquals("inheritdoc_target_ambiguous", ambiguous.get("diagnostic_code"));

            SourceContext comparable = contextFor(project, "consumer.ComparableItem", "compareTo");
            assertEquals(true, comparable.javadocMetadata().get("uses_inheritdoc"));
            assertFalse(comparable.javadocMetadata().get("effective_structured_tags").toString()
                    .contains("inheritdoc_target_not_overridden"));
        } finally {
            deleteRecursively(project);
        }
    }

    @Test
    public void explicitSupertypeCanNameAnInheritedMemberType() throws Exception {
        Path project = Files.createTempDirectory("cocomut-inherited-member-target");
        try {
            write(project.resolve("src/main/java/demo/Base.java"), """
                    package demo;
                    public class Base {
                        public interface Contract {
                            /** Inherited member contract. */ String value();
                        }
                    }
                    """);
            write(project.resolve("src/main/java/demo/Child.java"), """
                    package demo;
                    public class Child extends Base implements Base.Contract {
                        /** {@inheritDoc Contract} */
                        @Override public String value() { return ""; }
                    }
                    """);

            compileProject(project);
            SourceContext context = contextFor(project, "demo.Child", "value");
            assertEquals("Inherited member contract.", effectiveDescription(context).get("text"));
            assertEquals("resolved", effectiveDescription(context).get("resolution"));

            if (Runtime.version().feature() >= 25) {
                Path docs = project.resolve("javadoc");
                Process process = new ProcessBuilder(javadoc(), "-quiet", "-d", docs.toString(),
                        "-sourcepath", project.resolve("src/main/java").toString(), "demo")
                        .redirectErrorStream(true).start();
                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                assertEquals("JDK 25 javadoc failed: " + output, 0, process.waitFor());
                assertTrue(Files.readString(docs.resolve("demo/Child.html"))
                        .contains("Inherited member contract."));
            }
        } finally {
            deleteRecursively(project);
        }
    }

    @Test
    public void inlineTagsAreCaseSensitiveExactAndPositionAware() throws Exception {
        Path project = Files.createTempDirectory("cocomut-inline-tag-grammar");
        try {
            write(project.resolve("src/main/java/demo/Parent.java"), """
                    package demo;
                    public interface Parent {
                        /** Parent description. */ String value();
                    }
                    """);
            write(project.resolve("src/main/java/demo/Child.java"), """
                    package demo;
                    public class Child implements Parent {
                        /** {@inheritdoc Parent} */
                        public String lowerCaseTag() { return ""; }
                        /** {@INHERITDOC Parent} */
                        public String upperCaseTag() { return ""; }
                        /** Text first. {@return invalid return} */
                        public String misplacedReturn() { return ""; }
                        /** {@summary Summary.} {@return invalid return} */
                        public String afterSummary() { return ""; }
                        /** {@RETURN upper-case return} */
                        public String upperCaseReturn() { return ""; }
                        /** {@returnValue prefixed return} */
                        public String prefixedReturn() { return ""; }
                        /** {@return valid return} */
                        public String validReturn() { return ""; }
                        /** {@inheritDoc Parent} {@returnValue not a return tag} */
                        @Override public String value() { return ""; }
                    }
                    """);

            compileProject(project);
            for (String method : List.of("lowerCaseTag", "upperCaseTag")) {
                SourceContext context = contextFor(project, "demo.Child", method);
                assertEquals(false, context.javadocMetadata().get("uses_inheritdoc"));
                assertEquals(false, context.documentationMetrics().get("uses_inheritdoc"));
            }
            for (String method : List.of(
                    "misplacedReturn", "afterSummary", "upperCaseReturn", "prefixedReturn")) {
                Map<String, Object> missingReturn = firstEffectiveItem(
                        contextFor(project, "demo.Child", method), "return");
                assertEquals("missing", missingReturn.get("source"));
                assertEquals("", missingReturn.get("text"));
            }
            assertEquals("valid return", firstEffectiveItem(
                    contextFor(project, "demo.Child", "validReturn"), "return").get("text"));
            Map<String, Object> prefixReturn = firstEffectiveItem(
                    contextFor(project, "demo.Child", "value"), "return");
            assertEquals("missing", prefixReturn.get("source"));
            assertEquals("", prefixReturn.get("text"));
        } finally {
            deleteRecursively(project);
        }
    }

    @Test
    public void thrownMethodTypeVariablesMatchByPosition() throws Exception {
        Path project = Files.createTempDirectory("cocomut-inheritdoc-thrown-type-variable");
        try {
            write(project.resolve("src/main/java/demo/Parent.java"), """
                    package demo;
                    public interface Parent {
                        /** @throws E when processing fails */
                        <E extends Exception> void process() throws E;
                    }
                    """);
            write(project.resolve("src/main/java/demo/Child.java"), """
                    package demo;
                    public class Child implements Parent {
                        /** Local description. */
                        @Override public <F extends Exception> void process() throws F {}
                    }
                    """);

            compileProject(project);
            String effective = contextFor(project, "demo.Child", "process")
                    .javadocMetadata().get("effective_structured_tags").toString();
            assertTrue(effective.contains("type=F"));
            assertTrue(effective.contains("when processing fails"));
        } finally {
            deleteRecursively(project);
        }
    }

    @Test
    public void repeatedInheritDocInsideOneThrowsDescriptionIsInvalid() throws Exception {
        Path project = Files.createTempDirectory("cocomut-inheritdoc-repeated-throws");
        try {
            write(project.resolve("src/main/java/demo/Parent.java"), """
                    package demo;
                    public interface Parent {
                        /** @throws java.io.IOException parent failure */
                        void read() throws java.io.IOException;
                    }
                    """);
            write(project.resolve("src/main/java/demo/Child.java"), """
                    package demo;
                    public class Child implements Parent {
                        /** @throws java.io.IOException before {@inheritDoc} and {@inheritDoc} */
                        @Override public void read() throws java.io.IOException {}
                    }
                    """);

            compileProject(project);
            String effective = contextFor(project, "demo.Child", "read")
                    .javadocMetadata().get("effective_structured_tags").toString();
            assertTrue(effective.contains("resolution=invalid"));
            assertTrue(effective.contains("throws_multiple_inheritdoc"));
        } finally {
            deleteRecursively(project);
        }
    }

    @Test
    public void decoratedThrowsCannotExpandToMultipleInheritedEntries() throws Exception {
        Path project = Files.createTempDirectory("cocomut-inheritdoc-decorated-throws");
        try {
            write(project.resolve("src/main/java/demo/Parent.java"), """
                    package demo;
                    public interface Parent {
                        /** @throws java.io.IOException if reading fails
                         * @throws java.io.IOException if closing fails
                         */
                        void read() throws java.io.IOException;
                    }
                    """);
            write(project.resolve("src/main/java/demo/LoneChild.java"), """
                    package demo;
                    public class LoneChild implements Parent {
                        /** @throws java.io.IOException {@inheritDoc} */
                        @Override public void read() throws java.io.IOException {}
                    }
                    """);
            write(project.resolve("src/main/java/demo/DecoratedChild.java"), """
                    package demo;
                    public class DecoratedChild implements Parent {
                        /** @throws java.io.IOException before {@inheritDoc} after */
                        @Override public void read() throws java.io.IOException {}
                    }
                    """);

            compileProject(project);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> lone = (List<Map<String, Object>>) effectiveTags(
                    contextFor(project, "demo.LoneChild", "read")).get("throws");
            assertEquals(2, lone.size());
            assertEquals(List.of("if reading fails", "if closing fails"),
                    lone.stream().map(item -> item.get("text").toString()).toList());

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> decorated = (List<Map<String, Object>>) effectiveTags(
                    contextFor(project, "demo.DecoratedChild", "read")).get("throws");
            assertEquals(1, decorated.size());
            assertEquals("invalid", decorated.get(0).get("resolution"));
            assertEquals("throws_multiple_expansion_with_local_text",
                    decorated.get(0).get("diagnostic_code"));

            if (Runtime.version().feature() >= 25) {
                Path sourceRoot = project.resolve("src/main/java");
                Path loneDocs = project.resolve("javadoc-lone");
                Process loneProcess = new ProcessBuilder(javadoc(), "-quiet", "-d", loneDocs.toString(),
                        sourceRoot.resolve("demo/Parent.java").toString(),
                        sourceRoot.resolve("demo/LoneChild.java").toString())
                        .redirectErrorStream(true).start();
                String loneOutput = new String(loneProcess.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                assertEquals("JDK 25 should accept standalone throws expansion: " + loneOutput,
                        0, loneProcess.waitFor());

                Path decoratedDocs = project.resolve("javadoc-decorated");
                Process decoratedProcess = new ProcessBuilder(javadoc(), "-quiet", "-d", decoratedDocs.toString(),
                        sourceRoot.resolve("demo/Parent.java").toString(),
                        sourceRoot.resolve("demo/DecoratedChild.java").toString())
                        .redirectErrorStream(true).start();
                String decoratedOutput = new String(
                        decoratedProcess.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                assertTrue("JDK 25 should reject decorated multi-entry throws expansion: "
                                + decoratedOutput,
                        decoratedProcess.waitFor() != 0);
            }
        } finally {
            deleteRecursively(project);
        }
    }

    @Test
    public void candidatesExposeAbstractAndDefaultProvenance() throws Exception {
        Path project = Files.createTempDirectory("cocomut-inheritdoc-candidate-kind");
        try {
            write(project.resolve("src/main/java/demo/AbstractBase.java"), """
                    package demo;
                    public abstract class AbstractBase {
                        /** Abstract contract. */ public abstract String value();
                    }
                    """);
            write(project.resolve("src/main/java/demo/Child.java"), """
                    package demo;
                    public class Child extends AbstractBase {
                        /** {@inheritDoc} */ @Override public String value() { return ""; }
                    }
                    """);

            compileProject(project);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> candidates = (List<Map<String, Object>>) contextFor(
                    project, "demo.Child", "value").javadocMetadata().get("inherited_javadoc_candidates");
            Map<String, Object> candidate = candidates.stream()
                    .filter(value -> "demo.AbstractBase".equals(value.get("declaring_type")))
                    .findFirst().orElseThrow();
            assertEquals(true, candidate.get("declaring_type_abstract"));
            assertEquals(true, candidate.get("method_abstract"));
            assertEquals(false, candidate.get("method_default"));
        } finally {
            deleteRecursively(project);
        }
    }

    @Test
    public void duplicateThrowsTagsRemainDistinctEffectiveEntries() throws Exception {
        Path project = Files.createTempDirectory("cocomut-inheritdoc-duplicate-throws");
        try {
            write(project.resolve("src/main/java/demo/Contract.java"), """
                    package demo;
                    public interface Contract {
                        /**
                         * Reads data.
                         * @throws java.io.IOException if reading fails
                         * @throws java.io.IOException if closing fails
                         */
                        void read() throws java.io.IOException;
                    }
                    """);
            write(project.resolve("src/main/java/demo/Child.java"), """
                    package demo;
                    public class Child implements Contract {
                        /** Local note. */
                        @Override public void read() throws java.io.IOException {}
                    }
                    """);

            compileProject(project);
            SourceContext context = contextFor(project, "demo.Child", "read");
            @SuppressWarnings("unchecked")
            Map<String, Object> effective = (Map<String, Object>) context.javadocMetadata()
                    .get("effective_structured_tags");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> throwsTags = (List<Map<String, Object>>) effective.get("throws");
            assertEquals(2, throwsTags.size());
            assertTrue(throwsTags.get(0).get("text").toString().contains("reading fails"));
            assertTrue(throwsTags.get(1).get("text").toString().contains("closing fails"));
        } finally {
            deleteRecursively(project);
        }
    }

    @Test
    public void constructorsKeepDeclaredParamAndThrowsDocumentation() throws Exception {
        Path project = Files.createTempDirectory("cocomut-constructor-effective-docs");
        try {
            write(project.resolve("src/main/java/demo/Value.java"), """
                    package demo;
                    public class Value {
                        /**
                         * Creates a value.
                         * @param text input text
                         * @throws java.lang.IllegalArgumentException for blank input
                         */
                        public Value(String text) throws IllegalArgumentException {}
                    }
                    """);

            compileProject(project);
            SourceContext context = contextFor(project, "demo.Value", "Value");
            assertEquals("not_applicable", context.javadocMetadata().get("inheritdoc_policy"));
            String effective = context.javadocMetadata().get("effective_structured_tags").toString();
            assertTrue(effective.contains("input text"));
            assertTrue(effective.contains("for blank input"));
        } finally {
            deleteRecursively(project);
        }
    }

    @Test
    public void fixedJdk25PolicyMatchesStandardDocletSuperclassOrdering() throws Exception {
        Path project = Files.createTempDirectory("cocomut-inheritdoc-version-policy");
        try {
            write(project.resolve("src/main/java/demo/Contract.java"), """
                    package demo;
                    public interface Contract { /** Interface documentation. */ void process(); }
                    """);
            write(project.resolve("src/main/java/demo/Base.java"), """
                    package demo;
                    public class Base { /** Superclass documentation. */ public void process() {} }
                    """);
            write(project.resolve("src/main/java/demo/Child.java"), """
                    package demo;
                    public class Child extends Base implements Contract {
                        /** {@inheritDoc} */
                        @Override public void process() {}
                    }
                    """);

            compileProject(project);
            SourceContext context = contextFor(project, "demo.Child", "process");
            String effective = context.javadocMetadata().get("effective_structured_tags").toString();
            assertTrue("CoCoMUT's fixed JDK 25 policy must select the superclass branch: " + effective,
                    effective.contains("Superclass documentation"));
            assertFalse(effective.contains("Interface documentation"));

            Path docs = project.resolve("javadoc");
            Process process = new ProcessBuilder(javadoc(), "-quiet", "-d", docs.toString(),
                    "-sourcepath", project.resolve("src/main/java").toString(), "demo")
                    .redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals("javadoc failed: " + output, 0, process.waitFor());
            String rendered = Files.readString(docs.resolve("demo/Child.html"));
            assertTrue("standard doclet should select the superclass documentation: " + rendered,
                    rendered.contains("Superclass documentation"));
        } finally {
            deleteRecursively(project);
        }
    }

    @Test
    public void jdk25OracleSupportsExplicitTargetsAndInlineReturnInheritance() throws Exception {
        Assume.assumeTrue("requires the JDK 25 standard doclet",
                Runtime.version().feature() >= 25);
        Path project = Files.createTempDirectory("cocomut-inheritdoc-jdk25-oracle");
        try {
            write(project.resolve("src/main/java/demo/Contract.java"), """
                    package demo;
                    public interface Contract {
                        /** Computes the configured value.
                         * @return the result contract
                         */
                        String value();
                    }
                    """);
            write(project.resolve("src/main/java/demo/Child.java"), """
                    package demo;
                    public class Child implements Contract {
                        /** {@return {@inheritDoc Contract}} */
                        @Override public String value() { return ""; }
                    }
                    """);

            compileProject(project);
            SourceContext context = contextFor(project, "demo.Child", "value");
            assertEquals("Returns the result contract.", effectiveDescription(context).get("text"));
            assertEquals("the result contract", firstEffectiveItem(context, "return").get("text"));

            Path docs = project.resolve("javadoc");
            Process process = new ProcessBuilder(javadoc(), "-quiet", "-d", docs.toString(),
                    "-sourcepath", project.resolve("src/main/java").toString(), "demo")
                    .redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals("JDK 25 javadoc failed: " + output, 0, process.waitFor());
            String rendered = Files.readString(docs.resolve("demo/Child.html"));
            assertTrue("standard doclet should inherit the return contract: " + rendered,
                    rendered.contains("the result contract"));
        } finally {
            deleteRecursively(project);
        }
    }

    @Test
    public void javadocFileReferencesCarryParserProvenanceAndStayInsideProject() throws Exception {
        Path project = Files.createTempDirectory("cocomut-javadoc-file-references");
        Path outsideSecret = project.getParent().resolve(project.getFileName() + "-secret.txt");
        try {
            write(project.resolve("src/main/java/demo/doc-files/protocol.html"), "<html>protocol</html>");
            write(project.resolve("src/main/java/demo/doc-files/diagram.svg"), "<svg></svg>");
            write(project.resolve("src/main/java/demo/examples/Usage.java"), """
                    package demo.examples;
                    public class Usage {}
                    """);
            write(project.resolve("src/main/java/demo/examples/Sample.java"), """
                    package demo.examples;
                    public class Sample {}
                    """);
            write(project.resolve("src/main/java/demo/examples/ParseExample.java"), """
                    package demo.examples;
                    public class ParseExample {}
                    """);
            Files.writeString(outsideSecret, "outside", StandardCharsets.UTF_8);
            write(project.resolve("src/main/java/demo/FileDocs.java"), """
                    package demo;

                    public class FileDocs {
                        /**
                         * See {@docRoot}/doc-files/protocol.html.
                         * See doc-files/diagram.svg.
                         * See examples/Usage.java.
                         * @filename examples/Sample.java
                         * {@snippet file="examples/ParseExample.java" region="main"}
                         * Do not resolve ../../../../../%s.
                         */
                        public void files() {
                        }
                    }
                    """.formatted(outsideSecret.getFileName()));

            compileProject(project);
            ProjectModel model = ProjectModel.from(new ProjectAnalyzer(project).analyze());
            SourceMethod focal = SourceBackends.spoon().findMethods(model).stream()
                    .filter(method -> method.className().equals("demo.FileDocs"))
                    .filter(method -> method.methodName().equals("files"))
                    .findFirst()
                    .orElseThrow();

            SourceContext context = SourceBackends.spoon()
                    .extractContext(model, focal.methodUri())
                    .orElseThrow();

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> files = (List<Map<String, Object>>) context.javadocMetadata()
                    .get("file_references");

            Map<String, Object> protocol = fileReferenceByPath(files, "doc-files/protocol.html");
            assertEquals("html", protocol.get("kind"));
            assertEquals("cocomut-file-regex", protocol.get("parser"));
            assertEquals("low", protocol.get("parse_confidence"));
            assertEquals("doc_root", protocol.get("source_form"));
            assertEquals(true, protocol.get("exists"));

            Map<String, Object> diagram = fileReferenceByPath(files, "doc-files/diagram.svg");
            assertEquals("image", diagram.get("kind"));
            assertEquals("doc_files", diagram.get("source_form"));
            assertEquals(true, diagram.get("exists"));

            Map<String, Object> usage = fileReferenceByPath(files, "examples/Usage.java");
            assertEquals("sample_source", usage.get("kind"));
            assertEquals("regex_text", usage.get("source_form"));
            assertEquals(true, usage.get("exists"));

            Map<String, Object> filename = fileReferenceByPath(files, "examples/Sample.java");
            assertEquals("filename_tag", filename.get("source_form"));
            assertEquals(true, filename.get("exists"));

            Map<String, Object> snippet = fileReferenceByPath(files, "examples/ParseExample.java");
            assertEquals("snippet_file_attribute", snippet.get("source_form"));
            assertEquals(true, snippet.get("exists"));

            Map<String, Object> traversal = fileReferenceByPath(files, "../../../../../" + outsideSecret.getFileName());
            assertEquals(false, traversal.get("exists"));
            assertEquals("", traversal.get("resolved_path"));
        } finally {
            Files.deleteIfExists(outsideSecret);
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

    private static SourceContext contextFor(Path project, String className, String methodName) throws Exception {
        ProjectModel model = ProjectModel.from(new ProjectAnalyzer(project).analyze());
        SourceMethod focal = SourceBackends.spoon().findMethods(model).stream()
                .filter(method -> method.className().equals(className))
                .filter(method -> method.methodName().equals(methodName))
                .findFirst().orElseThrow();
        return SourceBackends.spoon().extractContext(model, focal.methodUri()).orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> effectiveTags(SourceContext context) {
        return (Map<String, Object>) context.javadocMetadata().get("effective_structured_tags");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> effectiveDescription(SourceContext context) {
        return (Map<String, Object>) effectiveTags(context).get("description");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstEffectiveItem(SourceContext context, String field) {
        List<Map<String, Object>> items = (List<Map<String, Object>>) effectiveTags(context).get(field);
        assertFalse("Expected an effective documentation item for " + field, items.isEmpty());
        return items.get(0);
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

    private static String javadoc() {
        Path javaHome = Path.of(System.getProperty("java.home"));
        Path javadoc = javaHome.resolve("bin").resolve("javadoc");
        if (Files.isRegularFile(javadoc)) {
            return javadoc.toString();
        }
        Path parentJavadoc = javaHome.getParent() == null
                ? null : javaHome.getParent().resolve("bin").resolve("javadoc");
        return parentJavadoc != null && Files.isRegularFile(parentJavadoc)
                ? parentJavadoc.toString() : "javadoc";
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

    private static Map<String, Object> fileReferenceByPath(List<Map<String, Object>> references, String path) {
        return references.stream()
                .filter(reference -> path.equals(reference.get("path")))
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
