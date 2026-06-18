package org.assertlab.cocox;

import java.util.Objects;

/**
 * Public method-selection model for API callers.
 */
public final class MethodSelection {
    public enum Kind {
        ALL,
        ENTRY_POINTS
    }

    private final Kind kind;

    private MethodSelection(Kind kind) {
        this.kind = Objects.requireNonNull(kind, "kind cannot be null");
    }

    public static MethodSelection all() {
        return new MethodSelection(Kind.ALL);
    }

    public static MethodSelection entryPoints() {
        return new MethodSelection(Kind.ENTRY_POINTS);
    }

    public Kind kind() {
        return kind;
    }

    AnalysisOptions.Scope toScope() {
        return switch (kind) {
            case ALL -> AnalysisOptions.Scope.ALL;
            case ENTRY_POINTS -> AnalysisOptions.Scope.ENTRY_POINTS;
        };
    }
}
