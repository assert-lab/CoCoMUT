package org.assertlab.cocomut.source;

import org.assertlab.cocomut.ProjectMetadata;
import org.junit.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import static org.junit.Assert.*;

public class SourceModelCompletenessTest {
    @Test
    public void retainsJava25UnnamedLambdaAndUnrelatedMethods() throws Exception {
        Path project = Files.createTempDirectory("cocomut-unnamed-lambda-");
        try {
            for (String name : List.of("NamedLambda", "UnnamedLambda")) {
                String parameter = name.equals("NamedLambda") ? "ignored" : "_";
                Files.writeString(project.resolve(name + ".java"), """
                        import java.util.List;
                        import java.util.function.Function;
                        import java.util.stream.Collectors;
                        public final class %s {
                            public String transform(List<String> values) {
                                return values.stream().collect(Collectors.toMap(Function.identity(), %s -> "value")).toString();
                            }
                            public int retained() { return 1; }
                        }
                        """.formatted(name, parameter));
            }
            try (var session = SourceBackends.spoon().open(project(project, "25"))) {
                assertEquals(4, session.methods().stream().filter(method -> !method.constructor()).count());
                assertEquals(2, session.parseStats().parsed());
                assertEquals(0, session.parseStats().failed());
                assertTrue(session.parseStats().modelAttempts().stream().anyMatch(attempt ->
                        attempt.effectiveCompliance() == 25 && attempt.outcome().equals("success")));
            }
        } finally { remove(project); }
    }

    @Test
    public void addingRocketmqCompilationUnitPreservesTargetUris() throws Exception {
        Path project = Files.createTempDirectory("cocomut-rocketmq-reduction-");
        try {
            copyFixture(project, "ControllerMetricsConstant.java");
            Set<String> isolated;
            try (var session = SourceBackends.spoon().open(project(project, "8"))) {
                isolated = targetUris(session);
                assertEquals(5, isolated.size());
            }
            copyFixture(project, "ControllerMetricsManager.java");
            try (var session = SourceBackends.spoon().open(project(project, "8"))) {
                assertEquals(isolated, targetUris(session));
                assertEquals(0, session.parseStats().failed());
                assertEquals(List.of(project.resolve("ControllerMetricsConstant.java")), session.parseStats().recoveredFiles());
            }
        } finally { remove(project); }
    }

    @Test
    public void separateRootsAndOverlappingRootsPreserveTargetDeclarations() throws Exception {
        Path project = Files.createTempDirectory("cocomut-multi-root-reduction-");
        try {
            Path first = Files.createDirectories(project.resolve("first"));
            Path second = Files.createDirectories(project.resolve("second"));
            copyFixture(first, "ControllerMetricsConstant.java");
            copyFixture(second, "ControllerMetricsManager.java");
            ProjectModel model = ProjectModel.from(new ProjectMetadata.Builder()
                    .projectName("multi-root").projectPath(project).buildSystem("none").javaVersion("8")
                    .sourceRoot(first).sourceRoots(List.of(first, second, project)).build());
            try (var session = SourceBackends.spoon().open(model)) {
                assertEquals(5, session.methods().stream().filter(method -> !method.constructor()
                        && method.sourceFile().equals(first.resolve("ControllerMetricsConstant.java"))).count());
                assertEquals(2, session.parseStats().discovered());
                assertEquals(2, session.parseStats().parsed());
                assertEquals(0, session.parseStats().failed());
            }
        } finally { remove(project); }
    }

    @Test
    public void malformedSourceIsFailedWhileMethodlessFilesRemainParsed() throws Exception {
        Path project = Files.createTempDirectory("cocomut-syntax-coverage-");
        try {
            Files.writeString(project.resolve("Broken.java"), "class Broken { void lost( { } }");
            Files.writeString(project.resolve("Marker.java"), "interface Marker {} ");
            Files.writeString(project.resolve("package-info.java"), "/** Docs. */ package example;");
            try (var session = SourceBackends.spoon().open(project(project, "17"))) {
                assertEquals(3, session.parseStats().discovered());
                assertEquals(2, session.parseStats().parsed());
                assertEquals(List.of(project.resolve("Broken.java")), session.parseStats().failedFiles());
                assertTrue(session.parseStats().modelAttempts().stream().anyMatch(attempt ->
                        attempt.diagnosticCode().equals("source_syntax_error")));
                assertTrue(session.parseStats().recoveredFiles().isEmpty());
            }
        } finally { remove(project); }
    }

    @Test
    public void auditDistinguishesOverloadsConstructorsAndNestedMethods() throws Exception {
        Path project = Files.createTempDirectory("cocomut-declaration-coverage-");
        try {
            Files.writeString(project.resolve("Sample.java"), """
                    class Sample {
                        Sample() {}
                        @Deprecated private int same() { return 1; }
                        int same(int arg) {
                            class Local { Local() {} int same() { return 3; } }
                            return arg;
                        }
                        class Nested { int same() { return 2; } }
                    }
                    """);
            try (var session = SourceBackends.spoon().open(project(project, "17"))) {
                assertEquals(6, session.methods().size());
                assertEquals(1, session.parseStats().parsed());
                assertEquals(0, session.parseStats().failed());
                assertTrue(session.parseStats().recoveredFiles().isEmpty());
            }
        } finally { remove(project); }
    }

    private static Set<String> targetUris(SourceAnalysisSession session) throws Exception {
        return session.methods().stream().filter(method -> !method.constructor()
                && method.methodUri().startsWith("ControllerMetricsConstant.java#"))
                .map(SourceMethod::methodUri).collect(Collectors.toSet());
    }

    private static void copyFixture(Path project, String name) throws Exception {
        try (var input = SourceModelCompletenessTest.class.getResourceAsStream("/fixtures/rocketmq-model-loss/" + name)) {
            assertNotNull(input);
            Files.copy(input, project.resolve(name));
        }
    }

    private static ProjectModel project(Path root, String javaVersion) {
        return ProjectModel.from(new ProjectMetadata.Builder().projectName("completeness")
                .projectPath(root).buildSystem("none").javaVersion(javaVersion)
                .sourceRoot(root).sourceRoots(List.of(root)).build());
    }

    private static void remove(Path root) throws Exception {
        try (var files = Files.walk(root)) {
            for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(file);
        }
    }
}
