package org.assertlab.context4docugen.strategy;

import org.assertlab.context4docugen.FastTests;
import org.assertlab.context4docugen.MethodInfo;
import org.assertlab.context4docugen.ProjectMetadata;
import org.assertlab.context4docugen.ProjectAnalyzer;
import org.assertlab.context4docugen.TestFixtures;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import java.util.List;

import static org.junit.Assert.*;

/**
 * Tests for {@link EntryPointScanStrategy}.
 *
 * The minimal fixture {@code Hello} has: greet (public), prefix (private),
 * main (public static). So entry-points = {greet, main}; with main excluded = {greet}.
 */
@Category(FastTests.class)
public class EntryPointScanStrategyTest {

    private ProjectMetadata meta;

    @Before
    public void setUp() throws Exception {
        TestFixtures.ensureMinimalMavenProjectCompiled();
        meta = new ProjectAnalyzer(TestFixtures.minimalMavenProjectRoot()).analyze();
    }

    @Test
    public void name_isEntryPoints() {
        assertEquals("ENTRY_POINTS", new EntryPointScanStrategy().name());
    }

    @Test
    public void keepsOnlyPublicMethods() throws Exception {
        List<MethodInfo> methods = new EntryPointScanStrategy(false).loadMethods(meta);
        assertFalse("Should find public methods", methods.isEmpty());
        for (MethodInfo m : methods) {
            assertEquals("Strategy must keep only public methods — found "
                    + m.getMethodName() + " (" + m.getVisibility() + ")",
                    "public", m.getVisibility());
        }
        // private prefix() must be excluded
        assertTrue("prefix() is private and must be excluded",
                methods.stream().noneMatch(m -> m.getMethodName().equals("prefix")));
    }

    @Test
    public void excludeMainByDefault() throws Exception {
        List<MethodInfo> withMain    = new EntryPointScanStrategy(false).loadMethods(meta);
        List<MethodInfo> withoutMain = new EntryPointScanStrategy(true).loadMethods(meta);

        assertTrue("main present when not excluded",
                withMain.stream().anyMatch(m -> m.getMethodName().equals("main")));
        assertTrue("main excluded by default",
                withoutMain.stream().noneMatch(m -> m.getMethodName().equals("main")));
        assertTrue("greet (public, non-main) always kept",
                withoutMain.stream().anyMatch(m -> m.getMethodName().equals("greet")));
    }
}
