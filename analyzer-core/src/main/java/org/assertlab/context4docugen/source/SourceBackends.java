package org.assertlab.context4docugen.source;

import org.assertlab.context4docugen.AnalysisOptions;

public final class SourceBackends {
    private static final ThreadLocal<AnalysisOptions.SourceResolution> RESOLUTION =
            ThreadLocal.withInitial(() -> AnalysisOptions.SourceResolution.NOCLASSPATH);
    private static final SourceModelBackend SPOON_NOCLASSPATH =
            new SpoonSourceModelBackend(AnalysisOptions.SourceResolution.NOCLASSPATH);
    private static final SourceModelBackend SPOON_CLASSPATH =
            new SpoonSourceModelBackend(AnalysisOptions.SourceResolution.CLASSPATH);
    private static final SourceModelBackend SPOON_AUTO =
            new SpoonSourceModelBackend(AnalysisOptions.SourceResolution.AUTO);

    private SourceBackends() {
    }

    public static void configure(AnalysisOptions.SourceResolution resolution) {
        RESOLUTION.set(resolution != null ? resolution : AnalysisOptions.SourceResolution.NOCLASSPATH);
    }

    public static void clearConfiguration() {
        RESOLUTION.remove();
    }

    public static SourceModelBackend spoon() {
        return spoon(RESOLUTION.get());
    }

    public static SourceModelBackend spoon(AnalysisOptions.SourceResolution resolution) {
        return switch (resolution) {
            case CLASSPATH -> SPOON_CLASSPATH;
            case AUTO -> SPOON_AUTO;
            case NOCLASSPATH -> SPOON_NOCLASSPATH;
        };
    }
}
