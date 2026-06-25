package org.assertlab.cocomut;

import org.assertlab.cocomut.adapter.ProjectAdapter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Internal bridge between the public service API and the extraction pipeline.
 */
final class AnalyzerFacade {

    private AnalyzerFacade() {
    }

    static Map<String, Object> analyze(Path projectPath) throws IOException {
        return analyze(ContextRequest.defaults(projectPath));
    }

    static Map<String, Object> analyze(ContextRequest request) throws IOException {
        Objects.requireNonNull(request, "request cannot be null");

        try {
            ProjectAdapter adapter = ProjectAdapter.of(request.projectRoot());
            System.out.printf("[AnalyzerFacade] Project: %s | Adapter: %s | Scope: %s%n",
                    request.projectRoot().getFileName(),
                    adapter.getClass().getSimpleName(),
                    request.scope());
            ProjectMetadata metadata = adapter.toMetadata(request);
            Orchestrator orchestrator = new Orchestrator(request, metadata);
            orchestrator.execute();
            return orchestrator.getExecutionReport();
        } catch (Exception e) {
            ExtractionManifest.GitInfo gitAtStart = ExtractionManifest.captureGitInfo(request.projectRoot());
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("status", "ERROR");
            report.put("failed_at_phase", 1);
            report.put("phase_1_error", e.getMessage());
            report.put("failure_codes", java.util.List.of(FailureCode.METADATA_RESOLUTION_FAILED.toString()));
            Path outputRoot = request.outputDirectory() != null
                    ? request.outputDirectory().toAbsolutePath().normalize()
                    : Path.of(System.getProperty("user.dir")).resolve("cocomut_output")
                            .resolve(sanitize(request.projectRoot().getFileName().toString()) + "-"
                                    + shortProjectHash(request.projectRoot()))
                            .toAbsolutePath().normalize();
            Files.createDirectories(outputRoot);
            ProjectMetadata metadata = failedMetadata(request);
            Path manifest = ExtractionManifest.write(outputRoot, metadata, null,
                    selection(request),
                    RequestFingerprint.hash(request), null, report, gitAtStart);
            report.put("extraction_manifest_file", manifest.toString());
            writeExecutionReport(outputRoot, report);
            return report;
        }
    }

    private static Map<String, Object> selection(ContextRequest request) {
        Map<String, Object> selection = new LinkedHashMap<>();
        selection.put("kind", request.targets().isEmpty() ? "project" : "target");
        selection.put("selector", request.targets().isEmpty() ? "project"
                : request.targets().stream().map(SymbolTarget::prefixedUri).toList());
        selection.put("packages", request.packages());
        selection.put("classes", request.classes());
        selection.put("methods", request.methods());
        selection.put("source_sets", request.sourceSets());
        return selection;
    }

    private static ProjectMetadata failedMetadata(ContextRequest request) {
        return new ProjectMetadata.Builder()
                .projectName(request.projectRoot().getFileName().toString())
                .projectPath(request.projectRoot())
                .buildSystem("unknown")
                .javaVersion("unknown")
                .sourceRoot(request.projectRoot())
                .sourceRoots(java.util.List.of())
                .testSourceRoots(java.util.List.of())
                .classpath(java.util.List.of())
                .mainClassOutputs(java.util.List.of())
                .testClassOutputs(java.util.List.of())
                .projectArtifactJars(java.util.List.of())
                .dependencyClasspath(java.util.List.of())
                .compileStatus("METADATA RESOLUTION FAILED")
                .buildPolicy(request.buildPolicy())
                .allowPreexistingBytecodeAfterBuildFailure(request.allowPreexistingBytecodeAfterBuildFailure())
                .buildSkipped(request.skipBuild())
                .buildSandboxed(request.buildPolicy() == ContextRequest.BuildPolicy.EXTERNALLY_SANDBOXED_BUILD)
                .explicitClassOutputDirs(request.classOutputDirs())
                .explicitTestClassOutputDirs(request.testClassOutputDirs())
                .explicitProjectJars(request.projectJars())
                .explicitDependencyJars(request.dependencyJars())
                .explicitClasspathFiles(request.classpathFiles())
                .bytecodeAvailable(false)
                .bytecodeOrigin("none")
                .analysisCanProceed(false)
                .build();
    }

    private static String shortProjectHash(Path projectRoot) {
        String normalized = projectRoot.toAbsolutePath().normalize().toString();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 4);
        } catch (Exception e) {
            return Integer.toHexString(normalized.hashCode());
        }
    }

    private static String sanitize(String value) {
        return value.replaceAll("[^A-Za-z0-9._#()\\-]+", "_");
    }

    private static void writeExecutionReport(Path outputRoot, Map<String, Object> report) throws IOException {
        Path reportPath = outputRoot.resolve("extraction_report.json");
        report.put("extraction_report_file", reportPath.toString());
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        mapper.writerWithDefaultPrettyPrinter().writeValue(reportPath.toFile(), report);
    }
}
