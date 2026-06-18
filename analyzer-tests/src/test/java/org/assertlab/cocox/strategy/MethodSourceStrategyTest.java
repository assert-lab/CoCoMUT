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
 * Verifies default method-source strategy detection.
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
    public void localCsvPresenceDoesNotChangeStrategy() throws IOException {
        Path csv = tempDir.resolve("data.csv");
        Files.writeString(csv, "value\n1\n");
        MethodSourceStrategy strategy = MethodSourceStrategy.detect(tempDir);
        assertTrue("Local CSV files should not change source scanning",
                strategy instanceof ScanAllSourcesStrategy);
        assertEquals("SCAN_ALL", strategy.name());
    }

    @Test
    public void noCsvSelectsScanAllStrategy() {
        MethodSourceStrategy strategy = MethodSourceStrategy.detect(tempDir);
        assertTrue("No CSV should pick ScanAllSourcesStrategy",
                strategy instanceof ScanAllSourcesStrategy);
        assertEquals("SCAN_ALL", strategy.name());
    }

}
