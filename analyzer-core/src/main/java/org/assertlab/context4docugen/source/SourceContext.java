package org.assertlab.context4docugen.source;

import java.util.List;
import java.util.Map;

public record SourceContext(
        SourceMethod method,
        String methodBody,
        String javadoc,
        String classJavadoc,
        String classHierarchy,
        String hierarchyResolution,
        Map<String, String> classMethods,
        List<String> fieldReads,
        List<String> fieldWrites,
        List<String> siblingMethods,
        List<String> overloadGroup,
        List<String> dynamicFeatures,
        Map<String, Object> javadocMetadata,
        Map<String, Object> documentationMetrics) {
    public SourceContext {
        classMethods = classMethods != null ? Map.copyOf(classMethods) : Map.of();
        fieldReads = fieldReads != null ? List.copyOf(fieldReads) : List.of();
        fieldWrites = fieldWrites != null ? List.copyOf(fieldWrites) : List.of();
        siblingMethods = siblingMethods != null ? List.copyOf(siblingMethods) : List.of();
        overloadGroup = overloadGroup != null ? List.copyOf(overloadGroup) : List.of();
        dynamicFeatures = dynamicFeatures != null ? List.copyOf(dynamicFeatures) : List.of();
        javadocMetadata = javadocMetadata != null ? Map.copyOf(javadocMetadata) : Map.of();
        documentationMetrics = documentationMetrics != null ? Map.copyOf(documentationMetrics) : Map.of();
    }
}
