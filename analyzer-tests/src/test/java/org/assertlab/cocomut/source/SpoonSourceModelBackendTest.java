package org.assertlab.cocomut.source;

import static org.junit.Assert.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.assertlab.cocomut.ContextRequest;
import org.assertlab.cocomut.ModuleSourceSet;
import org.assertlab.cocomut.ProjectMetadata;
import org.junit.Test;
import spoon.Launcher;
import spoon.reflect.declaration.CtMethod;
import spoon.support.compiler.VirtualFile;

public class SpoonSourceModelBackendTest {

    @Test
    public void reportsTheEffectiveModeAcrossParsedModels() {
        assertEquals("classpath", SpoonSourceModelBackend.mergedMode(List.of("classpath")));
        assertEquals("no_classpath", SpoonSourceModelBackend.mergedMode(List.of("no_classpath")));
        assertEquals("mixed", SpoonSourceModelBackend.mergedMode(List.of("classpath", "no_classpath")));
    }

    @Test
    public void distinguishesMissingFocalJavadocFromExtractionFailure() {
        assertEquals(InheritedJavadocResolver.Availability.ABSENT,
                SpoonSourceModelBackend.focalJavadocAvailability("", "", false, false));
        assertEquals(InheritedJavadocResolver.Availability.PARSE_FAILED,
                SpoonSourceModelBackend.focalJavadocAvailability("", "", true, false));
        assertEquals(InheritedJavadocResolver.Availability.PARSE_FAILED,
                SpoonSourceModelBackend.focalJavadocAvailability("", "", false, true));
        assertEquals(InheritedJavadocResolver.Availability.PRESENT,
                SpoonSourceModelBackend.focalJavadocAvailability("parsed", "", false, true));
        assertEquals(InheritedJavadocResolver.Availability.PRESENT,
                SpoonSourceModelBackend.focalJavadocAvailability("", "raw", true, false));
    }

    @Test
    public void focalJavadocExtractionFailureMakesEffectiveItemsIndeterminate() {
        Launcher launcher = new Launcher();
        launcher.getEnvironment().setComplianceLevel(17);
        launcher.addInputResource(new VirtualFile(
                "class Sample { String value() { return \"\"; } }", "Sample.java"));
        launcher.buildModel();
        CtMethod<?> method = launcher.getFactory().Class().get("Sample")
                .getMethodsByName("value").get(0);
        Map<String, Object> tags = new LinkedHashMap<>();
        tags.put("parser", "");
        tags.put("parse_confidence", "none");
        tags.put("params", List.of());
        tags.put("return", List.of());
        tags.put("throws", List.of());
        tags.put("since", List.of());
        tags.put("api_notes", List.of());
        tags.put("impl_specs", List.of());
        tags.put("impl_notes", List.of());
        tags.put("deprecated", List.of());
        InheritedJavadocResolver.Documentation failed = new InheritedJavadocResolver.Documentation(
                InheritedJavadocResolver.Availability.PARSE_FAILED,
                "method://Sample.value", "", "", tags, "", tags,
                "", "none", "source_javadoc_extraction_failed");

        InheritedJavadocResolver.Resolution resolution = InheritedJavadocResolver.resolve(
                method, false, failed, ignored -> failed,
                ContextRequest.JavadocInheritancePolicy.JDK25_STANDARD_DOCLET);
        @SuppressWarnings("unchecked")
        Map<String, Object> description = (Map<String, Object>) resolution
                .effectiveStructuredTags().get("description");
        assertEquals("indeterminate", description.get("resolution"));
        assertEquals("source_documentation_unavailable", description.get("diagnostic_code"));
        assertEquals("partial", resolution.effectiveStructuredTags().get("resolution"));
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
