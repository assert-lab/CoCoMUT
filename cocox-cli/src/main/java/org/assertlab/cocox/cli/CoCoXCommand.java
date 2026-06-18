package org.assertlab.cocox.cli;

import org.assertlab.cocox.AnalysisOptions;
import org.assertlab.cocox.CallGraphGenerator;
import org.assertlab.cocox.ContextExtractorService;
import org.assertlab.cocox.ContextRequest;
import org.assertlab.cocox.ExtractionReport;
import org.assertlab.cocox.MethodSelection;
import org.assertlab.cocox.ProjectAnalyzer;
import org.assertlab.cocox.ProjectMetadata;
import org.assertlab.cocox.source.ProjectModel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

@Command(
        name = "cocox",
        mixinStandardHelpOptions = true,
        version = "CoCoX 1.0-SNAPSHOT",
        description = "Static Java method-context extraction.",
        subcommands = {
                CoCoXCommand.ExtractCommand.class,
                CoCoXCommand.ValidateCommand.class,
                CoCoXCommand.SchemaCommand.class
        })
public final class CoCoXCommand implements Callable<Integer> {
    public static void main(String[] args) {
        int exitCode = new CommandLine(new CoCoXCommand()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() {
        CommandLine.usage(this, System.out);
        return 0;
    }

    @Command(name = "extract", description = "Extract method contexts from a Java project.")
    static final class ExtractCommand implements Callable<Integer> {
        @Option(names = "--project", required = true, description = "Project root to analyze.")
        private Path project;

        @Option(names = "--selected", description = "Pipe-delimited selected-method CSV.")
        private Path selected;

        @Option(names = "--scope", defaultValue = "all", description = "Method scope: all or entry-points.")
        private String scope;

        @Option(names = "--entry-points", description = "Shortcut for --scope entry-points.")
        private boolean entryPoints;

        @Option(names = "--call-graph", defaultValue = "auto", description = "Call graph mode: none, cha, rta, or auto.")
        private String callGraph;

        @Option(names = "--resolution", defaultValue = "noclasspath",
                description = "Source resolution mode: noclasspath, classpath, or auto.")
        private String resolution;

        @Option(names = "--output-dir", description = "Directory for generated artifacts. Defaults to ./cocox_output/<project-name>.")
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
            MethodSelection selection = selected != null
                    ? MethodSelection.fromCsv(selected.toAbsolutePath().normalize())
                    : toSelection(entryPoints ? "entry-points" : scope);

            ContextRequest request = ContextRequest.builder()
                    .projectRoot(project)
                    .methodSelection(selection)
                    .callGraphAlgorithm(toAlgorithm(callGraph))
                    .sourceResolution(toSourceResolution(resolution))
                    .outputMode(AnalysisOptions.OutputMode.JSONL)
                    .maxMethods(maxMethods)
                    .maxSourceFiles(maxSourceFiles)
                    .sourceSets(toSourceSets(sourceSet))
                    .packages(emptyIfNull(packages))
                    .classes(emptyIfNull(classes))
                    .methods(emptyIfNull(methods))
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
    }

    @Command(name = "validate", description = "Validate projects, selected CSVs, or generated JSON/JSONL.")
    static final class ValidateCommand implements Callable<Integer> {
        @Option(names = "--project", description = "Validate project detection/source roots.")
        private Path project;

        @Option(names = "--selected", description = "Validate selected-method CSV shape.")
        private Path selected;

        @Option(names = "--json", description = "Validate a generated method-context JSON file.")
        private Path json;

        @Option(names = "--jsonl", description = "Validate a generated method-context JSONL file.")
        private Path jsonl;

        private final ObjectMapper mapper = new ObjectMapper();

        @Override
        public Integer call() throws Exception {
            int checks = 0;
            boolean ok = true;
            if (project != null) {
                checks++;
                ok &= validateProject(project);
            }
            if (selected != null) {
                checks++;
                ok &= validateSelectedCsv(selected);
            }
            if (json != null) {
                checks++;
                ok &= validateJson(json);
            }
            if (jsonl != null) {
                checks++;
                ok &= validateJsonl(jsonl);
            }
            if (checks == 0) {
                System.err.println("No validation target supplied. Use --project, --selected, --json, or --jsonl.");
                return 2;
            }
            return ok ? 0 : 1;
        }

        private boolean validateProject(Path input) {
            Path root = input.toAbsolutePath().normalize();
            if (!Files.isDirectory(root)) {
                System.err.println("PROJECT invalid: not a directory: " + root);
                return false;
            }
            try {
                ProjectMetadata metadata = new ProjectAnalyzer(root).analyze();
                ProjectModel model = ProjectModel.from(metadata);
                System.out.printf("PROJECT ok: build_system=%s source_roots=%d test_source_roots=%d source_available=%s%n",
                        metadata.getBuildSystem(),
                        model.sourceRoots().size(),
                        model.testSourceRoots().size(),
                        model.sourceAvailable());
                return model.sourceAvailable();
            } catch (Exception e) {
                System.err.println("PROJECT invalid: " + e.getMessage());
                return false;
            }
        }

        private boolean validateSelectedCsv(Path input) {
            Path csv = input.toAbsolutePath().normalize();
            if (!Files.isRegularFile(csv)) {
                System.err.println("SELECTED invalid: not a file: " + csv);
                return false;
            }
            try {
                List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
                if (lines.isEmpty()) {
                    System.err.println("SELECTED invalid: empty file: " + csv);
                    return false;
                }
                String header = lines.get(0);
                boolean preferred = header.contains("method_uri");
                boolean legacy = header.contains("focal_method") && header.contains("id");
                if (!preferred && !legacy) {
                    System.err.println("SELECTED invalid: expected method_uri or legacy focal_method/id header");
                    return false;
                }
                System.out.printf("SELECTED ok: rows=%d mode=%s%n",
                        Math.max(0, lines.size() - 1),
                        preferred ? "method_uri" : "legacy");
                return true;
            } catch (IOException e) {
                System.err.println("SELECTED invalid: " + e.getMessage());
                return false;
            }
        }

        private boolean validateJson(Path input) {
            Path file = input.toAbsolutePath().normalize();
            if (!Files.isRegularFile(file)) {
                System.err.println("JSON invalid: not a file: " + file);
                return false;
            }
            try {
                JsonNode node = mapper.readTree(file.toFile());
                return validateContextNode(node, "JSON");
            } catch (IOException e) {
                System.err.println("JSON invalid: " + e.getMessage());
                return false;
            }
        }

        private boolean validateJsonl(Path input) {
            Path file = input.toAbsolutePath().normalize();
            if (!Files.isRegularFile(file)) {
                System.err.println("JSONL invalid: not a file: " + file);
                return false;
            }
            try {
                int rows = 0;
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    if (line.isBlank()) {
                        continue;
                    }
                    rows++;
                    JsonNode node = mapper.readTree(line);
                    List<String> errors = contextValidationErrors(node);
                    if (!errors.isEmpty()) {
                        System.err.println("JSONL invalid: row " + rows + " " + String.join("; ", errors));
                        return false;
                    }
                }
                if (rows == 0) {
                    System.err.println("JSONL invalid: no rows");
                    return false;
                }
                System.out.println("JSONL ok: rows=" + rows);
                return true;
            } catch (IOException e) {
                System.err.println("JSONL invalid: " + e.getMessage());
                return false;
            }
        }

        private boolean validateContextNode(JsonNode node, String label) {
            List<String> errors = contextValidationErrors(node);
            if (!errors.isEmpty()) {
                System.err.println(label + " invalid: " + String.join("; ", errors));
                return false;
            }
            System.out.println(label + " ok");
            return true;
        }

        private List<String> contextValidationErrors(JsonNode node) {
            java.util.ArrayList<String> errors = new java.util.ArrayList<>();
            if (node == null || !node.isObject()) {
                errors.add("root must be an object");
                return errors;
            }
            requireObject(node, "MUT", errors);
            requireObject(node, "metadata", errors);
            requireObject(node, "provenance", errors);
            requireObject(node, "documentation_metrics", errors);
            requireObject(node, "javadoc_metadata", errors);
            requireArray(node, "dynamic_features", errors);
            if (node.has("MUT")) {
                JsonNode mut = node.get("MUT");
                requireText(mut, "method_uri", errors);
                requireText(mut, "method_name", errors);
                requireText(mut, "source_set", errors);
                requireText(mut, "signature", errors);
                requireText(mut, "qualified_name", errors);
                requireArray(mut, "parameters", errors);
                requireText(mut, "code", errors);
                requireText(mut, "javadoc", errors);
                requireObject(mut, "class_hierarchy", errors);
                requireObject(mut, "source_context", errors);
            }
            if (node.has("metadata")) {
                JsonNode metadata = node.get("metadata");
                requireText(metadata, "schema_version", errors);
                requireText(metadata, "source_backend", errors);
                requireText(metadata, "source_backend_mode", errors);
                requireText(metadata, "method_identity", errors);
                requireText(metadata, "type_resolution", errors);
                requireObject(metadata, "call_graph", errors);
            }
            return errors;
        }

        private void requireObject(JsonNode node, String field, List<String> errors) {
            if (!node.has(field) || !node.get(field).isObject()) {
                errors.add("missing object field " + field);
            }
        }

        private void requireArray(JsonNode node, String field, List<String> errors) {
            if (!node.has(field) || !node.get(field).isArray()) {
                errors.add("missing array field " + field);
            }
        }

        private void requireText(JsonNode node, String field, List<String> errors) {
            if (node == null || !node.has(field) || !node.get(field).isTextual()) {
                errors.add("missing text field " + field);
            }
        }
    }

    @Command(name = "schema", description = "Print or write bundled schemas.")
    static final class SchemaCommand implements Callable<Integer> {
        @Parameters(index = "0", defaultValue = "method-context",
                description = "Schema name: method-context or selected-methods.")
        private String schemaName;

        @Option(names = "--print", description = "Print schema to stdout.")
        private boolean print;

        @Option(names = "--output", description = "Write schema to this file.")
        private Path output;

        @Override
        public Integer call() throws Exception {
            String schema = loadSchema(schemaName);
            if (output != null) {
                Path target = output.toAbsolutePath().normalize();
                if (target.getParent() != null) {
                    Files.createDirectories(target.getParent());
                }
                Files.writeString(target, schema, StandardCharsets.UTF_8);
                System.out.println("Wrote schema: " + target);
            }
            if (print || output == null) {
                PrintWriter writer = new PrintWriter(System.out, true, StandardCharsets.UTF_8);
                writer.print(schema);
                writer.flush();
            }
            return 0;
        }

        private String loadSchema(String name) throws IOException {
            String normalized = name.toLowerCase(Locale.ROOT);
            String resource = switch (normalized) {
                case "method-context", "method_context", "context" -> "/schemas/method-context.schema.json";
                case "selected-methods", "selected_methods", "selected" -> "/schemas/selected-methods.schema.json";
                default -> throw new CommandLine.ParameterException(
                        new CommandLine(this),
                        "Unsupported schema: " + name);
            };
            try (InputStream input = CoCoXCommand.class.getResourceAsStream(resource)) {
                if (input == null) {
                    throw new IOException("Missing bundled schema resource: " + resource);
                }
                return new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
    }

    private static MethodSelection toSelection(String value) {
        return switch (normalize(value)) {
            case "all" -> MethodSelection.all();
            case "entry-points", "entry_points" -> MethodSelection.entryPoints();
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

    private static AnalysisOptions.SourceResolution toSourceResolution(String value) {
        return switch (normalize(value)) {
            case "noclasspath", "no-classpath" -> AnalysisOptions.SourceResolution.NOCLASSPATH;
            case "classpath" -> AnalysisOptions.SourceResolution.CLASSPATH;
            case "auto" -> AnalysisOptions.SourceResolution.AUTO;
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

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
    }

    private static boolean isAuto(String value) {
        return "auto".equals(normalize(value));
    }
}
