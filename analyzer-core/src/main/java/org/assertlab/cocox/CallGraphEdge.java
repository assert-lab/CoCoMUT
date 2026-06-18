package org.assertlab.cocox;

import java.util.Objects;

/**
 * Normalized call-graph edge.
 *
 * <p>SootUp reports bytecode-level signatures. CoCoX keeps that signature as
 * provenance, but project-method identity is always the CoCoX method URI.
 */
public record CallGraphEdge(
        String kind,
        String methodUri,
        String rawSignature,
        String declaringClass,
        String methodName,
        String resolution) {

    public CallGraphEdge {
        kind = nonBlank(kind, "unresolved");
        methodUri = methodUri == null ? "" : methodUri;
        rawSignature = rawSignature == null ? "" : rawSignature;
        declaringClass = declaringClass == null ? "" : declaringClass;
        methodName = methodName == null ? "" : methodName;
        resolution = nonBlank(resolution, "unresolved");
    }

    public static CallGraphEdge resolved(String methodUri, String rawSignature,
                                         String declaringClass, String methodName) {
        return new CallGraphEdge("project_method", methodUri, rawSignature,
                declaringClass, methodName, "resolved");
    }

    public static CallGraphEdge unresolved(String rawSignature, String declaringClass,
                                           String methodName) {
        String kind = isSyntheticName(methodName) ? "synthetic_or_compiler_method" : "unresolved_method";
        String resolution = isSyntheticName(methodName) ? "synthetic_or_compiler_generated" : "unresolved";
        return new CallGraphEdge(kind, "", rawSignature, declaringClass, methodName, resolution);
    }

    private static boolean isSyntheticName(String methodName) {
        return methodName != null
                && (methodName.startsWith("access$")
                || methodName.startsWith("lambda$")
                || methodName.contains("$default$"));
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    public boolean resolved() {
        return !methodUri.isBlank();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CallGraphEdge that)) return false;
        return Objects.equals(methodUri, that.methodUri)
                && Objects.equals(rawSignature, that.rawSignature)
                && Objects.equals(kind, that.kind);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, methodUri, rawSignature);
    }
}
