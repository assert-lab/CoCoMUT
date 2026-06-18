package org.assertlab.cocox.strategy;

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
 * Unit tests for {@link MethodSourceStrategy} auto-detection.
 *
 * Verifies that the presence (or absence) of {@code inputs_selected.csv}
 * selects the correct strategy, using temp directories.
 */
@Category(FastTests.class)
public class MethodSourceStrategyTest {

    private Path tempDir;

    @Before
    public void setUp() throws IOException {
        tempDir = Files.createTempDirectory("strategy-test");
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
    public void csvPresentSelectsCsvStrategy() throws IOException {
        Path csv = tempDir.resolve("inputs_selected.csv");
        Files.writeString(csv, "focal_method|test_prefix|docstring|id\n");
        MethodSourceStrategy strategy = MethodSourceStrategy.detect(tempDir);
        assertTrue("inputs_selected.csv should pick CsvSelectedStrategy",
                strategy instanceof CsvSelectedStrategy);
        assertEquals("CSV_SELECTED", strategy.name());
    }

    @Test
    public void noCsvSelectsScanAllStrategy() {
        MethodSourceStrategy strategy = MethodSourceStrategy.detect(tempDir);
        assertTrue("No CSV should pick ScanAllSourcesStrategy",
                strategy instanceof ScanAllSourcesStrategy);
        assertEquals("SCAN_ALL", strategy.name());
    }

    @Test
    public void csvStrategyExposesResolvedPath() throws IOException {
        Path csv = tempDir.resolve("inputs_selected.csv");
        Files.writeString(csv, "focal_method|id\n");
        MethodSourceStrategy strategy = MethodSourceStrategy.detect(tempDir);
        assertTrue(strategy instanceof CsvSelectedStrategy);
        assertEquals("CsvSelectedStrategy should expose the detected CSV path",
                csv, ((CsvSelectedStrategy) strategy).getCsvPath());
    }
}
