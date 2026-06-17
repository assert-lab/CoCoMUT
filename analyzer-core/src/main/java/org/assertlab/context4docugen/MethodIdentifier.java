package org.assertlab.context4docugen;

import org.assertlab.context4docugen.source.ProjectModel;
import org.assertlab.context4docugen.source.SourceBackends;
import org.assertlab.context4docugen.source.SourceMethod;
import org.assertlab.context4docugen.source.SourceModelBackend;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Phase 2 of the method context extraction pipeline.
 * 
 * Scans all Java source files in the project to identify methods and:
 * - Extract method signatures, names, line numbers
 * - Generate stable method URIs for each method
 * 
 * Input: ProjectMetadata from Phase 1
 * Output: List of MethodInfo objects
 */
public class MethodIdentifier {
    private final ProjectMetadata projectMetadata;
    private final SourceModelBackend sourceBackend;

    /**
     * Create a MethodIdentifier with default configuration
     * @param projectMetadata Project metadata from Phase 1
     */
    public MethodIdentifier(ProjectMetadata projectMetadata) {
        this.projectMetadata = Objects.requireNonNull(projectMetadata, "projectMetadata cannot be null");
        this.sourceBackend = SourceBackends.spoon();
    }

    /**
     * Scan all Java files and identify methods
     * @return List of MethodInfo objects
     * @throws IOException if scanning fails
     */
    public List<MethodInfo> identify() throws IOException {
        ProjectModel project = ProjectModel.from(projectMetadata);
        return new ArrayList<>(sourceBackend.findMethods(project).stream()
                .map(MethodIdentifier::toMethodInfo)
                .toList());
    }

    public static MethodInfo toMethodInfo(SourceMethod method) {
        return new MethodInfo.Builder()
                .id(method.methodUri())
                .classname(method.className())
                .methodName(method.methodName())
                .methodSignature(method.signature())
                .sourceFile(method.sourceFile())
                .lineNumber(method.lineNumber())
                .columnNumber(method.columnNumber())
                .visibility(method.visibility())
                .isStatic(method.isStatic())
                .returnType(method.returnType())
                .sourceSet(method.sourceSet())
                .build();
    }

}
