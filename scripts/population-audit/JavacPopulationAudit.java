import java.nio.file.*;
import java.util.*;
import javax.tools.*;
import com.sun.source.tree.*;
import com.sun.source.util.*;
import com.fasterxml.jackson.databind.*;

/** Independent parse-only audit: never resolves dependencies or changes subjects. */
public class JavacPopulationAudit {
    public static void main(String[] args) throws Exception {
        if (args.length < 3 || args.length > 4) {
            throw new IllegalArgumentException("Usage: JavacPopulationAudit ROOT METHODS_JSONL OUTPUT_JSON [ROOTS_JSON]");
        }
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        Path model = Path.of(args[1]);
        Path output = Path.of(args[2]);
        ObjectMapper mapper = new ObjectMapper();
        Map<String,List<JsonNode>> actual = new TreeMap<>();
        try (var rows = Files.lines(model)) {
            for (String row : (Iterable<String>) rows::iterator) {
                JsonNode method = mapper.readTree(row);
                actual.computeIfAbsent(method.path("source_file").asText(), k -> new ArrayList<>()).add(method);
            }
        }
        Set<Path> inputs = new TreeSet<>();
        // Audit the model's actual files plus every tracked production Java file.
        // The latter detects omitted roots independently of native metadata.
        for (String file : actual.keySet()) inputs.add(root.resolve(file));
        Process git = new ProcessBuilder("git", "-C", root.toString(), "ls-files", "-z").start();
        String tracked = new String(git.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        if (git.waitFor() != 0) throw new IllegalStateException("git ls-files failed");
        for (String file : tracked.split("\u0000")) {
            if (file.endsWith(".java") && (file.startsWith("src/main/java/") || file.contains("/src/main/java/")))
                inputs.add(root.resolve(file));
        }
        if (args.length > 3) {
            JsonNode roots = mapper.readTree(Path.of(args[3]).toFile());
            for (String kind : List.of("main", "test")) {
                for (JsonNode value : roots.path(kind)) {
                    Path sourceRoot = Path.of(value.asText());
                    if (!Files.isDirectory(sourceRoot)) continue;
                    try (var paths = Files.walk(sourceRoot)) {
                        paths.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".java"))
                                .map(p -> p.toAbsolutePath().normalize()).forEach(inputs::add);
                    }
                }
            }
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("Run with a JDK, not a JRE");
        List<Map<String,Object>> missing = new ArrayList<>();
        List<Map<String,Object>> ambiguous = new ArrayList<>();
        List<Map<String,Object>> failures = new ArrayList<>();
        int declarations = 0;
        int matched = 0;
        // Per-file parsing keeps malformed inputs isolated and memory bounded.
        for (Path file : inputs) {
            DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
            try (StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, null, null)) {
                JavacTask task = (JavacTask) compiler.getTask(null, files, diagnostics,
                        List.of("-proc:none"), null, files.getJavaFileObjects(file));
                CompilationUnitTree unit = task.parse().iterator().next();
                String relative = root.relativize(file).toString();
                SourcePositions positions = Trees.instance(task).getSourcePositions();
                List<Map<String,Object>> expected = new ArrayList<>();
                new TreeScanner<Void,Void>() {
                    @Override public Void visitMethod(MethodTree method, Void unused) {
                        long start = positions.getStartPosition(unit, method);
                        if (start >= 0) {
                            boolean constructor = method.getReturnType() == null;
                            expected.add(Map.of("source_file", relative,
                                    "line", unit.getLineMap().getLineNumber(start),
                                    "column", unit.getLineMap().getColumnNumber(start),
                                    "start_offset", start,
                                    "header_end_offset", method.getBody() == null ? positions.getEndPosition(unit, method) : positions.getStartPosition(unit, method.getBody()),
                                    "name", method.getName().toString(),
                                    "constructor", constructor, "arity", method.getParameters().size()));
                        }
                        return super.visitMethod(method, unused);
                    }
                }.scan(unit, null);
                if (diagnostics.getDiagnostics().stream().anyMatch(d -> d.getKind() == Diagnostic.Kind.ERROR)) {
                    failures.add(Map.of("source_file", relative, "errors", diagnostics.getDiagnostics().stream()
                            .filter(d -> d.getKind() == Diagnostic.Kind.ERROR).map(Diagnostic::toString).toList()));
                    continue;
                }
                declarations += expected.size();
                for (Map<String,Object> method : expected) {
                    List<JsonNode> candidates = actual.getOrDefault(relative, List.of()).stream().filter(m ->
                            m.path("constructor").asBoolean() == (boolean)method.get("constructor") &&
                            ((boolean)method.get("constructor") || m.path("name").asText().equals(method.get("name"))) &&
                            (unit.getLineMap().getStartPosition(m.path("line").asLong()) + m.path("column").asLong() - 1) >= (long)method.get("start_offset") &&
                            (unit.getLineMap().getStartPosition(m.path("line").asLong()) + m.path("column").asLong() - 1) < (long)method.get("header_end_offset") &&
                            m.path("arity").asInt() == (int)method.get("arity")).toList();
                    // Match the model name position inside this exact declaration header.
                    // This handles annotations and multiline signatures without fuzzy matching.
                    // Spoon columns count raw characters; javac display columns expand tabs.
                    if (candidates.size() == 1) matched++;
                    else if (candidates.isEmpty()) missing.add(method);
                    else ambiguous.add(method);
                }
            } catch (Exception error) {
                failures.add(Map.of("source_file", root.relativize(file).toString(), "error", error.toString()));
            }
        }
        Map<String,Object> summary = new LinkedHashMap<>();
        summary.put("scope", "model files plus tracked src/main/java and optional declared roots; explicit methods and constructors; parse only");
        summary.put("files", inputs.size());
        summary.put("declarations", declarations);
        summary.put("matched", matched);
        summary.put("missing", missing);
        summary.put("ambiguous", ambiguous);
        summary.put("parse_failures", failures);
        mapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), summary);
        System.out.println("declarations=" + declarations + " matched=" + matched + " missing=" + missing.size()
                + " ambiguous=" + ambiguous.size() + " parse_failures=" + failures.size());
    }
}
