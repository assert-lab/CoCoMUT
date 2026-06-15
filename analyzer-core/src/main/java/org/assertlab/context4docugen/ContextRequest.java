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

    AnalysisOptions toAnalysisOptions() {
        AnalysisOptions.Builder builder = AnalysisOptions.builder()
                .scope(methodSelection.toScope())
                .callGraphAlgorithm(callGraphAlgorithm)
                .outputMode(outputMode)
                .maxMethods(maxMethods)
                .maxSourceFiles(maxSourceFiles)
                .attemptCompile(attemptCompile)
                .sourceResolution(sourceResolution)
                .sourceSets(sourceSets);
        if (methodSelection.kind() == MethodSelection.Kind.SELECTED_CSV) {
            builder.selectedCsv(methodSelection.selectedCsv());
        }
        return builder.build();
    }

    public static final class Builder {
        private Path projectRoot;
        private MethodSelection methodSelection = MethodSelection.all();
        private CallGraphGenerator.Algorithm callGraphAlgorithm = CallGraphGenerator.Algorithm.CHA;
        private AnalysisOptions.OutputMode outputMode = AnalysisOptions.OutputMode.JSON;
        private Integer maxMethods;
        private Integer maxSourceFiles;
        private boolean attemptCompile;
        private AnalysisOptions.SourceResolution sourceResolution = AnalysisOptions.SourceResolution.NOCLASSPATH;
        private Set<String> sourceSets = Set.of();

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

        public ContextRequest build() {
            return new ContextRequest(this);
        }
    }
}
