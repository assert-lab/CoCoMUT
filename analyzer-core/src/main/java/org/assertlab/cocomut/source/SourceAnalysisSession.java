package org.assertlab.cocomut.source;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Request-scoped source-analysis state.
 *
 * <p>A session owns the parsed source model for one extraction request. It must
 * not be cached by project path across requests because checkouts can change
 * under the same path.
 */
public interface SourceAnalysisSession extends AutoCloseable {
    List<SourceMethod> methods() throws IOException;

    default Optional<SourceMethod> findMethod(String methodUri) throws IOException {
        if (methodUri == null || methodUri.isBlank()) {
            return Optional.empty();
        }
        return methods().stream()
                .filter(method -> methodUri.equals(method.methodUri()))
                .findFirst();
    }

    Optional<SourceContext> extractContext(String methodUri) throws IOException;

    default SourceParseStats parseStats() {
        return SourceParseStats.empty();
    }

    @Override
    default void close() throws IOException {
        // Implementations with no native resources can keep the default.
    }
}
