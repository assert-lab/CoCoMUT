package org.assertlab.context4docugen;

import org.junit.Test;
import org.junit.experimental.categories.Category;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Regression test for selected-mode matching of valid Java declarations.
 */
@Category(FastTests.class)
public class SelectedMethodLoaderAstMatchingRegressionTest {

    private Path fixtureProject() {
        Path fixture = Path.of("src/test/resources/fixtures/ast-method-matching-project")
                .toAbsolutePath()
                .normalize();
        if (Files.exists(fixture.resolve("inputs_selected.csv"))) {
            return fixture;
        }
        throw new AssertionError("Could not find AST method-matching fixture project at " + fixture);
    }

    @Test
    public void selectedModeLoadsConstructorsAndComplexDeclarations() throws Exception {
        Path fixtureProject = fixtureProject();

        Orchestrator orchestrator = new Orchestrator(
                fixtureProject,
                Orchestrator.ExecutionMode.SELECTED);

        boolean ok = orchestrator.execute();

        assertEquals("Pipeline should still run. Report: " + orchestrator.getExecutionReport(), true, ok);
        assertEquals(
                "All six selected declarations should be loaded: simple method, generic method, constructor, annotated method, nested-class method, compact record constructor",
                6,
                ((Number) orchestrator.getExecutionReport().get("phase_2_methods_loaded")).intValue());
    }

    @Test
    public void selectedModePreservesNestedDeclaringClassIdentity() throws Exception {
        Path fixtureProject = fixtureProject();

        Orchestrator orchestrator = new Orchestrator(
                fixtureProject,
                Orchestrator.ExecutionMode.SELECTED);

        boolean ok = orchestrator.execute();

        assertEquals("Pipeline should still run. Report: " + orchestrator.getExecutionReport(), true, ok);

        MethodInfo nestedMethod = orchestrator.getMethodInfos().stream()
                .filter(method -> method.getMethodUri().contains("#poc.SelectedMethodMatchingFixture$Nested.value("))
                .findFirst()
                .orElseThrow(() -> new AssertionError("nested_value method missing from loaded methods"));

        assertTrue(
                "nested_value should be attributed to the nested declaring class, not only to the outer source file class",
                nestedMethod.getClassname().contains("Nested"));
    }
}
