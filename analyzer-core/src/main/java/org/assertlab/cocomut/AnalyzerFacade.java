package org.assertlab.cocomut;

import org.assertlab.cocomut.adapter.ProjectAdapter;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/**
 * Internal bridge between the public service API and the extraction pipeline.
 */
final class AnalyzerFacade {

    private AnalyzerFacade() {
    }

    static Map<String, Object> analyze(Path projectPath) throws IOException {
        return analyze(ContextRequest.defaults(projectPath));
    }

    static Map<String, Object> analyze(ContextRequest request) throws IOException {
        Objects.requireNonNull(request, "request cannot be null");
        ProjectAdapter adapter = ProjectAdapter.of(request.projectRoot());
        System.out.printf("[AnalyzerFacade] Project: %s | Adapter: %s | Scope: %s%n",
                request.projectRoot().getFileName(),
                adapter.getClass().getSimpleName(),
                request.scope());

        Orchestrator orchestrator = new Orchestrator(request);
        orchestrator.execute();
        return orchestrator.getExecutionReport();
    }
}
