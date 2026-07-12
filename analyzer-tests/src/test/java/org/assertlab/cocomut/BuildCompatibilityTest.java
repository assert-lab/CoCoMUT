package org.assertlab.cocomut;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.Test;

public class BuildCompatibilityTest {
    @Test
    public void classifiesSecondLevelBuildReasons() {
        assertEquals(BuildFailureReason.BUILD_FAILED_JDK_UNAVAILABLE,
                BuildFailureReason.classify("error: invalid target release: 25", false, false));
        assertEquals(BuildFailureReason.BUILD_FAILED_ANDROID_SDK_UNAVAILABLE,
                BuildFailureReason.classify("SDK location not found", false, false));
        assertEquals(BuildFailureReason.BUILD_FAILED_AUTHENTICATION_REQUIRED,
                BuildFailureReason.classify("401 Unauthorized", false, false));
        assertEquals(BuildFailureReason.BUILD_FAILED_NETWORK_FAILURE,
                BuildFailureReason.classify("status code 503", false, false));
        assertEquals(BuildFailureReason.BUILD_FAILED_TIMEOUT,
                BuildFailureReason.classify("", true, false));
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

            Files.createDirectories(project.resolve("other"));
            Files.writeString(project.resolve("other/pom.xml"), """
                    <project><modelVersion>4.0.0</modelVersion><groupId>demo</groupId>
                    <artifactId>other</artifactId><version>1</version></project>
                    """);
            ProjectMetadata ambiguous = new ProjectAnalyzer(project).analyze();
            assertEquals("none", ambiguous.getBuildSystem());
            assertEquals(project, ambiguous.getBuildRoot());
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
