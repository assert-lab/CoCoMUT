package org.assertlab.cocomut;

import org.assertlab.cocomut.source.ProjectModel;
import org.assertlab.cocomut.source.SourceBackends;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;

/**
 * Final coordinator of the method context extraction pipeline.
 *
 * Orchestrates the extraction phases in sequence:
 * Phase 1: ProjectAnalyzer - Detect project, build system, Java version, classpath
 * Phase 2: MethodIdentifier - Scan Java files, extract methods, generate URI identities
 * Phase 3: CallGraphGenerator - Generate call graphs for methods
 * Phase 4: ContextExtractor - Extract method bodies, javadocs, class hierarchy
 * Phase 5: JsonGenerator - Generate JSONL output
 *
 * Input: Project root path
 * Output: JSONL method contexts and execution report
 */
final class Orchestrator {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final Path projectPath;
    private final Map<String, Object> executionReport;
    private ContextRequest.Scope scope = ContextRequest.Scope.ALL;
    private CallGraphGenerator.Algorithm callGraphAlgorithm = CallGraphGenerator.Algorithm.RTA;
    private Integer maxMethods;
    private Integer maxSourceFiles;
    private Set<String> sourceSets = Set.of();
    private Set<String> packageFilters = Set.of();
    private Set<String> classFilters = Set.of();
    private Set<String> methodFilters = Set.of();
    private Set<String> visibilityFilters = Set.of();
    private Set<String> includePathGlobs = Set.of();
    private Set<String> excludePathGlobs = Set.of();
    private Set<SymbolTarget> targetFilters = Set.of();
    private Path outputDirectory;

    // Pipeline state passed between phases
    private ProjectMetadata projectMetadata;
    private List<MethodInfo> analysisUniverseMethods;
    private List<MethodInfo> methodInfos;
    private CallGraphGenerator callGraphGenerator;
    private Map<String, CallGraphResult> callGraphResults;
    private Map<String, MethodContext> methodContexts;
    private Map<String, String> contextExtractionFailures = new LinkedHashMap<>();
    private final Set<FailureCode> failureCodes = new LinkedHashSet<>();

    Orchestrator(Path projectPath) {
        this.projectPath = Objects.requireNonNull(projectPath, "projectPath cannot be null");
        this.executionReport = new LinkedHashMap<>();
    }

    Orchestrator(ContextRequest request) {
        this(request.projectRoot());
        this.scope = request.scope();
        this.callGraphAlgorithm = request.callGraphAlgorithm();
        this.maxMethods = request.maxMethods();
        this.maxSourceFiles = request.maxSourceFiles();
        this.sourceSets = request.sourceSets();
        this.packageFilters = request.packages();
        this.classFilters = request.classes();
        this.methodFilters = request.methods();
        this.visibilityFilters = request.visibilities();
        this.includePathGlobs = request.includePathGlobs();
        this.excludePathGlobs = request.excludePathGlobs();
        this.targetFilters = request.targets();
        this.outputDirectory = request.outputDirectory();
    }

    Orchestrator setCallGraphAlgorithm(CallGraphGenerator.Algorithm algorithm) {
        this.callGraphAlgorithm = Objects.requireNonNull(algorithm, "algorithm cannot be null");
        return this;
    }

    Orchestrator setMaxMethods(Integer maxMethods) {
        this.maxMethods = maxMethods;
        return this;
    }

    Orchestrator setMaxSourceFiles(Integer maxSourceFiles) {
        this.maxSourceFiles = maxSourceFiles;
        return this;
    }

    Orchestrator setSourceSets(Set<String> sourceSets) {
        this.sourceSets = sourceSets == null ? Set.of() : Set.copyOf(sourceSets);
        return this;
    }

    Orchestrator setPackageFilters(Set<String> packageFilters) {
        this.packageFilters = packageFilters == null ? Set.of() : Set.copyOf(packageFilters);
        return this;
    }

    Orchestrator setClassFilters(Set<String> classFilters) {
        this.classFilters = classFilters == null ? Set.of() : Set.copyOf(classFilters);
        return this;
    }

    Orchestrator setMethodFilters(Set<String> methodFilters) {
        this.methodFilters = methodFilters == null ? Set.of() : Set.copyOf(methodFilters);
        return this;
    }

    Orchestrator setVisibilityFilters(Set<String> visibilityFilters) {
        this.visibilityFilters = visibilityFilters == null ? Set.of() : Set.copyOf(visibilityFilters);
        return this;
    }

    Orchestrator setIncludePathGlobs(Set<String> includePathGlobs) {
        this.includePathGlobs = includePathGlobs == null ? Set.of() : Set.copyOf(includePathGlobs);
        return this;
    }

    Orchestrator setExcludePathGlobs(Set<String> excludePathGlobs) {
        this.excludePathGlobs = excludePathGlobs == null ? Set.of() : Set.copyOf(excludePathGlobs);
        return this;
    }

    Orchestrator setTargetFilters(Set<SymbolTarget> targetFilters) {
        this.targetFilters = targetFilters == null ? Set.of() : Set.copyOf(targetFilters);
        return this;
    }

    Orchestrator setOutputDirectory(Path outputDirectory) {
        this.outputDirectory = outputDirectory;
        return this;
    }

    public boolean execute() {
        long startTime = System.currentTimeMillis();
        executionReport.put("start_time", new java.util.Date());
        executionReport.put("pipeline_phases", 5);

        boolean success = false;
        try {
            if (!executePhase1()) { executionReport.put("status", "FAILED"); executionReport.put("failed_at_phase", 1); return false; }
            configureSourceFileLimit();
            if (!executePhase2()) { executionReport.put("status", "FAILED"); executionReport.put("failed_at_phase", 2); return false; }
            if (!executePhase3()) { executionReport.put("status", "FAILED"); executionReport.put("failed_at_phase", 3); return false; }
            if (!executePhase4()) { executionReport.put("status", "FAILED"); executionReport.put("failed_at_phase", 4); return false; }
            if (!executePhase5()) { executionReport.put("status", "FAILED"); executionReport.put("failed_at_phase", 5); return false; }

            executionReport.put("status", "SUCCESS");
            executionReport.put("completed_phases", 5);
            success = true;
            return true;

        } catch (Exception e) {
            executionReport.put("status", "ERROR");
            executionReport.put("error_message", e.getMessage());
            return false;
        } finally {
            long endTime = System.currentTimeMillis();
            executionReport.put("duration_ms", endTime - startTime);
            executionReport.put("end_time", new java.util.Date());
            executionReport.putIfAbsent("status", success ? "SUCCESS" : "FAILED");
            executionReport.put("failure_codes", failureCodes.isEmpty()
                    ? List.of(FailureCode.NONE.toString())
                    : failureCodes.stream().map(Enum::toString).toList());
            writeExecutionReportIfPossible();
            restoreSourceFileLimit();
        }
    }

    private boolean executePhase1() {
        try {
            ProjectAnalyzer analyzer = new ProjectAnalyzer(projectPath, true, "auto");
            projectMetadata = analyzer.analyze();

            executionReport.put("phase_1_project", projectMetadata.getProjectName());
            executionReport.put("phase_1_build_system", projectMetadata.getBuildSystem());
            executionReport.put("phase_1_java_version", projectMetadata.getJavaVersion());
            executionReport.put("phase_1_compiles", projectMetadata.isCompiles());
            executionReport.put("phase_1_compile_status", projectMetadata.getCompileStatus());
            ProjectModel model = ProjectModel.from(projectMetadata);
            executionReport.put("phase_1_source_available", model.sourceAvailable());
            executionReport.put("phase_1_source_roots", model.sourceRoots().size());
            executionReport.put("phase_1_test_source_roots", model.testSourceRoots().size());
            executionReport.put("phase_1_class_output_dirs", model.classOutputDirs().size());
            executionReport.put("phase_1_dependency_jars", model.dependencyJars().size());

            if (!projectMetadata.isCompiles()) {
                failureCodes.add(FailureCode.BUILD_FAILED);
                executionReport.put("phase_1_error",
                        "CoCoMUT requires compiled project bytecode. Build the project or provide compiled class files.");
                return false;
            }
            if (model.classOutputDirs().isEmpty() && model.dependencyJars().isEmpty()) {
                failureCodes.add(FailureCode.CALL_GRAPH_UNAVAILABLE);
                executionReport.put("phase_1_error",
                        "CoCoMUT requires compiled class directories or JAR bytecode for static bytecode analysis.");
                return false;
            }

            return true;
        } catch (Exception e) {
            executionReport.put("phase_1_error", e.getMessage());
            return false;
        }
    }

    private boolean executePhase2() {
        try {
            MethodIdentifier identifier = new MethodIdentifier(projectMetadata);

            analysisUniverseMethods = identifier.identify();
            methodInfos = filterScope(analysisUniverseMethods);
            methodInfos = filterMethods(methodInfos);
            methodInfos = limitMethods(methodInfos);

            executionReport.put("phase_2_strategy", scope.toString());
            executionReport.put("phase_2_analysis_universe_methods", analysisUniverseMethods.size());
            executionReport.put("phase_2_methods_identified", methodInfos.size());
            if (methodInfos.isEmpty()) {
                failureCodes.add(FailureCode.EMPTY_SELECTION);
                executionReport.put("phase_2_error", "Selection matched zero methods.");
                return false;
            }
            return true;
        } catch (Exception e) {
            executionReport.put("phase_2_error", e.getMessage());
            failureCodes.add(FailureCode.PARSE_ERROR);
            return false;
        }
    }

    /**
     * Phase 3: Build call graph once and store for reuse in Phase 4.
     */
    private boolean executePhase3() {
        try {
            CallGraphGenerator.Algorithm effectiveAlgorithm = callGraphAlgorithm;
            callGraphGenerator = new CallGraphGenerator(projectMetadata, effectiveAlgorithm);
            if (!callGraphGenerator.initialize()) {
                callGraphResults = new HashMap<>();
                failureCodes.add(FailureCode.CALL_GRAPH_UNAVAILABLE);
                executionReport.put("phase_3_available", false);
                executionReport.put("phase_3_algorithm", callGraphAlgorithm.toString());
                executionReport.put("phase_3_effective_algorithm", effectiveAlgorithm.toString());
                executionReport.put("phase_3_error",
                        "Static bytecode analysis could not be initialized.");
                executionReport.put("phase_3_call_graph_artifact_exists", false);
                executionReport.put("phase_3_call_graphs_generated", 0);
                executionReport.put("phase_3_non_empty_call_graphs", 0);
                executionReport.put("phase_3_call_edges_generated", 0);
                return false;
            }

            callGraphResults = callGraphGenerator.generateForMethods(analysisUniverseMethods, methodInfos);
            int callGraphEdgeCount = callGraphResults.values().stream()
                    .mapToInt(result -> result.getCallerCount() + result.getCalleeCount())
                    .sum();
            long nonEmptyCallGraphResults = callGraphResults.values().stream()
                    .filter(result -> result.getCallerCount() > 0 || result.getCalleeCount() > 0)
                    .count();

            String cgText = callGraphGenerator.getCallGraphText();
            if (!cgText.isEmpty()) {
                Path cgOutputPath = outputRoot().resolve("Output_CallGraph_" + effectiveAlgorithm + ".txt");
                Files.createDirectories(cgOutputPath.getParent());
                Files.writeString(cgOutputPath, cgText);
                executionReport.put("phase_3_call_graph_file", cgOutputPath.toString());
            }

            boolean artifactExists = !cgText.isEmpty() || !callGraphResults.isEmpty();
            boolean hasUsableEdges = callGraphEdgeCount > 0;
            if (!hasUsableEdges) {
                failureCodes.add(FailureCode.CALL_GRAPH_EMPTY);
                executionReport.put("phase_3_warning",
                        "Call graph generated but contained no usable caller/callee edges");
            }
            executionReport.put("phase_3_available", hasUsableEdges);
            executionReport.put("phase_3_algorithm", callGraphAlgorithm.toString());
            executionReport.put("phase_3_effective_algorithm", effectiveAlgorithm.toString());
            executionReport.put("phase_3_call_graph_artifact_exists", artifactExists);
            executionReport.put("phase_3_call_graphs_generated", callGraphResults.size());
            executionReport.put("phase_3_non_empty_call_graphs", nonEmptyCallGraphResults);
            executionReport.put("phase_3_call_edges_generated", callGraphEdgeCount);
            return true;
        } catch (Exception e) {
            executionReport.put("phase_3_error", e.getMessage());
            failureCodes.add(FailureCode.CALL_GRAPH_UNAVAILABLE);
            return false;
        }
    }

    /**
     * Phase 4: Reuse the SAME CallGraphGenerator from Phase 3 so getCachedResult() works.
     */
    private boolean executePhase4() {
        try {
            ContextExtractor extractor = new ContextExtractor(projectMetadata, callGraphGenerator);
            methodContexts = extractor.extractContextForMethods(methodInfos);
            contextExtractionFailures = missingContextFailures(methodInfos, methodContexts);

            executionReport.put("phase_4_contexts_extracted", methodContexts.size());
            if (!contextExtractionFailures.isEmpty()) {
                failureCodes.add(FailureCode.CONTEXT_EXTRACTION_FAILED);
                Path failuresPath = writeContextExtractionFailures(contextExtractionFailures);
                executionReport.put("phase_4_context_failures", contextExtractionFailures.size());
                executionReport.put("phase_4_context_failures_file", failuresPath.toString());
            }
            return true;
        } catch (Exception e) {
            executionReport.put("phase_4_error", e.getMessage());
            failureCodes.add(FailureCode.CONTEXT_EXTRACTION_FAILED);
            return false;
        }
    }

    private List<MethodInfo> filterMethods(List<MethodInfo> methods) {
        List<MethodInfo> filtered = methods;
        if (sourceSets == null || sourceSets.isEmpty()) {
            executionReport.put("phase_2_source_set_filter", "all");
        } else {
            int before = filtered.size();
            filtered = filtered.stream()
                    .filter(method -> sourceSets.contains(method.getSourceSet()))
                    .toList();
            executionReport.put("phase_2_source_set_filter", String.join(",", sourceSets));
            executionReport.put("phase_2_source_set_filter_before", before);
            executionReport.put("phase_2_source_set_filter_after", filtered.size());
        }

        int beforeSelection = filtered.size();
        List<PathMatcher> includeMatchers = pathMatchers(includePathGlobs);
        List<PathMatcher> excludeMatchers = pathMatchers(excludePathGlobs);
        filtered = filtered.stream()
                .filter(this::matchesPackageFilter)
                .filter(this::matchesClassFilter)
                .filter(this::matchesMethodFilter)
                .filter(this::matchesVisibilityFilter)
                .filter(method -> matchesPathFilters(method, includeMatchers, excludeMatchers))
                .filter(this::matchesTargetFilter)
                .toList();
        executionReport.put("phase_2_selection_filter_before", beforeSelection);
        executionReport.put("phase_2_selection_filter_after", filtered.size());
        executionReport.put("phase_2_package_filter", packageFilters.isEmpty() ? "all" : String.join(",", packageFilters));
        executionReport.put("phase_2_class_filter", classFilters.isEmpty() ? "all" : String.join(",", classFilters));
        executionReport.put("phase_2_method_filter", methodFilters.isEmpty() ? "all" : String.join(",", methodFilters));
        executionReport.put("phase_2_visibility_filter", visibilityFilters.isEmpty() ? "all" : String.join(",", visibilityFilters));
        executionReport.put("phase_2_include_path_filter", includePathGlobs.isEmpty() ? "all" : String.join(",", includePathGlobs));
        executionReport.put("phase_2_exclude_path_filter", excludePathGlobs.isEmpty() ? "none" : String.join(",", excludePathGlobs));
        executionReport.put("phase_2_target_filter", targetFilters.isEmpty()
                ? "all"
                : targetFilters.stream().map(SymbolTarget::prefixedUri).toList());
        executionReport.put("selection", selectionProvenance());
        return filtered;
    }

    private List<MethodInfo> filterScope(List<MethodInfo> methods) {
        if (scope == ContextRequest.Scope.ALL) {
            executionReport.put("phase_2_scope_filter_before", methods.size());
            executionReport.put("phase_2_scope_filter_after", methods.size());
            return methods;
        }
        List<MethodInfo> filtered = methods.stream()
                .filter(method -> "public".equals(method.getVisibility())
                        || "protected".equals(method.getVisibility()))
                .filter(method -> !isMain(method))
                .toList();
        executionReport.put("phase_2_scope_filter_before", methods.size());
        executionReport.put("phase_2_scope_filter_after", filtered.size());
        return filtered;
    }

    private static boolean isMain(MethodInfo method) {
        return "main".equals(method.getMethodName())
                && method.isStatic()
                && "void".equals(method.getErasedReturnType())
                && method.getMethodSignature().contains("String[]");
    }

    private void configureSourceFileLimit() {
        if (maxSourceFiles != null && maxSourceFiles > 0) {
            SourceBackends.setMaxSourceFiles(maxSourceFiles);
            executionReport.put("source_max_files", maxSourceFiles);
        }
    }

    private void restoreSourceFileLimit() {
        SourceBackends.clearConfiguration();
    }

    /**
     * Phase 5: Generate JSONL for ALL methods (no sampling) and collect file paths.
     */
    private boolean executePhase5() {
        try {
            if (methodContexts == null || methodContexts.isEmpty()) {
                executionReport.put("phase_5_warning", "No method contexts to generate JSON for");
                executionReport.put("phase_5_files_generated", 0);
                return true;
            }

            Path root = outputRoot();
            Files.createDirectories(root);
            executionReport.put("output_directory", root.toString());

            JsonGenerator jsonGen = new JsonGenerator(root, methodContexts, callGraphGenerator, selectionProvenance());
            Path jsonlPath = root.resolve(outputJsonlFilename());
            int jsonlRows = jsonGen.generateJsonLinesFile(methodContexts, jsonlPath);

            executionReport.put("phase_5_jsonl_file", jsonlPath.toString());
            executionReport.put("phase_5_jsonl_rows", jsonlRows);
            executionReport.put("phase_5_files_generated", jsonlRows);
            executionReport.put("phase_5_call_edges_serialized", serializedCallEdgeCount(methodContexts));
            if (jsonlRows < methodContexts.size()) {
                failureCodes.add(FailureCode.JSON_GENERATION_FAILED);
                executionReport.put("phase_5_error", "JSONL row count is lower than extracted context count.");
                return false;
            }
            return true;
        } catch (Exception e) {
            executionReport.put("phase_5_error", e.getMessage());
            failureCodes.add(FailureCode.JSON_GENERATION_FAILED);
            return false;
        }
    }

    private static int serializedCallEdgeCount(Map<String, MethodContext> contexts) {
        if (contexts == null || contexts.isEmpty()) {
            return 0;
        }
        return contexts.values().stream()
                .map(MethodContext::getCallGraph)
                .filter(Objects::nonNull)
                .mapToInt(callGraph -> callGraph.getCallerCount() + callGraph.getCalleeCount())
                .sum();
    }

    public Map<String, Object> getExecutionReport() {
        return new LinkedHashMap<>(executionReport);
    }

    public ProjectMetadata getProjectMetadata() {
        return projectMetadata;
    }

    public List<MethodInfo> getMethodInfos() {
        return methodInfos != null ? methodInfos : List.of();
    }

    public List<MethodInfo> getAnalysisUniverseMethods() {
        return analysisUniverseMethods != null ? analysisUniverseMethods : List.of();
    }

    public void printReport() {
        System.out.println("\n=== ORCHESTRATOR EXECUTION REPORT ===");
        executionReport.forEach((key, value) ->
                System.out.println(String.format("%s: %s", key, value)));
        System.out.println("=====================================\n");
    }

    public boolean validateConfiguration() {
        return Files.exists(projectPath) && Files.isDirectory(projectPath);
    }

    private List<MethodInfo> limitMethods(List<MethodInfo> methods) {
        if (maxMethods == null || maxMethods <= 0 || methods.size() <= maxMethods) {
            return methods;
        }
        executionReport.put("phase_2_max_methods", maxMethods);
        executionReport.put("phase_2_methods_before_limit", methods.size());
        return new ArrayList<>(methods.subList(0, maxMethods));
    }

    private Map<String, String> missingContextFailures(List<MethodInfo> expected,
                                                       Map<String, MethodContext> actual) {
        Map<String, String> failures = new LinkedHashMap<>();
        Set<String> extracted = actual != null ? actual.keySet() : Set.of();
        for (MethodInfo method : expected) {
            if (!extracted.contains(method.getMethodUri())) {
                failures.put(method.getMethodUri(), "CONTEXT_EXTRACTION_FAILED");
            }
        }
        return failures;
    }

    private Path writeContextExtractionFailures(Map<String, String> failures)
            throws java.io.IOException {
        Path output = outputRoot().resolve("method_context_failures.jsonl");
        Files.createDirectories(output.getParent());
        try (var writer = Files.newBufferedWriter(output)) {
            for (MethodInfo method : methodInfos) {
                if (!failures.containsKey(method.getMethodUri())) {
                    continue;
                }
                ObjectNode node = OBJECT_MAPPER.createObjectNode();
                node.put("phase", "context_extraction");
                node.put("failure_code", failures.get(method.getMethodUri()));
                node.put("method_uri", method.getMethodUri());
                node.put("class_name", method.getClassname());
                node.put("method_name", method.getMethodName());
                node.put("signature", method.getMethodSignature());
                node.put("source_file", method.getSourceFile().toString());
                node.put("line_number", method.getLineNumber());
                writer.write(OBJECT_MAPPER.writeValueAsString(node));
                writer.newLine();
            }
        }
        return output;
    }

    private void writeExecutionReportIfPossible() {
        try {
            Path reportPath = outputRoot().resolve("extraction_report.json");
            Files.createDirectories(reportPath.getParent());
            executionReport.put("extraction_report_file", reportPath.toString());
            OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValue(reportPath.toFile(), executionReport);
        } catch (Exception e) {
            executionReport.put("extraction_report_error", e.getMessage());
        }
    }

    private static boolean hasJavaSources(Path sourceRoot) {
        if (sourceRoot == null || !Files.isDirectory(sourceRoot)) {
            return false;
        }
        try (var stream = Files.walk(sourceRoot)) {
            return stream.anyMatch(p -> p.toString().endsWith(".java"));
        } catch (Exception e) {
            return false;
        }
    }

    private boolean matchesPackageFilter(MethodInfo method) {
        if (packageFilters.isEmpty()) {
            return true;
        }
        String className = method.getClassname();
        int lastDot = className.lastIndexOf('.');
        String pkg = lastDot >= 0 ? className.substring(0, lastDot) : "";
        return packageFilters.stream().anyMatch(filter ->
                pkg.equals(filter) || pkg.startsWith(filter + "."));
    }

    private boolean matchesClassFilter(MethodInfo method) {
        if (classFilters.isEmpty()) {
            return true;
        }
        String className = method.getClassname();
        String simple = className.substring(className.lastIndexOf('.') + 1);
        return classFilters.stream().anyMatch(filter -> className.equals(filter) || simple.equals(filter));
    }

    private boolean matchesMethodFilter(MethodInfo method) {
        if (methodFilters.isEmpty()) {
            return true;
        }
        return methodFilters.stream().anyMatch(filter ->
                method.getMethodName().equals(filter)
                        || method.getMethodUri().equals(filter)
                        || method.getMethodUri().contains(filter));
    }

    private boolean matchesVisibilityFilter(MethodInfo method) {
        return visibilityFilters.isEmpty() || visibilityFilters.contains(method.getVisibility());
    }

    private boolean matchesPathFilters(MethodInfo method, List<PathMatcher> includes, List<PathMatcher> excludes) {
        Path relative = relativeSourceFile(method);
        boolean included = includes.isEmpty() || includes.stream().anyMatch(matcher -> matcher.matches(relative));
        boolean excluded = excludes.stream().anyMatch(matcher -> matcher.matches(relative));
        return included && !excluded;
    }

    private boolean matchesTargetFilter(MethodInfo method) {
        if (targetFilters.isEmpty()) {
            return true;
        }
        String methodUri = method.getMethodUri();
        String typeUri = typeUri(method);
        String packageUri = packageUri(method);
        for (SymbolTarget target : targetFilters) {
            if (target.kind() == SymbolTarget.Kind.METHOD && methodUri.equals(target.uri())) {
                return true;
            }
            if (target.kind() == SymbolTarget.Kind.TYPE && typeUri.equals(target.uri())) {
                return true;
            }
            if (target.kind() == SymbolTarget.Kind.PACKAGE && packageUri.equals(target.uri())) {
                return true;
            }
            if (target.kind() == SymbolTarget.Kind.PROJECT) {
                return true;
            }
        }
        return false;
    }

    private Path relativeSourceFile(MethodInfo method) {
        try {
            return projectPath.toAbsolutePath().normalize()
                    .relativize(method.getSourceFile().toAbsolutePath().normalize());
        } catch (IllegalArgumentException e) {
            return method.getSourceFile();
        }
    }

    private String typeUri(MethodInfo method) {
        return relativeSourceFile(method).toString().replace('\\', '/') + "#" + method.getClassname();
    }

    private String packageUri(MethodInfo method) {
        String className = method.getClassname();
        int lastDot = className.lastIndexOf('.');
        String packageName = lastDot >= 0 ? className.substring(0, lastDot) : "";
        Path relativeFile = relativeSourceFile(method);
        Path packageDir = relativeFile.getParent();
        Path packageInfo = packageDir != null ? packageDir.resolve("package-info.java") : Path.of("package-info.java");
        Path absolutePackageInfo = projectPath.toAbsolutePath().normalize().resolve(packageInfo).normalize();
        String anchor = Files.isRegularFile(absolutePackageInfo)
                ? packageInfo.toString().replace('\\', '/')
                : (packageDir != null ? packageDir.toString().replace('\\', '/') + "/" : "");
        return anchor + "#" + packageName;
    }

    private Map<String, Object> selectionProvenance() {
        Map<String, Object> selection = new LinkedHashMap<>();
        selection.put("scope", scope.toString().toLowerCase(Locale.ROOT));
        selection.put("source_sets", sourceSets.isEmpty() ? List.of("all") : sourceSets.stream().sorted().toList());
        selection.put("packages", packageFilters.stream().sorted().toList());
        selection.put("classes", classFilters.stream().sorted().toList());
        selection.put("methods", methodFilters.stream().sorted().toList());
        selection.put("visibilities", visibilityFilters.stream().sorted().toList());
        selection.put("include_paths", includePathGlobs.stream().sorted().toList());
        selection.put("exclude_paths", excludePathGlobs.stream().sorted().toList());
        selection.put("max_methods", maxMethods);
        selection.put("max_source_files", maxSourceFiles);
        if (targetFilters.isEmpty()) {
            selection.put("kind", "project");
            selection.put("uri", projectUri());
            selection.put("project_name", projectName());
            selection.put("selector", selectionLabel().isBlank() ? "project" : selectionLabel());
            return selection;
        }
        if (targetFilters.size() == 1) {
            SymbolTarget target = targetFilters.iterator().next();
            selection.put("kind", target.kind().name().toLowerCase(Locale.ROOT));
            selection.put("uri", target.uri());
            selection.put("selector", target.prefixedUri());
            return selection;
        }
        selection.put("kind", "multiple");
        selection.put("targets", targetFilters.stream()
                .map(target -> Map.of(
                        "kind", target.kind().name().toLowerCase(Locale.ROOT),
                        "uri", target.uri()))
                .toList());
        return selection;
    }

    private static List<PathMatcher> pathMatchers(Set<String> globs) {
        return globs.stream()
                .map(glob -> FileSystems.getDefault().getPathMatcher("glob:" + glob))
                .toList();
    }

    private Path outputRoot() {
        if (outputDirectory != null) {
            return outputDirectory.toAbsolutePath().normalize();
        }
        return Path.of(System.getProperty("user.dir"))
                .resolve("cocomut_output")
                .resolve(sanitize(projectName()) + "-" + shortProjectHash())
                .toAbsolutePath()
                .normalize();
    }

    private String projectName() {
        return projectPath.getFileName() != null ? projectPath.getFileName().toString() : "project";
    }

    private String projectUri() {
        return "project://" + sanitize(projectName());
    }

    private String shortProjectHash() {
        String normalized = projectPath.toAbsolutePath().normalize().toString();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 4);
        } catch (Exception e) {
            return Integer.toHexString(normalized.hashCode());
        }
    }

    private String outputJsonlFilename() {
        String selection = selectionLabel();
        return selection.isBlank() ? "method_contexts.jsonl" : sanitize(selection) + ".jsonl";
    }

    private String selectionLabel() {
        if (!methodFilters.isEmpty()) {
            return "method__" + String.join("__", methodFilters);
        }
        if (!targetFilters.isEmpty()) {
            return targetFilters.size() == 1
                    ? targetFilters.iterator().next().prefixedUri()
                    : "targets__" + targetFilters.size();
        }
        if (!classFilters.isEmpty()) {
            return "class__" + String.join("__", classFilters);
        }
        if (!packageFilters.isEmpty()) {
            return "package__" + String.join("__", packageFilters);
        }
        return "";
    }

    private static String sanitize(String value) {
        String sanitized = value.replaceAll("[^A-Za-z0-9._#()\\-]+", "_");
        return sanitized.length() > 180 ? sanitized.substring(0, 180) : sanitized;
    }
}
