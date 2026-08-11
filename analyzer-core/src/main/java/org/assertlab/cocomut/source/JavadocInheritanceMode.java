package org.assertlab.cocomut.source;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

enum JavadocInheritanceMode {
    DECLARED("declared"),
    EXPLICIT_INHERITDOC("explicit_inheritdoc"),
    IMPLICIT_MISSING_ITEM("implicit_missing_item"),
    UNKNOWN("unknown");

    private final String id;

    JavadocInheritanceMode(String id) {
        this.id = id;
    }

    String id() {
        return id;
    }

    static Set<String> ids() {
        return Arrays.stream(values())
                .map(JavadocInheritanceMode::id)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }
}
