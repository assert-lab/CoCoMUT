package org.assertlab.context4docugen;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Public method-selection model for API callers.
 */
public final class MethodSelection {
    public enum Kind {
        ALL,
        ENTRY_POINTS,
        SELECTED_CSV
    }

    private final Kind kind;
    private final Path selectedCsv;

    private MethodSelection(Kind kind, Path selectedCsv) {
        this.kind = Objects.requireNonNull(kind, "kind cannot be null");
        this.selectedCsv = selectedCsv;
    }

    public static MethodSelection all() {
        return new MethodSelection(Kind.ALL, null);
    }

    public static MethodSelection entryPoints() {
        return new MethodSelection(Kind.ENTRY_POINTS, null);
    }

    public static MethodSelection fromCsv(Path selectedCsv) {
        return new MethodSelection(Kind.SELECTED_CSV,
                Objects.requireNonNull(selectedCsv, "selectedCsv cannot be null"));
    }

    public Kind kind() {
        return kind;
    }

    public Path selectedCsv() {
        return selectedCsv;
    }

    AnalysisOptions.Scope toScope() {
        return switch (kind) {
            case ALL -> AnalysisOptions.Scope.ALL;
            case ENTRY_POINTS -> AnalysisOptions.Scope.ENTRY_POINTS;
            case SELECTED_CSV -> AnalysisOptions.Scope.SELECTED;
        };
    }
}
