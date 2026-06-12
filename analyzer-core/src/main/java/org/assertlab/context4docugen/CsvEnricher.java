package org.assertlab.context4docugen;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.opencsv.CSVParser;
import com.opencsv.CSVParserBuilder;
import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;

/**
 * Phase 6 of the method context extraction pipeline.
 * 
 * Enriches the methods.csv file with additional information:
 * - Add call graph results (caller_count, callee_count)
 * - Add JSON file path for each method
 * - Add verification status (SUCCESS/FAILED)
 * - Maintain original pipe-delimited format
 * - Backup original file before enrichment
 * 
 * Input: Original methods.csv from Phase 2, call graph results from Phase 3, JSON paths from Phase 5
 * Output: Enriched methods.csv with new columns
 */
public class CsvEnricher {
    private static final String DELIMITER = "|";
    private static final String BACKUP_SUFFIX = ".backup";

    private final Path csvFile;
    private final Map<String, CallGraphResult> callGraphResults;
    private final Map<String, String> jsonFilePaths;
    private final Map<String, Integer> enrichmentStats;

    /**
     * Create CsvEnricher
     * @param csvFile Path to methods.csv file to enrich
     */
    public CsvEnricher(Path csvFile) {
        this.csvFile = Objects.requireNonNull(csvFile, "csvFile cannot be null");
        this.callGraphResults = new HashMap<>();
        this.jsonFilePaths = new HashMap<>();
        this.enrichmentStats = new HashMap<>();
    }

    /**
     * Register call graph results
     */
    public void registerCallGraphResults(Map<String, CallGraphResult> results) {
        this.callGraphResults.putAll(results);
    }

    /**
     * Register JSON file paths
     */
    public void registerJsonFilePaths(Map<String, String> paths) {
        this.jsonFilePaths.putAll(paths);
    }

    /**
     * Enrich the CSV file
     * @return true if enrichment succeeded
     */
    public boolean enrich() {
        try {
            // Backup original file
            if (!backupOriginalFile()) {
                return false;
            }

            // Read original CSV
            List<String[]> rows = readOriginalCsv();
            if (rows.isEmpty()) {
                return false;
            }

            // Enrich rows
            List<String[]> enrichedRows = enrichRows(rows);

            // Write enriched CSV
            writeEnrichedCsv(enrichedRows);

            enrichmentStats.put("original_rows", rows.size());
            enrichmentStats.put("enriched_rows", enrichedRows.size());

            return true;

        } catch (IOException e) {
            enrichmentStats.put("error", 1);
            return false;
        }
    }

    /**
     * Backup original file
     */
    private boolean backupOriginalFile() throws IOException {
        if (!Files.exists(csvFile)) {
            return false;
        }

        Path backupFile = Path.of(csvFile.toString() + BACKUP_SUFFIX);
        if (!Files.exists(backupFile)) {
            Files.copy(csvFile, backupFile);
        }

        return true;
    }

    /**
     * Read original CSV file
     */
    private List<String[]> readOriginalCsv() throws IOException {
        List<String[]> rows = new ArrayList<>();

        CSVParser parser = new CSVParserBuilder().withSeparator('|').build();
        try (CSVReader reader = new CSVReaderBuilder(Files.newBufferedReader(csvFile))
                .withCSVParser(parser).build()) {
            String[] line;
            while ((line = reader.readNext()) != null) {
                rows.add(line);
            }
        } catch (com.opencsv.exceptions.CsvValidationException e) {
            throw new IOException("CSV validation error", e);
        }

        return rows;
    }

    /**
     * Enrich rows with additional columns
     */
    private List<String[]> enrichRows(List<String[]> originalRows) {
        List<String[]> enrichedRows = new ArrayList<>();

        for (int i = 0; i < originalRows.size(); i++) {
            String[] row = originalRows.get(i);

            if (i == 0) {
                // Enrich header row
                enrichedRows.add(enrichHeaderRow(row));
            } else {
                // Enrich data rows
                enrichedRows.add(enrichDataRow(row));
            }
        }

        return enrichedRows;
    }

    /**
     * Enrich header row
     */
    private String[] enrichHeaderRow(String[] headerRow) {
        List<String> enrichedHeader = new ArrayList<>(Arrays.asList(headerRow));
        enrichedHeader.add("caller_count");
        enrichedHeader.add("callee_count");
        enrichedHeader.add("json_file_path");
        enrichedHeader.add("verification_status");
        return enrichedHeader.toArray(new String[0]);
    }

    /**
     * Enrich data row
     */
    private String[] enrichDataRow(String[] dataRow) {
        // Expected format: id|classname|methodname|filepath|line_number
        if (dataRow.length < 1) {
            return dataRow;
        }

        String methodId = dataRow[0].trim();
        List<String> enrichedRow = new ArrayList<>(Arrays.asList(dataRow));

        // Add caller count
        int callerCount = 0;
        if (callGraphResults.containsKey(methodId)) {
            callerCount = callGraphResults.get(methodId).getCallerCount();
        }
        enrichedRow.add(String.valueOf(callerCount));

        // Add callee count
        int calleeCount = 0;
        if (callGraphResults.containsKey(methodId)) {
            calleeCount = callGraphResults.get(methodId).getCalleeCount();
        }
        enrichedRow.add(String.valueOf(calleeCount));

        // Add JSON file path
        String jsonPath = jsonFilePaths.getOrDefault(methodId, "");
        enrichedRow.add(jsonPath);

        // Add verification status
        String status = "SUCCESS";
        if (jsonPath.isEmpty()) {
            status = "FAILED";
        }
        enrichedRow.add(status);

        return enrichedRow.toArray(new String[0]);
    }

    /**
     * Write enriched CSV file
     */
    private void writeEnrichedCsv(List<String[]> enrichedRows) throws IOException {
        StringBuilder content = new StringBuilder();
        
        for (String[] row : enrichedRows) {
            content.append(String.join(DELIMITER, row)).append("\n");
        }
        
        Files.write(csvFile, content.toString().getBytes());
    }

    /**
     * Get enrichment statistics
     */
    public Map<String, Integer> getEnrichmentStats() {
        return new HashMap<>(enrichmentStats);
    }

    /**
     * Verify enriched CSV file
     */
    public boolean verifyCsvFile() {
        if (!Files.exists(csvFile)) {
            return false;
        }

        try {
            List<String[]> rows = readOriginalCsv();
            return !rows.isEmpty();
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Get enriched CSV columns
     */
    public List<String> getEnrichedColumns() throws IOException {
        List<String[]> rows = readOriginalCsv();
        if (rows.isEmpty()) {
            return List.of();
        }

        return Arrays.asList(rows.get(0));
    }

    /**
     * Restore from backup
     */
    public boolean restoreFromBackup() {
        try {
            Path backupFile = Path.of(csvFile.toString() + BACKUP_SUFFIX);
            if (!Files.exists(backupFile)) {
                return false;
            }

            Files.copy(backupFile, csvFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return true;

        } catch (IOException e) {
            return false;
        }
    }
}
