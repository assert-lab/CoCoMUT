package org.assertlab.cocomut.cli;

import org.assertlab.cocomut.CallGraphGenerator;
import org.assertlab.cocomut.ContextExtractorService;
import org.assertlab.cocomut.ContextRequest;
import org.assertlab.cocomut.ExtractionReport;
import org.assertlab.cocomut.SymbolTarget;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.Callable;

@Command(
        name = "cocomut",
        mixinStandardHelpOptions = true,
        version = "CoCoMUT 0.1.0",
        description = "Extract static Java method contexts from a Java project.")
public final class CoCoMUTCommand implements Callable<Integer> {
    public static void main(String[] args) {
        int exitCode = new CommandLine(new CoCoMUTCommand()).execute(args);
        System.exit(exitCode);
    }

    @Option(names = "--project", required = true, description = "Project root to analyze.")
    private Path project;

    @Option(names = "--scope", defaultValue = "all", description = "Method scope: all or entry-points.")
    private String scope;

    @Option(names = "--entry-points", description = "Shortcut for --scope entry-points.")
    private boolean entryPoints;

    @Option(names = "--call-graph", defaultValue = "rta",
            description = "Static bytecode call-graph algorithm: rta or cha.")
    private String callGraph;

    @Option(names = "--output-dir", description = "Directory for generated artifacts. Defaults to ./cocomut_output/<project-name>-<path-hash>.")
    private Path outputDir;

    @Option(names = "--max-methods", description = "Limit methods for smoke tests.")
    private Integer maxMethods;

    @Option(names = "--max-source-files", description = "Limit parsed Java source files for low-memory smoke tests.")
    private Integer maxSourceFiles;

    @Option(names = "--source-set", defaultValue = "all",
            description = "Filter methods by source set: all, main, test, integration_test, generated, example, unknown. Comma-separated values are allowed.")
    private String sourceSet;

    @Option(names = "--package", split = ",", description = "Include package prefix, for example org.example.api.")
    private Set<String> packages;

    @Option(names = "--class", split = ",", description = "Include fully qualified or simple class name.")
    private Set<String> classes;

    @Option(names = "--method", split = ",", description = "Include method name or method URI substring.")
    private Set<String> methods;

    @Option(names = "--target-uri", split = ",",
            description = "First-class target URI prefixed with method:, type:, package:, or project:.")
    private Set<String> targetUris;

    @Option(names = "--method-uri", split = ",", description = "Exact method URI target.")
    private Set<String> methodUris;

    @Option(names = {"--type-uri", "--class-uri"}, split = ",",
            description = "Exact type/class URI target: path#qualified.Type.")
    private Set<String> typeUris;

    @Option(names = "--package-uri", split = ",",
            description = "Exact package URI target: package-info path or package dir#qualified.package.")
    private Set<String> packageUris;

    @Option(names = "--visibility", split = ",", description = "Include visibility: public, protected, package-private, private.")
    private Set<String> visibilities;

    @Option(names = "--include-path", split = ",", description = "Include source path glob relative to project root.")
    private Set<String> includePaths;

    @Option(names = "--exclude-path", split = ",", description = "Exclude source path glob relative to project root.")
    private Set<String> excludePaths;

    @Option(names = "--skip-build",
            description = "Do not execute Maven/Gradle. Use existing or explicitly supplied class/JAR artifacts.")
    private boolean skipBuild;

    @Option(names = "--allow-build",
            description = "Explicitly allow unsandboxed Maven/Gradle execution on the host.")
    private boolean allowBuild;

    @Option(names = "--externally-sandboxed-build",
            description = "Allow Maven/Gradle execution and record that the caller provided external sandboxing.")
    private boolean externallySandboxedBuild;

    @Option(names = "--allow-preexisting-bytecode-after-build-failure",
            description = "Allow analysis to continue with pre-existing project bytecode if an attempted build fails.")
    private boolean allowPreexistingBytecodeAfterBuildFailure;

    @Option(names = "--class-output", split = ",",
            description = "Project class-output directory to analyze. May be repeated or comma-separated.")
    private java.util.List<Path> classOutputs;

    @Option(names = "--test-class-output", split = ",",
            description = "Project test class-output directory to analyze. May be repeated or comma-separated.")
    private java.util.List<Path> testClassOutputs;

    @Option(names = "--project-jar", split = ",",
            description = "Project artifact JAR to analyze. May be repeated or comma-separated.")
    private java.util.List<Path> projectJars;

    @Option(names = "--dependency-jar", split = ",",
            description = "Dependency JAR for source/type resolution. May be repeated or comma-separated.")
    private java.util.List<Path> dependencyJars;

    @Option(names = "--classpath-file", split = ",",
            description = "File containing classpath entries, one per line or path-separated.")
    private java.util.List<Path> classpathFiles;

    @Override
    public Integer call() throws Exception {
        ContextRequest.Scope selectedScope = toScope(entryPoints ? "entry-points" : scope);

        ContextRequest.Builder builder = ContextRequest.builder()
                .projectRoot(project)
                .scope(selectedScope)
                .callGraphAlgorithm(toCallGraphAlgorithm(callGraph))
                .maxMethods(maxMethods)
                .maxSourceFiles(maxSourceFiles)
                .sourceSets(toSourceSets(sourceSet))
                .packages(emptyIfNull(packages))
                .classes(emptyIfNull(classes))
                .methods(emptyIfNull(methods))
                .targets(toTargets(targetUris, methodUris, typeUris, packageUris))
                .visibilities(emptyIfNull(visibilities))
                .includePathGlobs(emptyIfNull(includePaths))
                .excludePathGlobs(emptyIfNull(excludePaths))
                .buildPolicy(buildPolicy())
                .classOutputDirs(emptyPathListIfNull(classOutputs))
                .testClassOutputDirs(emptyPathListIfNull(testClassOutputs))
                .projectJars(emptyPathListIfNull(projectJars))
                .dependencyJars(emptyPathListIfNull(dependencyJars))
                .classpathFiles(emptyPathListIfNull(classpathFiles))
                .outputDirectory(outputDir);
        if (allowPreexistingBytecodeAfterBuildFailure) {
            builder.allowPreexistingBytecodeAfterBuildFailure();
        }
        ContextRequest request = builder.build();

        ExtractionReport report = ContextExtractorService.createDefault().extract(request);
        report.asMap().forEach((key, value) -> System.out.printf("%s=%s%n", key, value));
        return report.successful() ? 0 : 1;
    }

    private ContextRequest.BuildPolicy buildPolicy() {
        int selected = (skipBuild ? 1 : 0)
                + (allowBuild ? 1 : 0)
                + (externallySandboxedBuild ? 1 : 0);
        if (selected > 1) {
            throw new IllegalArgumentException("Choose only one build execution policy flag: --skip-build, "
                    + "--allow-build, or --externally-sandboxed-build");
        }
        if (allowPreexistingBytecodeAfterBuildFailure && !allowBuild && !externallySandboxedBuild) {
            throw new IllegalArgumentException("--allow-preexisting-bytecode-after-build-failure requires "
                    + "--allow-build or --externally-sandboxed-build");
        }
        if (skipBuild) {
            return ContextRequest.BuildPolicy.DENY_BUILD;
        }
        if (externallySandboxedBuild) {
            return ContextRequest.BuildPolicy.EXTERNALLY_SANDBOXED_BUILD;
        }
        if (allowBuild) {
            return ContextRequest.BuildPolicy.ALLOW_UNSANDBOXED_BUILD;
        }
        return ContextRequest.BuildPolicy.DENY_BUILD;
    }

    private static ContextRequest.Scope toScope(String value) {
        return switch (normalize(value)) {
            case "all" -> ContextRequest.Scope.ALL;
            case "entry-points", "entry_points" -> ContextRequest.Scope.ENTRY_POINTS;
            default -> throw new IllegalArgumentException("Unsupported --scope: " + value);
        };
    }

    private static CallGraphGenerator.Algorithm toCallGraphAlgorithm(String value) {
        return switch (normalize(value)) {
            case "rta" -> CallGraphGenerator.Algorithm.RTA;
            case "cha" -> CallGraphGenerator.Algorithm.CHA;
            default -> throw new IllegalArgumentException("Unsupported --call-graph: " + value + " (expected rta or cha)");
        };
    }

    private static Set<String> toSourceSets(String value) {
        if (value == null || value.isBlank() || "all".equalsIgnoreCase(value.trim())) {
            return Set.of();
        }
        return java.util.Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(part -> !part.isBlank())
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    private static Set<String> emptyIfNull(Set<String> values) {
        return values == null ? Set.of() : values;
    }

    private static java.util.List<Path> emptyPathListIfNull(java.util.List<Path> values) {
        return values == null ? java.util.List.of() : values;
    }

    private static Set<SymbolTarget> toTargets(Set<String> targetUris, Set<String> methodUris,
                                               Set<String> typeUris, Set<String> packageUris) {
        java.util.LinkedHashSet<SymbolTarget> targets = new java.util.LinkedHashSet<>();
        emptyIfNull(targetUris).stream()
                .filter(value -> value != null && !value.isBlank())
                .map(SymbolTarget::parse)
                .forEach(targets::add);
        emptyIfNull(methodUris).stream()
                .filter(value -> value != null && !value.isBlank())
                .map(SymbolTarget::method)
                .forEach(targets::add);
        emptyIfNull(typeUris).stream()
                .filter(value -> value != null && !value.isBlank())
                .map(SymbolTarget::type)
                .forEach(targets::add);
        emptyIfNull(packageUris).stream()
                .filter(value -> value != null && !value.isBlank())
                .map(SymbolTarget::packageTarget)
                .forEach(targets::add);
        return targets;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(java.util.Locale.ROOT).trim();
    }
}
