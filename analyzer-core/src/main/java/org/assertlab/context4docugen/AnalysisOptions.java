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
        JSONL
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
    private final Set<String> packages;
    private final Set<String> classes;
    private final Set<String> methods;
    private final Set<String> visibilities;
    private final Set<String> includePathGlobs;
    private final Set<String> excludePathGlobs;
    private final Path outputDirectory;

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
        this.packages = Collections.unmodifiableSet(new LinkedHashSet<>(builder.packages));
        this.classes = Collections.unmodifiableSet(new LinkedHashSet<>(builder.classes));
        this.methods = Collections.unmodifiableSet(new LinkedHashSet<>(builder.methods));
        this.visibilities = Collections.unmodifiableSet(new LinkedHashSet<>(builder.visibilities));
        this.includePathGlobs = Collections.unmodifiableSet(new LinkedHashSet<>(builder.includePathGlobs));
        this.excludePathGlobs = Collections.unmodifiableSet(new LinkedHashSet<>(builder.excludePathGlobs));
        this.outputDirectory = builder.outputDirectory;
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

    public Set<String> packages() {
        return packages;
    }

    public Set<String> classes() {
        return classes;
    }

    public Set<String> methods() {
        return methods;
    }

    public Set<String> visibilities() {
        return visibilities;
    }

    public Set<String> includePathGlobs() {
        return includePathGlobs;
    }

    public Set<String> excludePathGlobs() {
        return excludePathGlobs;
    }

    public Path outputDirectory() {
        return outputDirectory;
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
        private CallGraphGenerator.Algorithm callGraphAlgorithm = CallGraphGenerator.Algorithm.AUTO;
        private Integer maxMethods;
        private Integer maxSourceFiles;
        private boolean attemptCompile;
        private OutputMode outputMode = OutputMode.JSONL;
        private SourceResolution sourceResolution = SourceResolution.NOCLASSPATH;
        private Set<String> sourceSets = new LinkedHashSet<>();
        private Set<String> packages = new LinkedHashSet<>();
        private Set<String> classes = new LinkedHashSet<>();
        private Set<String> methods = new LinkedHashSet<>();
        private Set<String> visibilities = new LinkedHashSet<>();
        private Set<String> includePathGlobs = new LinkedHashSet<>();
        private Set<String> excludePathGlobs = new LinkedHashSet<>();
        private Path outputDirectory;

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

        public Builder packages(Set<String> packages) {
            this.packages = normalizeNonBlank(packages);
            return this;
        }

        public Builder classes(Set<String> classes) {
            this.classes = normalizeNonBlank(classes);
            return this;
        }

        public Builder methods(Set<String> methods) {
            this.methods = normalizeNonBlank(methods);
            return this;
        }

        public Builder visibilities(Set<String> visibilities) {
            this.visibilities = normalizeVisibilities(visibilities);
            return this;
        }

        public Builder includePathGlobs(Set<String> includePathGlobs) {
            this.includePathGlobs = normalizeNonBlank(includePathGlobs);
            return this;
        }

        public Builder excludePathGlobs(Set<String> excludePathGlobs) {
            this.excludePathGlobs = normalizeNonBlank(excludePathGlobs);
            return this;
        }

        public Builder outputDirectory(Path outputDirectory) {
            this.outputDirectory = outputDirectory;
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

        private static Set<String> normalizeNonBlank(Set<String> values) {
            if (values == null || values.isEmpty()) {
                return new LinkedHashSet<>();
            }
            LinkedHashSet<String> normalized = new LinkedHashSet<>();
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    normalized.add(value.trim());
                }
            }
            return normalized;
        }

        private static Set<String> normalizeVisibilities(Set<String> values) {
            LinkedHashSet<String> normalized = new LinkedHashSet<>();
            for (String value : normalizeNonBlank(values)) {
                String lower = value.toLowerCase(Locale.ROOT).replace('_', '-');
                if (!Set.of("public", "protected", "private", "package-private").contains(lower)) {
                    throw new IllegalArgumentException("Unsupported visibility: " + value);
                }
                normalized.add(lower);
            }
            return normalized;
        }
    }
}
