package org.assertlab.cocomut;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

final class RequestFingerprint {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RequestFingerprint() {
    }

    static String hash(ContextRequest request) {
        Objects.requireNonNull(request, "request cannot be null");
        ObjectNode node = MAPPER.createObjectNode();
        node.put("schema", "cocomut-request-v3");
        node.put("project_selector", "project-root");
        node.put("project_name", request.projectRoot().getFileName().toString());
        node.put("scope", request.scope().toString());
        node.set("source_sets", MAPPER.valueToTree(request.sourceSets().stream().sorted().toList()));
        node.set("packages", MAPPER.valueToTree(request.packages().stream().sorted().toList()));
        node.set("classes", MAPPER.valueToTree(request.classes().stream().sorted().toList()));
        node.set("methods", MAPPER.valueToTree(request.methods().stream().sorted().toList()));
        node.set("visibilities", MAPPER.valueToTree(request.visibilities().stream().sorted().toList()));
        node.set("include_paths", MAPPER.valueToTree(request.includePathGlobs().stream().sorted().toList()));
        node.set("exclude_paths", MAPPER.valueToTree(request.excludePathGlobs().stream().sorted().toList()));
        node.set("targets", MAPPER.valueToTree(request.targets().stream().map(SymbolTarget::prefixedUri).sorted().toList()));
        if (request.maxMethods() == null) {
            node.putNull("max_methods");
        } else {
            node.put("max_methods", request.maxMethods());
        }
        if (request.maxSourceFiles() == null) {
            node.putNull("max_source_files");
        } else {
            node.put("max_source_files", request.maxSourceFiles());
        }
        node.put("call_graph", request.callGraphAlgorithm().toString());
        node.put("build_policy", request.buildPolicy().toString());
        node.put("allow_preexisting_bytecode_after_build_failure",
                request.allowPreexistingBytecodeAfterBuildFailure());
        node.set("class_outputs", MAPPER.valueToTree(artifactIdentities(request.projectRoot(), request.classOutputDirs())));
        node.set("test_class_outputs", MAPPER.valueToTree(artifactIdentities(request.projectRoot(), request.testClassOutputDirs())));
        node.set("project_jars", MAPPER.valueToTree(artifactIdentities(request.projectRoot(), request.projectJars())));
        node.set("dependency_jars", MAPPER.valueToTree(artifactIdentities(request.projectRoot(), request.dependencyJars())));
        node.set("classpath_files", MAPPER.valueToTree(artifactIdentities(request.projectRoot(), request.classpathFiles())));
        node.set("source_roots", MAPPER.valueToTree(artifactIdentities(request.projectRoot(), request.sourceRoots())));
        node.set("test_source_roots", MAPPER.valueToTree(artifactIdentities(request.projectRoot(), request.testSourceRoots())));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(MAPPER.writeValueAsBytes(node)));
        } catch (Exception e) {
            return sha256Hex("cocomut-request-fallback|" + node);
        }
    }

    private static List<String> artifactIdentities(Path projectRoot, List<Path> paths) {
        return (paths == null ? List.<Path>of() : paths).stream()
                .filter(Objects::nonNull)
                .map(path -> artifactIdentity(projectRoot, path.toAbsolutePath().normalize()))
                .toList();
    }

    private static String artifactIdentity(Path projectRoot, Path path) {
        return stableRequestPath(projectRoot, path) + ":" + contentDigest(path);
    }

    private static String stableRequestPath(Path projectRoot, Path path) {
        Path root = projectRoot.toAbsolutePath().normalize();
        try {
            if (path.startsWith(root)) {
                return "project:" + root.relativize(path).toString().replace('\\', '/');
            }
        } catch (Exception ignored) {
            // Use location-independent external identity below.
        }
        Path name = path.getFileName();
        return "external:" + (name == null ? "artifact" : name.toString());
    }

    private static String contentDigest(Path path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            if (Files.isRegularFile(path)) {
                updateFile(digest, path);
                return HexFormat.of().formatHex(digest.digest());
            }
            if (Files.isDirectory(path)) {
                try (var walk = Files.walk(path)) {
                    for (Path file : walk.filter(Files::isRegularFile)
                            .sorted(java.util.Comparator.comparing(p -> path.relativize(p).toString()))
                            .toList()) {
                        digest.update(path.relativize(file).toString().replace('\\', '/')
                                .getBytes(StandardCharsets.UTF_8));
                        digest.update((byte) 0);
                        updateFile(digest, file);
                        digest.update((byte) 0);
                    }
                }
                return HexFormat.of().formatHex(digest.digest());
            }
            return "missing";
        } catch (Exception e) {
            return "error:" + e.getClass().getSimpleName();
        }
    }

    private static void updateFile(MessageDigest digest, Path file) throws java.io.IOException {
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

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            return "0".repeat(64);
        }
    }
}
