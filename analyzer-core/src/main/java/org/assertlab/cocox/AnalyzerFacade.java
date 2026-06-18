package org.assertlab.cocox;

import org.assertlab.cocox.adapter.ProjectAdapter;
import org.assertlab.cocox.strategy.CsvSelectedStrategy;
import org.assertlab.cocox.strategy.MethodSourceStrategy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/**
 * Single entry point for the method context extraction pipeline.
 *
 * <p>Pass any Java project directory — Maven, Gradle, or plain — and the facade
 * will auto-detect the build system ({@link ProjectAdapter}) and the method
 * source ({@link MethodSourceStrategy}), then run all 6 pipeline phases.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Analyse any project — one line:
 * Map<String, Object> report = AnalyzerFacade.analyze(Path.of("/projects/my-library"));
 *
 * // OE-25 research mode (inputs_selected.csv present):
 * //   → auto-detects CsvSelectedStrategy, runs in SELECTED mode
 *
 * // Generic project (no CSV):
 * //   → auto-detects ScanAllSourcesStrategy, runs in FULL mode
 * }</pre>
 *
 * <h2>Auto-detection logic</h2>
 * <pre>
 *  Project root
 *   ├── pom.xml?          → MavenProjectAdapter
 *   ├── build.gradle?     → GradleProjectAdapter
 *   └── (fallback)        → GenericJavaAdapter
 *
 *   ├── inputs_selected.csv? → CsvSelectedStrategy  (SELECTED mode)
 *   └── (fallback)           → ScanAllSourcesStrategy (FULL mode)
 * </pre>
 *
 * <h2>Design notes</h2>
 * The facade delegates all execution to the existing {@link Orchestrator}. The
 * {@link ProjectAdapter} and {@link MethodSourceStrategy} layers translate project
 * diversity into the two types the Orchestrator already understands:
 * {@link Orchestrator.ExecutionMode#SELECTED} and {@link Orchestrator.ExecutionMode#FULL}.
 * No pipeline phase (1–6) is modified.
 */
public class AnalyzerFacade {

    private AnalyzerFacade() {
        // utility class — not instantiated
    }

    /**
     * Analyse a Java project and return the pipeline execution report.
     *
     * <p>The method auto-detects the build system and method source, reuses
     * existing compiled classes when present, and runs all 6 pipeline phases. On success the
     * report contains {@code "status": "SUCCESS"} and per-phase statistics.
     * On failure the report contains {@code "status": "FAILED"} with the
     * failing phase number and error message.
     *
     * @param projectPath absolute or relative path to the project root directory
     * @return execution report map (same structure as {@link Orchestrator#getExecutionReport()})
     * @throws IOException if the project path cannot be read or the build system
     *                     cannot produce a classpath
     */
    public static Map<String, Object> analyze(Path projectPath) throws IOException {
        // Auto-detect method source — CSV (research) or scan-all (generic)
        return analyze(projectPath, MethodSourceStrategy.detect(projectPath));
    }

    public static Map<String, Object> analyze(Path projectPath, AnalysisOptions options)
            throws IOException {
        Objects.requireNonNull(options, "options cannot be null");
        ProjectAdapter adapter = ProjectAdapter.of(projectPath);
        System.out.printf("[AnalyzerFacade] Project: %s | Adapter: %s | Scope: %s%n",
                projectPath.getFileName(),
                adapter.getClass().getSimpleName(),
                options.scope());

        Orchestrator orchestrator = buildOrchestrator(projectPath, options);
        orchestrator.execute();
        return orchestrator.getExecutionReport();
    }

    /**
     * Analyse a Java project with an explicitly chosen method-source strategy.
     *
     * <p>Use this overload to opt into a non-default strategy such as
     * {@link org.assertlab.cocox.strategy.EntryPointScanStrategy} (public methods only):
     * <pre>{@code
     * AnalyzerFacade.analyze(path, new EntryPointScanStrategy());
     * }</pre>
     *
     * @param projectPath path to the project root
     * @param strategy    the method-source strategy to use
     * @return execution report map
     * @throws IOException if the project cannot be read
     */
    public static Map<String, Object> analyze(Path projectPath, MethodSourceStrategy strategy)
            throws IOException {
        // Detect build system — Maven, Gradle, or generic fallback
        ProjectAdapter adapter = ProjectAdapter.of(projectPath);

        System.out.printf("[AnalyzerFacade] Project: %s | Adapter: %s | Strategy: %s%n",
                projectPath.getFileName(),
                adapter.getClass().getSimpleName(),
                strategy.name());

        // Wire into Orchestrator — no pipeline phases changed
        Orchestrator orchestrator = buildOrchestrator(projectPath, strategy);
        orchestrator.execute();
        return orchestrator.getExecutionReport();
    }

    /**
     * Analyse a project and print the execution report to stdout.
     *
     * <p>Convenience wrapper for CLI and script usage.
     *
     * @param projectPath path to the project root
     * @throws IOException if analysis fails at the project-detection stage
     */
    public static void analyzeAndPrint(Path projectPath) throws IOException {
        Map<String, Object> report = analyze(projectPath);
        System.out.println("\n=== AnalyzerFacade Report ===");
        report.forEach((k, v) -> System.out.printf("  %s: %s%n", k, v));
        System.out.println("=============================");
    }

    // -----------------------------------------------------------------------
    // Internal wiring
    // -----------------------------------------------------------------------

    /**
     * Translate the detected strategy into the appropriate {@link Orchestrator}
     * execution mode.  This keeps the facade's auto-detection logic out of the
     * Orchestrator, preserving backward compatibility for callers that already
     * construct Orchestrator directly.
     */
    private static Orchestrator buildOrchestrator(Path projectPath,
                                                   MethodSourceStrategy strategy) {
        if (strategy instanceof CsvSelectedStrategy csvStrategy) {
            // SELECTED mode: Orchestrator reads the pre-curated CSV in Phase 2
            return new Orchestrator(projectPath, Orchestrator.ExecutionMode.SELECTED)
                    .setInputCsvPath(csvStrategy.getCsvPath());
        }
        // Any other strategy (e.g., EntryPointScanStrategy): FULL mode with the
        // strategy injected into Phase 2 via the override hook.
        return new Orchestrator(projectPath, Orchestrator.ExecutionMode.FULL)
                .setMethodSourceStrategy(strategy);
    }

    private static Orchestrator buildOrchestrator(Path projectPath, AnalysisOptions options) {
        Orchestrator orchestrator;
        if (options.scope() == AnalysisOptions.Scope.SELECTED) {
            orchestrator = new Orchestrator(projectPath, Orchestrator.ExecutionMode.SELECTED)
                    .setInputCsvPath(options.selectedCsv());
        } else {
            orchestrator = new Orchestrator(projectPath, Orchestrator.ExecutionMode.FULL)
                    .setMethodSourceStrategy(options.methodSourceStrategy());
        }
        return orchestrator
                .setCallGraphAlgorithm(options.callGraphAlgorithm())
                .setMaxMethods(options.maxMethods())
                .setMaxSourceFiles(options.maxSourceFiles())
                .setAttemptCompile(options.attemptCompile())
                .setSourceResolution(options.sourceResolution())
                .setOutputMode(options.outputMode())
                .setSourceSets(options.sourceSets())
                .setPackageFilters(options.packages())
                .setClassFilters(options.classes())
                .setMethodFilters(options.methods())
                .setVisibilityFilters(options.visibilities())
                .setIncludePathGlobs(options.includePathGlobs())
                .setExcludePathGlobs(options.excludePathGlobs())
                .setTargetFilters(options.targets())
                .setOutputDirectory(options.outputDirectory());
    }
}
