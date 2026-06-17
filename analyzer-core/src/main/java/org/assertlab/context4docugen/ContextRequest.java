package org.assertlab.context4docugen;

import java.nio.file.Path;
import java.util.Set;
import java.util.Objects;

/**
 * Public request object for extracting method context from a Java project.
 */
public final class ContextRequest {
    private final Path projectRoot;
    private final MethodSelection methodSelection;
    private final CallGraphGenerator.Algorithm callGraphAlgorithm;
    private final AnalysisOptions.OutputMode outputMode;
    private final Integer maxMethods;
    private final Integer maxSourceFiles;
    private final boolean attemptCompile;
    private final AnalysisOptions.SourceResolution sourceResolution;
    private final Set<String> sourceSets;
    private final Set<String> packages;
    private final Set<String> classes;
    private final Set<String> methods;
    private final Set<String> visibilities;
    private final Set<String> includePathGlobs;
    private final Set<String> excludePathGlobs;
    private final Path outputDirectory;

    private ContextRequest(Builder builder) {
        this.projectRoot = Objects.requireNonNull(builder.projectRoot, "projectRoot cannot be null")
                .toAbsolutePath()
                .normalize();
        this.methodSelection = Objects.requireNonNull(builder.methodSelection, "methodSelection cannot be null");
        this.callGraphAlgorithm = Objects.requireNonNull(builder.callGraphAlgorithm,
                "callGraphAlgorithm cannot be null");
        this.outputMode = Objects.requireNonNull(builder.outputMode, "outputMode cannot be null");
        this.maxMethods = builder.maxMethods;
        this.maxSourceFiles = builder.maxSourceFiles;
        this.attemptCompile = builder.attemptCompile;
        this.sourceResolution = Objects.requireNonNull(builder.sourceResolution,
                "sourceResolution cannot be null");
        this.sourceSets = Set.copyOf(builder.sourceSets);
        this.packages = Set.copyOf(builder.packages);
        this.classes = Set.copyOf(builder.classes);
        this.methods = Set.copyOf(builder.methods);
        this.visibilities = Set.copyOf(builder.visibilities);
        this.includePathGlobs = Set.copyOf(builder.includePathGlobs);
        this.excludePathGlobs = Set.copyOf(builder.excludePathGlobs);
        this.outputDirectory = builder.outputDirectory != null
                ? builder.outputDirectory.toAbsolutePath().normalize()
                : null;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Path projectRoot() {
        return projectRoot;
    }

    public MethodSelection methodSelection() {
        return methodSelection;
    }

    public CallGraphGenerator.Algorithm callGraphAlgorithm() {
        return callGraphAlgorithm;
    }

    public AnalysisOptions.OutputMode outputMode() {
        return outputMode;
    }

    public Integer maxMethods() {
        return maxMethods;
    }

    public Integer maxSourceFiles() {
        return maxSourceFiles;
    }

    public boolean attemptCompile() {
        return attemptCompile;
    }

    public AnalysisOptions.SourceResolution sourceResolution() {
        return sourceResolution;
    }

    public Set<String> sourceSets() {
        return sourceSets;
    }

    public Path outputDirectory() {
        return outputDirectory;
    }

    AnalysisOptions toAnalysisOptions() {
        AnalysisOptions.Builder builder = AnalysisOptions.builder()
                .scope(methodSelection.toScope())
                .callGraphAlgorithm(callGraphAlgorithm)
                .outputMode(outputMode)
                .maxMethods(maxMethods)
                .maxSourceFiles(maxSourceFiles)
                .attemptCompile(attemptCompile)
                .sourceResolution(sourceResolution)
                .sourceSets(sourceSets)
                .packages(packages)
                .classes(classes)
                .methods(methods)
                .visibilities(visibilities)
                .includePathGlobs(includePathGlobs)
                .excludePathGlobs(excludePathGlobs)
                .outputDirectory(outputDirectory);
        if (methodSelection.kind() == MethodSelection.Kind.SELECTED_CSV) {
            builder.selectedCsv(methodSelection.selectedCsv());
        }
        return builder.build();
    }

    public static final class Builder {
        private Path projectRoot;
        private MethodSelection methodSelection = MethodSelection.all();
        private CallGraphGenerator.Algorithm callGraphAlgorithm = CallGraphGenerator.Algorithm.AUTO;
        private AnalysisOptions.OutputMode outputMode = AnalysisOptions.OutputMode.JSONL;
        private Integer maxMethods;
        private Integer maxSourceFiles;
        private boolean attemptCompile;
        private AnalysisOptions.SourceResolution sourceResolution = AnalysisOptions.SourceResolution.NOCLASSPATH;
        private Set<String> sourceSets = Set.of();
        private Set<String> packages = Set.of();
        private Set<String> classes = Set.of();
        private Set<String> methods = Set.of();
        private Set<String> visibilities = Set.of();
        private Set<String> includePathGlobs = Set.of();
        private Set<String> excludePathGlobs = Set.of();
        private Path outputDirectory;

        public Builder projectRoot(Path projectRoot) {
            this.projectRoot = projectRoot;
            return this;
        }

        public Builder methodSelection(MethodSelection methodSelection) {
            this.methodSelection = methodSelection;
            return this;
        }

        public Builder callGraphAlgorithm(CallGraphGenerator.Algorithm callGraphAlgorithm) {
            this.callGraphAlgorithm = callGraphAlgorithm;
            return this;
        }

        public Builder outputMode(AnalysisOptions.OutputMode outputMode) {
            this.outputMode = outputMode;
            return this;
        }

        public Builder maxMethods(Integer maxMethods) {
            this.maxMethods = maxMethods;
            return this;
        }

        public Builder maxSourceFiles(Integer maxSourceFiles) {
            this.maxSourceFiles = maxSourceFiles;
            return this;
        }

        public Builder attemptCompile(boolean attemptCompile) {
            this.attemptCompile = attemptCompile;
            return this;
        }

        public Builder sourceResolution(AnalysisOptions.SourceResolution sourceResolution) {
            this.sourceResolution = sourceResolution;
            return this;
        }

        public Builder sourceSets(Set<String> sourceSets) {
            this.sourceSets = sourceSets == null ? Set.of() : Set.copyOf(sourceSets);
            return this;
        }

        public Builder sourceSet(String sourceSet) {
            this.sourceSets = sourceSet == null ? Set.of() : Set.of(sourceSet);
            return this;
        }

        public Builder packages(Set<String> packages) {
            this.packages = packages == null ? Set.of() : Set.copyOf(packages);
            return this;
        }

        public Builder classes(Set<String> classes) {
            this.classes = classes == null ? Set.of() : Set.copyOf(classes);
            return this;
        }

        public Builder methods(Set<String> methods) {
            this.methods = methods == null ? Set.of() : Set.copyOf(methods);
            return this;
        }

        public Builder visibilities(Set<String> visibilities) {
            this.visibilities = visibilities == null ? Set.of() : Set.copyOf(visibilities);
            return this;
        }

        public Builder includePathGlobs(Set<String> includePathGlobs) {
            this.includePathGlobs = includePathGlobs == null ? Set.of() : Set.copyOf(includePathGlobs);
            return this;
        }

        public Builder excludePathGlobs(Set<String> excludePathGlobs) {
            this.excludePathGlobs = excludePathGlobs == null ? Set.of() : Set.copyOf(excludePathGlobs);
            return this;
        }

        public Builder outputDirectory(Path outputDirectory) {
            this.outputDirectory = outputDirectory;
            return this;
        }

        public ContextRequest build() {
            return new ContextRequest(this);
        }
    }
}
