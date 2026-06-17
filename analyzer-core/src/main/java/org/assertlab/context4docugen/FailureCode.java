package org.assertlab.context4docugen;

/**
 * Stable failure/degradation codes for empirical filtering.
 */
public enum FailureCode {
    NONE,
    NO_SOURCE_ROOT,
    BUILD_FAILED,
    CALL_GRAPH_DISABLED,
    CALL_GRAPH_UNAVAILABLE,
    PARSE_ERROR,
    SELECTED_METHOD_NOT_FOUND,
    CONTEXT_EXTRACTION_FAILED,
    JSON_GENERATION_FAILED,
    DYNAMIC_FEATURE_DETECTED,
    ERROR
}
