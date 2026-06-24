package org.assertlab.cocomut.source;

import java.nio.file.Path;
import java.util.List;

public record SourceParseStats(
        int discovered,
        int parsed,
        List<Path> failedFiles) {
    public SourceParseStats {
        failedFiles = failedFiles != null ? List.copyOf(failedFiles) : List.of();
    }

    public int failed() {
        return failedFiles.size();
    }

    public static SourceParseStats empty() {
        return new SourceParseStats(0, 0, List.of());
    }
}
