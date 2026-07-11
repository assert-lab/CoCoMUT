package org.assertlab.cocomut.source;

import static org.junit.Assert.assertEquals;

import java.util.List;
import org.junit.Test;

public class SpoonSourceModelBackendTest {

    @Test
    public void reportsTheEffectiveModeAcrossParsedModels() {
        assertEquals("classpath", SpoonSourceModelBackend.mergedMode(List.of("classpath")));
        assertEquals("no_classpath", SpoonSourceModelBackend.mergedMode(List.of("no_classpath")));
        assertEquals("mixed", SpoonSourceModelBackend.mergedMode(List.of("classpath", "no_classpath")));
    }
}
