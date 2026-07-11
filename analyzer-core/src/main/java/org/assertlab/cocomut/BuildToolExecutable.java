package org.assertlab.cocomut;

import java.nio.file.Files;
import java.nio.file.Path;

/** Resolves project build wrappers without selecting incomplete wrapper installations. */
public final class BuildToolExecutable {

    private BuildToolExecutable() {
    }

    /**
     * Returns a complete project wrapper when one is available, or the system executable otherwise.
     *
     * @param projectPath project root containing the optional wrapper
     * @param tool build tool name, currently {@code mvn} or {@code gradle}
     * @param windows whether Windows command names and executable rules apply
     * @return absolute wrapper path or the platform system command
     */
    public static String resolve(Path projectPath, String tool, boolean windows) {
        String wrapperName = switch (tool) {
            case "mvn" -> windows ? "mvnw.cmd" : "mvnw";
            case "gradle" -> windows ? "gradlew.bat" : "gradlew";
            default -> null;
        };
        if (wrapperName != null) {
            Path wrapper = projectPath.resolve(wrapperName);
            if (wrapperIsUsable(projectPath, wrapper, tool, windows)) {
                return wrapper.toAbsolutePath().toString();
            }
        }
        return windows ? tool + ".cmd" : tool;
    }

    static boolean wrapperIsUsable(Path projectPath, Path wrapper, String tool, boolean windows) {
        if (!Files.isRegularFile(wrapper) || (!windows && !Files.isExecutable(wrapper))) {
            return false;
        }
        return switch (tool) {
            case "mvn" -> Files.isRegularFile(projectPath.resolve(".mvn/wrapper/maven-wrapper.properties"));
            case "gradle" -> Files.isRegularFile(projectPath.resolve("gradle/wrapper/gradle-wrapper.properties"))
                    && Files.isRegularFile(projectPath.resolve("gradle/wrapper/gradle-wrapper.jar"));
            default -> false;
        };
    }
}
