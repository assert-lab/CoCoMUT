package org.assertlab.cocomut.adapter;

import org.assertlab.cocomut.ProjectAnalyzer;
import org.assertlab.cocomut.ProjectMetadata;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Adapter for Maven projects ({@code pom.xml} present).
 *
 * Delegates to the existing {@link ProjectAnalyzer}, which already handles
 * Maven classpath resolution, source-root detection, and compilation checks.
 * This class is a thin wrapper that fits Maven into the {@link ProjectAdapter}
 * contract without modifying any existing pipeline code.
 */
public class MavenProjectAdapter implements ProjectAdapter {

    private final Path projectPath;

    public MavenProjectAdapter(Path projectPath) {
        this.projectPath = projectPath;
    }

    /** Matches when a {@code pom.xml} exists directly under the project root. */
    @Override
    public boolean canHandle(Path path) {
        return Files.exists(path.resolve("pom.xml"));
    }

    /**
     * Delegates to {@link ProjectAnalyzer#analyze()} which performs Maven-aware
     * classpath resolution, Java version detection, and {@code mvn compile}.
     */
    @Override
    public ProjectMetadata toMetadata() throws IOException {
        return new ProjectAnalyzer(projectPath).analyze();
    }
}
