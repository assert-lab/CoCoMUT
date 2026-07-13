package org.assertlab.cocomut.source;

import static org.junit.Assert.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.assertlab.cocomut.ModuleSourceSet;
import org.assertlab.cocomut.ProjectMetadata;
import org.junit.Test;

public class SpoonSourceModelBackendTest {

    @Test
    public void reportsTheEffectiveModeAcrossParsedModels() {
        assertEquals("classpath", SpoonSourceModelBackend.mergedMode(List.of("classpath")));
        assertEquals("no_classpath", SpoonSourceModelBackend.mergedMode(List.of("no_classpath")));
        assertEquals("mixed", SpoonSourceModelBackend.mergedMode(List.of("classpath", "no_classpath")));
    }

    @Test
    public void classifiesCustomGradleSourceRootsFromTheProjectModel() throws Exception {
        Path projectRoot = Files.createTempDirectory("cocomut-custom-gradle-root");
        Path mainRoot = Files.createDirectories(projectRoot.resolve("gdx/src"));
        Path testRoot = Files.createDirectories(projectRoot.resolve("gdx/test"));
        Path mainFile = mainRoot.resolve("example/Main.java");
        Path testFile = testRoot.resolve("example/MainTest.java");

        ProjectMetadata metadata = new ProjectMetadata.Builder()
                .projectName("custom-gradle-layout")
                .projectPath(projectRoot)
                .buildSystem("gradle")
                .javaVersion("17")
                .sourceRoot(mainRoot)
                .sourceRoots(List.of(mainRoot))
                .testSourceRoots(List.of(testRoot))
                .moduleSourceSets(List.of(
                        new ModuleSourceSet(":gdx", "main", List.of(mainRoot), List.of(), List.of(), "17"),
                        new ModuleSourceSet(":gdx", "test", List.of(testRoot), List.of(), List.of(), "17")))
                .build();
        ProjectModel project = ProjectModel.from(metadata);

        assertEquals("main", SpoonSourceModelBackend.sourceSet(project, mainFile));
        assertEquals("test", SpoonSourceModelBackend.sourceSet(project, testFile));
    }
}
