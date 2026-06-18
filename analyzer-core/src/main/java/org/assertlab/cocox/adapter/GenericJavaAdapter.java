package org.assertlab.cocox.adapter;

import org.assertlab.cocox.ProjectAnalyzer;
import org.assertlab.cocox.ProjectMetadata;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * Fallback adapter for plain Java directories or any project layout that is
 * neither Maven nor Gradle.
 *
 * <p>This adapter always returns {@code true} from {@link #canHandle} and must be
 * registered <em>last</em> in {@link ProjectAdapter#of(Path)} so that more specific
 * adapters take precedence.
 *
 * <p>CoCoX is a static source-analysis tool. If a project has no
 * Java source files, the adapter returns the detected metadata unchanged and
 * downstream phases will report that no methods could be extracted.
 */
public class GenericJavaAdapter implements ProjectAdapter {
    private final Path projectPath;

    public GenericJavaAdapter(Path projectPath) {
        this.projectPath = projectPath;
    }

    /** Always returns {@code true} — this is the catch-all fallback. */
    @Override
    public boolean canHandle(Path path) {
        return true;
    }

    @Override
    public ProjectMetadata toMetadata() throws IOException {
        ProjectMetadata base = new ProjectAnalyzer(projectPath).analyze();

        if (hasJavaSource(base.getSourceRoot())) {
            return base;
        }

        System.out.println("[GenericJavaAdapter] no Java source found; source extraction will be empty");
        return base;
    }

    /** True if {@code sourceRoot} exists and contains at least one {@code .java} file. */
    private static boolean hasJavaSource(Path sourceRoot) {
        if (sourceRoot == null || !Files.isDirectory(sourceRoot)) {
            return false;
        }
        try (Stream<Path> walk = Files.walk(sourceRoot)) {
            return walk.anyMatch(p -> p.toString().endsWith(".java"));
        } catch (IOException e) {
            return false;
        }
    }

}
