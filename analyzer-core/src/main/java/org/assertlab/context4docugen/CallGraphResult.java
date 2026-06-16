package org.assertlab.context4docugen;

import java.util.*;

/**
 * Represents the call graph results for a single method.
 * Contains callers (methods that call this method) and callees (methods this method calls).
 */
public class CallGraphResult {
    private final String methodId;
    private final String methodName;
    private final String classname;
    private final Set<String> callers;    // Methods that call this method
    private final Set<String> callees;    // Methods this method calls
    private final String algorithm;        // CHA, RTA, etc.
    private final long generationTime;

    private CallGraphResult(Builder builder) {
        this.methodId = Objects.requireNonNull(builder.methodId, "methodId cannot be null");
        this.methodName = Objects.requireNonNull(builder.methodName, "methodName cannot be null");
        this.classname = Objects.requireNonNull(builder.classname, "classname cannot be null");
        this.callers = Collections.unmodifiableSet(new HashSet<>(builder.callers));
        this.callees = Collections.unmodifiableSet(new HashSet<>(builder.callees));
        this.algorithm = Objects.requireNonNull(builder.algorithm, "algorithm cannot be null");
        this.generationTime = builder.generationTime;
    }

    // Getters
    public String getMethodId() {
        return methodId;
    }

    public String getMethodName() {
        return methodName;
    }

    public String getClassname() {
        return classname;
    }

    public Set<String> getCallers() {
        return callers;
    }

    public Set<String> getCallees() {
        return callees;
    }

    public String getAlgorithm() {
        return algorithm;
    }

    public long getGenerationTime() {
        return generationTime;
    }

    public int getCallerCount() {
        return callers.size();
    }

    public int getCalleeCount() {
        return callees.size();
    }

    @Override
    public String toString() {
        return "CallGraphResult{" +
                "methodId='" + methodId + '\'' +
                ", methodName='" + methodName + '\'' +
                ", classname='" + classname + '\'' +
                ", callerCount=" + callers.size() +
                ", calleeCount=" + callees.size() +
                ", algorithm='" + algorithm + '\'' +
                '}';
    }

    /**
     * Builder for CallGraphResult
     */
    public static class Builder {
        private String methodId;
        private String methodName;
        private String classname;
        private Set<String> callers = new HashSet<>();
        private Set<String> callees = new HashSet<>();
        private String algorithm = "CHA";
        private long generationTime = 0;

        public Builder methodId(String methodId) {
            this.methodId = methodId;
            return this;
        }

        public Builder methodName(String methodName) {
            this.methodName = methodName;
            return this;
        }

        public Builder classname(String classname) {
            this.classname = classname;
            return this;
        }

        public Builder callers(Set<String> callers) {
            this.callers = new HashSet<>(callers);
            return this;
        }

        public Builder addCaller(String caller) {
            this.callers.add(caller);
            return this;
        }

        public Builder callees(Set<String> callees) {
            this.callees = new HashSet<>(callees);
            return this;
        }

        public Builder addCallee(String callee) {
            this.callees.add(callee);
            return this;
        }

        public Builder algorithm(String algorithm) {
            this.algorithm = algorithm;
            return this;
        }

        public Builder generationTime(long generationTime) {
            this.generationTime = generationTime;
            return this;
        }

        public CallGraphResult build() {
            return new CallGraphResult(this);
        }
    }
}
