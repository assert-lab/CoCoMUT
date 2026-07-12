package org.assertlab.cocomut;

import java.util.List;

/** One bounded project-build invocation recorded for reproducibility. */
public record BuildAttempt(
        List<String> command,
        String javaHome,
        String javaVersion,
        String javaEvidence,
        int exitCode,
        boolean timedOut,
        BuildFailureReason failureReason) {

    public BuildAttempt {
        command = command == null ? List.of() : List.copyOf(command);
        javaHome = javaHome == null ? "" : javaHome;
        javaVersion = javaVersion == null ? "inherited" : javaVersion;
        javaEvidence = javaEvidence == null ? "inherited_environment" : javaEvidence;
        failureReason = failureReason == null
                ? BuildFailureReason.BUILD_FAILED_UNKNOWN_ERROR : failureReason;
    }
}
