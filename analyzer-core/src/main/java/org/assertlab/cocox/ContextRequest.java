package org.assertlab.cocox;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Public request object for method-context extraction.
 *
 * <p>This is the product API configuration surface. The pipeline always emits
 * JSONL, so callers configure what to extract, not the output format.
 */
public final class ContextRequest {
    public enum Scope {
        ALL,
        ENTRY_POINTS
    }

    public enum SourceResolution {
        NOCLASSPATH,
        CLASSPATH,
        AUTO
    }

    private final Path projectRoot;
    private final Scope scope;
    private final CallGraphGenerator.Algorithm callGraphAlgorithm;
    private final Integer maxMethods;
    private final Integer maxSourceFiles;
    private final boolean attemptCompile;
    private final SourceResolution sourceResolution;
    private final Set<String> sourceSets;
    private final Set<String> packages;
    private final Set<String> classes;
    private final Set<String> methods;
    private final Set<String> visibilities;
    private final Set<String> includePathGlobs;
    private final Set<String> excludePathGlobs;
    private final Set<SymbolTarget> targets;
    private final Path outputDirectory;

    private ContextRequest(Builder builder) {
        this.projectRoot = Objects.requireNonNull(builder.projectRoot, "projectRoot cannot be null")
                .toAbsolutePath()
                .normalize();
        this.scope = Objects.requireNonNull(builder.scope, "scope cannot be null");
        this.callGraphAlgorithm = Objects.requireNonNull(builder.callGraphAlgorithm,
                "callGraphAlgorithm cannot be null");
        this.maxMethods = builder.maxMethods;
        this.maxSourceFiles = builder.maxSourceFiles;
        this.attemptCompile = builder.attemptCompile;
        this.sourceResolution = Objects.requireNonNull(builder.sourceResolution,
                "sourceResolution cannot be null");
        this.sourceSets = Collections.unmodifiableSet(new LinkedHashSet<>(builder.sourceSets));
        this.packages = Collections.unmodifiableSet(new LinkedHashSet<>(builder.packages));
        this.classes = Collections.unmodifiableSet(new LinkedHashSet<>(builder.classes));
        this.methods = Collections.unmodifiableSet(new LinkedHashSet<>(builder.methods));
        this.visibilities = Collections.unmodifiableSet(new LinkedHashSet<>(builder.visibilities));
        this.includePathGlobs = Collections.unmodifiableSet(new LinkedHashSet<>(builder.includePathGlobs));
        this.excludePathGlobs = Collections.unmodifiableSet(new LinkedHashSet<>(builder.excludePathGlobs));
        this.targets = Collections.unmodifiableSet(new LinkedHashSet<>(builder.targets));
        this.outputDirectory = builder.outputDirectory != null
                ? builder.outputDirectory.toAbsolutePath().normalize()
                : null;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static ContextRequest defaults(Path projectRoot) {
        return builder().projectRoot(projectRoot).build();
    }

    public Path projectRoot() {
        return projectRoot;
    }

    public Scope scope() {
        return scope;
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

    public Set<SymbolTarget> targets() {
        return targets;
    }

    public Path outputDirectory() {
        return outputDirectory;
    }

    public static final class Builder {
        private Path projectRoot;
        private Scope scope = Scope.ALL;
        private CallGraphGenerator.Algorithm callGraphAlgorithm = CallGraphGenerator.Algorithm.AUTO;
        private Integer maxMethods;
        private Integer maxSourceFiles;
        private boolean attemptCompile;
        private SourceResolution sourceResolution = SourceResolution.NOCLASSPATH;
        private Set<String> sourceSets = new LinkedHashSet<>();
        private Set<String> packages = new LinkedHashSet<>();
        private Set<String> classes = new LinkedHashSet<>();
        private Set<String> methods = new LinkedHashSet<>();
        private Set<String> visibilities = new LinkedHashSet<>();
        private Set<String> includePathGlobs = new LinkedHashSet<>();
        private Set<String> excludePathGlobs = new LinkedHashSet<>();
        private Set<SymbolTarget> targets = new LinkedHashSet<>();
        private Path outputDirectory;

        public Builder projectRoot(Path projectRoot) {
            this.projectRoot = projectRoot;
            return this;
        }

        public Builder scope(Scope scope) {
            this.scope = Objects.requireNonNull(scope, "scope cannot be null");
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

        public Builder targets(Set<SymbolTarget> targets) {
            this.targets = targets == null ? new LinkedHashSet<>() : new LinkedHashSet<>(targets);
            return this;
        }

        public Builder target(SymbolTarget target) {
            if (target != null) {
                this.targets.add(target);
            }
            return this;
        }

        public Builder targetUri(String targetUri) {
            if (targetUri != null && !targetUri.isBlank()) {
                this.targets.add(SymbolTarget.parse(targetUri));
            }
            return this;
        }

        public Builder methodUri(String methodUri) {
            if (methodUri != null && !methodUri.isBlank()) {
                this.targets.add(SymbolTarget.method(methodUri));
            }
            return this;
        }

        public Builder typeUri(String typeUri) {
            if (typeUri != null && !typeUri.isBlank()) {
                this.targets.add(SymbolTarget.type(typeUri));
            }
            return this;
        }

        public Builder packageUri(String packageUri) {
            if (packageUri != null && !packageUri.isBlank()) {
                this.targets.add(SymbolTarget.packageTarget(packageUri));
            }
            return this;
        }

        public Builder outputDirectory(Path outputDirectory) {
            this.outputDirectory = outputDirectory;
            return this;
        }

        public ContextRequest build() {
            return new ContextRequest(this);
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
