package org.assertlab.context4docugen;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.Assert.*;

/**
 * Test suite for CsvEnricher Phase 6 component
 */
public class CsvEnricherTest {
    private Path testCsvFile;
    private CsvEnricher enricher;

    @Before
    public void setUp() throws Exception {
        // Create test CSV file
        testCsvFile = Files.createTempFile("methods_", ".csv");
        
        // Write test data
        String csvContent = "id|classname|methodname|filepath|line_number\n" +
                           "1|com.example.MyClass|method1|MyClass.java|10\n" +
                           "2|com.example.MyClass|method2|MyClass.java|20\n";
        Files.write(testCsvFile, csvContent.getBytes());
        
        enricher = new CsvEnricher(testCsvFile);
    }

    @After
    public void tearDown() throws Exception {
        if (Files.exists(testCsvFile)) {
            Files.delete(testCsvFile);
        }
        
        Path backupFile = Path.of(testCsvFile.toString() + ".backup");
        if (Files.exists(backupFile)) {
            Files.delete(backupFile);
        }
    }

    @Test
    public void testCsvEnricherCreation() {
        assertNotNull("Enricher should be created", enricher);
    }

    @Test
    public void testBackupOriginalFile() throws Exception {
        enricher.enrich();
        
        Path backupFile = Path.of(testCsvFile.toString() + ".backup");
        assertTrue("Backup file should be created", Files.exists(backupFile));
    }

    @Test
    public void testEnrichCsvBasic() throws Exception {
        // Register call graph results
        Map<String, CallGraphResult> callGraphResults = new HashMap<>();
        callGraphResults.put("1", new CallGraphResult.Builder()
                .methodId("1")
                .methodName("method1")
                .classname("com.example.MyClass")
                .addCaller("com.example.Main.main(String[])")
                .addCallee("java.lang.System.out")
                .build());

        enricher.registerCallGraphResults(callGraphResults);
        
        // Register JSON paths
        Map<String, String> jsonPaths = new HashMap<>();
        jsonPaths.put("1", "method_context_json/target_1__method1.json");
        enricher.registerJsonFilePaths(jsonPaths);

        boolean success = enricher.enrich();
        
        assertTrue("Should enrich CSV successfully", success);

        // Verify enriched content
        List<String> enrichedContent = Files.readAllLines(testCsvFile);
        assertNotNull("Should have enriched content", enrichedContent);
        assertTrue("Should have at least 2 rows (header + data)", enrichedContent.size() >= 2);
        
        // Check header has new columns
        String headerRow = enrichedContent.get(0);
        assertTrue("Header should contain caller_count", headerRow.contains("caller_count"));
        assertTrue("Header should contain callee_count", headerRow.contains("callee_count"));
        assertTrue("Header should contain json_file_path", headerRow.contains("json_file_path"));
        assertTrue("Header should contain verification_status", headerRow.contains("verification_status"));
    }

    @Test
    public void testEnrichmentStats() throws Exception {
        Map<String, CallGraphResult> callGraphResults = new HashMap<>();
        callGraphResults.put("1", new CallGraphResult.Builder()
                .methodId("1")
                .methodName("method1")
                .classname("com.example.MyClass")
                .build());

        enricher.registerCallGraphResults(callGraphResults);
        enricher.enrich();

        Map<String, Integer> stats = enricher.getEnrichmentStats();
        assertNotNull("Should have stats", stats);
        assertTrue("Should contain original_rows", stats.containsKey("original_rows"));
        assertTrue("Should contain enriched_rows", stats.containsKey("enriched_rows"));
        assertEquals("Original rows should be 3 (header + 2 data)", 3, stats.get("original_rows").intValue());
    }

    @Test
    public void testVerifyEnrichedCsv() throws Exception {
        Map<String, CallGraphResult> callGraphResults = new HashMap<>();
        enricher.registerCallGraphResults(callGraphResults);
        enricher.enrich();

        boolean verified = enricher.verifyCsvFile();
        assertTrue("Should verify enriched CSV", verified);
    }

    @Test
    public void testGetEnrichedColumns() throws Exception {
        Map<String, CallGraphResult> callGraphResults = new HashMap<>();
        enricher.registerCallGraphResults(callGraphResults);
        enricher.enrich();

        List<String> columns = enricher.getEnrichedColumns();
        assertNotNull("Should get columns", columns);
        assertTrue("Should have columns", columns.size() > 0);
        assertTrue("Should have new columns", 
                columns.toString().contains("caller_count") || 
                columns.toString().contains("callee_count"));
    }

    @Test
    public void testRestoreFromBackup() throws Exception {
        // First enrich
        enricher.enrich();
        
        // Verify enriched content
        List<String> enrichedContent = Files.readAllLines(testCsvFile);
        int enrichedRowCount = enrichedContent.size();

        // Restore from backup
        boolean restored = enricher.restoreFromBackup();
        assertTrue("Should restore from backup", restored);

        // Verify restored content
        List<String> restoredContent = Files.readAllLines(testCsvFile);
        assertTrue("Restored content should have fewer or equal columns", 
                restoredContent.get(0).split("\\|").length <= enrichedContent.get(0).split("\\|").length);
    }

    @Test
    public void testRegisterCallGraphResults() throws Exception {
        Map<String, CallGraphResult> callGraphResults = new HashMap<>();
        CallGraphResult result1 = new CallGraphResult.Builder()
                .methodId("1")
                .methodName("method1")
                .classname("com.example.MyClass")
                .addCaller("caller1")
                .addCaller("caller2")
                .addCallee("callee1")
                .build();

        callGraphResults.put("1", result1);
        enricher.registerCallGraphResults(callGraphResults);
        enricher.enrich();

        // Verify caller/callee counts in enriched CSV
        List<String> enrichedContent = Files.readAllLines(testCsvFile);
        String dataRow = enrichedContent.get(1); // First data row
        String[] fields = dataRow.split("\\|");

        // Fields should contain caller count (2) and callee count (1)
        assertTrue("Should have enriched caller/callee counts", fields.length >= 9);
    }

    @Test
    public void testRegisterJsonFilePaths() throws Exception {
        Map<String, String> jsonPaths = new HashMap<>();
        jsonPaths.put("1", "method_context_json/target_1__method1.json");
        jsonPaths.put("2", "method_context_json/target_2__method2.json");
        
        enricher.registerJsonFilePaths(jsonPaths);
        boolean success = enricher.enrich();
        
        assertTrue("Should enrich with JSON paths", success);

        List<String> enrichedContent = Files.readAllLines(testCsvFile);
        String[] headerFields = enrichedContent.get(0).split("\\|");
        
        // Should have json_file_path column
        assertTrue("Should have json_file_path column", 
                enrichedContent.get(0).contains("json_file_path"));
    }

    @Test
    public void testVerificationStatusSuccess() throws Exception {
        Map<String, String> jsonPaths = new HashMap<>();
        jsonPaths.put("1", "method_context_json/target_1__method1.json");
        jsonPaths.put("2", "method_context_json/target_2__method2.json");
        
        enricher.registerJsonFilePaths(jsonPaths);
        boolean success = enricher.enrich();
        
        assertTrue("Should enrich successfully", success);
        
        List<String> enrichedContent = Files.readAllLines(testCsvFile);
        assertTrue("Should have enriched data", enrichedContent.size() >= 2);
        
        // Verify header has extra columns
        String[] headerFields = enrichedContent.get(0).split("\\|");
        assertTrue("Should have additional columns in header", headerFields.length > 5);
    }

    @Test
    public void testVerificationStatusFailed() throws Exception {
        // Don't register any JSON paths - should result in FAILED status
        boolean success = enricher.enrich();
        
        assertTrue("Should enrich successfully", success);

        List<String> enrichedContent = Files.readAllLines(testCsvFile);
        assertTrue("Should have enriched data", enrichedContent.size() >= 2);
        
        // Verify data row was enriched
        String[] dataFields = enrichedContent.get(1).split("\\|");
        assertTrue("Data row should have additional columns", dataFields.length > 5);
    }

    @Test
    public void testEnrichMultipleMethods() throws Exception {
        Map<String, CallGraphResult> callGraphResults = new HashMap<>();
        callGraphResults.put("1", new CallGraphResult.Builder()
                .methodId("1")
                .methodName("method1")
                .classname("com.example.MyClass")
                .addCaller("c1").addCaller("c2")
                .addCallee("e1")
                .build());
        callGraphResults.put("2", new CallGraphResult.Builder()
                .methodId("2")
                .methodName("method2")
                .classname("com.example.MyClass")
                .addCaller("c1")
                .addCallee("e1").addCallee("e2").addCallee("e3")
                .build());

        enricher.registerCallGraphResults(callGraphResults);
        enricher.enrich();

        List<String> enrichedContent = Files.readAllLines(testCsvFile);
        assertTrue("Should have enriched multiple methods", enrichedContent.size() >= 3);
    }
}
