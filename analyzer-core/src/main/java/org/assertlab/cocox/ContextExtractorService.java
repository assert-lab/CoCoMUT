package org.assertlab.cocox;

import java.io.IOException;
import java.util.Objects;

/**
 * Public service facade for method-context extraction.
 */
public final class ContextExtractorService {
    private ContextExtractorService() {
    }

    public static ContextExtractorService createDefault() {
        return new ContextExtractorService();
    }

    public ExtractionReport extract(ContextRequest request) throws IOException {
        Objects.requireNonNull(request, "request cannot be null");
        return new ExtractionReport(AnalyzerFacade.analyze(
                request.projectRoot(),
                request.toAnalysisOptions()));
    }
}
