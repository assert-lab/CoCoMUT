package org.assertlab.context4docugen.source;

import java.nio.file.Path;
import java.util.List;

public record SourceMethod(
        String methodUri,
        String className,
        String methodName,
        String signature,
        Path sourceFile,
        int lineNumber,
        int columnNumber,
        String visibility,
        boolean isStatic,
        String returnType,
        String erasedReturnType,
        List<SourceParameter> parameters,
        List<String> annotations,
        List<String> thrownExceptions,
        String sourceSet,
        boolean constructor) {
    public SourceMethod {
        erasedReturnType = erasedReturnType != null ? erasedReturnType : returnType;
        parameters = parameters != null ? List.copyOf(parameters) : List.of();
        annotations = annotations != null ? List.copyOf(annotations) : List.of();
        thrownExceptions = thrownExceptions != null ? List.copyOf(thrownExceptions) : List.of();
        sourceSet = sourceSet != null ? sourceSet : "unknown";
    }
}
