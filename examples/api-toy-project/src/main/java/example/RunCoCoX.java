package example;

import org.assertlab.cocox.AnalysisOptions;
import org.assertlab.cocox.CallGraphGenerator;
import org.assertlab.cocox.ContextExtractorService;
import org.assertlab.cocox.ContextRequest;
import org.assertlab.cocox.ExtractionReport;

import java.nio.file.Path;

public class RunCoCoX {
    public static void main(String[] args) throws Exception {
        Path project = args.length > 0
                ? Path.of(args[0]).toAbsolutePath().normalize()
                : Path.of("../../analyzer-tests/src/test/resources/fixtures/minimal-maven-project")
                        .toAbsolutePath()
                        .normalize();

        ContextRequest request = ContextRequest.builder()
                .projectRoot(project)
                .scope(AnalysisOptions.Scope.ENTRY_POINTS)
                .callGraphAlgorithm(CallGraphGenerator.Algorithm.NONE)
                .outputMode(AnalysisOptions.OutputMode.JSONL)
                .build();

        ExtractionReport report = ContextExtractorService.createDefault().extract(request);
        report.asMap().forEach((key, value) -> System.out.printf("%s=%s%n", key, value));
    }
}
