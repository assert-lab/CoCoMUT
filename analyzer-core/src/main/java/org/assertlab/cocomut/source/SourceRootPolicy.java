package org.assertlab.cocomut.source;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.assertlab.cocomut.ProjectMetadata;

/** Deterministic build-directory provenance, independent of compilation source-set roles. */
public final class SourceRootPolicy {
    private SourceRootPolicy() {}

    public static boolean isGenerated(Path path) {
        if (path == null) return false;
        Path normalized = path.toAbsolutePath().normalize();
        try { normalized = normalized.toRealPath(); } catch (IOException ignored) { }
        for (int i = 0; i + 1 < normalized.getNameCount(); i++) {
            String parent = normalized.getName(i).toString();
            String child = normalized.getName(i + 1).toString();
            if (parent.equals("target") && child.startsWith("generated-")) return true;
            if (parent.equals("build") && child.equals("generated")) return true;
        }
        return false;
    }

    public static Map<String, String> roles(ProjectMetadata metadata) {
        Map<String, String> roles = new LinkedHashMap<>();
        java.util.stream.Stream.concat(metadata.getSourceRoots().stream(), metadata.getTestSourceRoots().stream())
                .sorted().forEach(root -> roles.put(root.toAbsolutePath().normalize().toString(),
                        isGenerated(root) ? "generated" : "original"));
        return roles;
    }
}
