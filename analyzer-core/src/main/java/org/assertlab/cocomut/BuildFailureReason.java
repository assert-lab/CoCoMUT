package org.assertlab.cocomut;

import java.util.Locale;

/** Stable second-level reason for an unsuccessful repository build. */
public enum BuildFailureReason {
    NONE,
    BUILD_FAILED_JDK_UNAVAILABLE,
    BUILD_FAILED_TOOLCHAIN_UNAVAILABLE,
    BUILD_FAILED_ANDROID_SDK_UNAVAILABLE,
    BUILD_FAILED_REACTOR_ARTIFACT_MISSING,
    BUILD_FAILED_DEPENDENCY_UNAVAILABLE,
    BUILD_FAILED_AUTHENTICATION_REQUIRED,
    BUILD_FAILED_PLUGIN_INCOMPATIBLE,
    BUILD_FAILED_PROJECT_COMPILATION_ERROR,
    BUILD_FAILED_NETWORK_FAILURE,
    BUILD_FAILED_TIMEOUT,
    BUILD_FAILED_UNKNOWN_ERROR;

    public static BuildFailureReason classify(String output, boolean timedOut, boolean succeeded) {
        if (succeeded) return NONE;
        if (timedOut) return BUILD_FAILED_TIMEOUT;
        String text = output == null ? "" : output.toLowerCase(Locale.ROOT);
        if (containsAny(text, "401 unauthorized", "403 forbidden", "authentication failed", "not authorized"))
            return BUILD_FAILED_AUTHENTICATION_REQUIRED;
        if (isTransientNetworkFailure(output)) return BUILD_FAILED_NETWORK_FAILURE;
        if (containsAny(text, "android sdk", "sdk location not found", "failed to find target with hash string 'android-",
                "compile sdk version is not specified")) return BUILD_FAILED_ANDROID_SDK_UNAVAILABLE;
        if (containsAny(text, "toolchain", "no matching toolchains found", "cannot find matching toolchain"))
            return BUILD_FAILED_TOOLCHAIN_UNAVAILABLE;
        if (containsAny(text, "invalid target release", "release version", "unsupported class file major version",
                "source option", "target option", "requires java", "jdk version")) return BUILD_FAILED_JDK_UNAVAILABLE;
        if (containsAny(text, "could not find artifact") && text.contains("snapshot"))
            return BUILD_FAILED_REACTOR_ARTIFACT_MISSING;
        if (containsAny(text, "could not resolve dependencies", "could not find artifact", "could not resolve all files"))
            return BUILD_FAILED_DEPENDENCY_UNAVAILABLE;
        if (containsAny(text, "pluginresolutionexception", "could not find goal", "failed to apply plugin",
                "plugin with id") || (text.contains("plugin") && text.contains("incompatible")))
            return BUILD_FAILED_PLUGIN_INCOMPATIBLE;
        if (containsAny(text, "compilation failure", "compilation error", "cannot find symbol", "does not exist",
                "should be declared in a file named")) return BUILD_FAILED_PROJECT_COMPILATION_ERROR;
        return BUILD_FAILED_UNKNOWN_ERROR;
    }

    public static boolean isTransientNetworkFailure(String output) {
        String text = output == null ? "" : output.toLowerCase(Locale.ROOT);
        return containsAny(text, "connection reset", "connection timed out", "read timed out",
                "temporary failure in name resolution", "unknown host", "status code 429",
                "status code 500", "status code 502", "status code 503", "status code 504",
                "remote host terminated the handshake");
    }

    private static boolean containsAny(String text, String... needles) {
        for (String needle : needles) if (text.contains(needle)) return true;
        return false;
    }
}
