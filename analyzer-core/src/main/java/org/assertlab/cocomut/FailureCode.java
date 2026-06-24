package org.assertlab.cocomut;

/**
 * Stable failure/degradation codes for empirical filtering.
 */
public enum FailureCode {
    NONE,
    NO_SOURCE_ROOT,
    BUILD_FAILED,
    CALL_GRAPH_UNAVAILABLE,
    CALL_GRAPH_EMPTY,
    PARSE_ERROR,
    CONTEXT_EXTRACTION_FAILED,
    JSON_GENERATION_FAILED,
    DYNAMIC_FEATURE_DETECTED,
    ERROR
}
