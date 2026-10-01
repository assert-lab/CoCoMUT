package org.assertlab.cocomut;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import org.junit.Assume;
import org.junit.Test;
import static org.junit.Assert.*;

/** Launch regressions use controlled executables, without downloading Gradle. */
public class GradleLaunchTest {
    @Test
    public void oldWrapperIsUsedWithoutBuildCache() throws Exception {
        Assume.assumeFalse(System.getProperty("os.name", "").toLowerCase().contains("win"));
        Path project = Files.createTempDirectory("cocomut-old-gradle-");
        try {
            Files.writeString(project.resolve("build.gradle"), "plugins { id 'java' }\n");
            Files.createDirectories(project.resolve("gradle/wrapper"));
            Files.writeString(project.resolve("gradle/wrapper/gradle-wrapper.properties"),
                    "distributionUrl=https://services.gradle.org/distributions/gradle-3.2-bin.zip\n");
            Files.write(project.resolve("gradle/wrapper/gradle-wrapper.jar"), new byte[] {0});
            Path wrapper = project.resolve("gradlew");
            Files.writeString(wrapper, """
                    #!/bin/sh
                    for arg in "$@"; do
                      if [ "$arg" = '--build-cache' ]; then
                        echo "Unknown command-line option '--build-cache'"
                        exit 1
                      fi
                    done
                    exit 0
                    """);
            assertTrue(wrapper.toFile().setExecutable(true));
            for (java.util.Set<String> sourceSets : List.of(java.util.Set.of("main"), java.util.Set.of("main", "test"))) {
                ProjectMetadata metadata = new ProjectAnalyzer(ContextRequest.builder()
                        .projectRoot(project).sourceSets(sourceSets).allowUnsandboxedBuild().build()).analyze();
                assertTrue(metadata.isBuildSucceeded());
                BuildAttempt build = metadata.getBuildAttempts().stream()
                        .filter(a -> a.action().equals("build")).findFirst().orElseThrow();
                assertEquals(wrapper.toAbsolutePath().toString(), build.command().get(0));
                assertFalse(build.command().contains("--build-cache"));
            }
        } finally {
            remove(project);
        }
    }

    @Test
    public void missingSystemGradleRetainsClassifiedAttempt() throws Exception {
        Assume.assumeFalse(System.getProperty("os.name", "").toLowerCase().contains("win"));
        Path project = Files.createTempDirectory("cocomut-missing-gradle-");
        try {
            Files.writeString(project.resolve("build.gradle"), "plugins { id 'java' }\n");
            Path emptyPath = Files.createDirectory(project.resolve("empty-path"));
            ProcessBuilder child = new ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", System.getProperty("java.class.path"),
                    GradleLaunchTest.class.getName(), project.toString());
            child.environment().put("PATH", emptyPath.toString());
            child.redirectErrorStream(true);
            Process process = child.start();
            String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(output, 0, process.waitFor());
        } finally {
            remove(project);
        }
    }

    public static void main(String[] args) throws Exception {
        ProjectMetadata metadata = new ProjectAnalyzer(ContextRequest.builder()
                .projectRoot(Path.of(args[0])).allowUnsandboxedBuild().build()).analyze();
        assertTrue(metadata.isBuildAttempted());
        assertFalse(metadata.isBuildSucceeded());
        assertEquals(BuildFailureReason.BUILD_FAILED_REQUIRED_TOOL_UNAVAILABLE, metadata.getBuildFailureReason());
        assertTrue(metadata.getBuildOutputTail().contains("gradle"));
        assertTrue(metadata.getBuildOutputTail().contains("No usable repository Gradle wrapper"));
        BuildAttempt build = metadata.getBuildAttempts().stream()
                .filter(a -> a.action().equals("build")).findFirst().orElseThrow();
        assertEquals("gradle", build.command().get(0));
        assertEquals(-1, build.exitCode());
        assertEquals(BuildFailureReason.BUILD_FAILED_REQUIRED_TOOL_UNAVAILABLE, build.failureReason());
    }

    private static void remove(Path root) throws Exception {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
