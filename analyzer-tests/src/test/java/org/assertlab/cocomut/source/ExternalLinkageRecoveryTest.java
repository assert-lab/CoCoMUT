package org.assertlab.cocomut.source;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class ExternalLinkageRecoveryTest {
    @Test
    public void enrichmentRecordsLinkageFailureButPropagatesResourceCauses() {
        var diagnostics = new ArrayList<EnrichmentDiagnostic>();
        assertEquals("unavailable", SpoonSourceModelBackend.enrich("javadoc_references", diagnostics,
                () -> { throw new NoClassDefFoundError("dependency/Missing"); }, "unavailable"));
        assertEquals("java.lang.NoClassDefFoundError", diagnostics.get(0).exceptionClass());
        for (Error resource : List.of(new OutOfMemoryError("test"), new StackOverflowError("test"))) {
            for (boolean wrapped : List.of(false, true)) {
                diagnostics.clear();
                try {
                    SpoonSourceModelBackend.enrich("javadoc_references", diagnostics, () -> {
                        if (wrapped) {
                            var failure = new NoClassDefFoundError("wrapper");
                            failure.initCause(resource);
                            throw failure;
                        }
                        throw resource;
                    }, "unavailable");
                    fail("Resource failures must remain terminal");
                } catch (Error failure) {
                    assertSame(resource, failure);
                    assertTrue(diagnostics.isEmpty());
                }
            }
        }
    }
}
