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
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("status", "ERROR");
            report.put("failed_at_phase", 1);
            report.put("phase_1_error", e.getMessage());
            report.put("failure_codes", java.util.List.of(FailureCode.BUILD_FAILED.toString()));
            Path outputRoot = request.outputDirectory() != null
                    ? request.outputDirectory().toAbsolutePath().normalize()
                    : Path.of(System.getProperty("user.dir")).resolve("cocomut_output")
                            .resolve(sanitize(request.projectRoot().getFileName().toString()) + "-"
                                    + shortProjectHash(request.projectRoot()))
                            .toAbsolutePath().normalize();
            Files.createDirectories(outputRoot);
            ProjectMetadata metadata = failedMetadata(request);
            Path manifest = ExtractionManifest.write(outputRoot, metadata, null,
                    Map.of("kind", "project", "selector", "project"),
                    failedRequestHash(request), null, report);
            report.put("extraction_manifest_file", manifest.toString());
            return report;
        }
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
                .buildSkipped(request.skipBuild())
                .buildSandboxed(request.buildPolicy() == ContextRequest.BuildPolicy.EXTERNALLY_SANDBOXED_BUILD)
                .bytecodeAvailable(false)
                .bytecodeOrigin("none")
                .analysisCanProceed(false)
                .build();
    }

    private static String failedRequestHash(ContextRequest request) {
        String value = request.scope() + "|" + request.callGraphAlgorithm() + "|" + request.buildPolicy()
                + "|" + request.sourceSets() + "|" + request.targets();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return sha256Hex("cocomut-failed-request-fallback|" + value);
        }
    }

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            return "0".repeat(64);
        }
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
}
