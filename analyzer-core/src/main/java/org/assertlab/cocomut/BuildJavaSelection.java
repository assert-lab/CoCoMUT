package org.assertlab.cocomut;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Selects a project build JDK independently from the JDK running CoCoMUT. */
public record BuildJavaSelection(Path javaHome, String version, String evidence) {
    private static final List<Integer> SUPPORTED_VERSIONS = List.of(8, 11, 17, 21, 25, 26);

    public BuildJavaSelection {
        version = version == null || version.isBlank() ? "inherited" : version;
        evidence = evidence == null || evidence.isBlank() ? "inherited_environment" : evidence;
    }

    public static BuildJavaSelection select(Path projectRoot, String buildSystem, String declaredJavaVersion) {
        Map<String, String> env = System.getenv();
        String explicit = env.getOrDefault("COCOMUT_BUILD_JAVA_HOME", "").trim();
        if (!explicit.isEmpty()) {
            Path home = Path.of(explicit).toAbsolutePath().normalize();
            return new BuildJavaSelection(home, versionFromHome(home), "COCOMUT_BUILD_JAVA_HOME");
        }

        VersionEvidence detected = detectProjectVersion(projectRoot, buildSystem, declaredJavaVersion);
        Path home = resolveHome(detected.version(), env);
        if (home != null) {
            return new BuildJavaSelection(home, versionFromHome(home), detected.evidence());
        }

        String inherited = env.getOrDefault("JAVA_HOME", "").trim();
        Path inheritedHome = inherited.isEmpty() ? null : Path.of(inherited).toAbsolutePath().normalize();
        String evidence = detected.version() > 0
                ? detected.evidence() + "; requested JDK unavailable, inherited environment used"
                : detected.evidence();
        return new BuildJavaSelection(inheritedHome, versionFromHome(inheritedHome), evidence);
    }

    public void apply(ProcessBuilder processBuilder) {
        if (javaHome == null || !Files.isDirectory(javaHome.resolve("bin"))) {
            return;
        }
        Map<String, String> env = processBuilder.environment();
        env.put("JAVA_HOME", javaHome.toString());
        String currentPath = env.getOrDefault("PATH", "");
        env.put("PATH", javaHome.resolve("bin") + java.io.File.pathSeparator + currentPath);
    }

    int majorVersion() {
        try {
            return Integer.parseInt(version);
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    public static BuildJavaSelection forRequiredVersion(int version, String evidence) {
        Path home = resolveHome(version, System.getenv());
        return home == null ? null : new BuildJavaSelection(home, versionFromHome(home), evidence);
    }

    private static VersionEvidence detectProjectVersion(Path root, String buildSystem, String declaredJavaVersion) {
        String javaVersion = firstLine(root.resolve(".java-version"));
        if (!javaVersion.isBlank()) {
            return new VersionEvidence(normalize(javaVersion), ".java-version");
        }

        String sdkman = firstLine(root.resolve(".sdkmanrc"));
        java.util.regex.Matcher sdkmanJava = java.util.regex.Pattern.compile("(?:^|\\s)java=([^\\s]+)").matcher(sdkman);
        if (sdkmanJava.find()) {
            return new VersionEvidence(normalize(sdkmanJava.group(1)), ".sdkmanrc");
        }

        if ("gradle".equals(buildSystem)) {
            String wrapper = read(root.resolve("gradle/wrapper/gradle-wrapper.properties"));
            java.util.regex.Matcher gradle = java.util.regex.Pattern
                    .compile("gradle-(\\d+)(?:\\.(\\d+))?[^/]*\\.(?:zip|tar)")
                    .matcher(wrapper);
            if (gradle.find()) {
                int major = Integer.parseInt(gradle.group(1));
                int selected = major <= 4 ? 8 : major <= 6 ? 11 : 17;
                return new VersionEvidence(selected, "Gradle wrapper " + gradle.group(1)
                        + (gradle.group(2) == null ? "" : "." + gradle.group(2)));
            }
        }

        int declared = normalize(declaredJavaVersion);
        if (declared > 0) {
            return new VersionEvidence(declared, "declared project Java version");
        }
        return new VersionEvidence(-1, "no deterministic build JDK declaration");
    }

    private static Path resolveHome(int version, Map<String, String> env) {
        int availableVersion = compatibleInstalledVersion(version);
        if (!SUPPORTED_VERSIONS.contains(availableVersion)) {
            return null;
        }
        String configured = env.getOrDefault("COCOMUT_JAVA_HOME_" + availableVersion, "").trim();
        if (!configured.isEmpty()) {
            Path path = Path.of(configured).toAbsolutePath().normalize();
            if (Files.isDirectory(path.resolve("bin"))) {
                return path;
            }
        }
        for (Path candidate : List.of(
                Path.of("/usr/lib/jvm/java-" + availableVersion + "-openjdk"),
                Path.of("/usr/lib/jvm/java-" + availableVersion + "-openjdk-amd64"))) {
            if (Files.isDirectory(candidate.resolve("bin"))) {
                return candidate;
            }
        }
        return null;
    }

    static int compatibleInstalledVersion(int requestedVersion) {
        if (requestedVersion >= 6 && requestedVersion <= 8) {
            return 8;
        }
        if (requestedVersion >= 9 && requestedVersion <= 11) {
            return 11;
        }
        if (requestedVersion >= 12 && requestedVersion <= 17) {
            return 17;
        }
        if (requestedVersion >= 18 && requestedVersion <= 21) {
            return 21;
        }
        if (requestedVersion >= 22 && requestedVersion <= 25) {
            return 25;
        }
        return requestedVersion;
    }

    private static int normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return -1;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?:^|[^0-9])(1\\.)?([6-9]|1[0-9]|2[0-6])(?:[^0-9]|$)")
                .matcher(raw.trim());
        return matcher.find() ? Integer.parseInt(matcher.group(2)) : -1;
    }

    private static String versionFromHome(Path home) {
        int version = home == null ? -1 : normalize(home.toString());
        return version > 0 ? Integer.toString(version) : "inherited";
    }

    private static String firstLine(Path path) {
        String text = read(path);
        return text.lines().findFirst().orElse("").trim();
    }

    private static String read(Path path) {
        try {
            return Files.isRegularFile(path) ? Files.readString(path) : "";
        } catch (java.io.IOException ignored) {
            return "";
        }
    }

    private record VersionEvidence(int version, String evidence) {
    }
}
