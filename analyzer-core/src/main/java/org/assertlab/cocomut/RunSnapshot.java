package org.assertlab.cocomut;

/**
 * Immutable run identity captured before any target-project build or model
 * resolution. Keeping this separate from phase-one metadata avoids reporting
 * post-build Git state or rehashing mutable request artifacts later.
 */
final class RunSnapshot {
    private final long startMillis;
    private final ExtractionManifest.GitInfo projectGitAtStart;
    private final ExtractionManifest.GitInfo toolGitAtStart;
    private final String requestHash;

    private RunSnapshot(long startMillis,
                        ExtractionManifest.GitInfo projectGitAtStart,
                        ExtractionManifest.GitInfo toolGitAtStart,
                        String requestHash) {
        this.startMillis = startMillis;
        this.projectGitAtStart = projectGitAtStart;
        this.toolGitAtStart = toolGitAtStart;
        this.requestHash = requestHash;
    }

    static RunSnapshot capture(ContextRequest request) {
        long start = System.currentTimeMillis();
        return new RunSnapshot(
                start,
                ExtractionManifest.captureGitInfo(request.projectRoot()),
                ExtractionManifest.captureGitInfo(java.nio.file.Path.of(System.getProperty("user.dir"))),
                RequestFingerprint.hash(request));
    }

    long startMillis() {
        return startMillis;
    }

    ExtractionManifest.GitInfo projectGitAtStart() {
        return projectGitAtStart;
    }

    ExtractionManifest.GitInfo toolGitAtStart() {
        return toolGitAtStart;
    }

    String requestHash() {
        return requestHash;
    }
}
