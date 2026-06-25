package org.assertlab.cocomut;

import org.assertlab.cocomut.source.ProjectModel;
import org.assertlab.cocomut.source.SourceAnalysisSession;
import org.assertlab.cocomut.source.SourceBackends;
import org.assertlab.cocomut.source.SourceModelBackend;
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
    private ContextRequest request;
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
    private ProjectMetadata providedProjectMetadata;
    private ContextRequest.BuildPolicy buildPolicy = ContextRequest.BuildPolicy.DENY_BUILD;
    private boolean allowPreexistingBytecodeAfterBuildFailure = false;
    private List<Path> explicitClassOutputDirs = List.of();
    private List<Path> explicitTestClassOutputDirs = List.of();
    private List<Path> explicitProjectJars = List.of();
    private List<Path> explicitDependencyJars = List.of();
    private List<Path> explicitClasspathFiles = List.of();
    private List<Path> explicitSourceRoots = List.of();
    private List<Path> explicitTestSourceRoots = List.of();
    private RunSnapshot runSnapshot;

    // Pipeline state passed between phases
    private ProjectMetadata projectMetadata;
    private ProjectModel projectModel;
    private SourceAnalysisSession sourceSession;
    private List<MethodInfo> analysisUniverseMethods;
    private List<MethodInfo> methodInfos;
    private CallGraphGenerator callGraphGenerator;
    private Map<String, CallGraphResult> callGraphResults;
    private Map<String, MethodContext> methodContexts;
    private Map<String, String> contextExtractionFailures = new LinkedHashMap<>();
    private final Set<FailureCode> failureCodes = new LinkedHashSet<>();
    private ExtractionManifest.GitInfo gitAtStart;

    Orchestrator(Path projectPath) {
        this.projectPath = Objects.requireNonNull(projectPath, "projectPath cannot be null");
        this.executionReport = new LinkedHashMap<>();
    }

    Orchestrator(ContextRequest request) {
        this(request.projectRoot());
        this.request = request;
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
        this.buildPolicy = request.buildPolicy();
        this.allowPreexistingBytecodeAfterBuildFailure = request.allowPreexistingBytecodeAfterBuildFailure();
        this.explicitClassOutputDirs = request.classOutputDirs();
        this.explicitTestClassOutputDirs = request.testClassOutputDirs();
        this.explicitProjectJars = request.projectJars();
        this.explicitDependencyJars = request.dependencyJars();
        this.explicitClasspathFiles = request.classpathFiles();
        this.explicitSourceRoots = request.sourceRoots();
        this.explicitTestSourceRoots = request.testSourceRoots();
    }

    Orchestrator(ContextRequest request, ProjectMetadata metadata) {
        this(request);
        this.providedProjectMetadata = Objects.requireNonNull(metadata, "metadata cannot be null");
    }

    Orchestrator(ContextRequest request, ProjectMetadata metadata, RunSnapshot runSnapshot) {
        this(request, metadata);
        this.runSnapshot = runSnapshot;
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
        long startTime = runSnapshot != null ? runSnapshot.startMillis() : System.currentTimeMillis();
        executionReport.put("start_time", new java.util.Date(startTime));
        executionReport.put("pipeline_phases", 5);
        gitAtStart = runSnapshot != null
                ? runSnapshot.projectGitAtStart()
                : ExtractionManifest.captureGitInfo(projectPath);

        boolean success = false;
        try {
            if (!executePhase1()) { executionReport.put("status", "FAILED"); executionReport.put("failed_at_phase", 1); return false; }
            configureSourceFileLimit();
            openSourceSession();
            if (!executePhase2()) { executionReport.put("status", "FAILED"); executionReport.put("failed_at_phase", 2); return false; }
            if (!executePhase3()) { executionReport.put("status", "FAILED"); executionReport.put("failed_at_phase", 3); return false; }
            if (!executePhase4()) { executionReport.put("status", "FAILED"); executionReport.put("failed_at_phase", 4); return false; }
            if (!executePhase5()) { executionReport.put("status", "FAILED"); executionReport.put("failed_at_phase", 5); return false; }

            if (failureCodes.isEmpty()) {
                executionReport.put("status", "SUCCESS");
                success = true;
            } else {
                executionReport.put("status", "PARTIAL");
            }
            executionReport.put("completed_phases", 5);
            return success;

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
            writeManifestIfPossible();
            executionReport.put("failure_codes", failureCodes.isEmpty()
                    ? List.of(FailureCode.NONE.toString())
                    : failureCodes.stream().map(Enum::toString).toList());
            writeExecutionReportIfPossible();
            closeSourceSession();
            restoreSourceFileLimit();
        }
    }

    private boolean executePhase1() {
        try {
            if (providedProjectMetadata != null) {
                projectMetadata = providedProjectMetadata;
            } else {
                ProjectAnalyzer analyzer = new ProjectAnalyzer(projectPath, true, "auto", includeTestBytecode(),
                        buildPolicy,
                        allowPreexistingBytecodeAfterBuildFailure,
                        explicitClassOutputDirs,
                        explicitTestClassOutputDirs,
                        explicitProjectJars,
                        explicitDependencyJars,
                        explicitClasspathFiles,
                        explicitSourceRoots,
                        explicitTestSourceRoots);
                projectMetadata = analyzer.analyze();
            }

            executionReport.put("phase_1_project", projectMetadata.getProjectName());
            executionReport.put("phase_1_build_system", projectMetadata.getBuildSystem());
            executionReport.put("phase_1_java_version", projectMetadata.getJavaVersion());
            executionReport.put("phase_1_compiles", projectMetadata.isCompiles());
            executionReport.put("phase_1_compile_status", projectMetadata.getCompileStatus());
            executionReport.put("phase_1_build_attempted", projectMetadata.isBuildAttempted());
            executionReport.put("phase_1_build_exit_code", projectMetadata.getBuildExitCode());
            executionReport.put("phase_1_build_succeeded", projectMetadata.isBuildSucceeded());
            executionReport.put("phase_1_build_timed_out", projectMetadata.isBuildTimedOut());
            executionReport.put("phase_1_build_skipped", projectMetadata.isBuildSkipped());
            executionReport.put("phase_1_build_sandboxed", projectMetadata.isBuildSandboxed());
            executionReport.put("phase_1_gradle_model", projectMetadata.getGradleModelReport());
            if (projectMetadata.getGradleModelReport().partial()
                    || (projectMetadata.getGradleModelReport().attempted()
                    && !projectMetadata.getGradleModelReport().succeeded())) {
                failureCodes.add(FailureCode.MODEL_RESOLUTION_PARTIAL);
            }
            executionReport.put("phase_1_build_execution_policy", projectMetadata.getBuildPolicy().toString());
            executionReport.put("phase_1_allow_preexisting_bytecode_after_build_failure",
                    projectMetadata.isAllowPreexistingBytecodeAfterBuildFailure());
            executionReport.put("phase_1_bytecode_available", projectMetadata.isBytecodeAvailable());
            executionReport.put("phase_1_bytecode_origin", projectMetadata.getBytecodeOrigin());
            executionReport.put("phase_1_analysis_can_proceed", projectMetadata.isAnalysisCanProceed());
            projectModel = ProjectModel.from(projectMetadata);
            executionReport.put("phase_1_source_available", projectModel.sourceAvailable());
            executionReport.put("phase_1_source_roots", projectModel.sourceRoots().size());
            executionReport.put("phase_1_test_source_roots", projectModel.testSourceRoots().size());
            executionReport.put("phase_1_class_output_dirs", projectModel.classOutputDirs().size());
            executionReport.put("phase_1_main_class_outputs", projectMetadata.getMainClassOutputs().size());
            executionReport.put("phase_1_test_class_outputs", projectMetadata.getTestClassOutputs().size());
            executionReport.put("phase_1_project_artifact_jars", projectMetadata.getProjectArtifactJars().size());
            executionReport.put("phase_1_project_bytecode_locations", projectBytecodeLocations().size());
            executionReport.put("phase_1_dependency_locations", projectMetadata.getDependencyClasspath().size());
            executionReport.put("phase_1_dependency_jars", projectModel.dependencyJars().size());
            executionReport.put("phase_1_explicit_class_outputs", projectMetadata.getExplicitClassOutputDirs().size());
            executionReport.put("phase_1_explicit_test_class_outputs",
                    projectMetadata.getExplicitTestClassOutputDirs().size());
            executionReport.put("phase_1_explicit_project_jars", projectMetadata.getExplicitProjectJars().size());
            executionReport.put("phase_1_explicit_dependency_jars", projectMetadata.getExplicitDependencyJars().size());
            executionReport.put("phase_1_explicit_classpath_files", projectMetadata.getExplicitClasspathFiles().size());

            if (!projectMetadata.isAnalysisCanProceed()) {
                failureCodes.add(FailureCode.BUILD_FAILED);
                executionReport.put("phase_1_error", projectMetadata.getCompileStatus());
                return false;
            }
            if (projectBytecodeLocations().isEmpty()) {
                failureCodes.add(FailureCode.CALL_GRAPH_UNAVAILABLE);
                executionReport.put("phase_1_error",
                        "CoCoMUT requires project class directories or project artifact JARs for static bytecode analysis.");
                return false;
            }

            return true;
        } catch (Exception e) {
            executionReport.put("phase_1_error", e.getMessage());
            return false;
        }
    }

    private boolean includeTestBytecode() {
        return sourceSets == null || sourceSets.isEmpty()
                || sourceSets.stream().anyMatch(set -> !"main".equals(set));
    }

    private List<Path> projectBytecodeLocations() {
        if (projectMetadata == null) {
            return List.of();
        }
        List<Path> locations = new ArrayList<>();
        locations.addAll(projectMetadata.getMainClassOutputs());
        locations.addAll(projectMetadata.getTestClassOutputs());
        locations.addAll(projectMetadata.getProjectArtifactJars());
        return locations;
    }

    private boolean executePhase2() {
        try {
            MethodIdentifier identifier = new MethodIdentifier(projectMetadata);

            analysisUniverseMethods = identifier.identify(sourceSession);
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
            long matchedToBytecode = callGraphResults.values().stream()
                    .filter(CallGraphResult::isMethodMatched)
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
                executionReport.put("phase_3_warning",
                        "Call graph generated but contained no usable caller/callee edges");
            }
            executionReport.put("phase_3_available", hasUsableEdges);
            executionReport.put("phase_3_algorithm", callGraphAlgorithm.toString());
            executionReport.put("phase_3_effective_algorithm", effectiveAlgorithm.toString());
            executionReport.put("phase_3_call_graph_artifact_exists", artifactExists);
            executionReport.put("phase_3_call_graphs_generated", callGraphResults.size());
            executionReport.put("phase_3_focal_methods_matched_to_bytecode", matchedToBytecode);
            executionReport.put("phase_3_non_empty_call_graphs", nonEmptyCallGraphResults);
            executionReport.put("phase_3_call_edges_generated", callGraphEdgeCount);
            if (callGraphResults.size() != methodInfos.size() || matchedToBytecode != methodInfos.size()) {
                failureCodes.add(FailureCode.CALL_GRAPH_UNAVAILABLE);
                executionReport.put("phase_3_warning",
                        "One or more selected methods did not receive a matched bytecode call graph result.");
            }
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
            ContextExtractor extractor = new ContextExtractor(projectMetadata, callGraphGenerator, sourceSession);
            methodContexts = extractor.extractContextForMethods(methodInfos);
            contextExtractionFailures = missingContextFailures(methodInfos, methodContexts);

            executionReport.put("phase_4_contexts_extracted", methodContexts.size());
            if (!contextExtractionFailures.isEmpty()) {
                failureCodes.add(FailureCode.CONTEXT_EXTRACTION_FAILED);
                Path failuresPath = writeContextExtractionFailures(contextExtractionFailures);
                executionReport.put("phase_4_context_failures", contextExtractionFailures.size());
                executionReport.put("phase_4_context_failures_file", failuresPath.toString());
            }
            if (methodContexts.size() != methodInfos.size()) {
                executionReport.put("phase_4_warning",
                        "One or more selected methods did not produce a method context.");
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
                && method.getErasedParameterTypes().equals(List.of("java.lang.String[]"));
    }

    private void configureSourceFileLimit() {
        if (maxSourceFiles != null && maxSourceFiles > 0) {
            SourceBackends.setMaxSourceFiles(maxSourceFiles);
            executionReport.put("source_max_files", maxSourceFiles);
        }
    }

    private void openSourceSession() throws java.io.IOException {
        SourceModelBackend backend = SourceBackends.spoon();
        sourceSession = backend.open(projectModel);
        executionReport.put("source_backend", backend.name());
        var stats = sourceSession.parseStats();
        executionReport.put("source_files_discovered", stats.discovered());
        executionReport.put("source_files_parsed", stats.parsed());
        executionReport.put("source_files_failed", stats.failed());
        if (stats.failed() > 0) {
            failureCodes.add(FailureCode.SOURCE_PARSE_FAILED);
            Path failures = writeFailedSourceFiles(stats.failedFiles());
            executionReport.put("failed_source_files_file", failures.toString());
        }
    }

    private Path writeFailedSourceFiles(List<Path> failedFiles) throws java.io.IOException {
        Path output = outputRoot().resolve("failed_source_files.jsonl");
        Files.createDirectories(output.getParent());
        try (var writer = Files.newBufferedWriter(output)) {
            for (Path file : failedFiles) {
                ObjectNode node = OBJECT_MAPPER.createObjectNode();
                node.put("failure_code", FailureCode.SOURCE_PARSE_FAILED.toString());
                node.put("source_file", relativePathString(file));
                writer.write(OBJECT_MAPPER.writeValueAsString(node));
                writer.newLine();
            }
        }
        return output;
    }

    private String relativePathString(Path file) {
        try {
            return projectPath.toAbsolutePath().normalize()
                    .relativize(file.toAbsolutePath().normalize())
                    .toString().replace('\\', '/');
        } catch (Exception e) {
            return file.toString().replace('\\', '/');
        }
    }

    private void closeSourceSession() {
        if (sourceSession == null) {
            return;
        }
        try {
            sourceSession.close();
        } catch (Exception e) {
            executionReport.put("source_session_close_error", e.getMessage());
        } finally {
            sourceSession = null;
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
                failureCodes.add(FailureCode.JSON_GENERATION_FAILED);
                return false;
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
            if (jsonlRows != methodContexts.size() || jsonlRows != methodInfos.size()) {
                failureCodes.add(FailureCode.JSON_GENERATION_FAILED);
                executionReport.put("phase_5_warning",
                        "JSONL row count does not match selected method count.");
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
        List<MethodInfo> ordered = methods.stream()
                .sorted(Comparator.comparing((MethodInfo method) -> relativeSourceFile(method).toString())
                        .thenComparingInt(MethodInfo::getLineNumber)
                        .thenComparingInt(MethodInfo::getColumnNumber)
                        .thenComparing(MethodInfo::getMethodUri))
                .toList();
        return new ArrayList<>(ordered.subList(0, maxMethods));
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

    private void writeManifestIfPossible() {
        try {
            Path root = outputRoot();
            Files.createDirectories(root);
            Path jsonlPath = null;
            Object jsonl = executionReport.get("phase_5_jsonl_file");
            if (jsonl != null && !String.valueOf(jsonl).isBlank()) {
                jsonlPath = Path.of(String.valueOf(jsonl));
            }
            Path manifestPath = runSnapshot != null
                    ? ExtractionManifest.write(root, projectMetadata, projectModel,
                            selectionProvenance(), requestHash(), jsonlPath, executionReport, runSnapshot)
                    : ExtractionManifest.write(root, projectMetadata, projectModel,
                            selectionProvenance(), requestHash(), jsonlPath, executionReport, gitAtStart);
            executionReport.put("extraction_manifest_file", manifestPath.toString());
            Object hashFailures = executionReport.get("provenance_hash_failures");
            if (hashFailures instanceof Collection<?> failures && !failures.isEmpty()) {
                failureCodes.add(FailureCode.PROVENANCE_FAILED);
                if ("SUCCESS".equals(executionReport.get("status"))) {
                    executionReport.put("status", "PARTIAL");
                }
            }
        } catch (Exception e) {
            executionReport.put("extraction_manifest_error", e.getMessage());
            failureCodes.add(FailureCode.PROVENANCE_FAILED);
            if ("SUCCESS".equals(executionReport.get("status"))) {
                executionReport.put("status", "PARTIAL");
            }
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
                .resolve(sanitize(projectName()) + "-" + requestHashPrefix())
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
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (Exception e) {
            return Integer.toHexString(normalized.hashCode());
        }
    }

    private String outputJsonlFilename() {
        String selection = selectionLabel();
        String base = selection.isBlank() ? "method_contexts" : sanitize(selection);
        return base + "__" + requestHashPrefix() + ".jsonl";
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

    private String requestHash() {
        if (runSnapshot != null) {
            return runSnapshot.requestHash();
        }
        if (request != null) {
            return RequestFingerprint.hash(request);
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("schema", "cocomut-request-v2");
        request.put("project_selector", "project-root");
        request.put("project_name", projectName());
        request.put("scope", scope.toString());
        request.put("source_sets", sourceSets.stream().sorted().toList());
        request.put("packages", packageFilters.stream().sorted().toList());
        request.put("classes", classFilters.stream().sorted().toList());
        request.put("methods", methodFilters.stream().sorted().toList());
        request.put("visibilities", visibilityFilters.stream().sorted().toList());
        request.put("include_paths", includePathGlobs.stream().sorted().toList());
        request.put("exclude_paths", excludePathGlobs.stream().sorted().toList());
        request.put("targets", targetFilters.stream().map(SymbolTarget::prefixedUri).sorted().toList());
        request.put("max_methods", maxMethods);
        request.put("max_source_files", maxSourceFiles);
        request.put("call_graph", callGraphAlgorithm.toString());
        request.put("build_policy", buildPolicy.toString());
        request.put("allow_preexisting_bytecode_after_build_failure", allowPreexistingBytecodeAfterBuildFailure);
        request.put("class_outputs", artifactIdentities(explicitClassOutputDirs));
        request.put("test_class_outputs", artifactIdentities(explicitTestClassOutputDirs));
        request.put("project_jars", artifactIdentities(explicitProjectJars));
        request.put("dependency_jars", artifactIdentities(explicitDependencyJars));
        request.put("classpath_files", artifactIdentities(explicitClasspathFiles));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(OBJECT_MAPPER.writeValueAsBytes(request));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            return sha256Hex("cocomut-request-fallback|" + request);
        }
    }

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            return "0".repeat(64);
        }
    }

    private List<String> artifactIdentities(List<Path> paths) {
        return (paths == null ? List.<Path>of() : paths).stream()
                .filter(Objects::nonNull)
                .map(path -> artifactIdentity(path.toAbsolutePath().normalize()))
                .toList();
    }

    private String artifactIdentity(Path path) {
        String rolePath = stableRequestPath(path);
        String content = contentDigest(path);
        return rolePath + ":" + content;
    }

    private String stableRequestPath(Path path) {
        Path root = projectPath.toAbsolutePath().normalize();
        try {
            if (path.startsWith(root)) {
                return "project:" + root.relativize(path).toString().replace('\\', '/');
            }
        } catch (Exception ignored) {
            // Fall through to location-independent external identity.
        }
        Path name = path.getFileName();
        return "external:" + (name == null ? "artifact" : name.toString());
    }

    private static String contentDigest(Path path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            if (Files.isRegularFile(path)) {
                try (var input = Files.newInputStream(path)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = input.read(buffer)) >= 0) {
                        digest.update(buffer, 0, read);
                    }
                }
                return HexFormat.of().formatHex(digest.digest());
            }
            if (Files.isDirectory(path)) {
                try (var walk = Files.walk(path)) {
                    for (Path file : walk.filter(Files::isRegularFile)
                            .sorted(Comparator.comparing(p -> path.relativize(p).toString()))
                            .toList()) {
                        digest.update(path.relativize(file).toString().replace('\\', '/')
                                .getBytes(StandardCharsets.UTF_8));
                        digest.update((byte) 0);
                        try (var input = Files.newInputStream(file)) {
                            byte[] buffer = new byte[8192];
                            int read;
                            while ((read = input.read(buffer)) >= 0) {
                                digest.update(buffer, 0, read);
                            }
                        }
                        digest.update((byte) 0);
                    }
                }
                return HexFormat.of().formatHex(digest.digest());
            }
            return "missing";
        } catch (Exception e) {
            return "error:" + e.getClass().getSimpleName();
        }
    }

    private String requestHashPrefix() {
        String hash = requestHash();
        return hash.length() > 16 ? hash.substring(0, 16) : hash;
    }

    private static String sanitize(String value) {
        String sanitized = value.replaceAll("[^A-Za-z0-9._#()\\-]+", "_");
        return sanitized.length() > 180 ? sanitized.substring(0, 180) : sanitized;
    }
}
