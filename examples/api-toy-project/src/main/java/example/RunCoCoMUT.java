package example;

import org.assertlab.cocomut.ContextExtractorService;
import org.assertlab.cocomut.ContextRequest;
import org.assertlab.cocomut.ExtractionReport;

import java.nio.file.Path;

public class RunCoCoMUT {
    public static void main(String[] args) throws Exception {
        Path project = args.length > 0
                ? Path.of(args[0]).toAbsolutePath().normalize()
                : Path.of("../../analyzer-tests/src/test/resources/fixtures/minimal-maven-project")
                        .toAbsolutePath()
                        .normalize();

        ContextRequest request = ContextRequest.builder()
                .projectRoot(project)
                .scope(ContextRequest.Scope.ENTRY_POINTS)
                // This toy analyzes a trusted local fixture. For untrusted
                // repositories, keep the default denied-build policy and pass
                // explicit --class-output / --project-jar artifacts instead.
                .allowUnsandboxedBuild()
                .build();

        ExtractionReport report = ContextExtractorService.createDefault().extract(request);
        report.asMap().forEach((key, value) -> System.out.printf("%s=%s%n", key, value));
    }
}
