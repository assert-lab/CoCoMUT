import java.nio.file.*;
import java.util.*;
import com.fasterxml.jackson.databind.*;
import org.assertlab.cocomut.*;
import org.assertlab.cocomut.source.*;

/** Read-only source-model probe using archived artifacts, optionally rediscovering Gradle roots. */
public class SourcePopulationProbe {
    static String git(Path root, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-C", root.toString()));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).start();
        String result = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        if (process.waitFor() != 0) throw new IllegalStateException("git receipt failed");
        return result.strip();
    }
    static List<Path> paths(JsonNode artifacts, String field, Path root) {
        List<Path> paths = new ArrayList<>();
        for (JsonNode value : artifacts.path(field)) paths.add(root.resolve(value.asText()).normalize());
        return paths;
    }
    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args.length > 3 || (args.length == 3 && !args[2].equals("--discover"))) {
            throw new IllegalArgumentException("Usage: SourcePopulationProbe MANIFEST_JSON OUTPUT_DIRECTORY [--discover]");
        }
        ObjectMapper mapper = new ObjectMapper();
        JsonNode manifest = mapper.readTree(Path.of(args[0]).toFile());
        Path root = Path.of(manifest.path("project").path("path").asText());
        Path output = Path.of(args[1]);
        Files.createDirectories(output);
        JsonNode artifacts = manifest.path("artifacts");
        ProjectMetadata metadata = new ProjectMetadata.Builder()
                .projectName(root.getFileName().toString()).projectPath(root)
                .buildSystem(manifest.path("project").path("build_system").asText())
                .javaVersion(manifest.path("project").path("java_version").asText())
                .sourceRoot(paths(artifacts, "source_roots", root).get(0))
                .sourceRoots(paths(artifacts, "source_roots", root))
                .testSourceRoots(paths(artifacts, "test_source_roots", root))
                .mainClassOutputs(paths(artifacts, "main_class_outputs", root))
                .testClassOutputs(paths(artifacts, "test_class_outputs", root))
                .dependencyClasspath(paths(artifacts, "dependency_classpath", root))
                .projectArtifactJars(paths(artifacts, "project_jars", root)).build();
        if (args.length > 2 && args[2].equals("--discover")) {
            metadata = new org.assertlab.cocomut.adapter.GradleProjectAdapter(root).toMetadata(
                ContextRequest.builder().projectRoot(root).scope(ContextRequest.Scope.ALL)
                    .sourceSets(Set.of("main", "test")).skipBuild(true).build());
        }
        mapper.writerWithDefaultPrettyPrinter().writeValue(output.resolve("source-roots.json").toFile(),
                Map.of("main", metadata.getSourceRoots().stream().map(Path::toString).toList(),
                       "test", metadata.getTestSourceRoots().stream().map(Path::toString).toList()));
        System.out.println("Opening source model for " + root);
        String commit = git(root, "rev-parse", "HEAD");
        String statusBefore = git(root, "status", "--porcelain");
        long start = System.currentTimeMillis();
        try (var session = SourceBackends.spoon().open(ProjectModel.from(metadata))) {
            Map<String,Object> summary = new LinkedHashMap<>();
            var stats = session.parseStats();
            summary.put("project_commit", manifest.path("project").path("git").path("commit").asText());
            summary.put("actual_project_commit", commit);
            summary.put("subject_status_before", statusBefore);
            summary.put("subject_status_after", git(root, "status", "--porcelain"));
            summary.put("source_root_mode", args.length > 2 ? "gradle_skip_build_discovery" : "archived_manifest");
            summary.put("runtime_java_version", System.getProperty("java.version"));
            summary.put("java_version", metadata.getJavaVersion());
            summary.put("discovered", stats.discovered());
            summary.put("parsed", stats.parsed());
            summary.put("failed", stats.failed());
            summary.put("failed_files", stats.failedFiles().stream().map(root::relativize).map(Path::toString).toList());
            summary.put("recovered_files", stats.recoveredFiles().stream().map(root::relativize).map(Path::toString).toList());
            summary.put("source_backend_mode", stats.mode());
            summary.put("methods", session.methods().size());
            summary.put("non_constructor_methods", session.methods().stream().filter(m -> !m.constructor()).count());
            summary.put("elapsed_ms", System.currentTimeMillis() - start);
            summary.put("attempts", stats.modelAttempts().stream().map(SourceModelAttempt::asMap).toList());
            mapper.writerWithDefaultPrettyPrinter().writeValue(output.resolve("source-population.json").toFile(), summary);
            try (var writer = Files.newBufferedWriter(output.resolve("methods.jsonl"))) {
                for (var method : session.methods()) {
                    writer.write(mapper.writeValueAsString(Map.of("method_uri", method.methodUri(),
                            "source_file", root.relativize(method.sourceFile()).toString(), "constructor", method.constructor(), "line", method.lineNumber(), "column", method.columnNumber(), "name", method.methodName(), "arity", method.parameters().size())));
                    writer.newLine();
                }
            }
            System.out.println("Source population: " + summary.get("non_constructor_methods") + " methods; " + stats.failed() + " failed files; " + stats.recoveredFiles().size() + " recovered files");
        }
    }
}
