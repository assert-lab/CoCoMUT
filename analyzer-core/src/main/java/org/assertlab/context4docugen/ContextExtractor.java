package org.assertlab.context4docugen;

import org.assertlab.context4docugen.source.ProjectModel;
import org.assertlab.context4docugen.source.SourceBackends;
import org.assertlab.context4docugen.source.SourceContext;
import org.assertlab.context4docugen.source.SourceMethod;
import org.assertlab.context4docugen.source.SourceModelBackend;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Phase 4 of the method context extraction pipeline.
 *
 * Extracts complete context for identified methods:
 * - Method body (brace-counted from source)
 * - JavaDoc documentation (method-level and class-level)
 * - Class hierarchy (via Spoon source model, with SootUp when available)
 * - Related class methods
 * - Metrics (LOC, cyclomatic complexity)
 * - Combines with call graph from Phase 3
 *
 * Input: ProjectMetadata from Phase 1, MethodInfo from Phase 2, CallGraphGenerator from Phase 3
 * Output: Map of method IDs to MethodContext objects
 */
public class ContextExtractor {
    private static final Pattern CLASS_DECLARATION_PATTERN = Pattern.compile(
            "(public|package-private)?\\s+(class|interface|enum)\\s+(\\w+)(?:\\s+extends\\s+([\\w,\\s]+))?(?:\\s+implements\\s+([\\w,\\s]+))?"
    );

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
        String cacheKey = method.getId();
        if (cache.containsKey(cacheKey)) {
            return cache.get(cacheKey);
        }

        try {
            java.util.Optional<SourceContext> sourceContext =
                    sourceBackend.extractContext(projectModel, method.getMethodUri());
            if (sourceContext.isPresent()) {
                MethodContext context = fromSourceContext(method, sourceContext.get());
                cache.put(cacheKey, context);
                return context;
            }

            String fileContent = readSourceFile(method.getSourceFile());

            String javadoc = extractJavadoc(fileContent, method.getLineNumber());
            String classJavadoc = extractClassJavadoc(fileContent);
            String methodBody = extractMethodBody(fileContent, method.getLineNumber());
            String classHierarchy = resolveClassHierarchy(method.getClassname(), fileContent);
            Map<String, String> classMethods = extractClassMethods(fileContent, method.getClassname());
            CallGraphResult callGraph = callGraphGenerator.getCachedResult(method.getId());
            int linesOfCode = countLinesOfCode(methodBody);
            int cyclomatic = calculateCyclomaticComplexity(methodBody);

            String formattedSignature = method.getClassname() + "."
                    + method.getMethodSignature();
            List<String> parameters = parseParameterTypes(method.getMethodSignature());

            MethodContext context = new MethodContext.Builder()
                    .methodId(method.getId())
                    .methodName(method.getMethodName())
                    .classname(method.getClassname())
                    .signature(formattedSignature)
                    .lineNumber(method.getLineNumber())
                    .parameters(parameters)
                    .methodBody(methodBody)
                    .javadoc(javadoc)
                    .classJavadoc(classJavadoc)
                    .classHierarchy(classHierarchy)
                    .classMethods(classMethods)
                    .callGraph(callGraph)
                    .linesOfCode(linesOfCode)
                    .cyclomatic(cyclomatic)
                    .originalDocstring(method.getOriginalDocstring())
                    .testPrefix(method.getTestPrefix())
                    .sourceBackend(sourceBackend.name())
                    .sourceBackendMode("fallback_text")
                    .hierarchyResolution("source_text")
                    .build();

            cache.put(cacheKey, context);
            return context;

        } catch (IOException e) {
            System.err.println("[ContextExtractor] Failed to extract context for method "
                    + method.getId() + " (" + method.getMethodName() + "): " + e.getMessage());
            return null;
        }
    }

    private MethodContext fromSourceContext(MethodInfo method, SourceContext sourceContext) {
        SourceMethod sourceMethod = sourceContext.method();
        CallGraphResult callGraph = callGraphGenerator.getCachedResult(method.getId());
        String methodBody = sourceContext.methodBody();

        return new MethodContext.Builder()
                .methodId(method.getId())
                .methodName(method.getMethodName())
                .classname(method.getClassname())
                .signature(method.getClassname() + "." + method.getMethodSignature())
                .lineNumber(method.getLineNumber())
                .parameters(sourceMethod.parameters().stream().map(p -> p.type()).toList())
                .parameterDetails(sourceMethod.parameters().stream()
                        .map(p -> {
                            Map<String, Object> detail = new java.util.LinkedHashMap<>();
                            detail.put("name", p.name());
                            detail.put("type", p.type());
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
                .originalDocstring(method.getOriginalDocstring())
                .testPrefix(method.getTestPrefix())
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
                result.put(ctx.getMethodId(), ctx);
            } else {
                System.err.println("[ContextExtractor] Dropped method id=" + method.getId()
                        + " (" + method.getMethodName() + ") — could not extract context");
            }
        }
        return result;
    }

    // ---- Class hierarchy resolution ----

    private String resolveClassHierarchy(String classname, String fileContent) {
        CallGraphGenerator.ClassHierarchyInfo info = callGraphGenerator.getClassHierarchy(classname);
        if (info != null) {
            return formatHierarchyInfo(info);
        }
        return extractClassHierarchyFromSource(fileContent);
    }

    private String formatHierarchyInfo(CallGraphGenerator.ClassHierarchyInfo info) {
        StringBuilder sb = new StringBuilder(info.getSimpleName());
        if (!info.getSuperclasses().isEmpty()) {
            sb.append(" extends ").append(String.join(", ", info.getSuperclasses()));
        }
        if (!info.getInterfaces().isEmpty()) {
            sb.append(" implements ").append(String.join(", ", info.getInterfaces()));
        }
        if (!info.getDirectSubclasses().isEmpty()) {
            sb.append(" [subclasses: ").append(String.join(", ", info.getDirectSubclasses())).append("]");
        }
        return sb.toString();
    }

    private String extractClassHierarchyFromSource(String fileContent) {
        Matcher matcher = CLASS_DECLARATION_PATTERN.matcher(fileContent);
        if (matcher.find()) {
            StringBuilder hierarchy = new StringBuilder();
            hierarchy.append(matcher.group(3));
            if (matcher.group(4) != null) {
                hierarchy.append(" extends ").append(matcher.group(4));
            }
            if (matcher.group(5) != null) {
                hierarchy.append(" implements ").append(matcher.group(5));
            }
            return hierarchy.toString();
        }
        return "";
    }

    // ---- Class-level javadoc ----

    private String extractClassJavadoc(String fileContent) {
        String[] lines = fileContent.split("\\R");
        StringBuilder javadoc = new StringBuilder();
        boolean inJavadoc = false;

        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.matches(".*\\b(class|interface|enum)\\s+\\w+.*") && !line.startsWith("*") && !line.startsWith("//")) {
                break;
            }
            if (line.startsWith("/**")) {
                inJavadoc = true;
                javadoc.setLength(0);
                javadoc.append(rawLine).append("\n");
            } else if (inJavadoc) {
                javadoc.append(rawLine).append("\n");
                if (line.endsWith("*/")) {
                    inJavadoc = false;
                }
            }
        }
        return javadoc.toString().trim();
    }

    // ---- Parameter parsing ----

    private List<String> parseParameterTypes(String methodSignature) {
        if (methodSignature == null) return List.of();
        int open = methodSignature.indexOf('(');
        int close = methodSignature.lastIndexOf(')');
        if (open < 0 || close <= open) return List.of();
        String paramStr = methodSignature.substring(open + 1, close).trim();
        if (paramStr.isEmpty()) return List.of();
        List<String> types = new ArrayList<>();
        for (String p : paramStr.split(",")) {
            String[] parts = p.trim().split("\\s+");
            if (parts.length >= 1) types.add(parts[0]);
        }
        return types;
    }

    // ---- Source file reading ----

    private String readSourceFile(Path sourceFile) throws IOException {
        Path projectRoot = projectMetadata.getSourceRoot().getParent();
        Path fullPath = projectRoot.resolve(sourceFile);

        if (!Files.exists(fullPath)) {
            fullPath = projectMetadata.getSourceRoot().resolve(sourceFile.getFileName());
        }

        if (!Files.exists(fullPath)) {
            fullPath = projectMetadata.getProjectPath().resolve("src/main/java").resolve(sourceFile.getFileName());
        }

        if (Files.exists(fullPath)) {
            return new String(Files.readAllBytes(fullPath), StandardCharsets.UTF_8);
        }

        return "";
    }

    // ---- Method javadoc ----

    private String extractJavadoc(String fileContent, int lineNumber) {
        String[] lines = fileContent.split("\\R");

        if (lineNumber > 0 && lineNumber <= lines.length) {
            for (int i = lineNumber - 2; i >= 0 && i >= lineNumber - 50; i--) {
                String line = lines[i].trim();
                if (line.startsWith("/**")) {
                    StringBuilder javadoc = new StringBuilder();
                    for (int j = i; j < lines.length && j < lineNumber; j++) {
                        javadoc.append(lines[j]).append("\n");
                        if (lines[j].contains("*/")) {
                            return javadoc.toString();
                        }
                    }
                }
            }
        }

        return "";
    }

    // ---- Method body extraction ----

    private String extractMethodBody(String fileContent, int lineNumber) {
        String[] lines = fileContent.split("\\R");

        if (lineNumber <= 0 || lineNumber > lines.length) {
            return "";
        }

        StringBuilder methodBody = new StringBuilder();
        int braceCount = 0;
        boolean inMethod = false;

        for (int i = lineNumber - 1; i < lines.length; i++) {
            String line = lines[i];

            for (char c : line.toCharArray()) {
                if (c == '{') {
                    braceCount++;
                    inMethod = true;
                } else if (c == '}') {
                    braceCount--;
                }
            }

            methodBody.append(line).append("\n");

            if (inMethod && braceCount == 0) {
                break;
            }
        }

        return methodBody.toString();
    }

    // ---- Class methods extraction ----

    private Map<String, String> extractClassMethods(String fileContent, String classname) {
        Map<String, String> methods = new HashMap<>();

        Pattern methodPattern = Pattern.compile(
                "\\b(public|private|protected)?\\s+(static)?\\s+([\\w<>,\\s]+?)\\s+(\\w+)\\s*\\([^)]*\\)"
        );

        Matcher matcher = methodPattern.matcher(fileContent);
        while (matcher.find()) {
            String methodName = matcher.group(4);
            String returnType = matcher.group(3);
            methods.put(methodName, returnType);
        }

        return methods;
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

    public MethodContext getCachedContext(String methodId) {
        return cache.get(methodId);
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
