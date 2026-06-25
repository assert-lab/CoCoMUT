package org.assertlab.cocomut.source;

import org.assertlab.cocomut.ProjectMetadata;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Plain project model used by source backends.
 *
 * <p>This deliberately wraps {@link ProjectMetadata} instead of replacing it:
 * the old pipeline keeps working, while source extraction gets explicit source
 * roots, test roots, class output dirs, dependency jars, and build provenance.
 */
public final class ProjectModel {
    private final ProjectMetadata metadata;
    private final List<Path> sourceRoots;
    private final List<Path> testSourceRoots;
    private final List<Path> classOutputDirs;
    private final List<Path> projectArtifactJars;
    private final List<Path> dependencyJars;
    private final List<Path> dependencyClasspath;
    private final boolean sourceAvailable;

    private ProjectModel(ProjectMetadata metadata,
                         List<Path> sourceRoots,
                         List<Path> testSourceRoots,
                         List<Path> classOutputDirs,
                         List<Path> projectArtifactJars,
                         List<Path> dependencyJars,
                         List<Path> dependencyClasspath) {
        this.metadata = Objects.requireNonNull(metadata, "metadata cannot be null");
        this.sourceRoots = List.copyOf(sourceRoots);
        this.testSourceRoots = List.copyOf(testSourceRoots);
        this.classOutputDirs = List.copyOf(classOutputDirs);
        this.projectArtifactJars = List.copyOf(projectArtifactJars);
        this.dependencyJars = List.copyOf(dependencyJars);
        this.dependencyClasspath = List.copyOf(dependencyClasspath);
        this.sourceAvailable = java.util.stream.Stream.concat(this.sourceRoots.stream(), this.testSourceRoots.stream())
                .anyMatch(ProjectModel::containsJavaFile);
    }

    public static ProjectModel from(ProjectMetadata metadata) {
        Path projectRoot = metadata.getProjectPath();
        Set<Path> sourceRoots = new LinkedHashSet<>();
        Set<Path> testSourceRoots = new LinkedHashSet<>();
        Set<Path> classOutputDirs = new LinkedHashSet<>();
        Set<Path> projectArtifactJars = new LinkedHashSet<>();
        Set<Path> dependencyJars = new LinkedHashSet<>();
        Set<Path> dependencyClasspath = new LinkedHashSet<>();

        metadata.getSourceRoots().forEach(path -> addIfDirectory(sourceRoots, path));
        metadata.getTestSourceRoots().forEach(path -> addIfDirectory(testSourceRoots, path));
        if (sourceRoots.isEmpty() && testSourceRoots.isEmpty()) {
            addIfDirectory(sourceRoots, metadata.getSourceRoot());
        }
        if (sourceRoots.isEmpty() && testSourceRoots.isEmpty()) {
            addStandardRoots(projectRoot, sourceRoots, testSourceRoots, classOutputDirs);
        }

        metadata.getMainClassOutputs().forEach(path -> addIfClassDirectory(classOutputDirs, path));
        metadata.getTestClassOutputs().forEach(path -> addIfClassDirectory(classOutputDirs, path));
        metadata.getProjectArtifactJars().forEach(path -> addIfJar(projectArtifactJars, path));
        metadata.getDependencyClasspath().stream()
                .filter(path -> path.toString().endsWith(".jar") && Files.isRegularFile(path))
                .map(ProjectModel::normalize)
                .forEach(dependencyJars::add);
        metadata.getDependencyClasspath().forEach(path -> addIfDependencyClasspath(dependencyClasspath, path));

        return new ProjectModel(metadata,
                new ArrayList<>(sourceRoots),
                new ArrayList<>(testSourceRoots),
                new ArrayList<>(classOutputDirs),
                new ArrayList<>(projectArtifactJars),
                new ArrayList<>(dependencyJars),
                new ArrayList<>(dependencyClasspath));
    }

    public ProjectMetadata metadata() {
        return metadata;
    }

    public Path projectPath() {
        return metadata.getProjectPath();
    }

    public String buildSystem() {
        return metadata.getBuildSystem();
    }

    public String javaVersion() {
        return metadata.getJavaVersion();
    }

    public boolean compileSucceeded() {
        return metadata.isCompiles();
    }

    public String compileStatus() {
        return metadata.getCompileStatus();
    }

    public boolean sourceAvailable() {
        return sourceAvailable;
    }

    public List<Path> sourceRoots() {
        return sourceRoots;
    }

    public List<Path> testSourceRoots() {
        return testSourceRoots;
    }

    public List<Path> classOutputDirs() {
        return classOutputDirs;
    }

    public List<Path> projectArtifactJars() {
        return projectArtifactJars;
    }

    public List<Path> dependencyJars() {
        return dependencyJars;
    }

    public List<Path> dependencyClasspath() {
        return dependencyClasspath;
    }

    private static void addStandardRoots(Path projectRoot,
                                         Set<Path> sourceRoots,
                                         Set<Path> testSourceRoots,
                                         Set<Path> classOutputDirs) {
        addIfDirectory(sourceRoots, projectRoot.resolve("src/main/java"));
        addIfDirectory(testSourceRoots, projectRoot.resolve("src/test/java"));
        addIfClassDirectory(classOutputDirs, projectRoot.resolve("target/classes"));
        addIfClassDirectory(classOutputDirs, projectRoot.resolve("target/test-classes"));
        addIfClassDirectory(classOutputDirs, projectRoot.resolve("build/classes"));

        try (var walk = Files.walk(projectRoot, 5)) {
            for (Path dir : walk.filter(Files::isDirectory).toList()) {
                Path normalized = normalize(dir);
                if (normalized.endsWith(Path.of("src/main/java"))) {
                    sourceRoots.add(normalized);
                } else if (normalized.endsWith(Path.of("src/test/java"))) {
                    testSourceRoots.add(normalized);
                } else if (isCandidateClassOutput(normalized) && containsClassFile(normalized)) {
                    classOutputDirs.add(normalized);
                }
            }
        } catch (IOException ignored) {
            // Best-effort model. Extraction reports provenance instead of failing here.
        }
    }

    private static boolean isCandidateClassOutput(Path path) {
        return path.endsWith(Path.of("target/classes"))
                || path.endsWith(Path.of("target/test-classes"))
                || path.endsWith(Path.of("build/classes/java/main"))
                || path.endsWith(Path.of("build/classes/java/test"));
    }

    private static void addIfDirectory(Set<Path> dirs, Path path) {
        if (path != null && Files.isDirectory(path)) {
            dirs.add(normalize(path));
        }
    }

    private static void addIfClassDirectory(Set<Path> dirs, Path path) {
        if (path != null && Files.isDirectory(path) && containsClassFile(path)) {
            dirs.add(normalize(path));
        }
    }

    private static void addIfJar(Set<Path> jars, Path path) {
        if (path != null && Files.isRegularFile(path) && path.toString().endsWith(".jar")) {
            jars.add(normalize(path));
        }
    }

    private static void addIfDependencyClasspath(Set<Path> entries, Path path) {
        if (path == null) {
            return;
        }
        if ((Files.isRegularFile(path) && path.toString().endsWith(".jar"))
                || (Files.isDirectory(path) && containsClassFile(path))) {
            entries.add(normalize(path));
        }
    }

    private static Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }

    private static boolean containsJavaFile(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return false;
        }
        try (var walk = Files.walk(dir)) {
            return walk.anyMatch(p -> p.toString().endsWith(".java"));
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean containsClassFile(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return false;
        }
        try (var walk = Files.walk(dir)) {
            return walk.anyMatch(p -> p.toString().endsWith(".class"));
        } catch (IOException e) {
            return false;
        }
    }
}
