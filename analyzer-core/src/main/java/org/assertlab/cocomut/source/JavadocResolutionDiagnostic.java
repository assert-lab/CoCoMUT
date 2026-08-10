package org.assertlab.cocomut.source;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/** Stable diagnostic vocabulary shared by inheritance resolution and the JSON schema. */
enum JavadocResolutionDiagnostic {
    HIERARCHY_RESOLUTION_LIMIT("hierarchy_resolution_limit"),
    HIERARCHY_TRAVERSAL_LIMIT("hierarchy_traversal_limit"),
    HIERARCHY_TRUNCATED("hierarchy_truncated"),
    SUPERTYPE_DECLARATION_UNAVAILABLE("supertype_declaration_unavailable"),
    SOURCE_EVIDENCE_UNAVAILABLE("source_evidence_unavailable"),
    SOURCE_DOCUMENTATION_UNAVAILABLE("source_documentation_unavailable"),
    INHERITDOC_TARGET_NOT_OVERRIDDEN("inheritdoc_target_not_overridden"),
    INHERITDOC_TARGET_AMBIGUOUS("inheritdoc_target_ambiguous"),
    INHERITDOC_CORRESPONDING_ITEM_MISSING("inheritdoc_corresponding_item_missing"),
    THROWS_MULTIPLE_INHERITDOC("throws_multiple_inheritdoc");

    private final String id;

    JavadocResolutionDiagnostic(String id) {
        this.id = id;
    }

    String id() {
        return id;
    }

    static Set<String> ids() {
        return Arrays.stream(values()).map(JavadocResolutionDiagnostic::id)
                .collect(Collectors.toUnmodifiableSet());
    }
}
