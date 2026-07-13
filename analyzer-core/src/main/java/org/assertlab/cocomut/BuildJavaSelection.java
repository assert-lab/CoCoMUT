package org.assertlab.cocomut;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Selects a project build JDK independently from the JDK running CoCoMUT. */
public record BuildJavaSelection(Path javaHome, String version, String evidence) {
    private static final List<Integer> SUPPORTED_VERSIONS = java.util.stream.IntStream.rangeClosed(8, 26)
            .boxed().toList();

    public BuildJavaSelection {
        version = version == null || version.isBlank() ? "inherited" : version;
        evidence = evidence == null || evidence.isBlank() ? "inherited_environment" : evidence;
    }

    public static BuildJavaSelection select(Path projectRoot, String buildSystem, String declaredJavaVersion) {
        return select(projectRoot, projectRoot, buildSystem, declaredJavaVersion, System.getenv());
    }

    /**
     * Select a build JDK using the supplied environment.
     *
     * <p>The overload keeps selection deterministic in tests and lets callers
     * model CI environments without relying on the host process environment.
     */
    static BuildJavaSelection select(Path projectRoot, String buildSystem, String declaredJavaVersion,
                                     Map<String, String> env) {
        return select(projectRoot, projectRoot, buildSystem, declaredJavaVersion, env);
    }

    static BuildJavaSelection select(Path effectiveBuildRoot, Path repositoryRoot, String buildSystem,
                                     String declaredJavaVersion, Map<String, String> env) {
        env = env == null ? Map.of() : Map.copyOf(env);
        String explicit = env.getOrDefault("COCOMUT_BUILD_JAVA_HOME", "").trim();
        if (!explicit.isEmpty()) {
            Path home = Path.of(explicit).toAbsolutePath().normalize();
            return new BuildJavaSelection(home, versionFromHome(home), "COCOMUT_BUILD_JAVA_HOME");
        }

        VersionEvidence detected = detectProjectVersion(
                effectiveBuildRoot, repositoryRoot, buildSystem, declaredJavaVersion);
        Path home = resolveHome(detected.version(), env);
        if (home != null) {
            return new BuildJavaSelection(home, versionFromHome(home), detected.evidence());
        }

        if (detected.version() <= 0) {
            Path defaultHome = resolveHome(17, env);
            if (defaultHome != null) {
                return new BuildJavaSelection(defaultHome, versionFromHome(defaultHome),
                        detected.evidence() + "; default build JDK 17 used");
            }
        }

        Path inheritedHome = inheritedJavaHome(env);
        String evidence = detected.version() > 0
                ? detected.evidence() + "; requested JDK unavailable, runtime environment used"
                : detected.evidence() + "; CoCoMUT runtime environment used";
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

    static Map<Integer, Path> installedJdkHomes() {
        return installedJdkHomes(System.getenv());
    }

    static Map<Integer, Path> installedJdkHomes(Map<String, String> env) {
        Map<Integer, Path> homes = new java.util.LinkedHashMap<>();
        env = env == null ? Map.of() : Map.copyOf(env);
        Set<Path> candidates = new LinkedHashSet<>();
        candidates.add(inheritedJavaHome(env));
        addConfiguredHome(candidates, env.get("COCOMUT_BUILD_JAVA_HOME"));
        for (int version : SUPPORTED_VERSIONS) {
            addConfiguredHome(candidates, env.get("COCOMUT_JAVA_HOME_" + version));
            candidates.add(Path.of("/usr/lib/jvm/java-" + version + "-openjdk"));
            candidates.add(Path.of("/usr/lib/jvm/java-" + version + "-openjdk-amd64"));
        }
        for (Path candidate : candidates) {
            int actualVersion = javaHomeMajorVersion(candidate);
            if (SUPPORTED_VERSIONS.contains(actualVersion)) {
                homes.putIfAbsent(actualVersion, candidate.toAbsolutePath().normalize());
            }
        }
        return homes;
    }

    private static void addConfiguredHome(Set<Path> candidates, String raw) {
        if (raw != null && !raw.isBlank()) {
            candidates.add(Path.of(raw.trim()).toAbsolutePath().normalize());
        }
    }

    private static VersionEvidence detectProjectVersion(Path root, Path repositoryRoot,
                                                        String buildSystem, String declaredJavaVersion) {
        VersionEvidence local = detectRepositoryVersion(root, "");
        if (local.version() > 0) return local;
        if (repositoryRoot != null && !repositoryRoot.toAbsolutePath().normalize()
                .equals(root.toAbsolutePath().normalize())) {
            VersionEvidence repository = detectRepositoryVersion(repositoryRoot, "repository-root ");
            if (repository.version() > 0) return repository;
        }

        if ("gradle".equals(buildSystem)) {
            boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
            String executable = BuildToolExecutable.resolve(root, "gradle", windows);
            String systemCommand = windows ? "gradle.cmd" : "gradle";
            String wrapper = executable.equals(systemCommand)
                    ? gradleVersionOutput(executable)
                    : read(root.resolve("gradle/wrapper/gradle-wrapper.properties"));
            java.util.regex.Matcher gradle = java.util.regex.Pattern
                    .compile("(?:gradle-|gradle\\s+)(\\d+)(?:\\.(\\d+))?", java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(wrapper);
            if (gradle.find()) {
                int major = Integer.parseInt(gradle.group(1));
                int minor = gradle.group(2) == null ? 0 : Integer.parseInt(gradle.group(2));
                int selected = gradleRuntimeVersion(major, minor);
                String source = executable.equals(systemCommand) ? "system Gradle " : "Gradle wrapper ";
                return new VersionEvidence(selected, source + gradle.group(1)
                        + (gradle.group(2) == null ? "" : "." + gradle.group(2)));
            }
        }

        int declared = normalize(declaredJavaVersion);
        if (declared > 0) {
            return new VersionEvidence(declared, "declared project Java version");
        }
        return new VersionEvidence(-1, "no deterministic build JDK declaration");
    }

    private static VersionEvidence detectRepositoryVersion(Path root, String evidencePrefix) {
        String javaVersion = firstLine(root.resolve(".java-version"));
        if (!javaVersion.isBlank()) {
            return new VersionEvidence(normalize(javaVersion), evidencePrefix + ".java-version");
        }

        String sdkman = read(root.resolve(".sdkmanrc"));
        java.util.regex.Matcher sdkmanJava = java.util.regex.Pattern
                .compile("(?m)^\\s*java\\s*=\\s*([^#\\s]+)\\s*(?:#.*)?$")
                .matcher(sdkman);
        if (sdkmanJava.find()) {
            return new VersionEvidence(normalize(sdkmanJava.group(1)), evidencePrefix + ".sdkmanrc");
        }
        return new VersionEvidence(-1, "no deterministic build JDK declaration");
    }

    private static String gradleVersionOutput(String executable) {
        try {
            Process process = new ProcessBuilder(executable, "--version")
                    .redirectErrorStream(true)
                    .start();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return "";
            }
            return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return "";
        }
    }

    private static Path resolveHome(int version, Map<String, String> env) {
        int compatibleVersion = compatibleInstalledVersion(version);
        List<Integer> candidates = version >= 8 && version <= 26 && version != compatibleVersion
                ? List.of(version, compatibleVersion) : List.of(compatibleVersion);
        for (int availableVersion : candidates) {
            if (!SUPPORTED_VERSIONS.contains(availableVersion)) continue;
            Path inherited = inheritedJavaHome(env);
            if (isJavaHomeForVersion(inherited, availableVersion)) {
                return inherited;
            }
            String configured = env.getOrDefault("COCOMUT_JAVA_HOME_" + availableVersion, "").trim();
            if (!configured.isEmpty()) {
                Path path = Path.of(configured).toAbsolutePath().normalize();
                if (isJavaHomeForVersion(path, availableVersion)) return path;
            }
            for (Path candidate : List.of(
                    Path.of("/usr/lib/jvm/java-" + availableVersion + "-openjdk"),
                    Path.of("/usr/lib/jvm/java-" + availableVersion + "-openjdk-amd64"))) {
                if (isJavaHomeForVersion(candidate, availableVersion)) return candidate;
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

    static int gradleRuntimeVersion(int major, int minor) {
        if (major <= 4) return 8;
        if (major <= 6 || (major == 7 && minor < 3)) return 11;
        return 17;
    }

    static Path inheritedJavaHome(Map<String, String> env) {
        String configured = env.getOrDefault("JAVA_HOME", "").trim();
        return configured.isEmpty()
                ? Path.of(System.getProperty("java.home")).toAbsolutePath().normalize()
                : Path.of(configured).toAbsolutePath().normalize();
    }

    private static int normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return -1;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?:^|[^0-9])(1\\.)?([6-9]|1[0-9]|2[0-6])(?:[^0-9]|$)")
                .matcher(raw.trim());
        return matcher.find() ? Integer.parseInt(matcher.group(2)) : -1;
    }

    private static boolean isJavaHomeForVersion(Path home, int expectedVersion) {
        return home != null && Files.isDirectory(home.resolve("bin"))
                && javaHomeMajorVersion(home) == expectedVersion;
    }

    private static String versionFromHome(Path home) {
        int version = javaHomeMajorVersion(home);
        return version > 0 ? Integer.toString(version) : "inherited";
    }

    static int javaHomeMajorVersion(Path home) {
        if (home == null || !Files.isDirectory(home.resolve("bin"))) {
            return -1;
        }
        Path normalized = home.toAbsolutePath().normalize();
        Path runtimeHome = Path.of(System.getProperty("java.home")).toAbsolutePath().normalize();
        if (normalized.equals(runtimeHome)) {
            return Runtime.version().feature();
        }
        String release = read(normalized.resolve("release"));
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?m)^JAVA_VERSION=\\\"?([^\\\"\\r\\n]+)")
                .matcher(release);
        if (matcher.find()) {
            int version = normalize(matcher.group(1));
            if (version > 0) return version;
        }
        return normalize(normalized.toString());
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
