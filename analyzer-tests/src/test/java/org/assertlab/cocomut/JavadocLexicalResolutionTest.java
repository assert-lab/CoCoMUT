package org.assertlab.cocomut;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import org.assertlab.cocomut.source.ProjectModel;
import org.assertlab.cocomut.source.SourceAnalysisSession;
import org.assertlab.cocomut.source.SourceBackends;
import org.assertlab.cocomut.source.SourceContext;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/** Regressions for lexical Javadoc links, shared by typed and text parser paths. */
public class JavadocLexicalResolutionTest {
    @Test
    public void nestedTypesResolveThroughVisibleOuterNames() throws Exception {
        Path root = Files.createTempDirectory("cocomut-lexical-nested");
        try {
            write(root, "lib/Outer.java", """
                    package lib;
                    public class Outer {
                        public static class Inner { public static class Deep {} }
                    }
                    """);
            write(root, "explicit/Focal.java", """
                    package explicit;
                    import lib.Outer;
                    public class Focal {
                        /** {@link Outer.Inner} {@linkplain Outer.Inner.Deep} */
                        public void focal() {}
                    }
                    """);
            write(root, "wildcard/Focal.java", """
                    package wildcard;
                    import lib.*;
                    public class Focal {
                        /** {@link Outer.Inner} */ public void focal() {}
                    }
                    """);
            write(root, "lib/Focal.java", """
                    package lib;
                    public class Focal {
                        /** {@link Outer.Inner} {@link lib.Outer.Inner} */ public void focal() {}
                    }
                    """);
            write(root, "enclosing/Outer.java", """
                    package enclosing;
                    public class Outer {
                        public static class Inner {}
                        public class Focal {
                            /** {@link Outer.Inner} {@link Inner} */ public void focal() {}
                        }
                    }
                    """);
            write(root, "inherited/Focal.java", """
                    package inherited;
                    public class Focal extends lib.Outer {
                        /** {@link Inner.Deep} */ public void focal() {}
                    }
                    """);
            compile(root);
            assertType(root, "explicit.Focal", "Outer.Inner", "lib.Outer$Inner");
            assertType(root, "explicit.Focal", "Outer.Inner.Deep", "lib.Outer$Inner$Deep");
            assertType(root, "wildcard.Focal", "Outer.Inner", "lib.Outer$Inner");
            assertType(root, "lib.Focal", "Outer.Inner", "lib.Outer$Inner");
            assertType(root, "lib.Focal", "lib.Outer.Inner", "lib.Outer$Inner");
            assertType(root, "enclosing.Outer$Focal", "Outer.Inner", "enclosing.Outer$Inner");
            assertType(root, "enclosing.Outer$Focal", "Inner", "enclosing.Outer$Inner");
            assertType(root, "inherited.Focal", "Inner.Deep", "lib.Outer$Inner$Deep");
        } finally {
            delete(root);
        }
    }

    @Test
    public void typeParametersShadowUnrelatedProjectTypes() throws Exception {
        Path root = Files.createTempDirectory("cocomut-lexical-parameter");
        try {
            write(root, "other/T.java", "package other; public class T {}");
            write(root, "T.java", "public class T {}");
            write(root, "demo/Focal.java", """
                    package demo;
                    import other.T;
                    public class Focal<U> {
                        /**
                         * {@link T}
                         * @return {@link T}
                         */
                        public <T> T focal() { return null; }
                        /** {@linkplain U} */ public U ownerParameter() { return null; }
                        /** {@link U} */ public class Nested {
                            /** {@link U} */ public void enclosingParameter() {}
                        }
                    }
                    """);
            compile(root);
            ProjectMetadata metadata = new ProjectAnalyzer(root).analyze();
            try (SourceAnalysisSession session = SourceBackends.spoon().open(ProjectModel.from(metadata))) {
                for (var method : session.methods()) {
                    if (!List.of("focal", "ownerParameter", "enclosingParameter").contains(method.methodName())) {
                        continue;
                    }
                    var context = session.extractContext(method.methodUri()).orElseThrow();
                    assertFalse(refs(context).isEmpty());
                    if ("focal".equals(method.methodName())) {
                        assertEquals(2, refs(context).size());
                        validateJson(root, "demo.Focal");
                    }
                    for (var ref : refs(context)) {
                        assertEquals("unresolved", ref.get("resolution"));
                        assertEquals("unresolved", ref.get("reference_domain"));
                        assertEquals("lexical_type_parameter", ref.get("unresolved_reason"));
                        assertFalse(ref.containsKey("type_uri"));
                        assertFalse(ref.containsKey("resolved_type"));
                    }
                }
            }
        } finally {
            delete(root);
        }
    }

    @Test
    public void unrelatedAndAmbiguousNamesNeverAcquireProjectUris() throws Exception {
        Path root = Files.createTempDirectory("cocomut-lexical-negative");
        try {
            for (String pkg : List.of("left", "right")) {
                write(root, pkg + "/Outer.java", "package " + pkg
                        + "; public class Outer { public static class Inner {} }");
            }
            write(root, "T.java", "public class T {}");
            write(root, "Outer.java", "public class Outer { public static class Inner { public static class Deep {} } }");
            write(root, "none/Focal.java", """
                    package none;
                    public class Focal {
                        /** {@link Outer.Inner} {@link Outer.Inner.Deep} {@link T} {@link Broken..Name} */ public void focal() {}
                    }
                    """);
            write(root, "ambiguous/Focal.java", """
                    package ambiguous;
                    import left.*;
                    import right.*;
                    public class Focal {
                        /** {@link Outer.Inner} */ public void focal() {}
                    }
                    """);
            compile(root);
            for (String type : List.of("none.Focal", "ambiguous.Focal")) {
                var references = refs(context(root, type));
                assertEquals("none.Focal".equals(type) ? 4 : 1, references.size());
                for (var ref : references) {
                    assertNotEquals("project", ref.get("reference_domain"));
                    assertFalse(ref.containsKey("type_uri"));
                }
            }
        } finally {
            delete(root);
        }
    }

    @Test
    public void lexicalNestedTypePrecedesExplicitImport() throws Exception {
        Path root = Files.createTempDirectory("cocomut-lexical-shadow");
        try {
            write(root, "lib/Outer.java", "package lib; public class Outer { public static class Inner {} }");
            write(root, "demo/Focal.java", """
                    package demo;
                    import lib.Outer;
                    public class Focal {
                        public static class Outer { public static class Inner {} }
                        /** {@link Outer.Inner} */ public void focal() {}
                    }
                    """);
            compile(root);
            assertType(root, "demo.Focal", "Outer.Inner", "demo.Focal$Outer$Inner");
        } finally {
            delete(root);
        }
    }

    @Test
    public void missingNestedMemberDoesNotEscapeTheVisibleOuterScope() throws Exception {
        Path root = Files.createTempDirectory("cocomut-lexical-missing-member");
        try {
            write(root, "lib/Outer.java", "package lib; public class Outer { public static class Inner {} }");
            write(root, "other/Outer.java", "package other; public class Outer {}");
            write(root, "demo/Focal.java", """
                    package demo;
                    import lib.Outer;
                    public class Focal {
                        public static class Outer {}
                        /** {@link Outer.Inner} */ public void focal() {}
                    }
                    """);
            write(root, "same/Outer.java", "package same; public class Outer {}");
            write(root, "same/Focal.java", """
                    package same;
                    import lib.*;
                    public class Focal {
                        /** {@link Outer.Inner} */ public void focal() {}
                    }
                    """);
            write(root, "ambiguous/Focal.java", """
                    package ambiguous;
                    import lib.*;
                    import other.*;
                    public class Focal {
                        /** {@link Outer.Inner} */ public void focal() {}
                    }
                    """);
            compile(root);
            for (String owner : List.of("demo.Focal", "same.Focal", "ambiguous.Focal")) {
                var references = refs(context(root, owner));
                assertFalse(references.isEmpty());
                for (var ref : references) {
                    assertNotEquals("project", ref.get("reference_domain"));
                    assertFalse(ref.containsKey("type_uri"));
                }
            }
        } finally {
            delete(root);
        }
    }

    @Test
    public void inheritedTypeInChildPrecedesEnclosingDeclaration() throws Exception {
        Path root = Files.createTempDirectory("cocomut-inherited-before-enclosing");
        try {
            write(root, "demo/Base.java", "package demo; public class Base { public static class N {} }");
            write(root, "demo/Outer.java", """
                    package demo;
                    public class Outer {
                        public static class N {}
                        public class Child extends Base {
                            /** {@link N} */ public void focal() {}
                        }
                    }
                    """);
            compile(root);
            assertType(root, "demo.Outer$Child", "N", "demo.Base$N");
        } finally {
            delete(root);
        }
    }

    @Test
    public void nearerMemberTypeShadowsEnclosingTypeParameter() throws Exception {
        Path root = Files.createTempDirectory("cocomut-member-before-enclosing-parameter");
        try {
            write(root, "demo/Outer.java", """
                    package demo;
                    public class Outer<T> {
                        public class Child {
                            public class T {}
                            /** {@link T} */ public void focal() {}
                        }
                    }
                    """);
            compile(root);
            assertType(root, "demo.Outer$Child", "T", "demo.Outer$Child$T");
        } finally {
            delete(root);
        }
    }

    @Test
    public void dottedSegmentsResolveInheritedMembersAtEveryStep() throws Exception {
        Path root = Files.createTempDirectory("cocomut-inherited-dotted-segments");
        try {
            write(root, "lib/More.java", "package lib; public class More { public static class Deep {} }");
            write(root, "lib/Base.java", "package lib; public class Base { public static class Inner extends More {} }");
            write(root, "lib/Outer.java", "package lib; public class Outer extends Base {}");
            write(root, "explicit/Focal.java", """
                    package explicit;
                    import lib.Outer;
                    public class Focal {
                        /** {@link Outer.Inner} {@link Outer.Inner.Deep} */ public void focal() {}
                    }
                    """);
            write(root, "wildcard/Focal.java", """
                    package wildcard;
                    import lib.*;
                    public class Focal {
                        /** {@link Outer.Inner} */ public void focal() {}
                    }
                    """);
            write(root, "lib/Focal.java", """
                    package lib;
                    public class Focal {
                        /** {@link Outer.Inner} {@link lib.Outer.Inner.Deep} */ public void focal() {}
                    }
                    """);
            write(root, "enclosing/Outer.java", """
                    package enclosing;
                    public class Outer extends lib.Base {
                        public class Child {
                            /** {@link Outer.Inner.Deep} */ public void focal() {}
                        }
                    }
                    """);
            compile(root);
            assertType(root, "explicit.Focal", "Outer.Inner", "lib.Base$Inner");
            assertType(root, "explicit.Focal", "Outer.Inner.Deep", "lib.More$Deep");
            assertType(root, "wildcard.Focal", "Outer.Inner", "lib.Base$Inner");
            assertType(root, "lib.Focal", "Outer.Inner", "lib.Base$Inner");
            assertType(root, "lib.Focal", "lib.Outer.Inner.Deep", "lib.More$Deep");
            assertType(root, "enclosing.Outer$Child", "Outer.Inner.Deep", "lib.More$Deep");
        } finally {
            delete(root);
        }
    }

    private static void assertType(Path root, String owner, String target, String expected) throws Exception {
        SourceContext context = context(root, owner);
        var ref = refs(context).stream().filter(r -> target.equals(r.get("target"))).findFirst().orElseThrow();
        assertEquals("resolved_type", ref.get("resolution"));
        assertEquals("project", ref.get("reference_domain"));
        assertEquals(expected, ref.get("resolved_type"));
        assertTrue(ref.get("type_uri").toString().endsWith("#" + expected));
        validateJson(root, owner);
    }

    private static SourceContext context(Path root, String owner) throws Exception {
        ProjectMetadata metadata = new ProjectAnalyzer(root).analyze();
        try (SourceAnalysisSession session = SourceBackends.spoon().open(ProjectModel.from(metadata))) {
            var method = session.methods().stream().filter(m -> owner.equals(m.typeName()))
                    .filter(m -> "focal".equals(m.methodName())).findFirst().orElseThrow();
            return session.extractContext(method.methodUri()).orElseThrow();
        }
    }

    private static void validateJson(Path root, String owner) throws Exception {
        ProjectMetadata metadata = new ProjectAnalyzer(root).analyze();
        try (SourceAnalysisSession session = SourceBackends.spoon().open(ProjectModel.from(metadata))) {
            var method = new MethodIdentifier(metadata).identify(session).stream()
                    .filter(m -> owner.equals(m.getTypeName())).filter(m -> "focal".equals(m.getMethodName()))
                    .findFirst().orElseThrow();
            MethodContext context = new ContextExtractor(metadata, null, session).extractContext(method);
            assertNotNull(context);
            Path jsonl = root.resolve("output.jsonl");
            new JsonGenerator(root).generateJsonLinesFile(Map.of(method.getMethodUri(), context), jsonl);
            ObjectMapper mapper = new ObjectMapper();
            Path schemaPath = Path.of(System.getProperty("user.dir")).getParent()
                    .resolve("schemas/method-context.schema.json");
            var schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                    .getSchema(mapper.readTree(schemaPath.toFile()));
            for (String line : Files.readAllLines(jsonl)) {
                JsonNode json = mapper.readTree(line);
                assertTrue(schema.validate(json).toString(), schema.validate(json).isEmpty());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> refs(SourceContext context) {
        return (List<Map<String, Object>>) context.javadocMetadata().get("javadoc_references");
    }

    private static void write(Path root, String file, String source) throws Exception {
        Path path = root.resolve("src/main/java").resolve(file);
        Files.createDirectories(path.getParent());
        Files.writeString(path, source);
    }

    private static void compile(Path root) throws Exception {
        Path classes = root.resolve("classes");
        Files.createDirectories(classes);
        List<String> command = new ArrayList<>(List.of("javac", "-d", classes.toString()));
        try (var paths = Files.walk(root.resolve("src/main/java"))) {
            paths.filter(p -> p.toString().endsWith(".java")).sorted().forEach(p -> command.add(p.toString()));
        }
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        assertEquals(output, 0, process.waitFor());
    }

    private static void delete(Path root) throws Exception {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
