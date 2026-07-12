package org.assertlab.cocomut.adapter;

import org.assertlab.cocomut.FastTests;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

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
    public void gradleSettingsOnlyRootSelectsGradleAdapter() throws IOException {
        Files.writeString(tempDir.resolve("settings.gradle"), "include 'lib'");
        ProjectAdapter adapter = ProjectAdapter.of(tempDir);
        assertTrue("settings.gradle-only root should pick GradleProjectAdapter",
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
        // Maven must win when no Gradle descriptor is present.
        Files.writeString(tempDir.resolve("pom.xml"), "<project/>");
        ProjectAdapter adapter = ProjectAdapter.of(tempDir);
        assertTrue("Maven must take precedence over generic fallback",
                adapter instanceof MavenProjectAdapter);
    }

    @Test
    public void gradleDescriptorsTakePrecedenceOverPom() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), "<project/>");
        Files.writeString(tempDir.resolve("settings.gradle"), "include 'lib'");
        Files.writeString(tempDir.resolve("build.gradle"), "plugins { id 'java' }\n");

        ProjectAdapter adapter = ProjectAdapter.of(tempDir);

        assertTrue("Gradle root descriptors should take precedence over a root pom.xml",
                adapter instanceof GradleProjectAdapter);
    }

    @Test
    public void canHandleMatchesExpectedMarkers() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), "<project/>");
        assertTrue(new MavenProjectAdapter(tempDir).canHandle(tempDir));
        assertFalse(new GradleProjectAdapter(tempDir).canHandle(tempDir));
        assertTrue("Generic adapter always matches",
                new GenericJavaAdapter(tempDir).canHandle(tempDir));
    }

    @Test
    public void gradleSourceRootsCanBeRecoveredFromBuiltModuleOutputs() throws IOException {
        Path module = tempDir.resolve("modules/library");
        Path sourceRoot = module.resolve("src/main/java/example");
        Path output = module.resolve("build/classes/java/main");
        Files.createDirectories(sourceRoot);
        Files.createDirectories(output);
        Files.writeString(sourceRoot.resolve("Library.java"), "package example; class Library {}\n");

        assertEquals(List.of(module.resolve("src/main/java").toAbsolutePath().normalize()),
                GradleProjectAdapter.sourceRootsForOutputs(List.of(output), false));
        assertEquals(List.of(), GradleProjectAdapter.sourceRootsForOutputs(List.of(output), true));
    }
}
