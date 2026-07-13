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
    BUILD_FAILED_BUILD_TASK_UNAVAILABLE,
    BUILD_FAILED_REQUIRED_TOOL_UNAVAILABLE,
    BUILD_FAILED_VCS_HISTORY_UNAVAILABLE,
    BUILD_FAILED_PROJECT_COMPILATION_ERROR,
    BUILD_FAILED_NETWORK_FAILURE,
    BUILD_FAILED_TIMEOUT,
    BUILD_FAILED_INTERRUPTED,
    BUILD_FAILED_UNKNOWN_ERROR;

    public static BuildFailureReason classify(String output, boolean timedOut, boolean succeeded) {
        if (succeeded) return NONE;
        if (timedOut) return BUILD_FAILED_TIMEOUT;
        String text = output == null ? "" : output.toLowerCase(Locale.ROOT);
        if (text.contains("build interrupted by caller")) return BUILD_FAILED_INTERRUPTED;
        if (containsAny(text, "401 unauthorized", "403 forbidden", "authentication failed", "not authorized",
                "host key verification failed", "could not read from remote repository"))
            return BUILD_FAILED_AUTHENTICATION_REQUIRED;
        if (text.contains("credentials")
                && java.util.regex.Pattern.compile(
                        "unknown property ['\"][^'\"]*(?:user(?:name)?|password|token|secret|key)[^'\"]*['\"]")
                        .matcher(text).find())
            return BUILD_FAILED_AUTHENTICATION_REQUIRED;
        if (isTransientNetworkFailure(output)) return BUILD_FAILED_NETWORK_FAILURE;
        if (containsAny(text, "android sdk", "sdk location not found", "failed to find target with hash string 'android-",
                "compile sdk version is not specified")) return BUILD_FAILED_ANDROID_SDK_UNAVAILABLE;
        if (containsAny(text, "toolchain", "no matching toolchains found", "cannot find matching toolchain"))
            return BUILD_FAILED_TOOLCHAIN_UNAVAILABLE;
        if (containsAny(text, "invalid target release", "invalid source release", "release version",
                "unsupported class file major version",
                "source option", "target option", "requires java", "jdk version",
                "requires at least jvm runtime version", "run this build using a java",
                "built with java"))
            return BUILD_FAILED_JDK_UNAVAILABLE;
        if (text.contains("compiled by a more recent version of the java runtime")
                && text.contains("class file version"))
            return BUILD_FAILED_JDK_UNAVAILABLE;
        if (text.contains("class file has wrong version"))
            return BUILD_FAILED_JDK_UNAVAILABLE;
        if (containsAny(text, "unrecognized option: --add-opens", "unrecognized option: --add-exports"))
            return BUILD_FAILED_JDK_UNAVAILABLE;
        if (text.contains("jdk ") && text.contains(" is required to build"))
            return BUILD_FAILED_JDK_UNAVAILABLE;
        if (text.contains("requirejavavendor")
                || (text.contains("requires ") && text.contains(" jdk for development"))
                || (text.contains("jdk vendor") && text.contains("required")))
            return BUILD_FAILED_JDK_UNAVAILABLE;
        if (java.util.regex.Pattern.compile("java\\s+(?:1\\.)?\\d+\\s+is required")
                .matcher(text).find())
            return BUILD_FAILED_JDK_UNAVAILABLE;
        if (java.util.regex.Pattern.compile("jdk\\s*\\d+\\s*(?:\\([^)]*\\)\\s*)?is required")
                .matcher(text).find())
            return BUILD_FAILED_JDK_UNAVAILABLE;
        if (java.util.regex.Pattern.compile("build requires jdk\\s*\\d+\\s+or\\s+later")
                .matcher(text).find())
            return BUILD_FAILED_JDK_UNAVAILABLE;
        if (java.util.regex.Pattern.compile("only builds on jdk\\s*\\d+\\s+or higher")
                .matcher(text).find())
            return BUILD_FAILED_JDK_UNAVAILABLE;
        if (containsAny(text, "could not find artifact") && text.contains("snapshot"))
            return BUILD_FAILED_REACTOR_ARTIFACT_MISSING;
        if (text.contains("artifact has not been packaged yet")
                && text.contains("when used on reactor artifact"))
            return BUILD_FAILED_REACTOR_ARTIFACT_MISSING;
        if (containsAny(text, "could not resolve dependencies", "could not resolve all dependencies",
                "could not resolve all artifacts", "could not determine the dependencies",
                "could not find artifact", "could not resolve all files"))
            return BUILD_FAILED_DEPENDENCY_UNAVAILABLE;
        if (text.contains("could not find ")
                && text.contains("searched in the following locations:")
                && text.contains("required by:"))
            return BUILD_FAILED_DEPENDENCY_UNAVAILABLE;
        if (containsAny(text, "maven-default-http-blocker", "blocked mirror for repositories"))
            return BUILD_FAILED_DEPENDENCY_UNAVAILABLE;
        if (text.contains("unable to find the local maven repo"))
            return BUILD_FAILED_DEPENDENCY_UNAVAILABLE;
        if (text.contains("cannot run program")
                && containsAny(text, "no such file or directory", "error=2"))
            return BUILD_FAILED_REQUIRED_TOOL_UNAVAILABLE;
        if (text.contains("problem occurred starting process 'command '"))
            return BUILD_FAILED_REQUIRED_TOOL_UNAVAILABLE;
        if (text.contains(": command not found"))
            return BUILD_FAILED_REQUIRED_TOOL_UNAVAILABLE;
        if (containsAny(text, "unable to find commits until some tag", "walk failure. missing commit",
                "shallow update not allowed", "shallow repository"))
            return BUILD_FAILED_VCS_HISTORY_UNAVAILABLE;
        if (text.contains("parsing head commit") && text.contains("missing commit"))
            return BUILD_FAILED_VCS_HISTORY_UNAVAILABLE;
        if (text.contains("no plugin descriptor found at meta-inf/maven/plugin.xml"))
            return BUILD_FAILED_REACTOR_ARTIFACT_MISSING;
        if (text.contains("failed to create enforcer rules"))
            return BUILD_FAILED_PLUGIN_INCOMPATIBLE;
        if (containsAny(text, "pluginresolutionexception", "could not find goal", "failed to apply plugin",
                "plugin with id") || (text.contains("plugin") && text.contains("incompatible")))
            return BUILD_FAILED_PLUGIN_INCOMPATIBLE;
        if (containsAny(text, "task 'classes' not found", "task 'testclasses' not found"))
            return BUILD_FAILED_BUILD_TASK_UNAVAILABLE;
        if (text.contains("spotless") && text.contains("limits you to google-java-format"))
            return BUILD_FAILED_PLUGIN_INCOMPATIBLE;
        if (text.contains("provider.foruseatconfigurationtime()"))
            return BUILD_FAILED_PLUGIN_INCOMPATIBLE;
        if (text.contains("unknown property 'sourcecompatibility'")
                || text.contains("unknown property \"sourcecompatibility\""))
            return BUILD_FAILED_PLUGIN_INCOMPATIBLE;
        if (containsAny(text, "compilation failure", "compilation error", "cannot find symbol", "does not exist",
                "should be declared in a file named")) return BUILD_FAILED_PROJECT_COMPILATION_ERROR;
        return BUILD_FAILED_UNKNOWN_ERROR;
    }

    public static boolean isTransientNetworkFailure(String output) {
        String text = output == null ? "" : output.toLowerCase(Locale.ROOT);
        return containsAny(text, "connection reset", "connection timed out", "read timed out",
                "temporary failure in name resolution", "no address associated with hostname", "unknown host",
                "status code 429",
                "status code 500", "status code 502", "status code 503", "status code 504",
                "remote host terminated the handshake");
    }

    private static boolean containsAny(String text, String... needles) {
        for (String needle : needles) if (text.contains(needle)) return true;
        return false;
    }
}
