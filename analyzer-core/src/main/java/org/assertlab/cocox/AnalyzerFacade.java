package org.assertlab.cocox;

import org.assertlab.cocox.adapter.ProjectAdapter;
import org.assertlab.cocox.strategy.MethodSourceStrategy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/**
 * Single entry point for the method context extraction pipeline.
 *
 * <p>Pass any Java project directory — Maven, Gradle, or plain — and the facade
 * will auto-detect the build system ({@link ProjectAdapter}), choose the requested
 * source scope, and run the five extraction phases.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Analyse any project — one line:
 * Map<String, Object> report = AnalyzerFacade.analyze(Path.of("/projects/my-library"));
 *
 * // Generic project:
 * //   → scans source and applies URI/package/class/method filters
 * }</pre>
 *
 * <h2>Auto-detection logic</h2>
 * <pre>
 *  Project root
 *   ├── pom.xml?          → MavenProjectAdapter
 *   ├── build.gradle?     → GradleProjectAdapter
 *   └── (fallback)        → GenericJavaAdapter
 *
 *   └── scope all           → ScanAllSourcesStrategy
 *   └── scope entry-points  → EntryPointScanStrategy
 * </pre>
 *
 * <h2>Design notes</h2>
 * The facade delegates all execution to the existing {@link Orchestrator}. The
 * {@link ProjectAdapter} and {@link MethodSourceStrategy} layers translate project
 * diversity and source scope into the strategy the Orchestrator understands.
 */
public class AnalyzerFacade {

    private AnalyzerFacade() {
        // utility class — not instantiated
    }

    /**
     * Analyse a Java project and return the pipeline execution report.
     *
     * <p>The method auto-detects the build system and method source, reuses
     * existing compiled classes when present, and runs the five extraction phases. On success the
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
        return analyze(projectPath, AnalysisOptions.defaults());
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
     * Build an orchestrator with an explicitly supplied source strategy.
     */
    private static Orchestrator buildOrchestrator(Path projectPath,
                                                   MethodSourceStrategy strategy) {
        return new Orchestrator(projectPath)
                .setMethodSourceStrategy(strategy);
    }

    private static Orchestrator buildOrchestrator(Path projectPath, AnalysisOptions options) {
        Orchestrator orchestrator = new Orchestrator(projectPath)
                .setMethodSourceStrategy(options.methodSourceStrategy());
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
