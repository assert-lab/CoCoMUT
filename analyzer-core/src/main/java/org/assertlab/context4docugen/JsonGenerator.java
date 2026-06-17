package org.assertlab.context4docugen;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Phase 5 of the method context extraction pipeline.
 *
 * Generates JSONL rows for methods using real data from the pipeline:
 * - MUT node with signature, line_number, parameters, code, javadoc, class hierarchy
 * - Callers array: full context objects for project methods, flat strings for library calls
 * - Callees array: same structure as callers
 * - Metadata: tool, algorithm, counts, generation time
 *
 * Input: Map of method IDs to MethodContext objects from Phase 4
 * Output: JSONL written to filesystem, generation report
 */
public class JsonGenerator {
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private final Path outputDirectory;
    private final Map<String, String> generationResults;
    private final Map<String, MethodContext> allContexts;
    private final CallGraphGenerator callGraphGenerator;

    public JsonGenerator(Path outputDirectory) {
        this(outputDirectory, Map.of(), null);
    }

    public JsonGenerator(Path outputDirectory, Map<String, MethodContext> allContexts,
                         CallGraphGenerator callGraphGenerator) {
        this.outputDirectory = Objects.requireNonNull(outputDirectory, "outputDirectory cannot be null");
        this.generationResults = new LinkedHashMap<>();
        this.allContexts = allContexts != null ? allContexts : Map.of();
        this.callGraphGenerator = callGraphGenerator;
        try {
            Files.createDirectories(outputDirectory);
        } catch (Exception e) {
            // will be caught per-file if it truly fails
        }
    }

    public static JsonGenerator withDefaultDirectory() {
        return new JsonGenerator(Path.of(".", "c4dg_output"));
    }

    public int generateJsonLinesFile(Map<String, MethodContext> contexts, Path jsonlPath) {
        int rows = 0;
        try {
            if (jsonlPath.getParent() != null) {
                Files.createDirectories(jsonlPath.getParent());
            }
            try (var writer = Files.newBufferedWriter(jsonlPath, StandardCharsets.UTF_8)) {
                for (MethodContext context : contexts.values()) {
                    ObjectNode json = buildJsonFromContext(context);
                    writer.write(objectMapper.writeValueAsString(json));
                    writer.newLine();
                    generationResults.put(context.getMethodId(), "SUCCESS:" + jsonlPath);
                    rows++;
                }
            }
            generationResults.put("__jsonl__", "SUCCESS:" + jsonlPath);
        } catch (Exception e) {
            generationResults.put("__jsonl__", "FAILED:" + e.getMessage());
        }
        return rows;
    }

    private ObjectNode buildJsonFromContext(MethodContext context) {
        ObjectNode json = objectMapper.createObjectNode();

        // MUT (Method Under Test) node
        ObjectNode mutNode = buildMethodNode(context);
        if (context.hasClassJavadoc()) {
            mutNode.put("class_javadoc", context.getClassJavadoc());
        }
        json.set("MUT", mutNode);

        // Callers/Callees — rich objects for project methods, flat strings for library
        CallGraphResult cg = context.getCallGraph();
        json.set("callers", buildCallerCalleeArray(cg != null ? cg.getCallers() : Set.of()));
        json.set("callees", buildCallerCalleeArray(cg != null ? cg.getCallees() : Set.of()));

        // Research metadata: human-written docstring and test prefix from inputs_selected.csv.
        // Only emitted when present (SELECTED mode); absent in FULL-mode runs.
        if (!context.getOriginalDocstring().isEmpty()) {
            json.put("original_docstring", context.getOriginalDocstring());
        }
        if (!context.getTestPrefix().isEmpty()) {
            json.put("test_prefix", context.getTestPrefix());
        }

        // Metadata
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("schema_version", "0.3.0");
        metadata.put("source_backend", context.getSourceBackend());
        metadata.put("source_backend_mode", context.getSourceBackendMode());
        metadata.put("method_identity", "uri");
        metadata.put("type_resolution", context.getHierarchyResolution());
        metadata.put("call_graph_tool", "SootUp");
        metadata.put("call_graph_algorithm", cg != null ? cg.getAlgorithm() : "N/A");
        metadata.put("caller_count", cg != null ? cg.getCallerCount() : 0);
        metadata.put("callee_count", cg != null ? cg.getCalleeCount() : 0);
        metadata.put("generation_time_ms", cg != null ? cg.getGenerationTime() : 0);
        metadata.put("class_hierarchy_included", true);
        ObjectNode callGraphNode = objectMapper.createObjectNode();
        callGraphNode.put("available", cg != null);
        callGraphNode.put("tool", cg != null ? "SootUp" : "N/A");
        callGraphNode.put("algorithm", cg != null ? cg.getAlgorithm() : "N/A");
        callGraphNode.put("confidence", cg != null ? callGraphConfidence(cg.getAlgorithm()) : "missing");
        callGraphNode.put("caller_count", cg != null ? cg.getCallerCount() : 0);
        callGraphNode.put("callee_count", cg != null ? cg.getCalleeCount() : 0);
        metadata.set("call_graph", callGraphNode);
        json.set("metadata", metadata);

        ObjectNode provenance = objectMapper.createObjectNode();
        provenance.put("method_source", context.getOriginalDocstring().isEmpty() ? "source_scan" : "selected_csv");
        provenance.put("method_matching", context.getSourceBackend());
        provenance.put("source_backend", context.getSourceBackend());
        provenance.put("source_backend_mode", context.getSourceBackendMode());
        provenance.put("javadoc_extraction", context.getSourceBackend());
        provenance.put("call_graph", cg != null ? "sootup_" + cg.getAlgorithm().toLowerCase() : "not_available");
        provenance.put("compiled_project", cg != null);
        provenance.put("hierarchy_resolution", context.getHierarchyResolution());
        ObjectNode confidence = objectMapper.createObjectNode();
        confidence.put("method_identity", "uri");
        confidence.put("source_extraction", context.getSourceBackendMode());
        confidence.put("type_resolution", context.getHierarchyResolution());
        confidence.put("call_graph", cg != null ? callGraphConfidence(cg.getAlgorithm()) : "missing");
        provenance.set("context_confidence", confidence);
        json.set("provenance", provenance);

        json.set("documentation_metrics", objectMapper.valueToTree(context.getDocumentationMetrics()));
        json.set("javadoc_metadata", objectMapper.valueToTree(context.getJavadocMetadata()));
        json.set("dynamic_features", objectMapper.valueToTree(context.getDynamicFeatures()));

        return json;
    }

    private ObjectNode buildMethodNode(MethodContext context) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("method_uri", context.getMethodId());
        node.put("method_name", context.getMethodName());
        node.put("source_set", context.getSourceSet());
        node.put("signature", context.getSignature());
        node.put("qualified_name", context.getClassname() + "." + context.getMethodName());
        node.put("line_number", context.getLineNumber());

        node.set("parameters", context.getParameterDetails().isEmpty()
                ? buildParameterArray(context.getSignature())
                : objectMapper.valueToTree(context.getParameterDetails()));
        node.set("annotations", objectMapper.valueToTree(context.getAnnotations()));
        node.set("throws", objectMapper.valueToTree(context.getThrownExceptions()));

        node.put("lines_of_code", context.getLinesOfCode());
        node.put("cyclomatic_complexity", context.getCyclomatic());

        node.put("code", context.getMethodBody().isEmpty()
                ? ""
                : context.getMethodBody());

        node.put("javadoc", context.hasJavadoc() ? context.getJavadoc() : "");

        // Class hierarchy
        ObjectNode classHierarchy = objectMapper.createObjectNode();
        String className = context.getClassname();
        int lastDot = className.lastIndexOf('.');
        classHierarchy.put("simple_name", lastDot >= 0 ? className.substring(lastDot + 1) : className);
        classHierarchy.put("package_name", lastDot >= 0 ? className.substring(0, lastDot) : "");
        classHierarchy.put("hierarchy_detail", context.getClassHierarchy());
        classHierarchy.put("resolution", context.getHierarchyResolution());
        node.set("class_hierarchy", classHierarchy);

        ObjectNode sourceContext = objectMapper.createObjectNode();
        sourceContext.set("field_reads", objectMapper.valueToTree(context.getFieldReads()));
        sourceContext.set("field_writes", objectMapper.valueToTree(context.getFieldWrites()));
        sourceContext.set("sibling_methods", objectMapper.valueToTree(context.getSiblingMethods()));
        sourceContext.set("overload_group", objectMapper.valueToTree(context.getOverloadGroup()));
        node.set("source_context", sourceContext);

        return node;
    }

    private ArrayNode buildCallerCalleeArray(Set<String> signatures) {
        ArrayNode array = objectMapper.createArrayNode();
        for (String sig : signatures) {
            if (callGraphGenerator == null || allContexts.isEmpty()) {
                array.add(sig);
                continue;
            }
            String methodId = callGraphGenerator.resolveSignatureToMethodId(sig);
            MethodContext ctx = (methodId != null) ? allContexts.get(methodId) : null;
            if (ctx != null) {
                array.add(buildMethodNode(ctx));
            } else {
                array.add(sig);
            }
        }
        return array;
    }

    private ArrayNode buildParameterArray(String signature) {
        ArrayNode paramsArray = objectMapper.createArrayNode();
        int open = signature.indexOf('(');
        int close = signature.lastIndexOf(')');
        if (open < 0 || close <= open) {
            return paramsArray;
        }
        String rawParams = signature.substring(open + 1, close).trim();
        if (rawParams.isEmpty()) {
            return paramsArray;
        }
        for (String rawParam : splitParameters(rawParams)) {
            ObjectNode param = objectMapper.createObjectNode();
            ParsedParameter parsed = parseParameter(rawParam);
            param.put("name", parsed.name());
            param.put("type", parsed.type());
            ArrayNode modifiers = objectMapper.createArrayNode();
            parsed.modifiers().forEach(modifiers::add);
            param.set("modifiers", modifiers);
            paramsArray.add(param);
        }
        return paramsArray;
    }

    private String callGraphConfidence(String algorithm) {
        if ("CHA".equalsIgnoreCase(algorithm)) {
            return "conservative";
        }
        if ("RTA".equalsIgnoreCase(algorithm)) {
            return "instantiation_filtered";
        }
        return algorithm == null || algorithm.isBlank() ? "missing" : "unknown";
    }

    private List<String> splitParameters(String rawParams) {
        java.util.ArrayList<String> params = new java.util.ArrayList<>();
        StringBuilder current = new StringBuilder();
        int genericDepth = 0;
        for (int i = 0; i < rawParams.length(); i++) {
            char c = rawParams.charAt(i);
            if (c == '<') {
                genericDepth++;
            } else if (c == '>') {
                genericDepth = Math.max(0, genericDepth - 1);
            } else if (c == ',' && genericDepth == 0) {
                params.add(current.toString().trim());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        if (!current.isEmpty()) {
            params.add(current.toString().trim());
        }
        return params;
    }

    private ParsedParameter parseParameter(String rawParam) {
        String normalized = rawParam.replaceAll("\\s+", " ").trim();
        java.util.ArrayList<String> modifiers = new java.util.ArrayList<>();
        while (normalized.startsWith("final ")) {
            modifiers.add("final");
            normalized = normalized.substring("final ".length()).trim();
        }
        String[] parts = normalized.split(" ");
        if (parts.length == 1) {
            return new ParsedParameter("", parts[0], modifiers);
        }
        String name = parts[parts.length - 1].replace("...", "");
        String type = normalized.substring(0, normalized.length() - parts[parts.length - 1].length()).trim();
        return new ParsedParameter(name, type, modifiers);
    }

    private record ParsedParameter(String name, String type, List<String> modifiers) {
    }

    // ---- Results and stats ----

    public String getGenerationResult(String methodId) {
        return generationResults.get(methodId);
    }

    public Map<String, String> getAllGenerationResults() {
        return new LinkedHashMap<>(generationResults);
    }

    public Map<String, Integer> getGenerationStats() {
        int total = (int) generationResults.keySet()
                .stream()
                .filter(key -> !key.startsWith("__"))
                .count();
        int success = (int) generationResults.entrySet()
                .stream()
                .filter(entry -> !entry.getKey().startsWith("__"))
                .map(Map.Entry::getValue)
                .filter(value -> value.startsWith("SUCCESS"))
                .count();
        int failed = total - success;

        return Map.of(
                "total_generated", total,
                "successful", success,
                "failed", failed
        );
    }

    public Path getOutputDirectory() {
        return outputDirectory;
    }

    public void clearResults() {
        generationResults.clear();
    }

    public Map<String, Boolean> verifyJsonFiles() {
        Map<String, Boolean> verification = new LinkedHashMap<>();

        generationResults.forEach((methodId, result) -> {
            if (result.startsWith("SUCCESS:")) {
                String filePath = result.substring("SUCCESS:".length());
                boolean exists = Files.exists(Path.of(filePath));
                verification.put(methodId, exists);
            } else {
                verification.put(methodId, false);
            }
        });

        return verification;
    }
}
