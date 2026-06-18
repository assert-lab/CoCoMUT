package org.assertlab.cocox.strategy;

import org.assertlab.cocox.MethodInfo;
import org.assertlab.cocox.ProjectMetadata;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Strategy interface — abstracts <em>which methods</em> the pipeline processes.
 *
 * The pipeline always receives a {@code List<MethodInfo>} for Phase 3 onward.
 * How that list is produced depends on context. Built-in strategies are full
 * project scanning and entry-point scanning.
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
     * @param projectPath absolute path to the project root directory
     * @return the most appropriate strategy for this project
     */
    static MethodSourceStrategy detect(Path projectPath) {
        return new ScanAllSourcesStrategy();
    }
}
