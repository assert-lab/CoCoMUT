package org.assertlab.cocomut;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Map;
import org.assertlab.cocomut.cli.CoCoMUTCommand;
import org.junit.Test;

public class ExtractionReportTest {

    @Test
    public void partialReportExposesUsableRecordsAndDistinctCliExitCode() {
        ExtractionReport report = new ExtractionReport(Map.of(
                "status", "PARTIAL",
                "phase_5_jsonl_rows", 3,
                "phase_5_files_generated", 3));

        assertFalse(report.successful());
        assertTrue(report.partial());
        assertTrue(report.usableRecordsEmitted());
        assertEquals(2, CoCoMUTCommand.exitCodeFor(report));
    }

    @Test
    public void terminalFailureDoesNotClaimUsableRecords() {
        ExtractionReport report = new ExtractionReport(Map.of("status", "FAILED"));

        assertFalse(report.partial());
        assertFalse(report.usableRecordsEmitted());
        assertEquals(1, CoCoMUTCommand.exitCodeFor(report));
    }
}
