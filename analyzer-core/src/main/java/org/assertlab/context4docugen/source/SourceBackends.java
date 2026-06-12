package org.assertlab.context4docugen.source;

public final class SourceBackends {
    private static final SourceModelBackend SPOON = new SpoonSourceModelBackend();

    private SourceBackends() {
    }

    public static SourceModelBackend spoon() {
        return SPOON;
    }
}
