package org.assertlab.cocox;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Typed wrapper around the pipeline execution report.
 */
public final class ExtractionReport {
    private final Map<String, Object> values;

    public ExtractionReport(Map<String, Object> values) {
        this.values = java.util.Collections.unmodifiableMap(
                new LinkedHashMap<>(Objects.requireNonNull(values, "values cannot be null")));
    }

    public Map<String, Object> asMap() {
        return values;
    }

    public String status() {
        return stringValue("status");
    }

    public boolean successful() {
        return "SUCCESS".equals(status());
    }

    public Integer failedAtPhase() {
        return intValue("failed_at_phase");
    }

    public int methodsIdentified() {
        Integer identified = intValue("phase_2_methods_identified");
        if (identified != null) {
            return identified;
        }
        Integer loaded = intValue("phase_2_methods_loaded");
        return loaded != null ? loaded : 0;
    }

    public int contextsExtracted() {
        Integer value = intValue("phase_4_contexts_extracted");
        return value != null ? value : 0;
    }

    public int jsonFilesGenerated() {
        Integer value = intValue("phase_5_files_generated");
        return value != null ? value : 0;
    }

    public int jsonlRows() {
        Integer value = intValue("phase_5_jsonl_rows");
        return value != null ? value : 0;
    }

    public Path jsonlFile() {
        String value = stringValue("phase_5_jsonl_file");
        return value != null && !value.isBlank() ? Path.of(value) : null;
    }

    @SuppressWarnings("unchecked")
    public List<String> failureCodes() {
        Object value = values.get("failure_codes");
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        if (value != null) {
            return List.of(String.valueOf(value));
        }
        return List.of();
    }

    public String errorMessage() {
        return stringValue("error_message");
    }

    public long durationMs() {
        Object value = values.get("duration_ms");
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value != null) {
            try {
                return Long.parseLong(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }

    private String stringValue(String key) {
        Object value = values.get(key);
        return value != null ? String.valueOf(value) : null;
    }

    private Integer intValue(String key) {
        Object value = values.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
