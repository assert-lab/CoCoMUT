package org.assertlab.context4docugen;

import org.assertlab.context4docugen.strategy.EntryPointScanStrategy;
import org.assertlab.context4docugen.strategy.MethodSourceStrategy;
import org.assertlab.context4docugen.strategy.ScanAllSourcesStrategy;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Public options object for C4DG analysis.
 *
 * <p>This keeps CLI/API configuration in one place and prevents users from
 * wiring {@link Orchestrator} directly for common cases.
 */
public final class AnalysisOptions {
    public enum Scope {
        ALL,
        ENTRY_POINTS,
        SELECTED
    }

    public enum OutputMode {
        JSON,
        JSONL,
        BOTH
    }

    public enum SourceResolution {
        NOCLASSPATH,
        CLASSPATH,
        AUTO
    }

    private final Scope scope;
    private final Path selectedCsv;
    private final CallGraphGenerator.Algorithm callGraphAlgorithm;
    private final Integer maxMethods;
    private final Integer maxSourceFiles;
    private final boolean attemptCompile;
    private final OutputMode outputMode;
    private final SourceResolution sourceResolution;
    private final Set<String> sourceSets;

    private AnalysisOptions(Builder builder) {
        this.scope = Objects.requireNonNull(builder.scope, "scope cannot be null");
        this.selectedCsv = builder.selectedCsv;
        this.callGraphAlgorithm = Objects.requireNonNull(builder.callGraphAlgorithm, "callGraphAlgorithm cannot be null");
        this.maxMethods = builder.maxMethods;
        this.maxSourceFiles = builder.maxSourceFiles;
        this.attemptCompile = builder.attemptCompile;
        this.outputMode = Objects.requireNonNull(builder.outputMode, "outputMode cannot be null");
        this.sourceResolution = Objects.requireNonNull(builder.sourceResolution, "sourceResolution cannot be null");
        this.sourceSets = Collections.unmodifiableSet(new LinkedHashSet<>(builder.sourceSets));
    }

    public static Builder builder() {
        return new Builder();
    }

    public static AnalysisOptions defaults() {
        return builder().build();
    }

    public Scope scope() {
        return scope;
    }

    public Path selectedCsv() {
        return selectedCsv;
    }

    public CallGraphGenerator.Algorithm callGraphAlgorithm() {
        return callGraphAlgorithm;
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

    public OutputMode outputMode() {
        return outputMode;
    }

    public SourceResolution sourceResolution() {
        return sourceResolution;
    }

    public Set<String> sourceSets() {
        return sourceSets;
    }

    public boolean filtersSourceSets() {
        return !sourceSets.isEmpty();
    }

    MethodSourceStrategy methodSourceStrategy() {
        return switch (scope) {
            case ALL -> new ScanAllSourcesStrategy();
            case ENTRY_POINTS -> new EntryPointScanStrategy();
            case SELECTED -> null;
        };
    }

    public static final class Builder {
        private Scope scope = Scope.ALL;
        private Path selectedCsv;
        private CallGraphGenerator.Algorithm callGraphAlgorithm = CallGraphGenerator.Algorithm.CHA;
        private Integer maxMethods;
        private Integer maxSourceFiles;
        private boolean attemptCompile;
        private OutputMode outputMode = OutputMode.JSON;
        private SourceResolution sourceResolution = SourceResolution.NOCLASSPATH;
        private Set<String> sourceSets = new LinkedHashSet<>();

        public Builder scope(Scope scope) {
            this.scope = Objects.requireNonNull(scope, "scope cannot be null");
            return this;
        }

        public Builder selectedCsv(Path selectedCsv) {
            this.selectedCsv = selectedCsv;
            this.scope = Scope.SELECTED;
            return this;
        }

        public Builder callGraphAlgorithm(CallGraphGenerator.Algorithm callGraphAlgorithm) {
            this.callGraphAlgorithm = Objects.requireNonNull(callGraphAlgorithm, "callGraphAlgorithm cannot be null");
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

        public Builder outputMode(OutputMode outputMode) {
            this.outputMode = Objects.requireNonNull(outputMode, "outputMode cannot be null");
            return this;
        }

        public Builder sourceResolution(SourceResolution sourceResolution) {
            this.sourceResolution = Objects.requireNonNull(sourceResolution, "sourceResolution cannot be null");
            return this;
        }

        public Builder sourceSets(Set<String> sourceSets) {
            this.sourceSets = normalizeSourceSets(sourceSets);
            return this;
        }

        public Builder sourceSet(String sourceSet) {
            this.sourceSets = normalizeSourceSets(sourceSet == null ? Set.of() : Set.of(sourceSet));
            return this;
        }

        public AnalysisOptions build() {
            if (scope == Scope.SELECTED && selectedCsv == null) {
                throw new IllegalArgumentException("selectedCsv is required for SELECTED scope");
            }
            return new AnalysisOptions(this);
        }

        private static Set<String> normalizeSourceSets(Set<String> values) {
            if (values == null || values.isEmpty()) {
                return new LinkedHashSet<>();
            }
            LinkedHashSet<String> normalized = new LinkedHashSet<>();
            for (String value : values) {
                if (value == null || value.isBlank()) {
                    continue;
                }
                String lower = value.trim().toLowerCase(Locale.ROOT).replace('-', '_');
                if ("all".equals(lower)) {
                    return new LinkedHashSet<>();
                }
                if (!Set.of("main", "test", "integration_test", "generated", "example", "unknown").contains(lower)) {
                    throw new IllegalArgumentException("Unsupported source set: " + value);
                }
                normalized.add(lower);
            }
            return normalized;
        }
    }
}
