package org.assertlab.cocomut;

import java.nio.file.Path;
import java.util.List;

/**
 * Gradle module/source-set metadata preserved for provenance. The current
 * analysis backends may still consume flattened classpaths, but the manifest
 * records the module boundaries that produced them.
 */
public record ModuleSourceSet(String projectPath,
                              String sourceSet,
                              List<Path> sources,
                              List<Path> outputs,
                              List<Path> classpath,
                              String javaVersion) {
    public ModuleSourceSet {
        projectPath = projectPath == null || projectPath.isBlank() ? ":" : projectPath;
        sourceSet = sourceSet == null || sourceSet.isBlank() ? "unknown" : sourceSet;
        sources = sources == null ? List.of() : List.copyOf(sources);
        outputs = outputs == null ? List.of() : List.copyOf(outputs);
        classpath = classpath == null ? List.of() : List.copyOf(classpath);
        javaVersion = javaVersion == null || javaVersion.isBlank() ? "unknown" : javaVersion;
    }
}
