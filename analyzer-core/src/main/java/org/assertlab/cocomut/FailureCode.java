package org.assertlab.cocomut;

/**
 * Stable failure/degradation codes for empirical filtering.
 */
public enum FailureCode {
    NONE,
    NO_SOURCE_ROOT,
    METADATA_RESOLUTION_FAILED,
    MODEL_RESOLUTION_PARTIAL,
    BUILD_FAILED,
    PROVENANCE_FAILED,
    CALL_GRAPH_UNAVAILABLE,
    CALL_GRAPH_EMPTY,
    EMPTY_SELECTION,
    PARSE_ERROR,
    SOURCE_PARSE_FAILED,
    CONTEXT_EXTRACTION_FAILED,
    JSON_GENERATION_FAILED,
    DYNAMIC_FEATURE_DETECTED,
    ERROR
}
