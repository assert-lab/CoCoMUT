package org.assertlab.cocox.source;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Internal source-model API.
 *
 * <p>Implementations may use Spoon, JavaParser, JDT, or another parser, but the
 * rest of CoCoX consumes only these plain Java records.
 */
public interface SourceModelBackend {
    String name();

    String mode();

    List<SourceMethod> findMethods(ProjectModel project) throws IOException;

    Optional<SourceMethod> findMethod(ProjectModel project, String methodUri) throws IOException;

    Optional<SourceContext> extractContext(ProjectModel project, String methodUri) throws IOException;
}
