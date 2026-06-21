package org.assertlab.cocomut;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Immutable data class holding project metadata detected during project analysis.
 * Used by all downstream framework components (MethodIdentifier, CallGraphGenerator, etc.)
 */
public class ProjectMetadata {
    private final String projectName;
    private final Path projectPath;
    private final String buildSystem;  // "maven" or "gradle"
    private final String javaVersion;
    private final Path sourceRoot;
    private final List<Path> classpath;
    private final boolean compiles;
    private final String compileStatus;

    private ProjectMetadata(Builder builder) {
        this.projectName = Objects.requireNonNull(builder.projectName, "projectName cannot be null");
        this.projectPath = Objects.requireNonNull(builder.projectPath, "projectPath cannot be null");
        this.buildSystem = Objects.requireNonNull(builder.buildSystem, "buildSystem cannot be null");
        this.javaVersion = Objects.requireNonNull(builder.javaVersion, "javaVersion cannot be null");
        this.sourceRoot = Objects.requireNonNull(builder.sourceRoot, "sourceRoot cannot be null");
        this.classpath = Collections.unmodifiableList(builder.classpath);
        this.compiles = builder.compiles;
        this.compileStatus = builder.compileStatus;
    }

    // Getters
    public String getProjectName() {
        return projectName;
    }

    public Path getProjectPath() {
        return projectPath;
    }

    public String getBuildSystem() {
        return buildSystem;
    }

    public String getJavaVersion() {
        return javaVersion;
    }

    public Path getSourceRoot() {
        return sourceRoot;
    }

    public List<Path> getClasspath() {
        return classpath;
    }

    public boolean isCompiles() {
        return compiles;
    }

    public String getCompileStatus() {
        return compileStatus;
    }

    @Override
    public String toString() {
        return "ProjectMetadata{" +
                "projectName='" + projectName + '\'' +
                ", projectPath=" + projectPath +
                ", buildSystem='" + buildSystem + '\'' +
                ", javaVersion='" + javaVersion + '\'' +
                ", sourceRoot=" + sourceRoot +
                ", classpathSize=" + classpath.size() +
                ", compiles=" + compiles +
                ", compileStatus='" + compileStatus + '\'' +
                '}';
    }

    /**
     * Builder for ProjectMetadata - fluent API for construction
     */
    public static class Builder {
        private String projectName;
        private Path projectPath;
        private String buildSystem;
        private String javaVersion;
        private Path sourceRoot;
        private List<Path> classpath = Collections.emptyList();
        private boolean compiles = false;
        private String compileStatus = "";

        /**
         * Seed a builder from an existing metadata instance — useful for adapters
         * that want to return a copy with one field changed (e.g., a refined
         * classpath) without re-deriving everything.
         */
        public static Builder from(ProjectMetadata src) {
            return new Builder()
                    .projectName(src.projectName)
                    .projectPath(src.projectPath)
                    .buildSystem(src.buildSystem)
                    .javaVersion(src.javaVersion)
                    .sourceRoot(src.sourceRoot)
                    .classpath(src.classpath)
                    .compiles(src.compiles)
                    .compileStatus(src.compileStatus);
        }

        public Builder projectName(String projectName) {
            this.projectName = projectName;
            return this;
        }

        public Builder projectPath(Path projectPath) {
            this.projectPath = projectPath;
            return this;
        }

        public Builder buildSystem(String buildSystem) {
            this.buildSystem = buildSystem;
            return this;
        }

        public Builder javaVersion(String javaVersion) {
            this.javaVersion = javaVersion;
            return this;
        }

        public Builder sourceRoot(Path sourceRoot) {
            this.sourceRoot = sourceRoot;
            return this;
        }

        public Builder classpath(List<Path> classpath) {
            this.classpath = classpath;
            return this;
        }

        public Builder compiles(boolean compiles) {
            this.compiles = compiles;
            return this;
        }

        public Builder compileStatus(String compileStatus) {
            this.compileStatus = compileStatus;
            return this;
        }

        public ProjectMetadata build() {
            return new ProjectMetadata(this);
        }
    }
}
