package org.assertlab.cocomut.source;

import org.assertlab.cocomut.ContextRequest;

public final class SourceBackends {
    private static final SourceModelBackend SPOON = new SpoonSourceModelBackend();
    private static final ThreadLocal<Integer> MAX_SOURCE_FILES = new ThreadLocal<>();
    private static final ThreadLocal<ContextRequest.JavadocInheritancePolicy> JAVADOC_POLICY =
            ThreadLocal.withInitial(() -> ContextRequest.JavadocInheritancePolicy.JDK25_STANDARD_DOCLET);
    private static final ThreadLocal<Boolean> JAVADOC_POLICY_DEFAULTED =
            ThreadLocal.withInitial(() -> true);
    private static final java.util.concurrent.atomic.AtomicInteger PARSE_COUNT = new java.util.concurrent.atomic.AtomicInteger();

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

    public static void setJavadocInheritancePolicy(ContextRequest.JavadocInheritancePolicy policy,
                                                    boolean defaulted) {
        JAVADOC_POLICY.set(policy == null
                ? ContextRequest.JavadocInheritancePolicy.JDK25_STANDARD_DOCLET
                : policy);
        JAVADOC_POLICY_DEFAULTED.set(defaulted);
    }

    public static ContextRequest.JavadocInheritancePolicy javadocInheritancePolicy() {
        return JAVADOC_POLICY.get();
    }

    public static boolean javadocInheritancePolicyDefaulted() {
        return JAVADOC_POLICY_DEFAULTED.get();
    }

    public static void clearConfiguration() {
        MAX_SOURCE_FILES.remove();
        JAVADOC_POLICY.remove();
        JAVADOC_POLICY_DEFAULTED.remove();
    }

    static void recordParse() {
        PARSE_COUNT.incrementAndGet();
    }

    public static int parseCount() {
        return PARSE_COUNT.get();
    }

    public static void resetParseCount() {
        PARSE_COUNT.set(0);
    }
}
