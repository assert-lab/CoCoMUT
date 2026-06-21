package org.assertlab.cocomut;

import org.assertlab.cocomut.source.ProjectModel;
import org.assertlab.cocomut.source.SourceBackends;
import org.assertlab.cocomut.source.SourceContext;
import org.assertlab.cocomut.source.SourceMethod;
import org.assertlab.cocomut.source.SourceModelBackend;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Phase 4 of the method context extraction pipeline.
 *
 * Extracts complete context for identified methods:
 * - Method source
 * - Javadoc documentation (method-level and class-level)
 * - Class hierarchy (via Spoon source model, with SootUp when available)
 * - Related class methods
 * - Metrics (LOC, cyclomatic complexity)
 * - Combines with call graph from Phase 3
 *
 * Input: ProjectMetadata from Phase 1, MethodInfo from Phase 2, CallGraphGenerator from Phase 3
 * Output: Map of method URIs to MethodContext objects
 */
public class ContextExtractor {
    private final ProjectMetadata projectMetadata;
    private final CallGraphGenerator callGraphGenerator;
    private final Map<String, MethodContext> cache;
    private final ProjectModel projectModel;
    private final SourceModelBackend sourceBackend;

    public ContextExtractor(ProjectMetadata projectMetadata, CallGraphGenerator callGraphGenerator) {
        this.projectMetadata = Objects.requireNonNull(projectMetadata, "projectMetadata cannot be null");
        this.callGraphGenerator = Objects.requireNonNull(callGraphGenerator, "callGraphGenerator cannot be null");
        this.cache = new HashMap<>();
        this.projectModel = ProjectModel.from(projectMetadata);
        this.sourceBackend = SourceBackends.spoon();
    }

    public MethodContext extractContext(MethodInfo method) {
        String methodUri = method.getMethodUri();
        if (cache.containsKey(methodUri)) {
            return cache.get(methodUri);
        }

        try {
            java.util.Optional<SourceContext> sourceContext =
                    sourceBackend.extractContext(projectModel, methodUri);
            if (sourceContext.isPresent()) {
                MethodContext context = fromSourceContext(method, sourceContext.get());
                cache.put(methodUri, context);
                return context;
            }

            return null;
        } catch (Exception e) {
            System.err.println("[ContextExtractor] Failed to extract context for method "
                    + methodUri + " (" + method.getMethodName() + "): " + e.getMessage());
            return null;
        }
    }

    private MethodContext fromSourceContext(MethodInfo method, SourceContext sourceContext) {
        SourceMethod sourceMethod = sourceContext.method();
        CallGraphResult callGraph = callGraphGenerator.getCachedResult(method.getMethodUri());
        String methodBody = sourceContext.methodBody();

        return new MethodContext.Builder()
                .methodUri(method.getMethodUri())
                .methodName(method.getMethodName())
                .classname(method.getClassname())
                .signature(method.getClassname() + "." + method.getMethodSignature())
                .returnType(sourceMethod.returnType())
                .erasedReturnType(sourceMethod.erasedReturnType())
                .lineNumber(method.getLineNumber())
                .parameters(sourceMethod.parameters().stream().map(p -> p.type()).toList())
                .parameterDetails(sourceMethod.parameters().stream()
                        .map(p -> {
                            Map<String, Object> detail = new java.util.LinkedHashMap<>();
                            detail.put("name", p.name());
                            detail.put("type", p.type());
                            detail.put("erased_type", p.erasedType());
                            detail.put("modifiers", p.modifiers());
                            detail.put("annotations", p.annotations());
                            return detail;
                        })
                        .toList())
                .methodBody(methodBody)
                .javadoc(sourceContext.javadoc())
                .classJavadoc(sourceContext.classJavadoc())
                .classHierarchy(sourceContext.classHierarchy())
                .classMethods(sourceContext.classMethods())
                .callGraph(callGraph)
                .linesOfCode(countLinesOfCode(methodBody))
                .cyclomatic(calculateCyclomaticComplexity(methodBody))
                .annotations(sourceMethod.annotations())
                .thrownExceptions(sourceMethod.thrownExceptions())
                .fieldReads(sourceContext.fieldReads())
                .fieldWrites(sourceContext.fieldWrites())
                .siblingMethods(sourceContext.siblingMethods())
                .overloadGroup(sourceContext.overloadGroup())
                .dynamicFeatures(sourceContext.dynamicFeatures())
                .javadocMetadata(sourceContext.javadocMetadata())
                .documentationMetrics(sourceContext.documentationMetrics())
                .sourceBackend(sourceBackend.name())
                .sourceBackendMode(sourceContext.sourceBackendMode())
                .hierarchyResolution(sourceContext.hierarchyResolution())
                .sourceSet(sourceMethod.sourceSet())
                .build();
    }

    public Map<String, MethodContext> extractContextForMethods(List<MethodInfo> methods) {
        Map<String, MethodContext> result = new java.util.LinkedHashMap<>();
        for (MethodInfo method : methods) {
            MethodContext ctx = extractContext(method);
            if (ctx != null) {
                result.put(ctx.getMethodUri(), ctx);
            } else {
                System.err.println("[ContextExtractor] Dropped method_uri=" + method.getMethodUri()
                        + " (" + method.getMethodName() + ") — could not extract context");
            }
        }
        return result;
    }

    // ---- Metrics ----

    private int countLinesOfCode(String methodBody) {
        if (methodBody == null || methodBody.isEmpty()) {
            return 0;
        }
        return (int) java.util.Arrays.stream(methodBody.split("\n"))
                .map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("//") && !line.startsWith("*"))
                .count();
    }

    private int calculateCyclomaticComplexity(String methodBody) {
        if (methodBody == null || methodBody.isEmpty()) {
            return 1;
        }

        // McCabe complexity: 1 + one decision point per branch keyword.
        // \bif\b already matches the `if` inside `else if`, so do NOT add a
        // separate \belse\s+if\b term — that would count each else-if twice.
        int complexity = 1;
        complexity += countMatches(methodBody, "\\bif\\b");     // covers plain `if` and `else if`
        complexity += countMatches(methodBody, "\\bcase\\b");
        complexity += countMatches(methodBody, "\\bfor\\b");
        complexity += countMatches(methodBody, "\\bwhile\\b");
        complexity += countMatches(methodBody, "\\bcatch\\b");
        complexity += countMatches(methodBody, "\\?");          // ternary operator

        return complexity;
    }

    private int countMatches(String text, String regex) {
        return (int) Pattern.compile(regex).matcher(text).results().count();
    }

    // ---- Cache management ----

    public MethodContext getCachedContext(String methodUri) {
        return cache.get(methodUri);
    }

    public void clearCache() {
        cache.clear();
    }

    public Map<String, Integer> getCacheStats() {
        return Map.of(
                "cached_contexts", cache.size(),
                "total_linesOfCode", cache.values().stream()
                        .mapToInt(MethodContext::getLinesOfCode)
                        .sum()
        );
    }
}
