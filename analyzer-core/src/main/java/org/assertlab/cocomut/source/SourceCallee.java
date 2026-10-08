package org.assertlab.cocomut.source;

/** A source-referenced declaration, independent of runtime dispatch candidates. */
public record SourceCallee(String kind, String methodUri, String targetUri,
                           String declaringType, String methodName, String signature,
                           String resolution, String unresolvedReason) {
}
