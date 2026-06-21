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
import java.util.Locale;
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

    @Option(names = "--call-graph", defaultValue = "auto", description = "Call graph mode: none, cha, rta, or auto.")
    private String callGraph;

    @Option(names = "--resolution", defaultValue = "noclasspath",
            description = "Source resolution mode: noclasspath, classpath, or auto.")
    private String resolution;

    @Option(names = "--output-dir", description = "Directory for generated artifacts. Defaults to ./cocomut_output/<project-name>.")
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

    @Option(names = "--compile", description = "Attempt Maven/Gradle compilation before analysis.")
    private boolean compile;

    @Override
    public Integer call() throws Exception {
        ContextRequest.Scope selectedScope = toScope(entryPoints ? "entry-points" : scope);

        ContextRequest request = ContextRequest.builder()
                .projectRoot(project)
                .scope(selectedScope)
                .callGraphAlgorithm(toAlgorithm(callGraph))
                .sourceResolution(toSourceResolution(resolution))
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
                .outputDirectory(outputDir)
                .attemptCompile(compile || isAuto(callGraph) || isAuto(resolution))
                .build();

        ExtractionReport report = ContextExtractorService.createDefault().extract(request);
        report.asMap().forEach((key, value) -> System.out.printf("%s=%s%n", key, value));
        return report.successful() ? 0 : 1;
    }

    private static ContextRequest.Scope toScope(String value) {
        return switch (normalize(value)) {
            case "all" -> ContextRequest.Scope.ALL;
            case "entry-points", "entry_points" -> ContextRequest.Scope.ENTRY_POINTS;
            default -> throw new IllegalArgumentException("Unsupported --scope: " + value);
        };
    }

    private static CallGraphGenerator.Algorithm toAlgorithm(String value) {
        return switch (normalize(value)) {
            case "none" -> CallGraphGenerator.Algorithm.NONE;
            case "cha" -> CallGraphGenerator.Algorithm.CHA;
            case "rta" -> CallGraphGenerator.Algorithm.RTA;
            case "auto" -> CallGraphGenerator.Algorithm.AUTO;
            default -> throw new IllegalArgumentException("Unsupported --call-graph: " + value);
        };
    }

    private static ContextRequest.SourceResolution toSourceResolution(String value) {
        return switch (normalize(value)) {
            case "noclasspath", "no-classpath" -> ContextRequest.SourceResolution.NOCLASSPATH;
            case "classpath" -> ContextRequest.SourceResolution.CLASSPATH;
            case "auto" -> ContextRequest.SourceResolution.AUTO;
            default -> throw new IllegalArgumentException("Unsupported --resolution: " + value);
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
        return value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
    }

    private static boolean isAuto(String value) {
        return "auto".equals(normalize(value));
    }
}
