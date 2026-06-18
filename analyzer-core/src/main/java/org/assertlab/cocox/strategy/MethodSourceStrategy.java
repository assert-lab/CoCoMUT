package org.assertlab.cocox.strategy;

import org.assertlab.cocox.MethodInfo;
import org.assertlab.cocox.ProjectMetadata;

import java.io.IOException;
import java.util.List;

/**
 * Strategy interface — abstracts <em>which methods</em> the pipeline processes.
 *
 * The pipeline always receives a {@code List<MethodInfo>} for Phase 3 onward.
 * How that list is produced depends on context. Built-in strategies are full
 * project scanning and entry-point scanning.
 *
 * <p>Built-in strategies are selected from {@link org.assertlab.cocox.AnalysisOptions.Scope}.
 * Custom callers may still pass an explicit strategy through {@code AnalyzerFacade}.
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

}
