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
            int runtimeVersion = Runtime.version().feature();
            Files.writeString(project.resolve(".java-version"), runtimeVersion + "\n");
            BuildJavaSelection selection = BuildJavaSelection.select(project, "maven", "8",
                    java.util.Map.of("JAVA_HOME", System.getProperty("java.home")));
            assertEquals(Integer.toString(runtimeVersion), selection.version());
            assertEquals(".java-version", selection.evidence());
            assertEquals(Path.of(System.getProperty("java.home")).toAbsolutePath().normalize(), selection.javaHome());
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
            BuildJavaSelection selection = BuildJavaSelection.select(project, "maven", "unknown");
            if (BuildJavaSelection.installedJdkHomes().containsKey(17)) {
                assertEquals("17", selection.version());
                assertTrue(selection.evidence().contains("default build JDK 17"));
            }
        } finally {
            Files.deleteIfExists(project);
        }
    }

    @Test
    public void exactToolchainInventoryUsesActualHomeVersion() throws Exception {
        Path fakeJdk = Files.createTempDirectory("cocomut-fake-jdk");
        try {
            Files.createDirectories(fakeJdk.resolve("bin"));
            Files.writeString(fakeJdk.resolve("release"), "JAVA_VERSION=\"17.0.12\"\n");

            java.util.Map<Integer, Path> homes = BuildJavaSelection.installedJdkHomes(java.util.Map.of(
                    "JAVA_HOME", fakeJdk.toString(),
                    "COCOMUT_JAVA_HOME_12", fakeJdk.toString()));

            assertEquals(fakeJdk.toAbsolutePath().normalize(), homes.get(17));
            assertTrue("JDK 17 must not be advertised as exact JDK 12",
                    !fakeJdk.toAbsolutePath().normalize().equals(homes.get(12)));
            homes.forEach((version, home) -> assertEquals(version.intValue(),
                    BuildJavaSelection.javaHomeMajorVersion(home)));
        } finally {
            Files.deleteIfExists(fakeJdk.resolve("release"));
            Files.deleteIfExists(fakeJdk.resolve("bin"));
            Files.deleteIfExists(fakeJdk);
        }
    }

    @Test
    public void nestedBuildFallsBackToRepositoryRootJavaDeclaration() throws Exception {
        Path repository = Files.createTempDirectory("cocomut-repository-java");
        Path nested = repository.resolve("service");
        try {
            Files.createDirectories(nested);
            int runtimeVersion = Runtime.version().feature();
            Files.writeString(repository.resolve(".java-version"), runtimeVersion + "\n");

            BuildJavaSelection selection = BuildJavaSelection.select(nested, repository, "maven", "8",
                    java.util.Map.of("JAVA_HOME", System.getProperty("java.home")));

            assertEquals("repository-root .java-version", selection.evidence());
            assertEquals(Integer.toString(runtimeVersion), selection.version());
        } finally {
            Files.deleteIfExists(repository.resolve(".java-version"));
            Files.deleteIfExists(nested);
            Files.deleteIfExists(repository);
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
        assertEquals(25, ProjectAnalyzer.requiredJavaVersion(
                "This build requires at least JDK 25, but you are using JDK 17."));
        assertEquals(25, ProjectAnalyzer.requiredJavaVersion(
                "Project :app is only compatible with JVM runtime version 25 or newer."));
        assertEquals(21, ProjectAnalyzer.requiredJavaVersion(
                "This build requires Java 21 or 25, but is running on Java 17."));
        assertEquals(21, ProjectAnalyzer.requiredJavaVersion("JDK 21+ is required to build Apache Camel."));
        assertEquals(11, ProjectAnalyzer.requiredJavaVersion(
                "AuditListener has been compiled by a more recent version of the Java Runtime "
                        + "(class file version 55.0), this runtime recognizes versions up to 52.0"));
        assertEquals(17, ProjectAnalyzer.requiredJavaVersion(
                "bad class file: dependency.jar class file has wrong version 61.0, should be 55.0"));
        assertEquals(11, ProjectAnalyzer.requiredJavaVersion(
                "Unrecognized option: --add-opens=java.xml/com.sun.org.apache.xpath.internal=ALL-UNNAMED"));
        assertEquals(8, ProjectAnalyzer.requiredJavaVersion(
                "Java 1.8 is required for amd64. Detected version 17"));
        assertTrue(ProjectAnalyzer.exactJavaVersionRequired(
                "Java 1.8 is required for amd64. Detected version 17"));
        assertEquals(21, ProjectAnalyzer.requiredJavaVersion(
                "NullAway only builds on JDK 21 or higher now"));
        assertEquals(17, ProjectAnalyzer.requiredJavaVersion("Gradle requires JVM 17 or later to run"));
        assertEquals(6, ProjectAnalyzer.requiredJavaVersion("Source option 6 is no longer supported"));
        assertTrue(ProjectAnalyzer.obsoleteJavaSourceLevel(
                "Source option 6 is no longer supported. Use 7 or later."));
        assertTrue(ProjectAnalyzer.obsoleteJavaSourceLevel(
                "Target option 6 is no longer supported. Use 7 or later."));
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
