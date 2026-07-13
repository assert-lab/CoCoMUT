package org.assertlab.cocomut;

import java.util.List;

/** One bounded project-build invocation recorded for reproducibility. */
public record BuildAttempt(
        String action,
        List<String> command,
        List<String> components,
        String javaHome,
        String javaVersion,
        String javaEvidence,
        int exitCode,
        boolean timedOut,
        boolean changedEnvironment,
        BuildFailureReason failureReason) {

    public BuildAttempt {
        action = action == null || action.isBlank() ? "build" : action;
        command = command == null ? List.of() : List.copyOf(command);
        components = components == null ? List.of() : List.copyOf(components);
        javaHome = javaHome == null ? "" : javaHome;
        javaVersion = javaVersion == null ? "inherited" : javaVersion;
        javaEvidence = javaEvidence == null ? "inherited_environment" : javaEvidence;
        failureReason = failureReason == null
                ? BuildFailureReason.BUILD_FAILED_UNKNOWN_ERROR : failureReason;
    }

    public BuildAttempt(List<String> command, String javaHome, String javaVersion, String javaEvidence,
                        int exitCode, boolean timedOut, BuildFailureReason failureReason) {
        this("build", command, List.of(), javaHome, javaVersion, javaEvidence, exitCode, timedOut,
                false, failureReason);
    }
}
