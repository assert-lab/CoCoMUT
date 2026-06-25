package org.assertlab.cocomut;

import java.util.List;

/**
 * Observable result of the Gradle metadata task. A successful build and a
 * successful metadata model are separate facts.
 */
public record GradleModelReport(boolean attempted,
                                boolean succeeded,
                                boolean timedOut,
                                boolean partial,
                                int resolvedProjects,
                                List<String> diagnostics,
                                List<String> failedConfigurations) {
    public GradleModelReport {
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        failedConfigurations = failedConfigurations == null ? List.of() : List.copyOf(failedConfigurations);
    }

    public static GradleModelReport notAttempted() {
        return new GradleModelReport(false, false, false, false, 0, List.of(), List.of());
    }

    public static GradleModelReport skipped(String reason) {
        return new GradleModelReport(false, false, false, false, 0, List.of(reason), List.of());
    }
}
