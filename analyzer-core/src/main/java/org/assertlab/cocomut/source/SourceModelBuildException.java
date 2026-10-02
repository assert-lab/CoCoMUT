package org.assertlab.cocomut.source;

import java.io.IOException;
import java.util.List;

/** Terminal source-model failure retaining diagnostics from all attempted modes. */
public final class SourceModelBuildException extends IOException {
    private final List<SourceModelAttempt> attempts;

    public SourceModelBuildException(Throwable cause, List<SourceModelAttempt> attempts) {
        super("Source model construction failed", cause);
        this.attempts = List.copyOf(attempts);
    }

    public List<SourceModelAttempt> attempts() {
        return attempts;
    }
}
