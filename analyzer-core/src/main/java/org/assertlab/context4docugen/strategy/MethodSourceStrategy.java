package org.assertlab.context4docugen.strategy;

import org.assertlab.context4docugen.MethodInfo;
import org.assertlab.context4docugen.ProjectMetadata;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Strategy interface — abstracts <em>which methods</em> the pipeline processes.
 *
 * The pipeline always receives a {@code List<MethodInfo>} for Phase 3 onward.
 * How that list is produced depends on context:
 *
 * <table>
 *   <caption>Built-in method source strategies</caption>
 *   <tr><th>Scenario</th><th>Strategy</th></tr>
 *   <tr><td>OE-25 research (pre-selected samples)</td><td>{@link CsvSelectedStrategy}</td></tr>
 *   <tr><td>Full project scan</td><td>{@link ScanAllSourcesStrategy}</td></tr>
 *   <tr><td>Generic project (no CSV, no preset)</td><td>{@link EntryPointScanStrategy} (Sprint 2)</td></tr>
 * </table>
 *
 * <h2>Adding a new method source</h2>
 * <ol>
 *   <li>Implement this interface.</li>
 *   <li>Register it in {@link #detect(Path)} before the default fallback.</li>
 *   <li>No changes to the pipeline phases needed.</li>
 * </ol>
 */
public interface MethodSourceStrategy {

    /**
     * Load the methods this strategy selects for the given project.
     *
     * @param meta fully-populated project metadata from Phase 1
     * @return ordered list of methods to process; never {@code null}
     * @throws IOException if the underlying source (file, network) cannot be read
     */
    List<MethodInfo> loadMethods(ProjectMetadata meta) throws IOException;

    /** Short identifier used in logs and execution reports. */
    String name();

    /**
     * Auto-detect the best strategy for a project root.
     *
     * <p>Detection order:
     * <ol>
     *   <li>{@code inputs_selected.csv} present → {@link CsvSelectedStrategy}</li>
     *   <li>Otherwise → {@link ScanAllSourcesStrategy} (scan all public methods)</li>
     * </ol>
     *
     * @param projectPath absolute path to the project root directory
     * @return the most appropriate strategy for this project
     */
    static MethodSourceStrategy detect(Path projectPath) {
        Path csv = projectPath.resolve("inputs_selected.csv");
        if (Files.exists(csv)) {
            return new CsvSelectedStrategy(csv);
        }
        return new ScanAllSourcesStrategy();
    }
}
