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
    public void detectsCompilerRequestedRetryVersions() {
        assertEquals(25, ProjectAnalyzer.requiredJavaVersion("error: release version 25 not supported"));
        assertEquals(25, ProjectAnalyzer.requiredJavaVersion("error: invalid target release: 25"));
        assertEquals(6, ProjectAnalyzer.requiredJavaVersion("Source option 6 is no longer supported"));
        assertEquals(-1, ProjectAnalyzer.requiredJavaVersion("ordinary compilation failure"));
    }
}
