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

/** Detects and installs only explicitly declared Android SDK components. */
final class AndroidSdkSupport {
    private AndroidSdkSupport() {}

    record Preparation(boolean androidProject, boolean attempted, boolean succeeded, String diagnostic) {
        static Preparation notAndroid() { return new Preparation(false, false, true, ""); }
    }

    static Preparation prepare(Path projectRoot) {
        Set<String> components = declaredComponents(projectRoot);
        if (components.isEmpty()) return Preparation.notAndroid();
        Map<String, String> env = System.getenv();
        String rootText = !env.getOrDefault("ANDROID_SDK_ROOT", "").isBlank()
                ? env.get("ANDROID_SDK_ROOT") : env.getOrDefault("ANDROID_HOME", "");
        if (rootText == null || rootText.isBlank()) {
            return new Preparation(true, false, false,
                    "Android SDK components are declared but ANDROID_SDK_ROOT/ANDROID_HOME is unset.");
        }
        Path sdkRoot = Path.of(rootText).toAbsolutePath().normalize();
        Path sdkManager = findSdkManager(sdkRoot);
        if (sdkManager == null) {
            return new Preparation(true, false, false,
                    "Android SDK components are declared but sdkmanager is unavailable under " + sdkRoot);
        }
        try {
            List<String> command = new ArrayList<>();
            command.add(sdkManager.toString());
            command.add("--sdk_root=" + sdkRoot);
            command.addAll(components);
            Path log = Files.createTempFile("cocomut-sdkmanager-", ".log");
            ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
            Process process = pb.start();
            boolean completed = process.waitFor(5, TimeUnit.MINUTES);
            if (!completed) process.destroyForcibly();
            int exit = completed ? process.exitValue() : -1;
            String output = Files.readString(log);
            Files.deleteIfExists(log);
            if (output.length() > 20_000) output = output.substring(output.length() - 20_000);
            return new Preparation(true, true, completed && exit == 0,
                    "sdkmanager components=" + components + " exit=" + exit + "\n"
                            + output);
        } catch (Exception e) {
            return new Preparation(true, true, false,
                    "sdkmanager failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    static Set<String> declaredComponents(Path root) {
        Set<String> components = new LinkedHashSet<>();
        try (var walk = Files.walk(root, 5)) {
            for (Path file : walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().matches("build\\.gradle(?:\\.kts)?"))
                    .toList()) {
                String text = Files.readString(file);
                if (!text.contains("com.android.") && !text.contains("android {")) continue;
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

    private static Path findSdkManager(Path root) {
        for (Path path : List.of(root.resolve("cmdline-tools/latest/bin/sdkmanager"),
                root.resolve("cmdline-tools/bin/sdkmanager"), root.resolve("tools/bin/sdkmanager"))) {
            if (Files.isExecutable(path)) return path;
        }
        return null;
    }
}
