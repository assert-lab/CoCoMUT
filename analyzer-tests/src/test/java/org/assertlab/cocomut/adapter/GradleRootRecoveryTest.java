package org.assertlab.cocomut.adapter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Set;
import org.assertlab.cocomut.ContextRequest;
import org.assertlab.cocomut.MethodIdentifier;
import org.assertlab.cocomut.ProjectMetadata;
import org.junit.Assume;
import org.junit.Test;
import static org.junit.Assert.*;

public class GradleRootRecoveryTest {
    @Test
    public void timedOutModelRetainsSelectedDeclaredRoots() throws Exception {
        verifyTimeoutRecovery(true);
    }

    @Test
    public void aggregatorRecoveryDoesNotRestoreUnusedConventionalDirectories() throws Exception {
        verifyTimeoutRecovery(false);
    }

    private void verifyTimeoutRecovery(boolean rootSource) throws Exception {
        String home = System.getenv("COCOMUT_TEST_GRADLE_HOME");
        Assume.assumeTrue(home != null && Files.isExecutable(Path.of(home, "bin", "gradle")));
        Assume.assumeTrue(System.getenv("ANDROID_HOME") == null && System.getenv("ANDROID_SDK_ROOT") == null);
        Path root = Files.createTempDirectory("cocomut-model-timeout-");
        try {
            Files.writeString(root.resolve("settings.gradle"), "rootProject.name='timeout'\ninclude 'library','mobile'\n");
            Files.writeString(root.resolve("build.gradle"), rootSource ? "plugins { id 'java' }\n" : "");
            if (rootSource) source(root.resolve("src/main/java"), "Root");
            Path child = Files.createDirectories(root.resolve("library"));
            Files.writeString(child.resolve("build.gradle"), "plugins { id 'java' }\nsourceSets.main.java.setSrcDirs(['handwritten'])\n");
            source(child.resolve("handwritten"), "Library");
            source(child.resolve("src/main/java"), "NotSelected");
            Path mobile = Files.createDirectories(root.resolve("mobile"));
            Files.writeString(mobile.resolve("build.gradle"), "plugins { id 'com.android.library' }\nandroid { compileSdk 35 }\n");
            source(mobile.resolve("src/main/java"), "Mobile");
            Files.createDirectories(root.resolve("gradle/wrapper"));
            Files.writeString(root.resolve("gradle/wrapper/gradle-wrapper.properties"),
                    "distributionUrl=https://services.gradle.org/distributions/gradle-8.10-bin.zip\n");
            Files.write(root.resolve("gradle/wrapper/gradle-wrapper.jar"), new byte[] {0});
            Path wrapper = root.resolve("gradlew");
            Files.writeString(wrapper, "#!/bin/sh\ncase \"$*\" in *analyzerPrintClasspath*) /bin/sleep 30; exit 0;; esac\nexec '"
                    + home.replace("'", "'\\''") + "/bin/gradle' --offline --max-workers=2 '-Dorg.gradle.jvmargs=-Xmx512m' \"$@\"\n");
            assertTrue(wrapper.toFile().setExecutable(true));
            ProjectMetadata metadata = new GradleProjectAdapter(root, 1).toMetadata(ContextRequest.builder()
                    .projectRoot(root).sourceSet("main").scope(ContextRequest.Scope.ALL).allowUnsandboxedBuild().build());
            assertTrue(metadata.getBuildOutputTail(), metadata.isBuildSucceeded());
            assertTrue(metadata.isAnalysisCanProceed());
            assertTrue(metadata.getGradleModelReport().timedOut());
            assertFalse(metadata.getGradleModelReport().succeeded());
            assertTrue(metadata.getGradleModelReport().partial());
            Set<Path> expected = rootSource ? Set.of(root.resolve("src/main/java"), child.resolve("handwritten"))
                    : Set.of(child.resolve("handwritten"));
            assertEquals(expected, Set.copyOf(metadata.getSourceRoots()));
            assertTrue(metadata.getModuleSourceSets().stream().anyMatch(ss -> ss.projectPath().equals(":library")));
            var methods = new MethodIdentifier(metadata).identify();
            if (rootSource) assertTrue(methods.stream().anyMatch(m -> m.getTypeName().equals("demo.Root") && m.getMethodName().equals("value")));
            assertTrue(methods.stream().anyMatch(m -> m.getTypeName().equals("demo.Library") && m.getMethodName().equals("value")));
            assertFalse(methods.stream().anyMatch(m -> m.getTypeName().contains("NotSelected") || m.getTypeName().contains("Mobile")));
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static void source(Path root, String name) throws Exception {
        Path dir = Files.createDirectories(root.resolve("demo"));
        Files.writeString(dir.resolve(name + ".java"), "package demo; public class " + name + " { public int value(){return 1;} }\n");
    }
}
