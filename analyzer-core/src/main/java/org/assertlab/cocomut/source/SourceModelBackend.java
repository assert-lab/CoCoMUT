package org.assertlab.cocomut.source;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Internal source-model API.
 *
 * <p>Implementations may use Spoon, JavaParser, JDT, or another parser, but the
 * rest of CoCoMUT consumes only these plain Java records.
 */
public interface SourceModelBackend {
    String name();

    String mode();

    SourceAnalysisSession open(ProjectModel project) throws IOException;

    default List<SourceMethod> findMethods(ProjectModel project) throws IOException {
        try (SourceAnalysisSession session = open(project)) {
            return session.methods();
        }
    }

    default Optional<SourceMethod> findMethod(ProjectModel project, String methodUri) throws IOException {
        try (SourceAnalysisSession session = open(project)) {
            return session.findMethod(methodUri);
        }
    }

    default Optional<SourceContext> extractContext(ProjectModel project, String methodUri) throws IOException {
        try (SourceAnalysisSession session = open(project)) {
            return session.extractContext(methodUri);
        }
    }
}
