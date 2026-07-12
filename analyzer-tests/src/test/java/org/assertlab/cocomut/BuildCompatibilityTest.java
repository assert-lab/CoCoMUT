package org.assertlab.cocomut;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public class BuildCompatibilityTest {
    @Test
    public void classifiesSecondLevelBuildReasons() {
        assertEquals(BuildFailureReason.BUILD_FAILED_JDK_UNAVAILABLE,
                BuildFailureReason.classify("error: invalid target release: 25", false, false));
        assertEquals(BuildFailureReason.BUILD_FAILED_JDK_UNAVAILABLE,
                BuildFailureReason.classify("error: invalid source release: 21", false, false));
        assertEquals(BuildFailureReason.BUILD_FAILED_JDK_UNAVAILABLE,
                BuildFailureReason.classify(
                        "Dependency requires at least JVM runtime version 21. This build uses Java 17.",
                        false, false));
        assertEquals(BuildFailureReason.BUILD_FAILED_JDK_UNAVAILABLE,
                BuildFailureReason.classify("JDK 21+ is required to build this project.", false, false));
        assertEquals(BuildFailureReason.BUILD_FAILED_JDK_UNAVAILABLE,
                BuildFailureReason.classify(
                        "AuditListener has been compiled by a more recent version of the Java Runtime "
                                + "(class file version 55.0)", false, false));
        assertEquals(BuildFailureReason.BUILD_FAILED_JDK_UNAVAILABLE,
                BuildFailureReason.classify("Unrecognized option: --add-opens=java.base/java.lang", false, false));
        assertEquals(BuildFailureReason.BUILD_FAILED_JDK_UNAVAILABLE,
                BuildFailureReason.classify("Java 1.8 is required for amd64. Detected version 17", false, false));
        assertEquals(BuildFailureReason.BUILD_FAILED_JDK_UNAVAILABLE,
                BuildFailureReason.classify("NullAway only builds on JDK 21 or higher now", false, false));
        assertEquals(BuildFailureReason.BUILD_FAILED_ANDROID_SDK_UNAVAILABLE,
                BuildFailureReason.classify("SDK location not found", false, false));
        assertEquals(BuildFailureReason.BUILD_FAILED_AUTHENTICATION_REQUIRED,
                BuildFailureReason.classify("401 Unauthorized", false, false));
        assertEquals(BuildFailureReason.BUILD_FAILED_NETWORK_FAILURE,
                BuildFailureReason.classify("status code 503", false, false));
        assertEquals(BuildFailureReason.BUILD_FAILED_TIMEOUT,
                BuildFailureReason.classify("", true, false));
        assertEquals(BuildFailureReason.BUILD_FAILED_BUILD_TASK_UNAVAILABLE,
                BuildFailureReason.classify("Task 'classes' not found in root project", false, false));
        assertEquals(BuildFailureReason.BUILD_FAILED_REQUIRED_TOOL_UNAVAILABLE,
                BuildFailureReason.classify("Cannot run program npm: error=2, No such file or directory", false, false));
        assertEquals(BuildFailureReason.BUILD_FAILED_REQUIRED_TOOL_UNAVAILABLE,
                BuildFailureReason.classify("mvnw: line 278: shasum: command not found", false, false));
        assertEquals(BuildFailureReason.BUILD_FAILED_VCS_HISTORY_UNAVAILABLE,
                BuildFailureReason.classify(
                        "Unable to find commits until some tag: Walk failure. Missing commit abc123",
                        false, false));
        assertEquals(BuildFailureReason.BUILD_FAILED_AUTHENTICATION_REQUIRED,
                BuildFailureReason.classify("Host key verification failed", false, false));
        assertEquals(BuildFailureReason.BUILD_FAILED_AUTHENTICATION_REQUIRED,
                BuildFailureReason.classify(
                        "Could not get unknown property 'ossrhUsername' for Credentials [username: null]",
                        false, false));
        assertEquals(BuildFailureReason.BUILD_FAILED_UNKNOWN_ERROR,
                BuildFailureReason.classify("Could not get unknown property 'releaseMode' for root project", false, false));
        assertEquals(BuildFailureReason.BUILD_FAILED_DEPENDENCY_UNAVAILABLE,
                BuildFailureReason.classify("Blocked mirror for repositories: maven-default-http-blocker", false, false));
        assertEquals(BuildFailureReason.BUILD_FAILED_DEPENDENCY_UNAVAILABLE,
                BuildFailureReason.classify("Could not resolve all dependencies for configuration compileClasspath", false, false));
        assertEquals(BuildFailureReason.BUILD_FAILED_REACTOR_ARTIFACT_MISSING,
                BuildFailureReason.classify("No plugin descriptor found at META-INF/maven/plugin.xml", false, false));
        assertEquals(BuildFailureReason.BUILD_FAILED_PLUGIN_INCOMPATIBLE,
                BuildFailureReason.classify("Failed to create enforcer rules with name: customRule", false, false));
        assertEquals(BuildFailureReason.BUILD_FAILED_PLUGIN_INCOMPATIBLE,
                BuildFailureReason.classify(
                        "org.gradle.api.provider.Provider.forUseAtConfigurationTime()", false, false));
        assertEquals(BuildFailureReason.BUILD_FAILED_UNKNOWN_ERROR,
                BuildFailureReason.classify("unrecognized failure", false, false));
    }

    @Test
    public void androidProvisioningUsesOnlyDeclaredComponents() throws Exception {
        Path project = Files.createTempDirectory("cocomut-android-components");
        try {
            Files.writeString(project.resolve("build.gradle.kts"), """
                    plugins { id("com.android.library") }
                    android {
                      compileSdk = 35
                      buildToolsVersion = "35.0.0"
                    }
                    """);
            assertEquals(Set.of("platforms;android-35", "build-tools;35.0.0"),
                    AndroidSdkSupport.declaredComponents(project));
        } finally {
            delete(project);
        }
    }

    @Test
    public void androidProvisioningSkipsInstalledComponents() throws Exception {
        Path sdk = Files.createTempDirectory("cocomut-android-sdk");
        try {
            Files.createDirectories(sdk.resolve("platforms/android-35"));
            Set<String> declared = Set.of("platforms;android-35", "build-tools;35.0.0");
            assertEquals(Set.of("build-tools;35.0.0"),
                    AndroidSdkSupport.missingComponents(sdk, declared));
        } finally {
            delete(sdk);
        }
    }

    @Test
    public void selectsOnlyAnUnambiguousNestedBuildRoot() throws Exception {
        Path project = Files.createTempDirectory("cocomut-nested-root");
        try {
            Path service = project.resolve("service");
            Files.createDirectories(service.resolve("src/main/java/demo"));
            Files.writeString(service.resolve("pom.xml"), """
                    <project><modelVersion>4.0.0</modelVersion><groupId>demo</groupId>
                    <artifactId>service</artifactId><version>1</version></project>
                    """);
            Files.writeString(service.resolve("src/main/java/demo/App.java"),
                    "package demo; public class App {}");

            ProjectMetadata metadata = new ProjectAnalyzer(project).analyze();
            assertEquals("maven", metadata.getBuildSystem());
            assertEquals(service, metadata.getBuildRoot());
            assertEquals(List.of(service), metadata.getBuildRootCandidates());

            Files.createDirectories(project.resolve("other"));
            Files.writeString(project.resolve("other/pom.xml"), """
                    <project><modelVersion>4.0.0</modelVersion><groupId>demo</groupId>
                    <artifactId>other</artifactId><version>1</version></project>
                    """);
            ProjectMetadata ambiguous = new ProjectAnalyzer(project).analyze();
            assertEquals("none", ambiguous.getBuildSystem());
            assertEquals(project, ambiguous.getBuildRoot());
            assertEquals(2, ambiguous.getBuildRootCandidates().size());
        } finally {
            delete(project);
        }
    }

    @Test
    public void prefersProductionNestedBuildOverExampleBuild() throws Exception {
        Path project = Files.createTempDirectory("cocomut-production-root");
        try {
            Path library = project.resolve("android");
            Path example = project.resolve("example/android");
            Files.createDirectories(library);
            Files.createDirectories(example);
            Files.writeString(library.resolve("build.gradle"), "plugins { id 'java-library' }");
            Files.writeString(example.resolve("build.gradle"), "plugins { id 'java' }");

            ProjectMetadata metadata = new ProjectAnalyzer(project).analyze();
            assertEquals("gradle", metadata.getBuildSystem());
            assertEquals(library, metadata.getBuildRoot());
            assertEquals(List.of(library), metadata.getBuildRootCandidates());
        } finally {
            delete(project);
        }
    }

    @Test
    public void acceptsExampleNestedBuildWhenItIsTheOnlyBuild() throws Exception {
        Path project = Files.createTempDirectory("cocomut-example-root");
        try {
            Path example = project.resolve("example");
            Files.createDirectories(example);
            Files.writeString(example.resolve("pom.xml"), """
                    <project><modelVersion>4.0.0</modelVersion><groupId>demo</groupId>
                    <artifactId>example</artifactId><version>1</version></project>
                    """);

            ProjectMetadata metadata = new ProjectAnalyzer(project).analyze();
            assertEquals("maven", metadata.getBuildSystem());
            assertEquals(example, metadata.getBuildRoot());
            assertEquals(List.of(example), metadata.getBuildRootCandidates());
        } finally {
            delete(project);
        }
    }

    @Test
    public void reportsUnsupportedRootBuildBeforeNestedBuilds() throws Exception {
        Path project = Files.createTempDirectory("cocomut-ant-root");
        try {
            Files.writeString(project.resolve("build.xml"), "<project name=\"demo\"/>");
            Path incidentalModule = project.resolve("modules/incidental");
            Files.createDirectories(incidentalModule);
            Files.writeString(incidentalModule.resolve("pom.xml"), """
                    <project><modelVersion>4.0.0</modelVersion><groupId>demo</groupId>
                    <artifactId>incidental</artifactId><version>1</version></project>
                    """);

            ProjectMetadata metadata = new ProjectAnalyzer(project, true, "auto", false,
                    ContextRequest.BuildPolicy.ALLOW_UNSANDBOXED_BUILD,
                    List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()).analyze();
            assertEquals("ant", metadata.getBuildSystem());
            assertEquals(project, metadata.getBuildRoot());
            assertEquals(List.of(project), metadata.getBuildRootCandidates());
            assertFalse(metadata.isBuildAttempted());
            assertTrue(metadata.getCompileStatus().contains("UNSUPPORTED BUILD SYSTEM: ANT"));
        } finally {
            delete(project);
        }
    }

    @Test
    public void recognizesOnlyDeclaredReactorArtifacts() throws Exception {
        Path project = Files.createTempDirectory("cocomut-reactor-fallback");
        try {
            Files.writeString(project.resolve("pom.xml"), """
                    <project><modelVersion>4.0.0</modelVersion><groupId>demo</groupId>
                    <artifactId>parent</artifactId><version>1-SNAPSHOT</version>
                    <modules><module>api</module></modules></project>
                    """);
            Files.createDirectories(project.resolve("api"));
            Files.writeString(project.resolve("api/pom.xml"), """
                    <project><modelVersion>4.0.0</modelVersion><artifactId>api</artifactId></project>
                    """);
            ProjectAnalyzer analyzer = new ProjectAnalyzer(project);
            assertTrue(analyzer.missingSameReactorArtifacts(
                    "Could not find artifact demo:api:jar:1-SNAPSHOT"));
            assertFalse(analyzer.missingSameReactorArtifacts(
                    "Could not find artifact external:missing:jar:1-SNAPSHOT"));
        } finally {
            delete(project);
        }
    }

    private static void delete(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted((a, b) -> b.compareTo(a)).toList()) Files.deleteIfExists(path);
        }
    }
}
