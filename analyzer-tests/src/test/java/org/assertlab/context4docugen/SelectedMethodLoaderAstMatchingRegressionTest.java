package org.assertlab.context4docugen;

import org.junit.Test;
import org.junit.experimental.categories.Category;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Regression test for selected-mode matching of valid Java declarations.
 *
 * Copy this file to:
 *   analyzer-tests/src/test/java/analyzer/SelectedMethodLoaderAstMatchingRegressionTest.java
 *
 * The test expects this PoC fixture project to be available at:
 *   ../pocs/context4docugen-ast-method-matching/fixture-project
 *
 * from the Context4DocuGen repository root.
 */
@Category(FastTests.class)
public class SelectedMethodLoaderAstMatchingRegressionTest {

    private Path fixtureProject() {
        Path[] candidates = new Path[] {
                Path.of("../pocs/context4docugen-ast-method-matching/fixture-project").toAbsolutePath().normalize(),
                Path.of("../../pocs/context4docugen-ast-method-matching/fixture-project").toAbsolutePath().normalize(),
                Path.of("pocs/context4docugen-ast-method-matching/fixture-project").toAbsolutePath().normalize()
        };

        for (Path candidate : candidates) {
            if (Files.exists(candidate.resolve("inputs_selected.csv"))) {
                return candidate;
            }
        }

        throw new AssertionError("Could not find AST method-matching fixture project. Tried: "
                + Arrays.toString(candidates));
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
