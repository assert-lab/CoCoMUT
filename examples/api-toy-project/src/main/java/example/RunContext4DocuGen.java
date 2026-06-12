package example;

import org.assertlab.context4docugen.AnalysisOptions;
import org.assertlab.context4docugen.CallGraphGenerator;
import org.assertlab.context4docugen.ContextExtractorService;
import org.assertlab.context4docugen.ContextRequest;
import org.assertlab.context4docugen.ExtractionReport;
import org.assertlab.context4docugen.MethodSelection;

import java.nio.file.Path;

public class RunContext4DocuGen {
    public static void main(String[] args) throws Exception {
        Path project = args.length > 0
                ? Path.of(args[0]).toAbsolutePath().normalize()
                : Path.of("../../analyzer-tests/src/test/resources/fixtures/minimal-maven-project")
                        .toAbsolutePath()
                        .normalize();

        ContextRequest request = ContextRequest.builder()
                .projectRoot(project)
                .methodSelection(MethodSelection.entryPoints())
                .callGraphAlgorithm(CallGraphGenerator.Algorithm.NONE)
                .outputMode(AnalysisOptions.OutputMode.JSONL)
                .build();

        ExtractionReport report = ContextExtractorService.createDefault().extract(request);
        report.asMap().forEach((key, value) -> System.out.printf("%s=%s%n", key, value));
    }
}
