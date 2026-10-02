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
    public void phaseOneExceptionHasSpecificFailureCode() {
        Orchestrator invalid = new Orchestrator(Path.of("/nonexistent/cocomut-project"));

        assertFalse(invalid.execute());

        assertTrue(String.valueOf(invalid.getExecutionReport().get("failure_codes"))
                .contains("METADATA_RESOLUTION_FAILED"));
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
    public void failedSourceAuditWritesFileEvidenceAndPartialReport() throws Exception {
        Path project = Files.createTempDirectory("cocomut-failed-source-audit-");
        try {
            Path root = Files.createDirectories(project.resolve("src/main/java"));
            Path classes = Files.createDirectories(project.resolve("target/classes"));
            Path good = root.resolve("Good.java");
            Files.writeString(good, "public class Good { public int kept() { return 1; } }");
            assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null,
                    "-d", classes.toString(), good.toString()));
            Files.writeString(root.resolve("Broken.java"), "class Broken { void lost( { } }");
            Path output = project.resolve("output");
            var report = ContextExtractorService.createDefault().extract(ContextRequest.builder()
                    .projectRoot(project).sourceSets(java.util.Set.of("main"))
                    .scope(ContextRequest.Scope.ALL).skipBuild(true).outputDirectory(output).build());
            assertEquals("PARTIAL", report.asMap().get("status"));
            assertEquals(2, report.asMap().get("source_files_discovered"));
            assertEquals(1, report.asMap().get("source_files_parsed"));
            assertEquals(1, report.asMap().get("source_files_failed"));
            assertTrue(String.valueOf(report.asMap().get("failure_codes")).contains("SOURCE_PARSE_FAILED"));
            assertTrue(report.usableRecordsEmitted());
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var failure = mapper.readTree(output.resolve("failed_source_files.jsonl").toFile());
            assertEquals("src/main/java/Broken.java", failure.path("source_file").asText());
            assertEquals("SOURCE_PARSE_FAILED", failure.path("failure_code").asText());
            var saved = mapper.readTree(output.resolve("extraction_report.json").toFile());
            assertEquals("PARTIAL", saved.path("status").asText());
        } finally { deleteRecursively(project); }
    }

    @Test
    public void sourceClasspathFallbackReportsAttemptsAndStrictFailureWithArtifacts() throws Exception {
        Path project = Files.createTempDirectory("cocomut-source-classpath-");
        try {
            Path root = Files.createDirectories(project.resolve("src/main/java/example"));
            Path stub = project.resolve("External.java");
            Path classes = Files.createDirectories(project.resolve("classes"));
            Files.writeString(stub, "package missing; public class External {} ");
            Path source = root.resolve("Sample.java");
            Files.writeString(source, "package example; import missing.External; public class Sample { private External external; public int value() { return 1; } }");
            assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null,
                    "-d", classes.toString(), stub.toString(), source.toString()));
            Path dependency = Files.createDirectories(project.resolve("dependency/missing"));
            Path dependencyClass = dependency.resolve("External.class");
            Files.move(classes.resolve("missing/External.class"), dependencyClass);
            byte[] bytecode = Files.readAllBytes(dependencyClass);
            int unsupportedMajor = Runtime.version().feature() + 44 + 10;
            bytecode[6] = (byte) (unsupportedMajor >>> 8);
            bytecode[7] = (byte) unsupportedMajor;
            Files.write(dependencyClass, bytecode);
            Files.delete(stub);
            ProjectMetadata metadata = new ProjectMetadata.Builder()
                    .projectName("source-classpath").projectPath(project).buildSystem("none").javaVersion("17")
                    .sourceRoot(root.getParent()).sourceRoots(java.util.List.of(root.getParent()))
                    .classpath(java.util.List.of(classes, dependency.getParent()))
                    .dependencyClasspath(java.util.List.of(dependency.getParent()))
                    .mainClassOutputs(java.util.List.of(classes))
                    .compiles(true).compileStatus("PRECOMPILED BYTECODE")
                    .bytecodeAvailable(true).analysisCanProceed(true).build();
            for (boolean mixed : new boolean[] {false, true}) {
                if (mixed) Files.writeString(root.resolve("Control.java"), "package example; public class Control { public int ok() { return 2; } }");
                for (boolean strict : new boolean[] {false, true}) {
                    Path output = project.resolve((strict ? "strict" : "default") + (mixed ? "-mixed" : ""));
                    Orchestrator extraction = new Orchestrator(ContextRequest.builder()
                            .projectRoot(project).scope(ContextRequest.Scope.ALL)
                            .sourceSets(java.util.Set.of("main")).outputDirectory(output)
                            .requireSourceClasspath(strict).maxSourceFiles(mixed ? 2 : null).build(), metadata);
                    assertFalse(extraction.execute());
                    ExtractionReport report = new ExtractionReport(extraction.getExecutionReport());
                    assertEquals(strict ? "FAILED" : "PARTIAL", report.asMap().get("status"));
                    assertEquals(mixed ? "mixed_limited" : "no_classpath", report.asMap().get("source_backend_mode"));
                    assertEquals(false, report.asMap().get("source_classpath_requirement_satisfied"));
                    assertTrue(report.usableRecordsEmitted());
                    assertTrue(Files.isRegularFile(report.jsonlFile()));
                    @SuppressWarnings("unchecked")
                    var attempts = (java.util.List<java.util.Map<String, Object>>) report.asMap().get("source_model_attempts");
                    assertTrue(attempts.stream().anyMatch(attempt -> "failed".equals(attempt.get("outcome"))
                            && String.valueOf(attempt.get("exception_class")).contains("UnsupportedClassVersionError")));
                    assertEquals("no_classpath", attempts.get(attempts.size() - 1).get("mode"));
                    assertEquals(strict ? 1 : 2, org.assertlab.cocomut.cli.CoCoMUTCommand.exitCodeFor(report));
                    var saved = new com.fasterxml.jackson.databind.ObjectMapper().readTree(output.resolve("extraction_report.json").toFile());
                    assertEquals(strict ? "FAILED" : "PARTIAL", saved.path("status").asText());
                }
            }
        } finally {
            deleteRecursively(project);
        }
    }

    @Test
    public void degradedCallGraphStillEmitsJsonlAndReportsPartial() throws Exception {
        Path project = Files.createTempDirectory("cocomut-degraded-call-graph-");
        try {
            Path sourceRoot = project.resolve("src/main/java/example");
            Path classOutput = project.resolve("classes");
            Files.createDirectories(sourceRoot);
            Files.createDirectories(classOutput);
            Files.writeString(sourceRoot.resolve("Sample.java"), """
                    package example;
                    /** Sample documentation. */
                    public class Sample { public String value() { return "ok"; } }
                    """);
            Files.write(classOutput.resolve("Broken.class"), new byte[] {0, 1, 2, 3});

            ContextRequest request = ContextRequest.builder()
                    .projectRoot(project)
                    .sourceSets(java.util.Set.of("main"))
                    .outputDirectory(project.resolve("output"))
                    .build();
            ProjectMetadata metadata = new ProjectMetadata.Builder()
                    .projectName("degraded-call-graph")
                    .projectPath(project)
                    .buildSystem("none")
                    .javaVersion("17")
                    .sourceRoot(project.resolve("src/main/java"))
                    .sourceRoots(java.util.List.of(project.resolve("src/main/java")))
                    .classpath(java.util.List.of(classOutput))
                    .mainClassOutputs(java.util.List.of(classOutput))
                    .compiles(true)
                    .compileStatus("PRECOMPILED BYTECODE")
                    .bytecodeAvailable(true)
                    .analysisCanProceed(true)
                    .build();

            Orchestrator degraded = new Orchestrator(request, metadata);
            assertFalse("A usable degraded extraction retains PARTIAL status", degraded.execute());

            ExtractionReport report = new ExtractionReport(degraded.getExecutionReport());
            assertTrue(report.partial());
            assertTrue(report.usableRecordsEmitted());
            assertEquals(Boolean.TRUE, report.asMap().get("phase_3_degraded"));
            assertTrue(Files.isRegularFile(report.jsonlFile()));
            assertTrue(report.failureCodes().contains("CALL_GRAPH_UNAVAILABLE"));
            @SuppressWarnings("unchecked")
            Map<String, Object> diagnostic = (Map<String, Object>) report.asMap().get("phase_3_initialization");
            assertNotNull("Initialization evidence must survive generator disposal", diagnostic);
            assertEquals("failed", diagnostic.get("status"));
            assertEquals("class_loading", diagnostic.get("stage"));
            assertNotNull(diagnostic.get("exception_class"));
            assertNotNull(diagnostic.get("message"));
            assertTrue(String.valueOf(report.asMap().get("phase_3_warning"))
                    .contains(String.valueOf(diagnostic.get("exception_class"))));
            assertTrue(((Number) report.asMap().get("phase_3_max_heap_bytes")).longValue() > 0);
            assertReportAndManifestPersistDiagnostics(report.asMap());
        } finally {
            deleteRecursively(project);
        }
    }

    @Test
    public void preflightBlockedBuildDoesNotClaimProcessExecutionOrTrustStaleBytecode() throws Exception {
        Path project = Files.createTempDirectory("cocomut-preflight-blocked-");
        try {
            Path sourceRoot = project.resolve("src/main/java");
            Path classOutput = project.resolve("build/classes/java/main");
            Files.createDirectories(sourceRoot);
            Files.createDirectories(classOutput);
            ProjectMetadata metadata = new ProjectMetadata.Builder()
                    .projectName("blocked")
                    .projectPath(project)
                    .buildSystem("gradle")
                    .javaVersion("17")
                    .sourceRoot(sourceRoot)
                    .sourceRoots(java.util.List.of(sourceRoot))
                    .mainClassOutputs(java.util.List.of(classOutput))
                    .classpath(java.util.List.of(classOutput))
                    .compileStatus("BUILD BLOCKED: ANDROID SDK UNAVAILABLE")
                    .buildAttempted(false)
                    .buildBlocked(true)
                    .buildFailureReason(BuildFailureReason.BUILD_FAILED_ANDROID_SDK_UNAVAILABLE)
                    .bytecodeAvailable(true)
                    .bytecodeOrigin("preexisting")
                    .analysisCanProceed(false)
                    .build();
            Orchestrator blocked = new Orchestrator(ContextRequest.builder().projectRoot(project).build(), metadata);

            assertFalse(blocked.execute());

            Map<String, Object> report = blocked.getExecutionReport();
            assertEquals(Boolean.FALSE, report.get("phase_1_build_attempted"));
            assertEquals(Boolean.TRUE, report.get("phase_1_build_blocked"));
            assertTrue(String.valueOf(report.get("failure_codes")).contains("BUILD_PREFLIGHT_BLOCKED"));
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
            assertTrue(((java.util.List<?>) testOnly.getExecutionReport().get("phase_1_build_attempts")).stream()
                    .map(String::valueOf)
                    .anyMatch(value -> value.contains("maven_dependency_classpath")));
            assertEquals("SUCCESS", testOnly.getExecutionReport()
                    .get("phase_1_maven_dependency_classpath_status"));
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
    public void resourceClassificationUsesCausesWithoutTreatingOrdinaryErrorsAsExhaustion() {
        for (int phase : new int[] {0, 1, 2, 3, 4, 5}) {
            assertEquals(FailureCode.ANALYSIS_RESOURCE_EXHAUSTED,
                    Orchestrator.failureCodeForUnhandledFailureForTest(phase, new OutOfMemoryError("heap")));
            assertEquals(FailureCode.ANALYSIS_RESOURCE_EXHAUSTED,
                    Orchestrator.failureCodeForUnhandledFailureForTest(phase,
                            new IllegalStateException("wrapped", new StackOverflowError("stack"))));
        }
        assertEquals(FailureCode.CALL_GRAPH_UNAVAILABLE,
                Orchestrator.failureCodeForUnhandledFailureForTest(3, new IllegalArgumentException("bytecode")));
        assertEquals(FailureCode.CALL_GRAPH_UNAVAILABLE,
                Orchestrator.failureCodeForUnhandledFailureForTest(3, new AssertionError("invariant")));
        assertEquals(FailureCode.CALL_GRAPH_UNAVAILABLE,
                Orchestrator.failureCodeForUnhandledFailureForTest(3, new LinkageError("incompatible class")));
        RuntimeException first = new RuntimeException("first");
        RuntimeException second = new RuntimeException("second", first);
        first.initCause(second);
        assertEquals("Cyclic cause chains must terminate", FailureCode.CALL_GRAPH_UNAVAILABLE,
                Orchestrator.failureCodeForUnhandledFailureForTest(3, first));
    }

    @Test
    public void initializationOutOfMemoryRemainsTerminalAndPersistsResourceCode() throws Exception {
        assertTerminalCallGraphResourceFailure(new OutOfMemoryError("simulated heap shortage"), true);
    }

    @Test
    public void generationOutOfMemoryRemainsTerminalAndPersistsResourceCode() throws Exception {
        assertTerminalCallGraphResourceFailure(new OutOfMemoryError("simulated heap shortage"), false);
    }

    @Test
    public void wrappedGenerationResourceFailureIsNotConvertedToPartial() throws Exception {
        assertTerminalCallGraphResourceFailure(
                new IllegalStateException("library wrapper", new OutOfMemoryError("simulated heap shortage")), false);
    }

    @Test
    public void wrappedInitializationResourceFailureIsNotConvertedToPartial() throws Exception {
        assertTerminalCallGraphResourceFailure(
                new IllegalStateException("library wrapper", new OutOfMemoryError("simulated heap shortage")), true);
    }

    @Test
    public void generationStackOverflowRemainsTerminalAndPersistsResourceCode() throws Exception {
        assertTerminalCallGraphResourceFailure(new StackOverflowError("simulated stack shortage"), false);
    }

    @Test
    public void ordinaryGenerationFailureRetainsRowsAndReportsTheException() throws Exception {
        Path output = Files.createTempDirectory("cocomut-callgraph-generation-failure-");
        try {
            ContextRequest request = ContextRequest.builder().projectRoot(testProjectPath)
                    .sourceSet("main").outputDirectory(output).build();
            ProjectMetadata metadata = new ProjectAnalyzer(testProjectPath).analyze();
            Orchestrator partial = new Orchestrator(request, metadata, null, (project, algorithm) ->
                    new CallGraphGenerator(project, algorithm) {
                        @Override
                        public Map<String, CallGraphResult> generateForMethods(java.util.List<MethodInfo> universe,
                                                                               java.util.List<MethodInfo> focal) {
                            throw new IllegalArgumentException("forced graph-generation failure");
                        }
                    });
            assertFalse(partial.execute());
            ExtractionReport report = new ExtractionReport(partial.getExecutionReport());
            assertTrue(report.partial());
            assertTrue(report.usableRecordsEmitted());
            assertTrue(report.failureCodes().contains("CALL_GRAPH_UNAVAILABLE"));
            assertEquals("java.lang.IllegalArgumentException", report.asMap().get("phase_3_exception_class"));
            assertEquals("forced graph-generation failure", report.asMap().get("phase_3_exception_message"));
            assertTrue(String.valueOf(report.asMap().get("phase_3_warning"))
                    .contains("java.lang.IllegalArgumentException"));
            assertReportAndManifestPersistDiagnostics(report.asMap());
        } finally {
            deleteRecursively(output);
        }
    }

    private void assertTerminalCallGraphResourceFailure(Throwable failure, boolean duringInitialization) throws Exception {
        Path output = Files.createTempDirectory("cocomut-callgraph-resource-");
        try {
            ContextRequest request = ContextRequest.builder().projectRoot(testProjectPath)
                    .sourceSet("main").outputDirectory(output).build();
            ProjectMetadata metadata = new ProjectAnalyzer(testProjectPath).analyze();
            Orchestrator exhausted = new Orchestrator(request, metadata, null, (project, algorithm) ->
                    new CallGraphGenerator(project, algorithm) {
                        @Override
                        public boolean initialize() {
                            if (duringInitialization) throwSimulatedFailure(failure);
                            return super.initialize();
                        }

                        @Override
                        public Map<String, CallGraphResult> generateForMethods(java.util.List<MethodInfo> universe,
                                                                               java.util.List<MethodInfo> focal) {
                            throwSimulatedFailure(failure);
                            throw new AssertionError("unreachable");
                        }
                    });
            assertFalse(exhausted.execute());
            ExtractionReport report = new ExtractionReport(exhausted.getExecutionReport());
            assertEquals("ERROR", report.status());
            assertEquals(Integer.valueOf(3), report.failedAtPhase());
            assertFalse("Resource exhaustion must not emit a successful or partial dataset", report.usableRecordsEmitted());
            assertTrue(report.methodsIdentified() > 0);
            assertEquals(java.util.List.of("ANALYSIS_RESOURCE_EXHAUSTED"), report.failureCodes());
            Throwable resource = failure instanceof Error ? failure : failure.getCause();
            assertEquals(resource.getClass().getName(), report.asMap().get("error_type"));
            assertTrue(String.valueOf(report.asMap().get("phase_3_error")).contains(resource.getMessage()));
            assertTrue(String.valueOf(report.asMap().get("error_stacktrace")).contains(resource.getClass().getName()));
            assertEquals(Boolean.FALSE, report.asMap().get("phase_3_available"));
            assertTrue(((Number) report.asMap().get("phase_3_max_heap_bytes")).longValue() > 0);
            assertNotNull(report.asMap().get("phase_1_project_bytecode_locations"));
            assertNotNull(report.asMap().get("phase_1_dependency_jars"));
            assertReportAndManifestPersistDiagnostics(report.asMap());
        } finally {
            deleteRecursively(output);
        }
    }

    private static void throwSimulatedFailure(Throwable failure) {
        if (failure instanceof Error error) throw error;
        throw (RuntimeException) failure;
    }

    private static void assertReportAndManifestPersistDiagnostics(Map<String, Object> report) throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var reportNode = mapper.readTree(Path.of(String.valueOf(report.get("extraction_report_file"))).toFile());
        var manifestNode = mapper.readTree(Path.of(String.valueOf(report.get("extraction_manifest_file"))).toFile());
        for (String key : java.util.List.of("status", "failure_codes", "failed_at_phase", "error_type", "phase_3_error",
                "phase_3_initialization", "phase_3_max_heap_bytes", "phase_3_exception_class", "phase_3_exception_message")) {
            if (report.containsKey(key)) {
                assertEquals("Persisted report must agree with in-memory diagnostics for " + key,
                        mapper.valueToTree(report.get(key)), reportNode.path(key));
                assertEquals("Manifest must retain report diagnostics for " + key,
                        reportNode.path(key), manifestNode.path("execution").path(key));
            }
        }
        Path schemaPath = Path.of(System.getProperty("user.dir")).getParent()
                .resolve("schemas/extraction-manifest.schema.json");
        var schema = com.networknt.schema.JsonSchemaFactory
                .getInstance(com.networknt.schema.SpecVersion.VersionFlag.V202012)
                .getSchema(mapper.readTree(schemaPath.toFile()));
        assertTrue("Failure manifests must conform to the current schema", schema.validate(manifestNode).isEmpty());
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
