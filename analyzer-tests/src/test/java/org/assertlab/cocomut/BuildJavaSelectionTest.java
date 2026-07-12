package org.assertlab.cocomut;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

public class BuildJavaSelectionTest {

    @Test
    public void selectsDeclaredDotJavaVersionWhenInstalled() throws Exception {
        Path project = Files.createTempDirectory("cocomut-build-java");
        try {
            Files.writeString(project.resolve(".java-version"), "17\n");
            BuildJavaSelection selection = BuildJavaSelection.select(project, "maven", "8");
            assertEquals("17", selection.version());
            assertEquals(".java-version", selection.evidence());
            assertTrue(selection.javaHome() == null || Files.isDirectory(selection.javaHome().resolve("bin")));
        } finally {
            Files.deleteIfExists(project.resolve(".java-version"));
            Files.deleteIfExists(project);
        }
    }

    @Test
    public void appliesSelectedJavaHomeOnlyToTheChildProcess() {
        Path home = Path.of(System.getProperty("java.home"));
        BuildJavaSelection selection = new BuildJavaSelection(home, "17", "test");
        ProcessBuilder child = new ProcessBuilder("java", "-version");

        selection.apply(child);

        assertEquals(home.toString(), child.environment().get("JAVA_HOME"));
        assertTrue(child.environment().get("PATH").startsWith(home.resolve("bin").toString()));
    }

    @Test
    public void recordsRuntimeJavaWhenNoProjectVersionIsDeclared() throws Exception {
        Path project = Files.createTempDirectory("cocomut-build-java-default");
        try {
            assertEquals(Path.of(System.getProperty("java.home")).toAbsolutePath().normalize(),
                    BuildJavaSelection.inheritedJavaHome(java.util.Map.of()));
        } finally {
            Files.deleteIfExists(project);
        }
    }

    @Test
    public void detectsCompilerRequestedRetryVersions() {
        assertEquals(25, ProjectAnalyzer.requiredJavaVersion("error: release version 25 not supported"));
        assertEquals(25, ProjectAnalyzer.requiredJavaVersion("error: invalid target release: 25"));
        assertEquals(20, ProjectAnalyzer.requiredJavaVersion(
                "Detected JDK version 11.0.31 is not in the allowed range [20,)."));
        assertEquals(11, ProjectAnalyzer.requiredJavaVersion("Fatal error compiling: invalid flag: --release"));
        assertEquals(22, ProjectAnalyzer.requiredJavaVersion(
                "Cannot find a Java installation matching: {languageVersion=22, vendor=any vendor}"));
        assertEquals(25, ProjectAnalyzer.requiredJavaVersion("error: invalid source release: 25"));
        assertEquals(21, ProjectAnalyzer.requiredJavaVersion(
                "Dependency requires at least JVM runtime version 21. This build uses a Java 17 JVM."));
        assertEquals(17, ProjectAnalyzer.requiredJavaVersion("Gradle requires JVM 17 or later to run"));
        assertEquals(6, ProjectAnalyzer.requiredJavaVersion("Source option 6 is no longer supported"));
        assertEquals(-1, ProjectAnalyzer.requiredJavaVersion("ordinary compilation failure"));
    }

    @Test
    public void mapsReleaseTargetsToCompatibleInstalledJdks() {
        assertEquals(8, BuildJavaSelection.compatibleInstalledVersion(6));
        assertEquals(17, BuildJavaSelection.compatibleInstalledVersion(14));
        assertEquals(21, BuildJavaSelection.compatibleInstalledVersion(20));
        assertEquals(25, BuildJavaSelection.compatibleInstalledVersion(23));
        assertEquals(26, BuildJavaSelection.compatibleInstalledVersion(26));
    }

    @Test
    public void respectsGradleRuntimeCompatibilityBoundary() {
        assertEquals(8, BuildJavaSelection.gradleRuntimeVersion(4, 10));
        assertEquals(11, BuildJavaSelection.gradleRuntimeVersion(6, 9));
        assertEquals(11, BuildJavaSelection.gradleRuntimeVersion(7, 2));
        assertEquals(17, BuildJavaSelection.gradleRuntimeVersion(7, 3));
        assertEquals(17, BuildJavaSelection.gradleRuntimeVersion(9, 0));
    }
}
