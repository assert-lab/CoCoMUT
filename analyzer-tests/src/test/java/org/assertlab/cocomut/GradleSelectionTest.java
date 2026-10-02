package org.assertlab.cocomut;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import org.assertlab.cocomut.adapter.GradleProjectAdapter;
import org.junit.Assume;
import org.junit.Test;
import static org.junit.Assert.*;

public class GradleSelectionTest {
    @Test
    public void unappliedAndroidPluginDoesNotRequireSdk() throws Exception {
        Path root = Files.createTempDirectory("cocomut-unapplied-android-");
        try {
            for (String declaration : java.util.List.of(
                    "id 'com.android.library' version '8.2.0' apply false",
                    "id(\"com.android.library\") version \"8.2.0\" apply false")) {
                Files.writeString(root.resolve("build.gradle"), "plugins { " + declaration + " }\n");
                assertFalse(AndroidSdkSupport.isAndroidProject(root));
                assertTrue(AndroidSdkSupport.prepare(root, Map.of()).succeeded());
            }
        } finally { remove(root); }
    }

    @Test
    public void androidRequirementsBelongToTheirOwnModule() throws Exception {
        Path root = Files.createTempDirectory("cocomut-module-android-");
        try {
            Files.writeString(root.resolve("build.gradle"), "plugins { id 'java' }\n");
            Path example = Files.createDirectories(root.resolve("mobile"));
            Files.writeString(example.resolve("build.gradle"), "plugins { id 'com.android.library' }\nandroid { compileSdk 35 }\n");
            assertFalse(AndroidSdkSupport.isAndroidProject(root));
            assertTrue(AndroidSdkSupport.declaredComponents(root).isEmpty());
            assertTrue(AndroidSdkSupport.prepare(root, Map.of()).succeeded());
            assertTrue(AndroidSdkSupport.isAndroidProject(example));
            assertFalse(AndroidSdkSupport.prepare(example, Map.of()).succeeded());
        } finally { remove(root); }
    }

    @Test
    public void realGradle32StillCompilesWithSelectedTasks() throws Exception {
        String oldHome = System.getenv("COCOMUT_TEST_OLD_GRADLE_HOME");
        String java8 = System.getenv("COCOMUT_TEST_JAVA8_HOME");
        Assume.assumeTrue("Optional cached Gradle 3.2 / JDK 8 regression",
                oldHome != null && java8 != null && Files.isExecutable(Path.of(oldHome, "bin", "gradle")));
        Path root = Files.createTempDirectory("cocomut-real-gradle32-");
        try {
            Files.writeString(root.resolve("settings.gradle"), "rootProject.name='old-gradle'\n");
            Files.writeString(root.resolve("build.gradle"), "apply plugin: 'java'\n");
            Files.createDirectories(root.resolve("gradle/wrapper"));
            Files.writeString(root.resolve("gradle/wrapper/gradle-wrapper.properties"),
                    "distributionUrl=https://services.gradle.org/distributions/gradle-3.2-bin.zip\n");
            Files.write(root.resolve("gradle/wrapper/gradle-wrapper.jar"), new byte[] {0});
            Path wrapper = root.resolve("gradlew");
            Files.writeString(wrapper, "#!/bin/sh\nexport JAVA_HOME='" + java8.replace("'", "'\\''")
                    + "'\nexec '" + oldHome.replace("'", "'\\''")
                    + "/bin/gradle' --offline --max-workers=2 '-Dorg.gradle.jvmargs=-Xmx512m' \"$@\"\n");
            assertTrue(wrapper.toFile().setExecutable(true));
            source(root, "OldExample");
            ProjectMetadata metadata = analyze(root);
            assertTrue(metadata.getBuildOutputTail(), metadata.isBuildSucceeded());
            assertTrue(metadata.isAnalysisCanProceed());
            assertTrue(Files.isRegularFile(root.resolve("build/classes/main/demo/OldExample.class")));
            assertTrue(metadata.getGradleModelReport().buildPlan().compilationTasks().contains(":classes"));
            assertTrue(metadata.getGradleModelReport().succeeded());
        } finally { remove(root); }
    }

    @Test
    public void availableCompilationTaskWorksWithoutTestClasses() throws Exception {
        Path root = fixture();
        try {
            Files.writeString(root.resolve("settings.gradle"), "rootProject.name = 'custom-compile'\n");
            Files.writeString(root.resolve("build.gradle"), """
                    import org.gradle.api.tasks.compile.JavaCompile
                    tasks.create('compileJava', JavaCompile) {
                        sourceCompatibility = '17'
                        targetCompatibility = '17'
                        source = fileTree('src/main/java')
                        classpath = files()
                        destinationDirectory.set(file('build/classes/java/main'))
                    }
                    """);
            source(root, "Example");
            ProjectMetadata metadata = analyze(root);
            assertTrue(metadata.getBuildOutputTail(), metadata.isBuildSucceeded());
            assertTrue(metadata.isAnalysisCanProceed());
            assertTrue(Files.isRegularFile(root.resolve("build/classes/java/main/demo/Example.class")));
            assertEquals(java.util.List.of(":compileJava"), metadata.getGradleModelReport().buildPlan().compilationTasks());
            assertTrue(metadata.getGradleModelReport().buildPlan().unavailableSourceSets().contains("::test"));
            assertTrue(metadata.getGradleModelReport().partial());
            assertTrue(metadata.getSourceRoots().contains(root.resolve("src/main/java")));
        } finally { remove(root); }
    }

    @Test
    public void mixedBuildSkipsOnlyUnavailableAndroidProject() throws Exception {
        Assume.assumeTrue("This regression requires an unset Android SDK",
                System.getenv("ANDROID_HOME") == null && System.getenv("ANDROID_SDK_ROOT") == null);
        Path root = fixture();
        try {
            Files.writeString(root.resolve("settings.gradle"), "rootProject.name='mixed'\ninclude 'jvm', 'mobile'\n");
            Files.writeString(root.resolve("build.gradle"), "");
            Path jvm = Files.createDirectories(root.resolve("jvm"));
            Files.writeString(jvm.resolve("build.gradle"), "plugins { id 'java' }\ntest { doFirst { throw new GradleException('Tests must not execute') } }\n");
            source(jvm, "JvmExample");
            Path mobile = Files.createDirectories(root.resolve("mobile"));
            Files.writeString(mobile.resolve("build.gradle"), """
                    plugins { id 'com.android.library' }
                    android { compileSdk 35 }
                    throw new GradleException('Excluded Android build file must not execute')
                    """);
            source(mobile, "AndroidExample");
            Path stale = Files.createDirectories(mobile.resolve("build/classes/java/main/demo"));
            Files.write(stale.resolve("AndroidExample.class"), new byte[] {1});
            ProjectMetadata metadata = analyze(root);
            assertTrue(metadata.getBuildOutputTail(), metadata.isBuildSucceeded());
            assertTrue(metadata.isAnalysisCanProceed());
            GradleBuildPlan plan = metadata.getGradleModelReport().buildPlan();
            assertEquals(Set.of(":mobile"), plan.skippedProjects().keySet());
            assertTrue(plan.compilationTasks().contains(":jvm:classes"));
            assertFalse(plan.compilationTasks().stream().anyMatch(task -> task.startsWith(":mobile:")));
            assertTrue(metadata.getSourceRoots().contains(jvm.resolve("src/main/java")));
            assertTrue(metadata.getSourceRoots().stream().noneMatch(path -> path.startsWith(mobile)));
            assertTrue(metadata.getMainClassOutputs().stream().noneMatch(path -> path.startsWith(mobile)));
            assertTrue(metadata.getGradleModelReport().partial());
            assertTrue(metadata.getModuleSourceSets().stream().anyMatch(ss -> ss.projectPath().equals(":jvm")));
            Orchestrator pipeline = new Orchestrator(ContextRequest.builder().projectRoot(root)
                    .scope(ContextRequest.Scope.ALL).sourceSet("main")
                    .outputDirectory(root.resolve("analysis-output")).build(), metadata);
            pipeline.execute();
            assertEquals("PARTIAL", pipeline.getExecutionReport().get("status"));
            assertTrue(pipeline.getExecutionReport().get("failure_codes").toString().contains("MODEL_RESOLUTION_PARTIAL"));
            Path jsonl = Path.of(pipeline.getExecutionReport().get("phase_5_jsonl_file").toString());
            String rows = Files.readString(jsonl);
            assertTrue(rows.contains("JvmExample"));
            assertFalse(rows.contains("AndroidExample"));
            Path manifest = root.resolve("analysis-output/extraction_manifest.json");
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var manifestJson = mapper.readTree(manifest.toFile());
            assertTrue(manifestJson.path("build").path("gradle_model").path("buildPlan")
                    .path("skippedProjects").has(":mobile"));
            Path schema = Path.of(System.getProperty("user.dir")).getParent().resolve("schemas/extraction-manifest.schema.json");
            var validator = com.networknt.schema.JsonSchemaFactory
                    .getInstance(com.networknt.schema.SpecVersion.VersionFlag.V202012).getSchema(mapper.readTree(schema.toFile()));
            assertTrue(validator.validate(manifestJson).toString(), validator.validate(manifestJson).isEmpty());
        } finally { remove(root); }
    }

    @Test
    public void allAndroidChildProjectsKeepSdkFailureReason() throws Exception {
        Assume.assumeTrue(System.getenv("ANDROID_HOME") == null && System.getenv("ANDROID_SDK_ROOT") == null);
        Path root = fixture();
        try {
            Files.writeString(root.resolve("settings.gradle"), "rootProject.name='android-only'\ninclude 'mobile'\n");
            Files.writeString(root.resolve("build.gradle"), "");
            Path mobile = Files.createDirectories(root.resolve("mobile"));
            Files.writeString(mobile.resolve("build.gradle"), "plugins { id 'com.android.library' }\nandroid { compileSdk 35 }\n");
            ProjectMetadata metadata = analyze(root);
            assertFalse(metadata.isBuildSucceeded());
            assertEquals(BuildFailureReason.BUILD_FAILED_ANDROID_SDK_UNAVAILABLE, metadata.getBuildFailureReason());
            assertTrue(metadata.getGradleModelReport().buildPlan().skippedProjects().containsKey(":mobile"));
        } finally { remove(root); }
    }

    @Test
    public void rootAndroidRemainsBlockedWithoutSdk() throws Exception {
        Assume.assumeTrue(System.getenv("ANDROID_HOME") == null && System.getenv("ANDROID_SDK_ROOT") == null);
        Path root = Files.createTempDirectory("cocomut-root-android-");
        try {
            Files.writeString(root.resolve("build.gradle"), "plugins { id 'com.android.library' }\nandroid { compileSdk 35 }\n");
            ProjectMetadata metadata = new ProjectAnalyzer(ContextRequest.builder().projectRoot(root)
                    .allowUnsandboxedBuild().build()).analyze();
            assertTrue(metadata.isBuildBlocked());
            assertEquals(BuildFailureReason.BUILD_FAILED_ANDROID_SDK_UNAVAILABLE, metadata.getBuildFailureReason());
        } finally { remove(root); }
    }

    @Test
    public void lifecycleAggregatorDoesNotRequireTestSources() throws Exception {
        Path root = fixture();
        try {
            Files.writeString(root.resolve("settings.gradle"), "include 'child'\n");
            Files.writeString(root.resolve("build.gradle"), "apply plugin: 'base'\n");
            Path child = Files.createDirectories(root.resolve("child"));
            Files.writeString(child.resolve("build.gradle"), "apply plugin: 'java'\n");
            source(child, "Example");
            Path tests = Files.createDirectories(child.resolve("src/test/java/demo"));
            Files.writeString(tests.resolve("TestExample.java"), "package demo; public class TestExample { public int value(){return 2;} }\n");
            ProjectMetadata metadata = analyze(root);
            assertTrue(metadata.getBuildOutputTail(), metadata.isBuildSucceeded());
            assertTrue(metadata.getGradleModelReport().buildPlan().unavailableSourceSets().isEmpty());
            assertFalse(metadata.getGradleModelReport().partial());
            assertTrue(Files.exists(child.resolve("build/classes/java/test/demo/TestExample.class")));
            Orchestrator pipeline = new Orchestrator(ContextRequest.builder().projectRoot(root)
                    .sourceSets(Set.of("main", "test")).scope(ContextRequest.Scope.ALL)
                    .outputDirectory(root.resolve("analysis-output")).build(), metadata);
            pipeline.execute();
            assertEquals("SUCCESS", pipeline.getExecutionReport().get("status"));
            String rows = Files.readString(Path.of(pipeline.getExecutionReport().get("phase_5_jsonl_file").toString()));
            assertTrue(rows.contains("demo.Example"));
            assertTrue(rows.contains("demo.TestExample"));
        } finally { remove(root); }
    }

    private static ProjectMetadata analyze(Path root) throws Exception {
        return new GradleProjectAdapter(root).toMetadata(ContextRequest.builder().projectRoot(root)
                .sourceSets(Set.of("main", "test")).allowUnsandboxedBuild().build());
    }

    private static Path fixture() throws Exception {
        String gradleHome = System.getenv("COCOMUT_TEST_GRADLE_HOME");
        Assume.assumeTrue("Set COCOMUT_TEST_GRADLE_HOME to an existing Gradle 8 installation",
                gradleHome != null && Files.isExecutable(Path.of(gradleHome, "bin", "gradle")));
        Path root = Files.createTempDirectory("cocomut-gradle-selection-");
        Files.createDirectories(root.resolve("gradle/wrapper"));
        Files.writeString(root.resolve("gradle/wrapper/gradle-wrapper.properties"),
                "distributionUrl=https://services.gradle.org/distributions/gradle-8.10-bin.zip\n");
        Files.write(root.resolve("gradle/wrapper/gradle-wrapper.jar"), new byte[] {0});
        Path wrapper = root.resolve("gradlew");
        Files.writeString(wrapper, "#!/bin/sh\nexec '" + gradleHome.replace("'", "'\\''")
                + "/bin/gradle' --offline --max-workers=2 '-Dorg.gradle.jvmargs=-Xmx512m' \"$@\"\n");
        assertTrue(wrapper.toFile().setExecutable(true));
        return root;
    }

    private static void source(Path module, String type) throws Exception {
        Path dir = Files.createDirectories(module.resolve("src/main/java/demo"));
        Files.writeString(dir.resolve(type + ".java"), "package demo; public class " + type + " { public int value() { return 1; } }\n");
    }

    private static void remove(Path root) throws Exception {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
