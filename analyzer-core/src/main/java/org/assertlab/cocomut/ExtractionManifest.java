package org.assertlab.cocomut;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.assertlab.cocomut.source.ProjectModel;

import java.io.IOException;
import java.io.InputStream;
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

/**
 * Writes run-level extraction provenance.
 *
 * <p>The manifest is intentionally separate from JSONL method records. Method
 * rows should stay focused on method context; run identity, hashes, build
 * policy, and checkout state belong to this artifact.
 */
final class ExtractionManifest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ExtractionManifest() {
    }

    static Path write(Path outputRoot,
                      ProjectMetadata metadata,
                      ProjectModel model,
                      Map<String, Object> selection,
                      String requestHash,
                      Path jsonlPath) throws IOException {
        Objects.requireNonNull(outputRoot, "outputRoot cannot be null");
        Objects.requireNonNull(metadata, "metadata cannot be null");
        Objects.requireNonNull(model, "model cannot be null");

        ObjectNode root = MAPPER.createObjectNode();
        root.put("schema_version", "0.1.0");
        root.put("generated_at", Instant.now().toString());
        root.put("tool", "CoCoMUT");
        root.put("tool_version", toolVersion());
        root.put("request_hash", requestHash);
        root.set("selection", MAPPER.valueToTree(selection));
        root.put("jsonl_file", jsonlPath == null ? "" : jsonlPath.getFileName().toString());

        ObjectNode project = root.putObject("project");
        project.put("name", metadata.getProjectName());
        project.put("path", metadata.getProjectPath().toAbsolutePath().normalize().toString());
        project.put("build_system", metadata.getBuildSystem());
        project.put("java_version", metadata.getJavaVersion());
        project.set("git", MAPPER.valueToTree(gitInfo(metadata.getProjectPath())));

        ObjectNode build = root.putObject("build");
        build.put("attempted", metadata.isBuildAttempted());
        build.put("skipped", metadata.isBuildSkipped());
        build.put("sandboxed", metadata.isBuildSandboxed());
        build.put("status", metadata.getCompileStatus());
        build.put("policy", metadata.isBuildSkipped() ? "skip" : "allow_unsandboxed");

        ObjectNode artifacts = root.putObject("artifacts");
        artifacts.set("source_roots", paths(metadata.getProjectPath(), model.sourceRoots()));
        artifacts.set("test_source_roots", paths(metadata.getProjectPath(), model.testSourceRoots()));
        artifacts.set("main_class_outputs", paths(metadata.getProjectPath(), metadata.getMainClassOutputs()));
        artifacts.set("test_class_outputs", paths(metadata.getProjectPath(), metadata.getTestClassOutputs()));
        artifacts.set("project_jars", paths(metadata.getProjectPath(), metadata.getProjectArtifactJars()));
        artifacts.set("dependency_classpath", paths(metadata.getProjectPath(), metadata.getDependencyClasspath()));
        artifacts.set("explicit_class_output_dirs", paths(metadata.getProjectPath(), metadata.getExplicitClassOutputDirs()));
        artifacts.set("explicit_project_jars", paths(metadata.getProjectPath(), metadata.getExplicitProjectJars()));
        artifacts.set("explicit_dependency_jars", paths(metadata.getProjectPath(), metadata.getExplicitDependencyJars()));
        artifacts.set("explicit_classpath_files", paths(metadata.getProjectPath(), metadata.getExplicitClasspathFiles()));

        ObjectNode hashes = root.putObject("hashes");
        hashes.put("classpath_sha256", hashPaths(metadata.getClasspath()));
        hashes.put("project_bytecode_sha256", hashPaths(concat(metadata.getMainClassOutputs(),
                metadata.getProjectArtifactJars())));
        hashes.put("dependency_classpath_sha256", hashPaths(metadata.getDependencyClasspath()));

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

    private static Map<String, String> gitInfo(Path projectRoot) {
        return Map.of(
                "remote_url", git(projectRoot, "config", "--get", "remote.origin.url"),
                "commit", git(projectRoot, "rev-parse", "HEAD"),
                "dirty", git(projectRoot, "status", "--porcelain").isBlank() ? "false" : "true"
        );
    }

    private static String git(Path projectRoot, String... args) {
        try {
            java.util.ArrayList<String> command = new java.util.ArrayList<>();
            command.add("git");
            command.add("-C");
            command.add(projectRoot.toString());
            command.addAll(List.of(args));
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output;
            try (InputStream input = process.getInputStream()) {
                output = new String(input.readAllBytes(), StandardCharsets.UTF_8).strip();
            }
            if (!process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS) || process.exitValue() != 0) {
                return "";
            }
            return output;
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
        try {
            return projectRoot.toAbsolutePath().normalize()
                    .relativize(path.toAbsolutePath().normalize())
                    .toString().replace('\\', '/');
        } catch (IllegalArgumentException e) {
            return path.toAbsolutePath().normalize().toString();
        }
    }

    private static List<Path> concat(List<Path> first, List<Path> second) {
        java.util.ArrayList<Path> paths = new java.util.ArrayList<>();
        if (first != null) {
            paths.addAll(first);
        }
        if (second != null) {
            paths.addAll(second);
        }
        return paths;
    }

    private static String hashPaths(List<Path> paths) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Path path : paths == null ? List.<Path>of() : paths.stream()
                    .filter(Objects::nonNull)
                    .map(p -> p.toAbsolutePath().normalize())
                    .sorted()
                    .toList()) {
                updateDigest(digest, path);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            return "";
        }
    }

    private static void updateDigest(MessageDigest digest, Path path) throws IOException {
        if (Files.isRegularFile(path)) {
            digest.update(path.toString().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return;
        }
        if (!Files.isDirectory(path)) {
            return;
        }
        try (var walk = Files.walk(path)) {
            for (Path file : walk.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(Path::toString))
                    .toList()) {
                updateDigest(digest, file);
            }
        }
    }
}
