package org.assertlab.cocox.strategy;

import org.assertlab.cocox.MethodInfo;
import org.assertlab.cocox.ProjectMetadata;
import org.assertlab.cocox.SelectedMethodLoader;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Method source strategy for OE-25 research mode.
 *
 * Reads pre-curated focal methods from {@code inputs_selected.csv}
 * (pipe-delimited, columns: focal_method | test_prefix | docstring | id).
 * Each row is parsed by {@link SelectedMethodLoader}, which locates the
 * method in project source and attaches the original docstring and test
 * prefix to the resulting {@link MethodInfo} for end-to-end propagation
 * into the JSON output.
 *
 * <p>Selected automatically by {@link MethodSourceStrategy#detect(Path)} when
 * {@code inputs_selected.csv} exists in the project root.
 */
public class CsvSelectedStrategy implements MethodSourceStrategy {

    private final Path csvPath;

    public CsvSelectedStrategy(Path csvPath) {
        this.csvPath = csvPath;
    }

    @Override
    public String name() {
        return "CSV_SELECTED";
    }

    /** Returns the resolved path to the CSV file (used by {@code AnalyzerFacade}). */
    public Path getCsvPath() {
        return csvPath;
    }

    @Override
    public List<MethodInfo> loadMethods(ProjectMetadata meta) throws IOException {
        return new SelectedMethodLoader(meta, csvPath).load();
    }
}
