package org.assertlab.cocomut.source;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/** One source-model attempt, including failed attempts preceding recovery. */
public record SourceModelAttempt(List<String> inputs, String mode,
        int requestedCompliance, int effectiveCompliance, int classpathEntries,
        String outcome, String exceptionClass, String message, String stage, String diagnosticCode) {
    public SourceModelAttempt(List<String> inputs, String mode, int requestedCompliance,
            int effectiveCompliance, int classpathEntries, String outcome,
            String exceptionClass, String message) {
        this(inputs, mode, requestedCompliance, effectiveCompliance, classpathEntries,
                outcome, exceptionClass, message, "model_build", "");
    }

    public SourceModelAttempt {
        inputs = List.copyOf(inputs);
        message = message.replaceAll("[\\p{Cntrl}]", " ");
        if (message.length() > 2000) message = message.substring(0, 2000);
    }

    public Map<String, Object> asMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stage", stage);
        result.put("diagnostic_code", diagnosticCode);
        result.put("inputs", inputs);
        result.put("mode", mode);
        result.put("requested_compliance", requestedCompliance);
        result.put("effective_compliance", effectiveCompliance);
        result.put("classpath_entries", classpathEntries);
        result.put("outcome", outcome);
        result.put("exception_class", exceptionClass);
        result.put("message", message);
        return result;
    }
}
