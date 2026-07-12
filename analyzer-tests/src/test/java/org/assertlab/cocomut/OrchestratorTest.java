package org.assertlab.cocomut;

import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Comparator;
import javax.tools.ToolProvider;

import static org.junit.Assert.*;

/**
 * Tests for {@link Orchestrator}. Pipeline tests use {@link TestFixtures} so they
 * do not scan the entire workspace.
 */
@Category(FastTests.class)
public class OrchestratorTest {
    private Path testProjectPath;
    private Orchestrator orchestrator;

    @Before
    public void setUp() throws Exception {
        TestFixtures.ensureMinimalMavenProjectCompiled();
        testProjectPath = TestFixtures.minimalMavenProjectRoot();
        orchestrator = new Orchestrator(testProjectPath);
    }

    @Test
    public void testOrchestratorCreation() {
        assertNotNull("Orchestrator should be created", orchestrator);

        assertNotNull("Should be able to get execution report", orchestrator.getExecutionReport());
    }

    @Test
    public void testValidateConfiguration() {
        boolean valid = orchestrator.validateConfiguration();
        assertTrue("Should validate configuration for existing project", valid);
    }

    @Test
    public void testOrchestratorExecute() {
        orchestrator.execute();

        Map<String, Object> report = orchestrator.getExecutionReport();

        assertNotNull("Should have execution report", report);
        assertTrue("Report should contain status", report.containsKey("status"));
        assertEquals("Pipeline should have five phases", 5, report.get("pipeline_phases"));
    }

    @Test
    public void testExecutionReport() {
        orchestrator.execute();

        Map<String, Object> report = orchestrator.getExecutionReport();

        assertNotNull("Should have report", report);
        assertNotNull("Should have status", report.get("status"));
        assertNotNull("Should have pipeline phase count", report.get("pipeline_phases"));
    }

    @Test
    public void testProjectMetadataExtraction() {
        orchestrator.execute();

        ProjectMetadata metadata = orchestrator.getProjectMetadata();

        assertNotNull("Metadata should be present for fixture", metadata);
        assertNotNull("Should have project name", metadata.getProjectName());
        assertNotNull("Should have build system", metadata.getBuildSystem());
    }

    @Test
    public void testMethodInfosRetrieval() {
        orchestrator.execute();

        assertNotNull("Should have methods list", orchestrator.getMethodInfos());
        assertFalse("Fixture should yield methods", orchestrator.getMethodInfos().isEmpty());
    }

    @Test
    public void testExecutionReportHasAllKeys() {
        orchestrator.execute();

        Map<String, Object> report = orchestrator.getExecutionReport();

        assertTrue("Report should have status", report.containsKey("status"));
        assertTrue("Report should have execution data", report.size() > 1);
    }

    @Test
    public void testInvalidProjectPath() {
        Orchestrator invalidOrch = new Orchestrator(Path.of("/nonexistent/path"));

        boolean valid = invalidOrch.validateConfiguration();

        assertFalse("Should invalidate non-existent path", valid);
    }

    @Test
    public void testExecutionReportPrint() {
        orchestrator.execute();

        orchestrator.printReport();

        assertTrue("Should complete print without error", true);
    }

    @Test
    public void testExecutionReportContainsPhaseInfo() {
        orchestrator.execute();

        Map<String, Object> report = orchestrator.getExecutionReport();

        assertTrue("Report should contain execution information",
                report.size() > 0);
    }

    @Test
    public void testOrchestratorNullProjectPath() {
        try {
            new Orchestrator((Path) null);
            fail("Should throw NullPointerException");
        } catch (NullPointerException e) {
            assertTrue("Should catch null project path", true);
        }
    }

    @Test
    public void testExecutionReportImmutability() {
        orchestrator.execute();

        Map<String, Object> report1 = orchestrator.getExecutionReport();
        Map<String, Object> report2 = orchestrator.getExecutionReport();

        assertEquals("Reports should be equivalent", report1.keySet(), report2.keySet());
    }

    @Test
    public void testUncompiledProjectFailsWithoutBytecode() throws Exception {
        Path project = Files.createTempDirectory("cocomut-uncompiled-");
        try {
            Path sourceDir = project.resolve("src/main/java/example");
            Files.createDirectories(sourceDir);
            Files.writeString(sourceDir.resolve("SourceOnly.java"), """
                    package example;

                    public class SourceOnly {
                        /**
                         * Greets a person.
                         *
                         * @param name person name
                         * @return greeting text
                         */
                        public String greet(String name) {
                            return "Hello " + name;
                        }
                    }
                    """);

            Orchestrator sourceOnly = new Orchestrator(project);
            assertFalse("Source-only project should fail without compiled bytecode", sourceOnly.execute());

            Map<String, Object> report = sourceOnly.getExecutionReport();
            assertEquals("FAILED", report.get("status"));
            assertEquals(1, report.get("failed_at_phase"));
            assertEquals(false, report.get("phase_1_compiles"));
            assertEquals(true, report.get("phase_1_source_available"));
            assertTrue(String.valueOf(report.get("failure_codes"))
                    .contains("PROJECT_BYTECODE_UNAVAILABLE"));
            assertTrue(String.valueOf(report.get("phase_1_error")).contains("NO PROJECT BYTECODE"));
            assertTrue(Files.isRegularFile(Path.of(String.valueOf(report.get("extraction_report_file")))));
        } finally {
            deleteRecursively(project);
        }
    }

    @Test
    public void successfulEmptyBuildReportsUnavailableProjectBytecode() throws Exception {
        Path project = Files.createTempDirectory("cocomut-empty-maven-");
        try {
            write(project.resolve("pom.xml"), """
                    <project><modelVersion>4.0.0</modelVersion>
                      <groupId>demo</groupId><artifactId>empty</artifactId><version>1</version>
                    </project>
                    """);
            Orchestrator empty = new Orchestrator(ContextRequest.builder()
                    .projectRoot(project)
                    .allowUnsandboxedBuild()
                    .build());

            assertFalse(empty.execute());
            Map<String, Object> report = empty.getExecutionReport();
            assertEquals(true, report.get("phase_1_build_succeeded"));
            assertTrue(String.valueOf(report.get("failure_codes"))
                    .contains("PROJECT_BYTECODE_UNAVAILABLE"));
            assertFalse(String.valueOf(report.get("failure_codes")).contains("BUILD_FAILED"));
        } finally {
            deleteRecursively(project);
        }
    }

    @Test
    public void normalMavenTestSourceSetIsParsedAndCompiled() throws Exception {
        Path project = Files.createTempDirectory("cocomut-maven-test-source-set-");
        try {
            write(project.resolve("pom.xml"), """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <project xmlns="http://maven.apache.org/POM/4.0.0"
                             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                             xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                      <modelVersion>4.0.0</modelVersion>
                      <groupId>demo</groupId>
                      <artifactId>test-source-set</artifactId>
                      <version>1.0-SNAPSHOT</version>
                      <properties>
                        <maven.compiler.source>17</maven.compiler.source>
                        <maven.compiler.target>17</maven.compiler.target>
                        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
                      </properties>
                    </project>
                    """);
            write(project.resolve("src/main/java/demo/MainOnly.java"), """
                    package demo;
                    public class MainOnly {
                        public String value() { return "main"; }
                    }
                    """);
            write(project.resolve("src/test/java/demo/MainOnlyTest.java"), """
                    package demo;
                    public class MainOnlyTest {
                        public String testHelper() { return new MainOnly().value(); }
                    }
                    """);

            Orchestrator testOnly = new Orchestrator(ContextRequest.builder()
                    .projectRoot(project)
                    .sourceSets(java.util.Set.of("test"))
                    .allowUnsandboxedBuild()
                    .build());

            assertTrue(String.valueOf(testOnly.getExecutionReport()), testOnly.execute());
            assertEquals("SUCCESS", testOnly.getExecutionReport().get("status"));
            assertTrue(testOnly.getExecutionReport().get("phase_1_build_attempts") instanceof java.util.List<?>);
            assertFalse(((java.util.List<?>) testOnly.getExecutionReport().get("phase_1_build_attempts")).isEmpty());
            assertTrue(testOnly.getExecutionReport().get("phase_1_build_command") instanceof java.util.List<?>);
            assertTrue(((java.util.List<?>) testOnly.getExecutionReport().get("phase_1_build_command"))
                    .contains("test-compile"));
            assertTrue(testOnly.getMethodInfos().stream()
                    .anyMatch(method -> "test".equals(method.getSourceSet())
                            && "testHelper".equals(method.getMethodName())));
            assertTrue("Maven test bytecode should be part of the bytecode model",
                    ((Number) testOnly.getExecutionReport().get("phase_1_class_output_dirs")).intValue() >= 2);
        } finally {
            deleteRecursively(project);
        }
    }

    @Test
    public void emptySelectionFailsExplicitly() {
        Orchestrator missing = new Orchestrator(ContextRequest.builder()
                .projectRoot(testProjectPath)
                .methods(java.util.Set.of("doesNotExist"))
                .build());

        assertFalse(missing.execute());
        Map<String, Object> report = missing.getExecutionReport();
        assertEquals("FAILED", report.get("status"));
        assertEquals(2, report.get("failed_at_phase"));
        assertTrue(String.valueOf(report.get("failure_codes")).contains("EMPTY_SELECTION"));
    }

    @Test
    public void unhandledPhaseFailureCodesAreSpecific() {
        assertEquals(FailureCode.METADATA_RESOLUTION_FAILED,
                Orchestrator.failureCodeForUnhandledFailureForTest(1));
        assertEquals(FailureCode.SOURCE_ANALYSIS_FAILED,
                Orchestrator.failureCodeForUnhandledFailureForTest(2));
        assertEquals(FailureCode.CALL_GRAPH_UNAVAILABLE,
                Orchestrator.failureCodeForUnhandledFailureForTest(3));
        assertEquals(FailureCode.CONTEXT_EXTRACTION_FAILED,
                Orchestrator.failureCodeForUnhandledFailureForTest(4));
        assertEquals(FailureCode.JSON_GENERATION_FAILED,
                Orchestrator.failureCodeForUnhandledFailureForTest(5));
        assertEquals(FailureCode.ERROR,
                Orchestrator.failureCodeForUnhandledFailureForTest(0));
    }

    @Test
    public void partialFocalBytecodeMatchingIsAWarningNotFailure() throws Exception {
        Path project = Files.createTempDirectory("cocomut-partial-bytecode-");
        try {
            Path sourceDir = project.resolve("src/main/java/demo");
            Path classOutput = project.resolve("target/classes");
            Path compiledSource = sourceDir.resolve("CompiledOnly.java");
            write(compiledSource, """
                    package demo;
                    public class CompiledOnly {
                        public static void main(String[] args) { new CompiledOnly().publicMethod(); }
                        public String publicMethod() { return helper(); }
                        private String helper() { return "compiled"; }
                    }
                    """);
            write(sourceDir.resolve("SourceOnly.java"), """
                    package demo;
                    public class SourceOnly {
                        public String missingBytecode() { return "source"; }
                    }
                    """);
            Files.createDirectories(classOutput);
            var compiler = ToolProvider.getSystemJavaCompiler();
            assertNotNull("Tests require a JDK compiler", compiler);
            int compileExit = compiler.run(null, null, null,
                    "-d", classOutput.toString(), compiledSource.toString());
            assertEquals("Fixture source should compile", 0, compileExit);

            Orchestrator partial = new Orchestrator(ContextRequest.builder()
                    .projectRoot(project)
                    .sourceRoot(sourceDir.getParent())
                    .classOutputDir(classOutput)
                    .build());

            assertTrue(partial.execute());
            Map<String, Object> report = partial.getExecutionReport();
            assertEquals("SUCCESS", report.get("status"));
            assertEquals(java.util.List.of("NONE"), report.get("failure_codes"));
            assertEquals(Boolean.TRUE, report.get("phase_3_call_graph_artifact_exists"));
            long matched = ((Number) report.get("phase_3_focal_methods_matched_to_bytecode")).longValue();
            long selected = ((Number) report.get("phase_2_methods_identified")).longValue();
            assertTrue("Fixture should contain both matched and unmatched source methods",
                    matched > 0 && matched < selected);
            assertTrue(String.valueOf(report.get("phase_3_warning"))
                    .contains("did not receive matched bytecode call graph results"));
        } finally {
            deleteRecursively(project);
        }
    }

    @Test
    public void zeroFocalBytecodeMatchesRequirePartialStatus() {
        assertTrue(Orchestrator.requiresPartialForBytecodeMatching(0, 5));
        assertFalse(Orchestrator.requiresPartialForBytecodeMatching(1, 5));
        assertFalse(Orchestrator.requiresPartialForBytecodeMatching(0, 0));
    }

    private static void deleteRecursively(Path root) throws Exception {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void write(Path path, String text) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, text);
    }
}
