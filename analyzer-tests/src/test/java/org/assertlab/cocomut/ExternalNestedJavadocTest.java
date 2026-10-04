package org.assertlab.cocomut;

import org.assertlab.cocomut.source.ProjectModel;
import org.assertlab.cocomut.source.SourceBackends;
import org.junit.Test;
import java.nio.file.*;
import java.util.*;
import javax.tools.ToolProvider;
import static org.junit.Assert.*;

public class ExternalNestedJavadocTest {
    @Test
    public void externalNestedLinksRespectVisibilityAndAmbiguity() throws Exception {
        Path root = Files.createTempDirectory("external-nested-javadoc");
        try {
            Path dependency = root.resolve("dependency");
            Files.createDirectories(dependency);
            Path outer = dependency.resolve("Outer.java");
            Files.writeString(outer, "package dep; public class Outer extends Base { public static class Inner { public static class Deep {} } } class Base { public static class Inherited {} }");
            Path depClasses = root.resolve("dep-classes"); Files.createDirectories(depClasses);
            assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                    "-d", depClasses.toString(), outer.toString()));
            Path sources = root.resolve("src/main/java"); Files.createDirectories(sources);
            Map<String,String> fixtures = new LinkedHashMap<>();
            fixtures.put("Qualified", "/** {@link java.util.Map.Entry} {@link java.util.Map.Missing} */");
            fixtures.put("Imported", "import java.util.Map; /** {@link Map.Entry} */");
            fixtures.put("Direct", "import java.util.Map.Entry; /** {@link Entry} */");
            fixtures.put("Dependency", "import dep.Outer; /** {@link Outer.Inner.Deep} {@link Outer.Inherited} */");
            fixtures.put("Ambiguous", "import java.util.*; import java.sql.*; /** {@link Date} */");
            fixtures.put("Shadowed", "import java.util.*; /** {@link Map.Entry} */");
            fixtures.put("Parameter", "import java.util.Map; /** {@link Map.Entry} */");
            for (var entry : fixtures.entrySet()) {
                String text = entry.getValue(); int doc = text.indexOf("/**");
                String generic = entry.getKey().equals("Parameter") ? "<Map>" : "";
                String nested = entry.getKey().equals("Shadowed") ? "static class Map {}" : "";
                Files.writeString(sources.resolve(entry.getKey()+".java"), "package demo;\n"
                        + text.substring(0,doc).replace(";", ";\n") + "public class " + entry.getKey() + generic + " { "
                        + nested + text.substring(doc) + " public void focal() {} }");
            }
            ProjectMetadata metadata = new ProjectMetadata.Builder().projectName("fixture").projectPath(root).sourceRoot(sources).sourceRoots(List.of(sources))
                    .buildSystem("unknown").dependencyClasspath(List.of(depClasses)).javaVersion("17").build();
            try (var session = SourceBackends.spoon().open(ProjectModel.from(metadata))) {
                assertEquals(7, session.methods().stream().filter(m -> m.methodName().equals("focal")).count());
                for (var method : session.methods()) {
                    if (!method.methodName().equals("focal")) continue;
                    @SuppressWarnings("unchecked")
                    var refs = (List<Map<String,Object>>) session.extractContext(method.methodUri()).orElseThrow()
                            .javadocMetadata().get("javadoc_references");
                    assertFalse(method.typeName(), refs.isEmpty());
                    for (var ref : refs) {
                        String target = ref.get("target").toString();
                        boolean unresolved = target.endsWith("Missing") || method.typeName().endsWith("Ambiguous")
                                || method.typeName().endsWith("Shadowed") || method.typeName().endsWith("Parameter");
                        assertEquals(method.typeName()+" "+target, unresolved ? "unresolved" : "external_symbol", ref.get("resolution"));
                        assertFalse(ref.containsKey("type_uri"));
                        if (!unresolved) {
                            boolean dep = method.typeName().endsWith("Dependency");
                            assertEquals(dep ? (target.endsWith("Inherited") ? "dep.Base$Inherited" : "dep.Outer$Inner$Deep")
                                    : "java.util.Map$Entry", ref.get("external_type"));
                            assertEquals(dep ? "external_library" : "external_jdk", ref.get("reference_domain"));
                        }
                    }
                }
            }
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
