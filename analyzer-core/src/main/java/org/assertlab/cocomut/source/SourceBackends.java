package org.assertlab.cocomut.source;

public final class SourceBackends {
    private static final SourceModelBackend SPOON = new SpoonSourceModelBackend();
    private static final ThreadLocal<Integer> MAX_SOURCE_FILES = new ThreadLocal<>();

    private SourceBackends() {
    }

    public static SourceModelBackend spoon() {
        return SPOON;
    }

    public static void setMaxSourceFiles(Integer maxSourceFiles) {
        if (maxSourceFiles == null || maxSourceFiles <= 0) {
            MAX_SOURCE_FILES.remove();
        } else {
            MAX_SOURCE_FILES.set(maxSourceFiles);
        }
    }

    public static Integer maxSourceFiles() {
        return MAX_SOURCE_FILES.get();
    }

    public static void clearConfiguration() {
        MAX_SOURCE_FILES.remove();
    }
}
