package org.assertlab.cocomut;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Detects Android SDK requirements and optionally provisions explicit components. */
final class AndroidSdkSupport {
    static final String ALLOW_PROVISIONING_ENV = "COCOMUT_ALLOW_ANDROID_SDK_PROVISIONING";

    private AndroidSdkSupport() {}

    record Preparation(boolean androidProject, boolean attempted, boolean succeeded, boolean timedOut,
                       boolean changed, List<String> command, Set<String> components, int exitCode,
                       String diagnostic) {
        Preparation {
            command = command == null ? List.of() : List.copyOf(command);
            components = components == null ? Set.of() : Set.copyOf(components);
            diagnostic = diagnostic == null ? "" : diagnostic;
        }

        static Preparation notAndroid() {
            return new Preparation(false, false, true, false, false, List.of(), Set.of(), 0, "");
        }
    }

    static Preparation prepare(Path projectRoot) {
        return prepare(projectRoot, System.getenv());
    }

    static Preparation prepare(Path projectRoot, Map<String, String> env) {
        boolean androidProject = isAndroidProject(projectRoot);
        if (!androidProject) return Preparation.notAndroid();
        Set<String> components = declaredComponents(projectRoot);
        if (components.isEmpty()) {
            return new Preparation(true, false, true, false, false, List.of(), Set.of(), 0,
                    "Android plugin declared; SDK components are dynamic or not statically declared. "
                            + "Gradle will classify the build requirements.");
        }
        env = env == null ? Map.of() : Map.copyOf(env);
        String rootText = !env.getOrDefault("ANDROID_SDK_ROOT", "").isBlank()
                ? env.get("ANDROID_SDK_ROOT") : env.getOrDefault("ANDROID_HOME", "");
        if (rootText == null || rootText.isBlank()) {
            return new Preparation(true, false, false, false, false, List.of(), components, -1,
                    "Android SDK components are declared but ANDROID_SDK_ROOT/ANDROID_HOME is unset.");
        }
        Path sdkRoot = Path.of(rootText).toAbsolutePath().normalize();
        Set<String> missing = missingComponents(sdkRoot, components);
        if (missing.isEmpty()) {
            return new Preparation(true, false, true, false, false, List.of(), components, 0,
                    "Declared Android SDK components are already installed: " + components);
        }
        if (!Boolean.parseBoolean(env.getOrDefault(ALLOW_PROVISIONING_ENV, "false"))) {
            return new Preparation(true, false, false, false, false, List.of(), missing, -1,
                    "Declared Android SDK components are missing: " + missing + ". Provisioning is disabled; "
                            + "set " + ALLOW_PROVISIONING_ENV + "=true in an externally controlled environment "
                            + "to allow sdkmanager to install them.");
        }
        Path sdkManager = findSdkManager(sdkRoot);
        if (sdkManager == null) {
            return new Preparation(true, false, false, false, false, List.of(), missing, -1,
                    "Android SDK components are declared but sdkmanager is unavailable under " + sdkRoot);
        }
        List<String> command = new ArrayList<>();
        command.add(sdkManager.toString());
        command.add("--sdk_root=" + sdkRoot);
        command.addAll(missing);
        Path log = null;
        Process process = null;
        boolean completed = false;
        boolean timedOut = false;
        boolean interrupted = false;
        int exit = -1;
        String output = "";
        String failure = "";
        try {
            log = Files.createTempFile("cocomut-sdkmanager-", ".log");
            ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
            process = pb.start();
            process.getOutputStream().close();
            completed = process.waitFor(5, TimeUnit.MINUTES);
            timedOut = !completed;
            if (completed) exit = process.exitValue();
        } catch (InterruptedException e) {
            interrupted = true;
            failure = "sdkmanager interrupted";
        } catch (IOException e) {
            failure = "sdkmanager failed: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        } finally {
            terminateAndWait(process);
            if (log != null) {
                try {
                    output = Files.readString(log);
                } catch (IOException ignored) {
                    // Process status and filesystem verification remain authoritative.
                }
                try {
                    Files.deleteIfExists(log);
                } catch (IOException ignored) {
                    // Temporary diagnostic cleanup is best effort.
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
        if (output.length() > 20_000) output = output.substring(output.length() - 20_000);
        Set<String> remaining = missingComponents(sdkRoot, missing);
        boolean changed = remaining.size() < missing.size();
        boolean succeeded = remaining.isEmpty();
        String diagnostic = "sdkmanager components=" + missing + " remaining=" + remaining
                + " exit=" + exit + (failure.isBlank() ? "" : " " + failure) + "\n" + output;
        return new Preparation(true, true, succeeded, timedOut,
                changed, command, missing, exit, diagnostic);
    }

    static Set<String> declaredComponents(Path root) {
        Set<String> components = new LinkedHashSet<>();
        try (var walk = Files.walk(root, 5)) {
            for (Path file : walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().matches("build\\.gradle(?:\\.kts)?"))
                    .toList()) {
                String text = stripComments(Files.readString(file));
                if (!hasAndroidPlugin(text)) continue;
                Matcher sdk = Pattern.compile("compileSdk(?:Version)?\\s*(?:=|\\s)\\s*[\"']?(\\d+)").matcher(text);
                if (sdk.find()) components.add("platforms;android-" + sdk.group(1));
                Matcher tools = Pattern.compile("buildToolsVersion\\s*(?:=|\\s)\\s*[\"']([^\"']+)").matcher(text);
                if (tools.find()) components.add("build-tools;" + tools.group(1));
            }
        } catch (IOException ignored) {
            return Set.of();
        }
        return components;
    }

    static boolean isAndroidProject(Path root) {
        try (var walk = Files.walk(root, 5)) {
            for (Path file : walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().matches("build\\.gradle(?:\\.kts)?"))
                    .toList()) {
                if (hasAndroidPlugin(stripComments(Files.readString(file)))) return true;
            }
        } catch (IOException ignored) {
            return false;
        }
        return false;
    }

    static Set<String> missingComponents(Path sdkRoot, Set<String> components) {
        Set<String> missing = new LinkedHashSet<>();
        for (String component : components) {
            Path installed = sdkRoot;
            for (String segment : component.split(";")) installed = installed.resolve(segment);
            if (!Files.isDirectory(installed)) missing.add(component);
        }
        return missing;
    }

    private static Path findSdkManager(Path root) {
        for (Path path : List.of(root.resolve("cmdline-tools/latest/bin/sdkmanager"),
                root.resolve("cmdline-tools/bin/sdkmanager"), root.resolve("tools/bin/sdkmanager"))) {
            if (Files.isExecutable(path)) return path;
        }
        return null;
    }

    private static boolean hasAndroidPlugin(String text) {
        return Pattern.compile("(?m)\\bid\\s*(?:\\(|\\s+)['\"]com\\.android\\.(?:application|library|test|dynamic-feature)['\"]")
                .matcher(text).find()
                || Pattern.compile("(?m)\\bapply\\s*(?:\\(|\\s+)plugin\\s*(?:=|:)\\s*['\"]com\\.android\\.(?:application|library|test|dynamic-feature)['\"]")
                .matcher(text).find();
    }

    private static String stripComments(String text) {
        return text.replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("(?m)//.*$", "");
    }

    private static void terminateAndWait(Process process) {
        if (process == null || !process.isAlive()) return;
        process.descendants().forEach(ProcessHandle::destroy);
        process.destroy();
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }
}
