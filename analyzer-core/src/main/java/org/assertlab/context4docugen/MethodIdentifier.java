package org.assertlab.context4docugen;

import org.assertlab.context4docugen.source.ProjectModel;
import org.assertlab.context4docugen.source.SourceBackends;
import org.assertlab.context4docugen.source.SourceMethod;
import org.assertlab.context4docugen.source.SourceModelBackend;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Random;

/**
 * Phase 2 of the method context extraction pipeline.
 * 
 * Scans all Java source files in the project to identify methods and:
 * - Extract method signatures, names, line numbers
 * - Generate stable method URIs for each method
 * - Create methods.csv file with all identified methods
 * - Support optional sampling for large projects
 * 
 * Input: ProjectMetadata from Phase 1
 * Output: methods.csv file and List of MethodInfo objects
 */
public class MethodIdentifier {
    private final ProjectMetadata projectMetadata;
    private final IdStrategy idStrategy;
    private final Integer samplingSize;  // null = no sampling, N = random sample of N methods
    private final Path outputDirectory;
    private final SourceModelBackend sourceBackend;

    /** @deprecated Method identity is now URI-based. Kept for source compatibility. */
    @Deprecated
    public enum IdStrategy {
        SEQUENTIAL,
        UUID,
        HASH,
        URI
    }

    /**
     * Create a MethodIdentifier with default configuration
     * @param projectMetadata Project metadata from Phase 1
     */
    public MethodIdentifier(ProjectMetadata projectMetadata) {
        this(projectMetadata, IdStrategy.SEQUENTIAL, null, Paths.get("."));
    }

    /**
     * Create a MethodIdentifier with custom configuration
     * @param projectMetadata Project metadata from Phase 1
     * @param idStrategy Strategy for generating method IDs
     * @param samplingSize Optional sampling size (null = no sampling)
     * @param outputDirectory Directory for methods.csv output
     */
    public MethodIdentifier(ProjectMetadata projectMetadata, IdStrategy idStrategy, 
                           Integer samplingSize, Path outputDirectory) {
        this.projectMetadata = Objects.requireNonNull(projectMetadata, "projectMetadata cannot be null");
        this.idStrategy = Objects.requireNonNull(idStrategy, "idStrategy cannot be null");
        this.samplingSize = samplingSize;
        this.outputDirectory = Objects.requireNonNull(outputDirectory, "outputDirectory cannot be null");
        this.sourceBackend = SourceBackends.spoon();
    }

    /**
     * Scan all Java files and identify methods
     * @return List of MethodInfo objects
     * @throws IOException if scanning fails
     */
    public List<MethodInfo> identify() throws IOException {
        ProjectModel project = ProjectModel.from(projectMetadata);
        List<MethodInfo> methods = new ArrayList<>(sourceBackend.findMethods(project).stream()
                .map(MethodIdentifier::toMethodInfo)
                .toList());

        // Apply sampling if specified
        if (samplingSize != null && samplingSize > 0 && methods.size() > samplingSize) {
            methods = applySampling(methods);
        }

        return methods;
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
                .build();
    }

    /**
     * Apply random sampling to the method list
     */
    private List<MethodInfo> applySampling(List<MethodInfo> methods) {
        Collections.shuffle(methods, new Random());
        return methods.subList(0, Math.min(samplingSize, methods.size()));
    }

    /**
     * Write methods to CSV file
     * @param methods List of methods to write
     * @param csvPath Path to output CSV file
     * @throws IOException if write fails
     */
    public void writeCsv(List<MethodInfo> methods, Path csvPath) throws IOException {
        Files.createDirectories(csvPath.getParent());

        try (PrintWriter writer = new PrintWriter(
                new FileWriter(csvPath.toFile(), StandardCharsets.UTF_8))) {

            // Write header
            writer.println("method_uri|classname|methodname|filepath|line_number|signature");

            // Write method rows
            for (MethodInfo method : methods) {
                writer.println(method.toCsvRow());
            }
        }
    }

    /**
     * Identify methods and write to CSV file
     * @param csvPath Path for output CSV file
     * @return List of MethodInfo objects
     * @throws IOException if identification or writing fails
     */
    public List<MethodInfo> identifyAndWriteCsv(Path csvPath) throws IOException {
        List<MethodInfo> methods = identify();
        writeCsv(methods, csvPath);
        return methods;
    }

    /**
     * Get the output CSV path based on output directory
     */
    public Path getDefaultCsvPath() {
        return outputDirectory.resolve("methods.csv");
    }
}
