package org.assertlab.cocomut;

import org.assertlab.cocomut.cli.CoCoMUTCommand;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import picocli.CommandLine;

import static org.junit.Assert.fail;

@Category(FastTests.class)
public class CliTerminologyTest {

    @Test
    public void typeSelectionOptionsAreAccepted() {
        new CommandLine(new CoCoMUTCommand()).parseArgs(
                "--project", ".",
                "--type", "demo.PublicApi",
                "--type-uri", "src/main/java/demo/PublicApi.java#demo.PublicApi");
    }

    @Test
    public void removedClassSelectionOptionsAreRejected() {
        assertUnknownOption("--class", "demo.PublicApi");
        assertUnknownOption("--class-uri", "src/main/java/demo/PublicApi.java#demo.PublicApi");
    }

    private static void assertUnknownOption(String option, String value) {
        try {
            new CommandLine(new CoCoMUTCommand()).parseArgs(
                    "--project", ".", option, value);
            fail("Expected removed option to be rejected: " + option);
        } catch (CommandLine.ParameterException expected) {
            // Expected: source selection uses type terminology only.
        }
    }
}
