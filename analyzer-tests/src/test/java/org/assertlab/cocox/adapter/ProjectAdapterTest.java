package org.assertlab.cocox.adapter;

import org.assertlab.cocox.FastTests;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link ProjectAdapter} auto-detection.
 *
 * Uses throwaway temp directories with marker files (pom.xml, build.gradle)
 * so detection can be verified without compiling a real project.
 */
@Category(FastTests.class)
public class ProjectAdapterTest {

    private Path tempDir;

    @Before
    public void setUp() throws IOException {
        tempDir = Files.createTempDirectory("adapter-test");
    }

    @After
    public void tearDown() throws IOException {
        if (tempDir != null && Files.exists(tempDir)) {
            try (var walk = Files.walk(tempDir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException ignored) { }
                });
            }
        }
    }

    @Test
    public void mavenProjectSelectsMavenAdapter() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), "<project/>");
        ProjectAdapter adapter = ProjectAdapter.of(tempDir);
        assertTrue("pom.xml dir should pick MavenProjectAdapter",
                adapter instanceof MavenProjectAdapter);
    }

    @Test
    public void gradleProjectSelectsGradleAdapter() throws IOException {
        Files.writeString(tempDir.resolve("build.gradle"), "// gradle");
        ProjectAdapter adapter = ProjectAdapter.of(tempDir);
        assertTrue("build.gradle dir should pick GradleProjectAdapter",
                adapter instanceof GradleProjectAdapter);
    }

    @Test
    public void gradleKotlinDslSelectsGradleAdapter() throws IOException {
        Files.writeString(tempDir.resolve("build.gradle.kts"), "// kotlin dsl");
        ProjectAdapter adapter = ProjectAdapter.of(tempDir);
        assertTrue("build.gradle.kts dir should pick GradleProjectAdapter",
                adapter instanceof GradleProjectAdapter);
    }

    @Test
    public void plainDirectoryFallsBackToGenericAdapter() {
        // tempDir has no build descriptor → fallback
        ProjectAdapter adapter = ProjectAdapter.of(tempDir);
        assertTrue("Plain dir should fall back to GenericJavaAdapter",
                adapter instanceof GenericJavaAdapter);
    }

    @Test
    public void mavenTakesPrecedenceOverGenericFallback() throws IOException {
        // Both a pom.xml and the (always-matching) generic fallback could apply;
        // Maven must win because it is registered first.
        Files.writeString(tempDir.resolve("pom.xml"), "<project/>");
        ProjectAdapter adapter = ProjectAdapter.of(tempDir);
        assertTrue("Maven must take precedence over generic fallback",
                adapter instanceof MavenProjectAdapter);
    }

    @Test
    public void canHandleMatchesExpectedMarkers() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), "<project/>");
        assertTrue(new MavenProjectAdapter(tempDir).canHandle(tempDir));
        assertFalse(new GradleProjectAdapter(tempDir).canHandle(tempDir));
        assertTrue("Generic adapter always matches",
                new GenericJavaAdapter(tempDir).canHandle(tempDir));
    }
}
