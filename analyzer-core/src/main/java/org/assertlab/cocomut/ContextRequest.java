package org.assertlab.cocomut;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
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

    public enum BuildPolicy {
        DENY_BUILD,
        ALLOW_UNSANDBOXED_BUILD,
        EXTERNALLY_SANDBOXED_BUILD
    }

    private final Path projectRoot;
    private final Scope scope;
    private final CallGraphGenerator.Algorithm callGraphAlgorithm;
    private final Integer maxMethods;
    private final Integer maxSourceFiles;
    private final Set<String> sourceSets;
    private final Set<String> packages;
    private final Set<String> classes;
    private final Set<String> methods;
    private final Set<String> visibilities;
    private final Set<String> includePathGlobs;
    private final Set<String> excludePathGlobs;
    private final Set<SymbolTarget> targets;
    private final Path outputDirectory;
    private final BuildPolicy buildPolicy;
    private final boolean allowPreexistingBytecodeAfterBuildFailure;
    private final List<Path> classOutputDirs;
    private final List<Path> testClassOutputDirs;
    private final List<Path> projectJars;
    private final List<Path> dependencyJars;
    private final List<Path> classpathFiles;

    private ContextRequest(Builder builder) {
        this.projectRoot = Objects.requireNonNull(builder.projectRoot, "projectRoot cannot be null")
                .toAbsolutePath()
                .normalize();
        this.scope = Objects.requireNonNull(builder.scope, "scope cannot be null");
        this.callGraphAlgorithm = Objects.requireNonNull(builder.callGraphAlgorithm,
                "callGraphAlgorithm cannot be null");
        this.maxMethods = builder.maxMethods;
        this.maxSourceFiles = builder.maxSourceFiles;
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
        this.buildPolicy = Objects.requireNonNull(builder.buildPolicy, "buildPolicy cannot be null");
        this.allowPreexistingBytecodeAfterBuildFailure = builder.allowPreexistingBytecodeAfterBuildFailure;
        this.classOutputDirs = normalizePaths(builder.classOutputDirs, this.projectRoot);
        this.testClassOutputDirs = normalizePaths(builder.testClassOutputDirs, this.projectRoot);
        this.projectJars = normalizePaths(builder.projectJars, this.projectRoot);
        this.dependencyJars = normalizePaths(builder.dependencyJars, this.projectRoot);
        this.classpathFiles = normalizePaths(builder.classpathFiles, this.projectRoot);
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

    public boolean skipBuild() {
        return buildPolicy == BuildPolicy.DENY_BUILD;
    }

    public BuildPolicy buildPolicy() {
        return buildPolicy;
    }

    public boolean allowPreexistingBytecodeAfterBuildFailure() {
        return allowPreexistingBytecodeAfterBuildFailure;
    }

    public List<Path> classOutputDirs() {
        return classOutputDirs;
    }

    public List<Path> testClassOutputDirs() {
        return testClassOutputDirs;
    }

    public List<Path> projectJars() {
        return projectJars;
    }

    public List<Path> dependencyJars() {
        return dependencyJars;
    }

    public List<Path> classpathFiles() {
        return classpathFiles;
    }

    public static final class Builder {
        private Path projectRoot;
        private Scope scope = Scope.ALL;
        private CallGraphGenerator.Algorithm callGraphAlgorithm = CallGraphGenerator.Algorithm.RTA;
        private Integer maxMethods;
        private Integer maxSourceFiles;
        private Set<String> sourceSets = new LinkedHashSet<>();
        private Set<String> packages = new LinkedHashSet<>();
        private Set<String> classes = new LinkedHashSet<>();
        private Set<String> methods = new LinkedHashSet<>();
        private Set<String> visibilities = new LinkedHashSet<>();
        private Set<String> includePathGlobs = new LinkedHashSet<>();
        private Set<String> excludePathGlobs = new LinkedHashSet<>();
        private Set<SymbolTarget> targets = new LinkedHashSet<>();
        private Path outputDirectory;
        private BuildPolicy buildPolicy = BuildPolicy.DENY_BUILD;
        private boolean allowPreexistingBytecodeAfterBuildFailure;
        private List<Path> classOutputDirs = new java.util.ArrayList<>();
        private List<Path> testClassOutputDirs = new java.util.ArrayList<>();
        private List<Path> projectJars = new java.util.ArrayList<>();
        private List<Path> dependencyJars = new java.util.ArrayList<>();
        private List<Path> classpathFiles = new java.util.ArrayList<>();

        public Builder projectRoot(Path projectRoot) {
            this.projectRoot = projectRoot;
            return this;
        }

        public Builder scope(Scope scope) {
            this.scope = Objects.requireNonNull(scope, "scope cannot be null");
            return this;
        }

        public Builder entryPoints() {
            this.scope = Scope.ENTRY_POINTS;
            return this;
        }

        public Builder allMethods() {
            this.scope = Scope.ALL;
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

        public Builder packageName(String packageName) {
            addNonBlank(this.packages, packageName);
            return this;
        }

        public Builder classes(Set<String> classes) {
            this.classes = normalizeNonBlank(classes);
            return this;
        }

        public Builder className(String className) {
            addNonBlank(this.classes, className);
            return this;
        }

        public Builder typeName(String typeName) {
            return className(typeName);
        }

        public Builder methods(Set<String> methods) {
            this.methods = normalizeNonBlank(methods);
            return this;
        }

        public Builder methodName(String methodName) {
            addNonBlank(this.methods, methodName);
            return this;
        }

        public Builder visibilities(Set<String> visibilities) {
            this.visibilities = normalizeVisibilities(visibilities);
            return this;
        }

        public Builder visibility(String visibility) {
            this.visibilities = normalizeVisibilities(addToCopy(this.visibilities, visibility));
            return this;
        }

        public Builder includePathGlobs(Set<String> includePathGlobs) {
            this.includePathGlobs = normalizeNonBlank(includePathGlobs);
            return this;
        }

        public Builder includePathGlob(String includePathGlob) {
            addNonBlank(this.includePathGlobs, includePathGlob);
            return this;
        }

        public Builder excludePathGlobs(Set<String> excludePathGlobs) {
            this.excludePathGlobs = normalizeNonBlank(excludePathGlobs);
            return this;
        }

        public Builder excludePathGlob(String excludePathGlob) {
            addNonBlank(this.excludePathGlobs, excludePathGlob);
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

        public Builder classUri(String classUri) {
            return typeUri(classUri);
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

        public Builder skipBuild(boolean skipBuild) {
            this.buildPolicy = skipBuild ? BuildPolicy.DENY_BUILD : this.buildPolicy;
            return this;
        }

        public Builder buildPolicy(BuildPolicy buildPolicy) {
            this.buildPolicy = Objects.requireNonNull(buildPolicy, "buildPolicy cannot be null");
            return this;
        }

        public Builder allowUnsandboxedBuild() {
            this.buildPolicy = BuildPolicy.ALLOW_UNSANDBOXED_BUILD;
            return this;
        }

        public Builder externallySandboxedBuild() {
            this.buildPolicy = BuildPolicy.EXTERNALLY_SANDBOXED_BUILD;
            return this;
        }

        public Builder allowPreexistingBytecodeAfterBuildFailure() {
            this.allowPreexistingBytecodeAfterBuildFailure = true;
            return this;
        }

        public Builder classOutputDirs(Set<Path> classOutputDirs) {
            this.classOutputDirs = classOutputDirs == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(classOutputDirs);
            return this;
        }

        public Builder classOutputDirs(List<Path> classOutputDirs) {
            this.classOutputDirs = classOutputDirs == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(classOutputDirs);
            return this;
        }

        public Builder classOutputDir(Path classOutputDir) {
            addPath(this.classOutputDirs, classOutputDir);
            return this;
        }

        public Builder testClassOutputDirs(Set<Path> testClassOutputDirs) {
            this.testClassOutputDirs = testClassOutputDirs == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(testClassOutputDirs);
            return this;
        }

        public Builder testClassOutputDirs(List<Path> testClassOutputDirs) {
            this.testClassOutputDirs = testClassOutputDirs == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(testClassOutputDirs);
            return this;
        }

        public Builder testClassOutputDir(Path testClassOutputDir) {
            addPath(this.testClassOutputDirs, testClassOutputDir);
            return this;
        }

        public Builder projectJars(Set<Path> projectJars) {
            this.projectJars = projectJars == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(projectJars);
            return this;
        }

        public Builder projectJars(List<Path> projectJars) {
            this.projectJars = projectJars == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(projectJars);
            return this;
        }

        public Builder projectJar(Path projectJar) {
            addPath(this.projectJars, projectJar);
            return this;
        }

        public Builder dependencyJars(Set<Path> dependencyJars) {
            this.dependencyJars = dependencyJars == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(dependencyJars);
            return this;
        }

        public Builder dependencyJars(List<Path> dependencyJars) {
            this.dependencyJars = dependencyJars == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(dependencyJars);
            return this;
        }

        public Builder dependencyJar(Path dependencyJar) {
            addPath(this.dependencyJars, dependencyJar);
            return this;
        }

        public Builder classpathFiles(Set<Path> classpathFiles) {
            this.classpathFiles = classpathFiles == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(classpathFiles);
            return this;
        }

        public Builder classpathFiles(List<Path> classpathFiles) {
            this.classpathFiles = classpathFiles == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(classpathFiles);
            return this;
        }

        public Builder classpathFile(Path classpathFile) {
            addPath(this.classpathFiles, classpathFile);
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

        private static void addNonBlank(Set<String> values, String value) {
            if (value != null && !value.isBlank()) {
                values.add(value.trim());
            }
        }

        private static Set<String> addToCopy(Set<String> values, String value) {
            LinkedHashSet<String> copy = new LinkedHashSet<>(values == null ? Set.of() : values);
            addNonBlank(copy, value);
            return copy;
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

        private static void addPath(List<Path> values, Path value) {
            if (value != null) {
                values.add(value);
            }
        }
    }

    private static List<Path> normalizePaths(List<Path> values, Path projectRoot) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        java.util.ArrayList<Path> normalized = new java.util.ArrayList<>();
        for (Path value : values) {
            if (value != null) {
                Path resolved = value.isAbsolute() ? value : projectRoot.resolve(value);
                normalized.add(resolved.toAbsolutePath().normalize());
            }
        }
        return Collections.unmodifiableList(normalized);
    }
}
