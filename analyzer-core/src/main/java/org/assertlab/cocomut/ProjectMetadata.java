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
    private final List<Path> sourceRoots;
    private final List<Path> testSourceRoots;
    private final List<Path> classpath;
    private final List<Path> mainClassOutputs;
    private final List<Path> testClassOutputs;
    private final List<Path> projectArtifactJars;
    private final List<Path> dependencyClasspath;
    private final boolean compiles;
    private final String compileStatus;
    private final boolean buildAttempted;
    private final int buildExitCode;
    private final boolean buildSucceeded;
    private final boolean buildTimedOut;
    private final boolean buildSkipped;
    private final boolean buildSandboxed;
    private final ContextRequest.BuildPolicy buildPolicy;
    private final boolean bytecodeAvailable;
    private final String bytecodeOrigin;
    private final boolean analysisCanProceed;
    private final List<Path> explicitClassOutputDirs;
    private final List<Path> explicitProjectJars;
    private final List<Path> explicitDependencyJars;
    private final List<Path> explicitClasspathFiles;

    private ProjectMetadata(Builder builder) {
        this.projectName = Objects.requireNonNull(builder.projectName, "projectName cannot be null");
        this.projectPath = Objects.requireNonNull(builder.projectPath, "projectPath cannot be null");
        this.buildSystem = Objects.requireNonNull(builder.buildSystem, "buildSystem cannot be null");
        this.javaVersion = Objects.requireNonNull(builder.javaVersion, "javaVersion cannot be null");
        this.sourceRoot = Objects.requireNonNull(builder.sourceRoot, "sourceRoot cannot be null");
        this.sourceRoots = Collections.unmodifiableList(safeList(builder.sourceRoots));
        this.testSourceRoots = Collections.unmodifiableList(safeList(builder.testSourceRoots));
        this.classpath = Collections.unmodifiableList(safeList(builder.classpath));
        this.mainClassOutputs = Collections.unmodifiableList(safeList(builder.mainClassOutputs));
        this.testClassOutputs = Collections.unmodifiableList(safeList(builder.testClassOutputs));
        this.projectArtifactJars = Collections.unmodifiableList(safeList(builder.projectArtifactJars));
        this.dependencyClasspath = Collections.unmodifiableList(safeList(builder.dependencyClasspath));
        this.compiles = builder.compiles;
        this.compileStatus = builder.compileStatus;
        this.buildAttempted = builder.buildAttempted;
        this.buildExitCode = builder.buildExitCode;
        this.buildSucceeded = builder.buildSucceeded;
        this.buildTimedOut = builder.buildTimedOut;
        this.buildSkipped = builder.buildSkipped;
        this.buildSandboxed = builder.buildSandboxed;
        this.buildPolicy = builder.buildPolicy;
        this.bytecodeAvailable = builder.bytecodeAvailable;
        this.bytecodeOrigin = builder.bytecodeOrigin;
        this.analysisCanProceed = builder.analysisCanProceed;
        this.explicitClassOutputDirs = Collections.unmodifiableList(safeList(builder.explicitClassOutputDirs));
        this.explicitProjectJars = Collections.unmodifiableList(safeList(builder.explicitProjectJars));
        this.explicitDependencyJars = Collections.unmodifiableList(safeList(builder.explicitDependencyJars));
        this.explicitClasspathFiles = Collections.unmodifiableList(safeList(builder.explicitClasspathFiles));
    }

    private static List<Path> safeList(List<Path> paths) {
        return paths != null ? List.copyOf(paths) : List.of();
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

    public List<Path> getSourceRoots() {
        return sourceRoots;
    }

    public List<Path> getTestSourceRoots() {
        return testSourceRoots;
    }

    public List<Path> getClasspath() {
        return classpath;
    }

    public List<Path> getMainClassOutputs() {
        return mainClassOutputs;
    }

    public List<Path> getTestClassOutputs() {
        return testClassOutputs;
    }

    public List<Path> getProjectArtifactJars() {
        return projectArtifactJars;
    }

    public List<Path> getDependencyClasspath() {
        return dependencyClasspath;
    }

    public boolean isCompiles() {
        return compiles;
    }

    public String getCompileStatus() {
        return compileStatus;
    }

    public boolean isBuildAttempted() {
        return buildAttempted;
    }

    public int getBuildExitCode() {
        return buildExitCode;
    }

    public boolean isBuildSucceeded() {
        return buildSucceeded;
    }

    public boolean isBuildTimedOut() {
        return buildTimedOut;
    }

    public boolean isBuildSkipped() {
        return buildSkipped;
    }

    public boolean isBuildSandboxed() {
        return buildSandboxed;
    }

    public ContextRequest.BuildPolicy getBuildPolicy() {
        return buildPolicy;
    }

    public boolean isBytecodeAvailable() {
        return bytecodeAvailable;
    }

    public String getBytecodeOrigin() {
        return bytecodeOrigin;
    }

    public boolean isAnalysisCanProceed() {
        return analysisCanProceed;
    }

    public List<Path> getExplicitClassOutputDirs() {
        return explicitClassOutputDirs;
    }

    public List<Path> getExplicitProjectJars() {
        return explicitProjectJars;
    }

    public List<Path> getExplicitDependencyJars() {
        return explicitDependencyJars;
    }

    public List<Path> getExplicitClasspathFiles() {
        return explicitClasspathFiles;
    }

    @Override
    public String toString() {
        return "ProjectMetadata{" +
                "projectName='" + projectName + '\'' +
                ", projectPath=" + projectPath +
                ", buildSystem='" + buildSystem + '\'' +
                ", javaVersion='" + javaVersion + '\'' +
                ", sourceRoot=" + sourceRoot +
                ", sourceRoots=" + sourceRoots.size() +
                ", testSourceRoots=" + testSourceRoots.size() +
                ", classpathSize=" + classpath.size() +
                ", mainClassOutputs=" + mainClassOutputs.size() +
                ", testClassOutputs=" + testClassOutputs.size() +
                ", projectArtifactJars=" + projectArtifactJars.size() +
                ", dependencyClasspath=" + dependencyClasspath.size() +
                ", compiles=" + compiles +
                ", compileStatus='" + compileStatus + '\'' +
                ", buildAttempted=" + buildAttempted +
                ", buildSkipped=" + buildSkipped +
                ", explicitClassOutputDirs=" + explicitClassOutputDirs.size() +
                ", explicitProjectJars=" + explicitProjectJars.size() +
                ", explicitDependencyJars=" + explicitDependencyJars.size() +
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
        private List<Path> sourceRoots = Collections.emptyList();
        private List<Path> testSourceRoots = Collections.emptyList();
        private List<Path> classpath = Collections.emptyList();
        private List<Path> mainClassOutputs = Collections.emptyList();
        private List<Path> testClassOutputs = Collections.emptyList();
        private List<Path> projectArtifactJars = Collections.emptyList();
        private List<Path> dependencyClasspath = Collections.emptyList();
        private boolean compiles = false;
        private String compileStatus = "";
        private boolean buildAttempted = false;
        private int buildExitCode = -1;
        private boolean buildSucceeded = false;
        private boolean buildTimedOut = false;
        private boolean buildSkipped = false;
        private boolean buildSandboxed = false;
        private ContextRequest.BuildPolicy buildPolicy = ContextRequest.BuildPolicy.DENY_BUILD;
        private boolean bytecodeAvailable = false;
        private String bytecodeOrigin = "none";
        private boolean analysisCanProceed = false;
        private List<Path> explicitClassOutputDirs = Collections.emptyList();
        private List<Path> explicitProjectJars = Collections.emptyList();
        private List<Path> explicitDependencyJars = Collections.emptyList();
        private List<Path> explicitClasspathFiles = Collections.emptyList();

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
                    .sourceRoots(src.sourceRoots)
                    .testSourceRoots(src.testSourceRoots)
                    .classpath(src.classpath)
                    .mainClassOutputs(src.mainClassOutputs)
                    .testClassOutputs(src.testClassOutputs)
                    .projectArtifactJars(src.projectArtifactJars)
                    .dependencyClasspath(src.dependencyClasspath)
                    .compiles(src.compiles)
                    .compileStatus(src.compileStatus)
                    .buildAttempted(src.buildAttempted)
                    .buildExitCode(src.buildExitCode)
                    .buildSucceeded(src.buildSucceeded)
                    .buildTimedOut(src.buildTimedOut)
                    .buildSkipped(src.buildSkipped)
                    .buildSandboxed(src.buildSandboxed)
                    .buildPolicy(src.buildPolicy)
                    .bytecodeAvailable(src.bytecodeAvailable)
                    .bytecodeOrigin(src.bytecodeOrigin)
                    .analysisCanProceed(src.analysisCanProceed)
                    .explicitClassOutputDirs(src.explicitClassOutputDirs)
                    .explicitProjectJars(src.explicitProjectJars)
                    .explicitDependencyJars(src.explicitDependencyJars)
                    .explicitClasspathFiles(src.explicitClasspathFiles);
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

        public Builder sourceRoots(List<Path> sourceRoots) {
            this.sourceRoots = sourceRoots;
            return this;
        }

        public Builder testSourceRoots(List<Path> testSourceRoots) {
            this.testSourceRoots = testSourceRoots;
            return this;
        }

        public Builder classpath(List<Path> classpath) {
            this.classpath = classpath;
            return this;
        }

        public Builder mainClassOutputs(List<Path> mainClassOutputs) {
            this.mainClassOutputs = mainClassOutputs;
            return this;
        }

        public Builder testClassOutputs(List<Path> testClassOutputs) {
            this.testClassOutputs = testClassOutputs;
            return this;
        }

        public Builder projectArtifactJars(List<Path> projectArtifactJars) {
            this.projectArtifactJars = projectArtifactJars;
            return this;
        }

        public Builder dependencyClasspath(List<Path> dependencyClasspath) {
            this.dependencyClasspath = dependencyClasspath;
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

        public Builder buildAttempted(boolean buildAttempted) {
            this.buildAttempted = buildAttempted;
            return this;
        }

        public Builder buildExitCode(int buildExitCode) {
            this.buildExitCode = buildExitCode;
            return this;
        }

        public Builder buildSucceeded(boolean buildSucceeded) {
            this.buildSucceeded = buildSucceeded;
            return this;
        }

        public Builder buildTimedOut(boolean buildTimedOut) {
            this.buildTimedOut = buildTimedOut;
            return this;
        }

        public Builder buildSkipped(boolean buildSkipped) {
            this.buildSkipped = buildSkipped;
            return this;
        }

        public Builder buildSandboxed(boolean buildSandboxed) {
            this.buildSandboxed = buildSandboxed;
            return this;
        }

        public Builder buildPolicy(ContextRequest.BuildPolicy buildPolicy) {
            this.buildPolicy = buildPolicy == null ? ContextRequest.BuildPolicy.DENY_BUILD : buildPolicy;
            return this;
        }

        public Builder bytecodeAvailable(boolean bytecodeAvailable) {
            this.bytecodeAvailable = bytecodeAvailable;
            return this;
        }

        public Builder bytecodeOrigin(String bytecodeOrigin) {
            this.bytecodeOrigin = bytecodeOrigin == null || bytecodeOrigin.isBlank() ? "none" : bytecodeOrigin;
            return this;
        }

        public Builder analysisCanProceed(boolean analysisCanProceed) {
            this.analysisCanProceed = analysisCanProceed;
            return this;
        }

        public Builder explicitClassOutputDirs(List<Path> explicitClassOutputDirs) {
            this.explicitClassOutputDirs = explicitClassOutputDirs;
            return this;
        }

        public Builder explicitProjectJars(List<Path> explicitProjectJars) {
            this.explicitProjectJars = explicitProjectJars;
            return this;
        }

        public Builder explicitDependencyJars(List<Path> explicitDependencyJars) {
            this.explicitDependencyJars = explicitDependencyJars;
            return this;
        }

        public Builder explicitClasspathFiles(List<Path> explicitClasspathFiles) {
            this.explicitClasspathFiles = explicitClasspathFiles;
            return this;
        }

        public ProjectMetadata build() {
            return new ProjectMetadata(this);
        }
    }
}
