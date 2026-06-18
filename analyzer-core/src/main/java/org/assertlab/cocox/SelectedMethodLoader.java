package org.assertlab.cocox;

import org.assertlab.cocox.source.ProjectModel;
import org.assertlab.cocox.source.SourceBackends;
import org.assertlab.cocox.source.SourceMethod;
import org.assertlab.cocox.source.SourceModelBackend;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Phase 2 alternative: load focal methods from a selected-method CSV.
 *
 * <p>Preferred columns are {@code method_uri}, {@code docstring}, and
 * {@code test_prefix}. Legacy PoC/research CSVs with {@code focal_method} are
 * still supported, but the generated MethodInfo identity is always the method
 * URI, not the row id.
 */
public class SelectedMethodLoader {

    private final ProjectMetadata projectMetadata;
    private final Path inputCsvPath;
    private final SourceModelBackend sourceBackend;
    private final List<Failure> failures = new ArrayList<>();

    public SelectedMethodLoader(ProjectMetadata projectMetadata, Path inputCsvPath) {
        this.projectMetadata = Objects.requireNonNull(projectMetadata);
        this.inputCsvPath = Objects.requireNonNull(inputCsvPath);
        this.sourceBackend = SourceBackends.spoon();
    }

    /**
     * Read inputs_selected.csv and produce a MethodInfo per row.
     * Rows whose focal_method cannot be located in project source are skipped
     * with a warning.
     *
     * The CSV uses | as the column delimiter. Backslash is the escape character
     * (\n = newline, \| = literal pipe, \\ = literal backslash). We read the
     * file with BufferedReader and split with splitPipe() rather than OpenCSV,
     * because OpenCSV default escape character (\) silently consumes backslashes
     * -- turning every \n sequence into just n -- which breaks selected-method
     * matching for declarations whose throws clause appears on a separate line.
     */
    public List<MethodInfo> load() throws IOException {
        failures.clear();
        if (!Files.exists(inputCsvPath)) {
            throw new IOException("inputs_selected.csv not found: " + inputCsvPath);
        }

        List<ParsedSourceFile> javaFiles = collectProjectJavaFiles();
        List<MethodInfo> result = new ArrayList<>();

        try (BufferedReader br = Files.newBufferedReader(inputCsvPath, StandardCharsets.UTF_8)) {
            String headerLine = br.readLine();
            if (headerLine == null) {
                return result;
            }
            Map<String, Integer> idx = headerIndex(splitPipe(headerLine));
            int focalIdx  = idx.getOrDefault("focal_method", -1);
            int uriIdx    = idx.getOrDefault("method_uri", -1);
            int docIdx    = idx.getOrDefault("docstring",   -1);
            int testIdx   = idx.getOrDefault("test_prefix", -1);
            int idColIdx  = idx.getOrDefault("id",          -1);
            if (focalIdx < 0 && uriIdx < 0) {
                throw new IOException("selected-method CSV missing required column: method_uri or focal_method");
            }

            String line;
            int rowNum = 0;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;
                rowNum++;
                String[] row = splitPipe(line);
                if ((focalIdx >= 0 && row.length <= focalIdx) || (uriIdx >= 0 && row.length <= uriIdx)) {
                    continue;
                }
                String focal = (focalIdx >= 0 && row.length > focalIdx) ? unescape(row[focalIdx]) : "";
                String methodUri = (uriIdx >= 0 && row.length > uriIdx) ? safe(row[uriIdx]) : "";
                if (focal.isBlank() && methodUri.isBlank()) {
                    continue;
                }
                String docstring  = (docIdx  >= 0 && row.length > docIdx)  ? unescape(safe(row[docIdx]))  : "";
                String testPrefix = (testIdx >= 0 && row.length > testIdx) ? unescape(safe(row[testIdx])) : "";
                String id = (idColIdx >= 0 && row.length > idColIdx && !safe(row[idColIdx]).isBlank())
                        ? safe(row[idColIdx])
                        : String.valueOf(rowNum);

                MethodInfo info = buildMethodInfo(id, methodUri, focal, docstring, testPrefix, javaFiles);
                if (info != null) {
                    result.add(info);
                } else {
                    failures.add(new Failure(
                            id,
                            "SELECTED_METHOD_NOT_FOUND",
                            methodUri,
                            focal.length() > 120 ? focal.substring(0, 120) : focal));
                    System.err.println("[SelectedMethodLoader] Could not locate selected method for row id=" + id);
                }
            }
        }

        return result;
    }

    public List<Failure> getFailures() {
        return List.copyOf(failures);
    }

    /**
     * Split a pipe-delimited CSV line, treating backslash as an escape character.
     * Escape sequences (\n, \|, \\) are preserved verbatim so that unescape()
     * can process them correctly after the split.
     */
    private static String[] splitPipe(String line) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\\' && i + 1 < line.length()) {
                // Preserve escape sequence intact; unescape() handles it later.
                current.append(c);
                current.append(line.charAt(++i));
            } else if (c == '|') {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        parts.add(current.toString());
        return parts.toArray(new String[0]);
    }

    private MethodInfo buildMethodInfo(String rowId, String methodUri, String focal, String docstring, String testPrefix,
                                        List<ParsedSourceFile> javaFiles) {
        SourceMethod parsed = null;
        if (!methodUri.isBlank()) {
            parsed = javaFiles.stream()
                    .flatMap(f -> f.methods().stream())
                    .filter(m -> sameMethodUri(m.methodUri(), methodUri))
                    .findFirst()
                    .orElse(null);
        }

        if (parsed == null && !focal.isBlank()) {
            parsed = matchFocalMethod(rowId, focal, javaFiles);
        }

        if (parsed == null) {
            return null;
        }
        return new MethodInfo.Builder()
                .id(parsed.methodUri())
                .classname(parsed.className())
                .methodName(parsed.methodName())
                .methodSignature(parsed.signature())
                .sourceFile(parsed.sourceFile())
                .lineNumber(parsed.lineNumber())
                .columnNumber(parsed.columnNumber())
                .visibility(parsed.visibility())
                .isStatic(parsed.isStatic())
                .returnType(parsed.returnType())
                .erasedReturnType(parsed.erasedReturnType())
                .sourceSet(parsed.sourceSet())
                .originalDocstring(docstring)
                .testPrefix(testPrefix)
                .build();
    }

    private static boolean sameMethodUri(String currentUri, String requestedUri) {
        if (currentUri.equals(requestedUri)) {
            return true;
        }
        return stripReturnSuffix(currentUri).equals(stripReturnSuffix(requestedUri));
    }

    private static String stripReturnSuffix(String uri) {
        int hash = uri.indexOf('#');
        int close = uri.lastIndexOf(')');
        if (hash < 0 || close < hash || close + 1 >= uri.length() || uri.charAt(close + 1) != ':') {
            return uri;
        }
        return uri.substring(0, close + 1);
    }

    private SourceMethod matchFocalMethod(
            String rowId, String focal, List<ParsedSourceFile> javaFiles) {
        FocalSignature signature = parseFocalSignature(focal);
        if (signature == null) {
            return null;
        }
        String normalBody = normalize(extractBodySnippet(focal, 80));

        SourceMethod best = null;
        int bestScore = 0;
        for (ParsedSourceFile javaFile : javaFiles) {
            for (SourceMethod candidate : javaFile.methods()) {
                if (!candidate.methodName().equals(signature.methodName())) {
                    continue;
                }
                int score = sameParameters(candidate.signature(), signature.parameters()) ? 3 : 1;
                if (!normalBody.isEmpty() && normalize(javaFile.content()).contains(normalBody)) {
                    score += 2;
                }
                if (score > bestScore) {
                    bestScore = score;
                    best = candidate;
                }
            }
        }
        if (best != null && bestScore <= 1) {
            System.err.println("[SelectedMethodLoader] WARN row id=" + rowId
                    + ": matched by method name only; selected method may be ambiguous");
        }
        return best;
    }

    private List<ParsedSourceFile> collectProjectJavaFiles() throws IOException {
        ProjectModel project = ProjectModel.from(projectMetadata);
        List<SourceMethod> methods = sourceBackend.findMethods(project);
        Map<Path, List<SourceMethod>> byFile = new HashMap<>();
        for (SourceMethod method : methods) {
            byFile.computeIfAbsent(method.sourceFile(), ignored -> new ArrayList<>()).add(method);
        }
        List<ParsedSourceFile> files = new ArrayList<>();
        for (Map.Entry<Path, List<SourceMethod>> entry : byFile.entrySet()) {
            files.add(new ParsedSourceFile(entry.getKey(),
                    Files.readString(entry.getKey(), StandardCharsets.UTF_8),
                    entry.getValue()));
        }
        return files;
    }

    /** Extract snippet from inside method body (after {), up to maxLen chars. */
    private static String extractBodySnippet(String focal, int maxLen) {
        int brace = focal.indexOf('{');
        if (brace < 0) return "";
        String body = focal.substring(brace + 1).stripLeading();
        for (String line : body.split("\n")) {
            String t = line.strip();
            if (!t.isEmpty() && !t.equals("{") && !t.equals("}")) {
                return t.length() > maxLen ? t.substring(0, maxLen) : t;
            }
        }
        return body.length() > maxLen ? body.substring(0, maxLen) : body;
    }

    private static boolean sameParameters(String candidateSignature, String focalParameters) {
        int open = candidateSignature.indexOf('(');
        int close = candidateSignature.lastIndexOf(')');
        String candidate = (open >= 0 && close > open)
                ? candidateSignature.substring(open + 1, close)
                : "";
        return normalize(candidate).equals(normalize(focalParameters))
                || commaCount(candidate) == commaCount(focalParameters);
    }

    private static int commaCount(String s) {
        if (s == null || s.isEmpty()) return 0;
        // Number of parameters = commas + 1, but only when string is non-empty.
        int c = 1;
        for (char ch : s.toCharArray()) if (ch == ',') c++;
        return c;
    }

    private static Map<String, Integer> headerIndex(String[] header) {
        Map<String, Integer> map = new HashMap<>();
        for (int i = 0; i < header.length; i++) {
            map.put(header[i].trim().toLowerCase(), i);
        }
        return map;
    }

    private static int required(Map<String, Integer> idx, String key) throws IOException {
        Integer i = idx.get(key);
        if (i == null) {
            throw new IOException("inputs_selected.csv missing required column: " + key);
        }
        return i;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static String unescape(String s) {
        if (s == null) return "";
        // Reverse escaping done by build_selected_inputs.py:
        //   \n -> real newline    \| -> |    \\ -> \
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(i + 1);
                if (next == 'n') { sb.append('\n'); i++; }
                else if (next == '|') { sb.append('|'); i++; }
                else if (next == '\\') { sb.append('\\'); i++; }
                else sb.append(c);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String normalize(String s) {
        return s.replaceAll("\\s+", " ").trim();
    }

    private static FocalSignature parseFocalSignature(String focal) {
        for (String line : focal.split("\\R")) {
            String stripped = line.strip();
            if (stripped.startsWith("@") || stripped.startsWith("//") || stripped.startsWith("*")) {
                continue;
            }
            int open = stripped.indexOf('(');
            int close = stripped.indexOf(')', open);
            if (open < 0 || close < 0) {
                if (stripped.endsWith("{")) {
                    String compact = stripped.substring(0, stripped.length() - 1).strip();
                    if (compact.matches("[A-Z]\\w*(?:\\s+throws\\s+.+)?")) {
                        return new FocalSignature(compact.split("\\s+")[0], "");
                    }
                }
                continue;
            }
            String before = stripped.substring(0, open).strip();
            if (before.isEmpty()) {
                continue;
            }
            String[] tokens = before.split("\\s+");
            return new FocalSignature(tokens[tokens.length - 1], stripped.substring(open + 1, close).trim());
        }
        return null;
    }

    private record ParsedSourceFile(
            Path path,
            String content,
            List<SourceMethod> methods) {
    }

    private record FocalSignature(String methodName, String parameters) {
    }

    public record Failure(String rowId, String reason, String methodUri, String focalPrefix) {
    }
}
