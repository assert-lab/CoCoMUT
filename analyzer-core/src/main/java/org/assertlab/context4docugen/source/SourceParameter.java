package org.assertlab.context4docugen.source;

import java.util.List;

public record SourceParameter(
        String name,
        String type,
        List<String> modifiers,
        List<String> annotations) {
    public SourceParameter {
        modifiers = modifiers != null ? List.copyOf(modifiers) : List.of();
        annotations = annotations != null ? List.copyOf(annotations) : List.of();
    }
}
