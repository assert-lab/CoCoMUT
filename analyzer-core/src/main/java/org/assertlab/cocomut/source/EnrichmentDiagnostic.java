package org.assertlab.cocomut.source;

import com.fasterxml.jackson.annotation.JsonProperty;

/** A recoverable failure in optional source-context analysis, never a successful empty result. */
public record EnrichmentDiagnostic(String component,
                                   @JsonProperty("exception_class") String exceptionClass,
                                   String message) {
    public static EnrichmentDiagnostic from(String component, Exception failure) {
        String message = failure.getMessage();
        message = message == null ? "" : message.replaceAll("[\\r\\n]+", " ");
        if (message.length() > 1000) {
            message = message.substring(0, 1000);
        }
        return new EnrichmentDiagnostic(component, failure.getClass().getName(), message);
    }
}
