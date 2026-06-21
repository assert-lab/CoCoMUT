package org.assertlab.cocomut;

import java.util.Objects;

/**
 * Normalized call-graph edge.
 *
 * <p>SootUp reports bytecode-level signatures. CoCoMUT keeps that signature as
 * provenance, but project-method identity is always the CoCoMUT method URI.
 */
public record CallGraphEdge(
        String kind,
        String methodUri,
        String targetUri,
        String targetKind,
        String rawSignature,
        String declaringClass,
        String methodName,
        String resolution,
        java.util.List<String> candidateMethodUris,
        String unresolvedReason) {

    public CallGraphEdge {
        kind = nonBlank(kind, "unresolved");
        methodUri = methodUri == null ? "" : methodUri;
        targetUri = targetUri == null ? "" : targetUri;
        targetKind = nonBlank(targetKind, "unknown");
        rawSignature = rawSignature == null ? "" : rawSignature;
        declaringClass = declaringClass == null ? "" : declaringClass;
        methodName = methodName == null ? "" : methodName;
        resolution = nonBlank(resolution, "unresolved");
        candidateMethodUris = candidateMethodUris == null ? java.util.List.of() : java.util.List.copyOf(candidateMethodUris);
        unresolvedReason = unresolvedReason == null ? "" : unresolvedReason;
    }

    public static CallGraphEdge resolved(String methodUri, String rawSignature,
                                         String declaringClass, String methodName) {
        return resolved(methodUri, rawSignature, declaringClass, methodName, "resolved");
    }

    public static CallGraphEdge resolved(String methodUri, String rawSignature,
                                         String declaringClass, String methodName,
                                         String resolution) {
        return new CallGraphEdge("project_method", methodUri, bytecodeUri(rawSignature),
                "project_method", rawSignature, declaringClass, methodName, resolution,
                java.util.List.of(), "");
    }

    public static CallGraphEdge ambiguous(String rawSignature, String declaringClass,
                                          String methodName, java.util.List<String> candidateMethodUris,
                                          String reason) {
        return new CallGraphEdge("ambiguous_project_method", "", bytecodeUri(rawSignature),
                "project_method", rawSignature, declaringClass, methodName, "ambiguous",
                candidateMethodUris, reason);
    }

    public static CallGraphEdge unresolved(String rawSignature, String declaringClass,
                                           String methodName) {
        String targetKind = classifyTargetKind(rawSignature, declaringClass, methodName);
        return unresolved(rawSignature, declaringClass, methodName, targetKind,
                defaultUnresolvedReason(rawSignature, declaringClass, methodName, targetKind));
    }

    public static CallGraphEdge unresolved(String rawSignature, String declaringClass,
                                           String methodName, String targetKind, String reason) {
        String kind = isSyntheticName(methodName) ? "synthetic_or_compiler_method" : targetKind;
        String resolution = isSyntheticName(methodName) ? "synthetic_or_compiler_generated" : "unresolved";
        return new CallGraphEdge(kind, "", bytecodeUri(rawSignature),
                targetKind, rawSignature, declaringClass, methodName, resolution,
                java.util.List.of(), reason);
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

    public static String bytecodeUri(String rawSignature) {
        ParsedRawSignature parsed = ParsedRawSignature.parse(rawSignature);
        if (parsed == null) {
            return rawSignature == null || rawSignature.isBlank() ? "" : "bytecode://" + rawSignature;
        }
        return "bytecode://" + parsed.declaringClass + "." + parsed.methodName
                + "(" + parsed.parameters + "):" + parsed.returnType;
    }

    private static String classifyTargetKind(String rawSignature, String declaringClass, String methodName) {
        String owner = declaringClass == null ? "" : declaringClass;
        if (rawSignature != null && rawSignature.contains("sootup.dummy.InvokeDynamic")) {
            return "invokedynamic_method";
        }
        if (isSyntheticName(methodName)) {
            return "synthetic_or_compiler_method";
        }
        if (owner.startsWith("java.") || owner.startsWith("javax.") || owner.startsWith("jdk.")
                || owner.startsWith("sun.") || owner.startsWith("com.sun.")
                || owner.startsWith("org.w3c.dom.") || owner.startsWith("org.xml.sax.")) {
            return "jdk_method";
        }
        return "bytecode_method";
    }

    private static String defaultUnresolvedReason(String rawSignature, String declaringClass,
                                                  String methodName, String targetKind) {
        if (isSyntheticName(methodName)) {
            return "synthetic_or_compiler_generated";
        }
        if ("invokedynamic_method".equals(targetKind)) {
            return "invokedynamic_or_lambda_bytecode_artifact";
        }
        if ("jdk_method".equals(targetKind)) {
            return "jdk_or_platform_method_outside_project_source";
        }
        return "unresolved";
    }

    private record ParsedRawSignature(String declaringClass, String returnType,
                                      String methodName, String parameters) {
        private static ParsedRawSignature parse(String raw) {
            if (raw == null || !raw.startsWith("<") || !raw.endsWith(">")) {
                return null;
            }
            String body = raw.substring(1, raw.length() - 1);
            int colon = body.indexOf(':');
            int open = body.indexOf('(', colon + 1);
            int close = body.lastIndexOf(')');
            if (colon < 0 || open < 0 || close < open) {
                return null;
            }
            String owner = body.substring(0, colon).trim();
            String beforeParams = body.substring(colon + 1, open).trim();
            int lastSpace = beforeParams.lastIndexOf(' ');
            if (lastSpace < 0) {
                return null;
            }
            String returnType = beforeParams.substring(0, lastSpace).trim();
            String name = beforeParams.substring(lastSpace + 1).trim();
            String params = body.substring(open + 1, close).trim();
            return new ParsedRawSignature(owner, returnType, name, params);
        }
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
