package org.assertlab.cocomut;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.assertlab.cocomut.source.ProjectModel;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Writes run-level extraction provenance.
 *
 * <p>The manifest is intentionally separate from JSONL method records. Method
 * rows stay focused on context; run identity, build policy, artifact roles,
 * hashes, and checkout state belong here.
 */
final class ExtractionManifest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String HASH_FORMAT = "cocomut-artifact-v1";

    private ExtractionManifest() {
    }

    static Path write(Path outputRoot,
                      ProjectMetadata metadata,
                      ProjectModel model,
                      Map<String, Object> selection,
                      String requestHash,
                      Path jsonlPath,
                      Map<String, Object> executionReport) throws IOException {
        return write(outputRoot, metadata, model, selection, requestHash, jsonlPath, executionReport,
                captureGitInfo(metadata != null ? metadata.getProjectPath() : null));
    }

    static Path write(Path outputRoot,
                      ProjectMetadata metadata,
                      ProjectModel model,
                      Map<String, Object> selection,
                      String requestHash,
                      Path jsonlPath,
                      Map<String, Object> executionReport,
                      GitInfo gitAtStart) throws IOException {
        Objects.requireNonNull(outputRoot, "outputRoot cannot be null");

        ObjectNode root = MAPPER.createObjectNode();
        root.put("schema_version", "0.2.0");
        root.put("generated_at", Instant.now().toString());
        root.put("tool", "CoCoMUT");
        root.put("tool_version", toolVersion());
        root.put("request_hash", requestHash == null ? "" : requestHash);
        root.set("selection", MAPPER.valueToTree(selection == null ? Map.of() : selection));
        root.put("jsonl_file", jsonlPath == null ? null : jsonlPath.getFileName().toString());
        root.set("execution", MAPPER.valueToTree(executionReport == null ? Map.of() : executionReport));

        Path projectPath = metadata != null ? metadata.getProjectPath() : null;
        ObjectNode project = root.putObject("project");
        project.put("name", metadata != null ? metadata.getProjectName() : "");
        project.put("path", projectPath == null ? "" : projectPath.toAbsolutePath().normalize().toString());
        project.put("build_system", metadata != null ? metadata.getBuildSystem() : "unknown");
        project.put("java_version", metadata != null ? metadata.getJavaVersion() : "unknown");
        project.set("git", MAPPER.valueToTree(gitAtStart != null ? gitAtStart : captureGitInfo(projectPath)));

        ObjectNode build = root.putObject("build");
        build.put("attempted", metadata != null && metadata.isBuildAttempted());
        build.put("exit_code", metadata != null ? metadata.getBuildExitCode() : -1);
        build.put("succeeded", metadata != null && metadata.isBuildSucceeded());
        build.put("timed_out", metadata != null && metadata.isBuildTimedOut());
        build.put("skipped", metadata != null && metadata.isBuildSkipped());
        build.put("sandboxed", metadata != null && metadata.isBuildSandboxed());
        build.put("status", metadata != null ? metadata.getCompileStatus() : "NOT_ANALYZED");
        build.put("policy", metadata != null ? metadata.getBuildPolicy().toString() : "UNKNOWN");
        build.put("allow_preexisting_bytecode_after_build_failure",
                metadata != null && metadata.isAllowPreexistingBytecodeAfterBuildFailure());
        build.put("bytecode_available", metadata != null && metadata.isBytecodeAvailable());
        build.put("bytecode_origin", metadata != null ? metadata.getBytecodeOrigin() : "none");
        build.put("analysis_can_proceed", metadata != null && metadata.isAnalysisCanProceed());

        ObjectNode artifacts = root.putObject("artifacts");
        List<Path> sourceRoots = model != null ? model.sourceRoots() : List.of();
        List<Path> testSourceRoots = model != null ? model.testSourceRoots() : List.of();
        artifacts.set("source_roots", paths(projectPath, sourceRoots));
        artifacts.set("test_source_roots", paths(projectPath, testSourceRoots));
        artifacts.set("main_class_outputs", paths(projectPath,
                metadata != null ? metadata.getMainClassOutputs() : List.of()));
        artifacts.set("test_class_outputs", paths(projectPath,
                metadata != null ? metadata.getTestClassOutputs() : List.of()));
        artifacts.set("project_jars", paths(projectPath,
                metadata != null ? metadata.getProjectArtifactJars() : List.of()));
        artifacts.set("dependency_classpath", paths(projectPath,
                metadata != null ? metadata.getDependencyClasspath() : List.of()));
        artifacts.set("explicit_class_output_dirs", paths(projectPath,
                metadata != null ? metadata.getExplicitClassOutputDirs() : List.of()));
        artifacts.set("explicit_test_class_output_dirs", paths(projectPath,
                metadata != null ? metadata.getExplicitTestClassOutputDirs() : List.of()));
        artifacts.set("explicit_project_jars", paths(projectPath,
                metadata != null ? metadata.getExplicitProjectJars() : List.of()));
        artifacts.set("explicit_dependency_jars", paths(projectPath,
                metadata != null ? metadata.getExplicitDependencyJars() : List.of()));
        artifacts.set("explicit_classpath_files", paths(projectPath,
                metadata != null ? metadata.getExplicitClasspathFiles() : List.of()));

        ObjectNode hashes = root.putObject("hashes");
        hashes.put("algorithm", "sha-256");
        hashes.put("format", HASH_FORMAT);
        hashes.set("main_bytecode", hashNode(hashPaths("main_bytecode", projectPath,
                metadata != null ? concat(metadata.getMainClassOutputs(), metadata.getProjectArtifactJars()) : List.of())));
        hashes.set("test_bytecode", hashNode(hashPaths("test_bytecode", projectPath,
                metadata != null ? metadata.getTestClassOutputs() : List.of())));
        hashes.set("combined_project_bytecode", hashNode(hashPaths("combined_project_bytecode", projectPath,
                metadata != null ? concat(metadata.getMainClassOutputs(), metadata.getTestClassOutputs(),
                        metadata.getProjectArtifactJars()) : List.of())));
        hashes.set("dependency_classpath", hashNode(hashPaths("dependency_classpath_ordered", projectPath,
                metadata != null ? metadata.getDependencyClasspath() : List.of())));
        hashes.set("dependency_classpath_content_set", hashNode(hashPaths("dependency_classpath_content_set", projectPath,
                metadata != null ? metadata.getDependencyClasspath() : List.of(), false)));
        hashes.set("emitted_jsonl", hashNode(hashSingleFile("emitted_jsonl", jsonlPath)));

        Path manifest = outputRoot.resolve("extraction_manifest.json");
        Files.createDirectories(manifest.getParent());
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(manifest.toFile(), root);
        return manifest;
    }

    private static String toolVersion() {
        Package pkg = ExtractionManifest.class.getPackage();
        String version = pkg != null ? pkg.getImplementationVersion() : null;
        return version == null || version.isBlank() ? "0.1.0" : version;
    }

    static GitInfo captureGitInfo(Path projectRoot) {
        if (projectRoot == null || !Files.isDirectory(projectRoot)) {
            return GitInfo.unavailable("project path unavailable");
        }
        GitCommand root = git(projectRoot, "rev-parse", "--show-toplevel");
        if (!root.ok()) {
            return GitInfo.unavailable(root.error());
        }
        GitCommand commit = git(projectRoot, "rev-parse", "HEAD");
        GitCommand status = git(projectRoot, "status", "--porcelain");
        GitCommand remote = git(projectRoot, "config", "--get", "remote.origin.url");
        return new GitInfo(true,
                root.output(),
                relative(root.output(), projectRoot),
                remote.ok() ? sanitizeRemote(remote.output()) : "",
                commit.ok() ? commit.output() : "",
                status.ok() ? !status.output().isBlank() : null,
                firstError(commit, status, remote));
    }

    private static GitCommand git(Path projectRoot, String... args) {
        Path out = null;
        try {
            out = Files.createTempFile("cocomut-git", ".out");
            java.util.ArrayList<String> command = new java.util.ArrayList<>();
            command.add("git");
            command.add("-C");
            command.add(projectRoot.toString());
            command.addAll(List.of(args));
            Process process = new ProcessBuilder(command)
                    .redirectOutput(out.toFile())
                    .redirectErrorStream(true)
                    .start();
            boolean done = process.waitFor(3, TimeUnit.SECONDS);
            if (!done) {
                process.destroyForcibly();
                return new GitCommand(false, "", "git command timed out");
            }
            String output = Files.readString(out, StandardCharsets.UTF_8).strip();
            if (process.exitValue() != 0) {
                return new GitCommand(false, "", output.isBlank() ? "git command failed" : output);
            }
            return new GitCommand(true, output, "");
        } catch (Exception e) {
            return new GitCommand(false, "", e.getClass().getSimpleName());
        } finally {
            if (out != null) {
                try {
                    Files.deleteIfExists(out);
                } catch (IOException ignored) {
                    // Temporary diagnostics only.
                }
            }
        }
    }

    private static String sanitizeRemote(String remote) {
        if (remote == null || remote.isBlank()) {
            return "";
        }
        try {
            URI uri = URI.create(remote);
            if (uri.getScheme() != null && uri.getHost() != null) {
                URI sanitized = new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(),
                        uri.getPath(), null, null);
                return sanitized.toString();
            }
        } catch (Exception ignored) {
            // Fall through to conservative text sanitation.
        }
        return remote.replaceFirst("://[^/@]+@", "://").replaceAll("\\?.*$", "");
    }

    private static String firstError(GitCommand... commands) {
        for (GitCommand command : commands) {
            if (!command.ok() && command.error() != null && !command.error().isBlank()) {
                return command.error();
            }
        }
        return "";
    }

    private static String relative(String root, Path projectRoot) {
        try {
            return Path.of(root).toAbsolutePath().normalize()
                    .relativize(projectRoot.toAbsolutePath().normalize())
                    .toString().replace('\\', '/');
        } catch (Exception e) {
            return "";
        }
    }

    private static ArrayNode paths(Path projectRoot, List<Path> paths) {
        ArrayNode array = MAPPER.createArrayNode();
        for (Path path : paths == null ? List.<Path>of() : paths) {
            array.add(displayPath(projectRoot, path));
        }
        return array;
    }

    private static String displayPath(Path projectRoot, Path path) {
        if (path == null) {
            return "";
        }
        if (projectRoot != null) {
            try {
                return projectRoot.toAbsolutePath().normalize()
                        .relativize(path.toAbsolutePath().normalize())
                        .toString().replace('\\', '/');
            } catch (IllegalArgumentException ignored) {
                // External artifact: absolute path is useful in the manifest display,
                // but not in the content hash.
            }
        }
        return path.toAbsolutePath().normalize().toString();
    }

    @SafeVarargs
    private static List<Path> concat(List<Path>... lists) {
        java.util.ArrayList<Path> paths = new java.util.ArrayList<>();
        for (List<Path> list : lists) {
            if (list != null) {
                paths.addAll(list);
            }
        }
        return paths;
    }

    private static HashResult hashSingleFile(String role, Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return HashResult.missing(role);
        }
        return hashPaths(role, file.getParent(), List.of(file));
    }

    private static HashResult hashPaths(String role, Path stableRoot, List<Path> paths) {
        return hashPaths(role, stableRoot, paths, true);
    }

    private static HashResult hashPaths(String role, Path stableRoot, List<Path> paths, boolean preserveOrder) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            java.util.stream.Stream<Path> stream = (paths == null ? List.<Path>of() : paths).stream()
                    .filter(Objects::nonNull)
                    .map(path -> path.toAbsolutePath().normalize());
            List<Path> normalized = preserveOrder
                    ? stream.distinct().toList()
                    : stream.distinct()
                            .sorted(Comparator.comparing(ExtractionManifest::artifactContentKey)
                                    .thenComparing(path -> stableArtifactLabel(stableRoot, path)))
                            .toList();
            if (normalized.isEmpty()) {
                return new HashResult(role, null, "empty", List.of());
            }
            List<String> missing = normalized.stream()
                    .filter(path -> !Files.isRegularFile(path) && !Files.isDirectory(path))
                    .map(Path::toString)
                    .toList();
            if (!missing.isEmpty()) {
                return new HashResult(role, null, "missing", missing);
            }
            for (int i = 0; i < normalized.size(); i++) {
                updateDigest(digest, role, stableRoot, i, normalized.get(i));
            }
            return new HashResult(role, HexFormat.of().formatHex(digest.digest()), "ok", List.of());
        } catch (Exception e) {
            return new HashResult(role, null, "error", List.of(e.getClass().getSimpleName()));
        }
    }

    private static void updateDigest(MessageDigest digest, String role, Path stableRoot, int rootIndex, Path path)
            throws IOException {
        if (Files.isRegularFile(path)) {
            updateFileDigest(digest, role, stableRoot, rootIndex, path, fileEntryName(stableRoot, path));
            return;
        }
        if (!Files.isDirectory(path)) {
            return;
        }
        try (var walk = Files.walk(path)) {
            for (Path file : walk.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(Path::toString))
                    .toList()) {
                updateFileDigest(digest, role, stableRoot, rootIndex, file, path.relativize(file).toString());
            }
        }
    }

    private static String fileEntryName(Path stableRoot, Path file) {
        if (stableRoot != null) {
            try {
                Path root = stableRoot.toAbsolutePath().normalize();
                Path normalized = file.toAbsolutePath().normalize();
                if (normalized.startsWith(root)) {
                    return root.relativize(normalized).toString();
                }
            } catch (Exception ignored) {
                // Use stable file name below for external artifacts.
            }
        }
        return file.getFileName() == null ? "artifact" : file.getFileName().toString();
    }

    private static void updateFileDigest(MessageDigest digest, String role, Path stableRoot, int rootIndex,
                                         Path file, String entryName) throws IOException {
        digest.update(role.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(("root-" + rootIndex).getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(entryName.replace('\\', '/').getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(Long.toString(Files.size(file)).getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
    }

    private static String stableArtifactLabel(Path stableRoot, Path path) {
        if (stableRoot != null) {
            try {
                Path root = stableRoot.toAbsolutePath().normalize();
                Path normalized = path.toAbsolutePath().normalize();
                if (normalized.startsWith(root)) {
                    return "project:" + root.relativize(normalized).toString().replace('\\', '/');
                }
            } catch (Exception ignored) {
                // Fall through to path-independent external label.
            }
        }
        Path fileName = path.getFileName();
        return "external:" + (fileName == null ? "artifact" : fileName.toString());
    }

    private static String artifactContentKey(Path path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            if (Files.isRegularFile(path)) {
                updateContentDigest(digest, path);
            } else if (Files.isDirectory(path)) {
                try (var walk = Files.walk(path)) {
                    for (Path file : walk.filter(Files::isRegularFile)
                            .sorted(Comparator.comparing(file -> path.relativize(file).toString()))
                            .toList()) {
                        digest.update(path.relativize(file).toString().replace('\\', '/')
                                .getBytes(StandardCharsets.UTF_8));
                        digest.update((byte) 0);
                        updateContentDigest(digest, file);
                    }
                }
            } else {
                return "missing:" + path;
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            return "error:" + e.getClass().getSimpleName() + ":" + path.getFileName();
        }
    }

    private static void updateContentDigest(MessageDigest digest, Path file) throws IOException {
        digest.update(Long.toString(Files.size(file)).getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
    }

    private static ObjectNode hashNode(HashResult result) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("role", result.role());
        if (result.sha256() == null) {
            node.putNull("sha256");
        } else {
            node.put("sha256", result.sha256());
        }
        node.put("status", result.status());
        ArrayNode errors = node.putArray("errors");
        result.errors().forEach(errors::add);
        return node;
    }

    private record GitCommand(boolean ok, String output, String error) {}

    record GitInfo(boolean available,
                   String root,
                   String relative_project_path,
                   String remote_url,
                   String commit,
                   Boolean dirty,
                   String error) {
        static GitInfo unavailable(String error) {
            return new GitInfo(false, "", "", "", "", null, error == null ? "" : error);
        }
    }

    private record HashResult(String role, String sha256, String status, List<String> errors) {
        static HashResult missing(String role) {
            return new HashResult(role, null, "missing", List.of());
        }
    }
}
