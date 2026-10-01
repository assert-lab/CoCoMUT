package org.assertlab.cocomut.source;

import java.util.List;
import java.util.Map;

public record SourceContext(
        SourceMethod method,
        String methodBody,
        String javadoc,
        String typeJavadoc,
        String typeHierarchy,
        String hierarchyResolution,
        Map<String, String> typeMethods,
        List<String> fieldReads,
        List<String> fieldWrites,
        List<String> sameTypeMethods,
        List<String> overloadGroup,
        List<String> dynamicFeatures,
        Map<String, Object> javadocMetadata,
        Map<String, Object> documentationMetrics,
        String sourceBackendMode) {
    public SourceContext {
        typeMethods = typeMethods != null ? Map.copyOf(typeMethods) : Map.of();
        fieldReads = fieldReads != null ? List.copyOf(fieldReads) : List.of();
        fieldWrites = fieldWrites != null ? List.copyOf(fieldWrites) : List.of();
        sameTypeMethods = sameTypeMethods != null ? List.copyOf(sameTypeMethods) : List.of();
        overloadGroup = overloadGroup != null ? List.copyOf(overloadGroup) : List.of();
        dynamicFeatures = dynamicFeatures != null ? List.copyOf(dynamicFeatures) : List.of();
        javadocMetadata = javadocMetadata != null ? Map.copyOf(javadocMetadata) : Map.of();
        documentationMetrics = documentationMetrics != null ? Map.copyOf(documentationMetrics) : Map.of();
        sourceBackendMode = sourceBackendMode != null ? sourceBackendMode : "";
    }
}
