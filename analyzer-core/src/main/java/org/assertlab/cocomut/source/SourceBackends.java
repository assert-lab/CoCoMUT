package org.assertlab.cocomut.source;

import org.assertlab.cocomut.ContextRequest;

public final class SourceBackends {
    private static final ThreadLocal<ContextRequest.SourceResolution> RESOLUTION =
            ThreadLocal.withInitial(() -> ContextRequest.SourceResolution.NOCLASSPATH);
    private static final SourceModelBackend SPOON_NOCLASSPATH =
            new SpoonSourceModelBackend(ContextRequest.SourceResolution.NOCLASSPATH);
    private static final SourceModelBackend SPOON_CLASSPATH =
            new SpoonSourceModelBackend(ContextRequest.SourceResolution.CLASSPATH);
    private static final SourceModelBackend SPOON_AUTO =
            new SpoonSourceModelBackend(ContextRequest.SourceResolution.AUTO);

    private SourceBackends() {
    }

    public static void configure(ContextRequest.SourceResolution resolution) {
        RESOLUTION.set(resolution != null ? resolution : ContextRequest.SourceResolution.NOCLASSPATH);
    }

    public static void clearConfiguration() {
        RESOLUTION.remove();
    }

    public static SourceModelBackend spoon() {
        return spoon(RESOLUTION.get());
    }

    public static SourceModelBackend spoon(ContextRequest.SourceResolution resolution) {
        return switch (resolution) {
            case CLASSPATH -> SPOON_CLASSPATH;
            case AUTO -> SPOON_AUTO;
            case NOCLASSPATH -> SPOON_NOCLASSPATH;
        };
    }
}
