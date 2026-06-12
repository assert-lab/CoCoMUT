package org.assertlab.context4docugen.adapter;

import org.assertlab.context4docugen.ProjectMetadata;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Adapter interface — abstracts how the pipeline understands a Java project.
 *
 * Each implementation knows how to resolve the build system, source roots,
 * classpath, and compilation status for one project type (Maven, Gradle, plain
 * directory). The pipeline always receives the same {@link ProjectMetadata}
 * regardless of which adapter handled detection.
 *
 * <h2>Adding a new build system</h2>
 * <ol>
 *   <li>Implement this interface (e.g., {@code BazelProjectAdapter}).</li>
 *   <li>Register it in {@link #of(Path)} before {@code GenericJavaAdapter}.</li>
 *   <li>No changes needed anywhere else in the pipeline.</li>
 * </ol>
 *
 * <h2>Auto-detection order</h2>
 * <pre>
 *   pom.xml present          → MavenProjectAdapter
 *   build.gradle present     → GradleProjectAdapter
 *   fallback                 → GenericJavaAdapter
 * </pre>
 */
public interface ProjectAdapter {

    /**
     * Analyse the project and return a fully-populated {@link ProjectMetadata}.
     *
     * @return metadata used by phases 1–6 of the pipeline
     * @throws IOException if source roots or classpath cannot be resolved
     */
    ProjectMetadata toMetadata() throws IOException;

    /**
     * Return {@code true} if this adapter can handle the given project path.
     * Called by {@link #of(Path)} during auto-detection.
     */
    boolean canHandle(Path projectPath);

    /**
     * Factory method — returns the best adapter for {@code projectPath}.
     *
     * <p>Adapters are tried in priority order; {@code GenericJavaAdapter} is
     * always last and always matches, so this never returns {@code null}.
     *
     * @param projectPath absolute path to the project root
     * @return the most specific adapter that {@linkplain #canHandle handles} the path
     */
    static ProjectAdapter of(Path projectPath) {
        List<ProjectAdapter> candidates = List.of(
                new MavenProjectAdapter(projectPath),
                new GradleProjectAdapter(projectPath),
                new GenericJavaAdapter(projectPath)   // always matches — keep last
        );
        return candidates.stream()
                .filter(a -> a.canHandle(projectPath))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No ProjectAdapter matched: " + projectPath));
    }
}
