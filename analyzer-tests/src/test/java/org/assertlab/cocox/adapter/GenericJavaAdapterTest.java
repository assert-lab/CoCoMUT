package org.assertlab.cocox.adapter;

import org.assertlab.cocox.FastTests;
import org.assertlab.cocox.ProjectMetadata;
import org.assertlab.cocox.TestFixtures;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.Assert.*;

/**
 * Tests for {@link GenericJavaAdapter}.
 */
@Category(FastTests.class)
public class GenericJavaAdapterTest {

    private Path tempProject;

    @Before
    public void setUp() throws Exception {
        tempProject = Files.createTempDirectory("generic-adapter-test");
    }

    @After
    public void tearDown() throws IOException {
        if (tempProject != null && Files.exists(tempProject)) {
            deleteRecursively(tempProject);
        }
    }

    @Test
    public void canHandleIsAlwaysTrue() {
        assertTrue(new GenericJavaAdapter(tempProject).canHandle(tempProject));
    }

    @Test
    public void doesNotDecompileClassFilesWhenNoSourcePresent() throws Exception {
        Path classesDir = tempProject.resolve("target/classes/com/example");
        Files.createDirectories(classesDir);
        Files.write(classesDir.resolve("Hello.class"), new byte[] {0, 1, 2});

        ProjectMetadata meta = new GenericJavaAdapter(tempProject).toMetadata();

        Path decompiledRoot = tempProject.resolve("target/decompiled-sources");
        assertFalse("No decompiled dir should be created",
                Files.exists(decompiledRoot));
        assertNotEquals("sourceRoot should not point to generated decompiled source",
                decompiledRoot, meta.getSourceRoot());
    }

    @Test
    public void doesNotDecompileWhenSourceExists() throws Exception {
        // Project WITH source: a src/main/java/...java file present.
        Path src = tempProject.resolve("src/main/java/com/example");
        Files.createDirectories(src);
        Files.writeString(src.resolve("Foo.java"),
                "package com.example;\npublic class Foo { public void bar() {} }\n");

        ProjectMetadata meta = new GenericJavaAdapter(tempProject).toMetadata();

        // Source root must remain the real source tree, not a decompiled dir.
        assertFalse("Must NOT decompile when source exists",
                meta.getSourceRoot().toString().contains("decompiled-sources"));
        assertFalse("No decompiled dir should be created",
                Files.exists(tempProject.resolve("target/decompiled-sources")));
    }

    private static void deleteRecursively(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.sorted((a, b) -> b.compareTo(a)).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
