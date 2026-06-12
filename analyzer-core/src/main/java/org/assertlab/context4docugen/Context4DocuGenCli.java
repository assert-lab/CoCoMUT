package org.assertlab.context4docugen;

import java.nio.file.Path;
import java.util.Map;

/**
 * Minimal command-line entry point for static method-context extraction.
 */
public final class Context4DocuGenCli {
    private Context4DocuGenCli() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || has(args, "--help") || has(args, "-h")) {
            printHelp();
            return;
        }
        if (!"extract".equals(args[0])) {
            throw new IllegalArgumentException("Unknown command: " + args[0]);
        }

        Path project = optionPath(args, "--project");
        if (project == null) {
            throw new IllegalArgumentException("Missing required option: --project <path>");
        }

        Path selected = optionPath(args, "--selected");
        AnalysisOptions.Builder options = AnalysisOptions.builder()
                .callGraphAlgorithm(callGraphAlgorithm(args))
                .maxMethods(optionInt(args, "--max-methods"))
                .attemptCompile(has(args, "--compile"))
                .outputMode(outputMode(args));
        if (selected != null) {
            options.selectedCsv(selected);
        } else {
            String scope = option(args, "--scope");
            if ("entry-points".equals(scope) || has(args, "--entry-points")) {
                options.scope(AnalysisOptions.Scope.ENTRY_POINTS);
            } else if (scope != null && !"all".equals(scope)) {
                throw new IllegalArgumentException("Unsupported --scope: " + scope);
            } else {
                options.scope(AnalysisOptions.Scope.ALL);
            }
        }

        Map<String, Object> report = AnalyzerFacade.analyze(project, options.build());

        report.forEach((key, value) -> System.out.printf("%s=%s%n", key, value));
        if (!"SUCCESS".equals(String.valueOf(report.get("status")))) {
            System.exit(1);
        }
    }

    private static Path optionPath(String[] args, String name) {
        String value = option(args, name);
        return value != null ? Path.of(value).toAbsolutePath().normalize() : null;
    }

    private static String option(String[] args, String name) {
        for (int i = 0; i < args.length - 1; i++) {
            if (name.equals(args[i])) {
                return args[i + 1];
            }
        }
        return null;
    }

    private static Integer optionInt(String[] args, String name) {
        String value = option(args, name);
        if (value == null || value.isBlank()) {
            return null;
        }
        return Integer.parseInt(value);
    }

    private static CallGraphGenerator.Algorithm callGraphAlgorithm(String[] args) {
        String value = option(args, "--call-graph");
        if (value == null || value.isBlank()) {
            return CallGraphGenerator.Algorithm.CHA;
        }
        return switch (value.toLowerCase()) {
            case "none" -> CallGraphGenerator.Algorithm.NONE;
            case "cha" -> CallGraphGenerator.Algorithm.CHA;
            case "rta" -> CallGraphGenerator.Algorithm.RTA;
            default -> throw new IllegalArgumentException("Unsupported --call-graph: " + value);
        };
    }

    private static AnalysisOptions.OutputMode outputMode(String[] args) {
        String value = option(args, "--output");
        if (value == null || value.isBlank()) {
            return AnalysisOptions.OutputMode.JSON;
        }
        return switch (value.toLowerCase()) {
            case "json" -> AnalysisOptions.OutputMode.JSON;
            case "jsonl" -> AnalysisOptions.OutputMode.JSONL;
            case "both" -> AnalysisOptions.OutputMode.BOTH;
            default -> throw new IllegalArgumentException("Unsupported --output: " + value);
        };
    }

    private static boolean has(String[] args, String option) {
        for (String arg : args) {
            if (option.equals(arg)) {
                return true;
            }
        }
        return false;
    }

    private static void printHelp() {
        System.out.println("""
                Context4DocuGen

                Usage:
                  c4dg extract --project <path> [--scope all|entry-points]
                  c4dg extract --project <path> --selected <selected-methods.csv>

                Options:
                  --scope all|entry-points       Method scope for source scanning
                  --call-graph none|cha|rta      Optional SootUp call graph algorithm
                  --output json|jsonl|both       Output format
                  --max-methods N                Limit methods for large smoke tests
                  --compile                      Attempt build-tool compilation before analysis

                Maven exec:
                  mvn -pl analyzer-core exec:java \\
                    -Dexec.mainClass=analyzer.Context4DocuGenCli \\
                    -Dexec.args="extract --project /path/to/java/project --scope entry-points --call-graph none"

                Output:
                  <project>/methods.csv
                  <project>/method_context_json/*.json
                  <project>/method_contexts.jsonl when --output jsonl|both

                Notes:
                  Context4DocuGen performs static source/bytecode analysis only.
                  It does not execute the analyzed program.
                """);
    }
}
