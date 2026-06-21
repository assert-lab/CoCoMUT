package org.assertlab.cocomut;

import java.util.*;

/**
 * Represents the call graph results for a single method.
 * Contains callers (methods that call this method) and callees (methods this method calls).
 */
public class CallGraphResult {
    private final String methodUri;
    private final String methodName;
    private final String classname;
    private final Set<CallGraphEdge> callers;    // Methods that call this method
    private final Set<CallGraphEdge> callees;    // Methods this method calls
    private final String algorithm;        // CHA, RTA, etc.
    private final long generationTime;

    private CallGraphResult(Builder builder) {
        this.methodUri = Objects.requireNonNull(builder.methodUri, "methodUri cannot be null");
        this.methodName = Objects.requireNonNull(builder.methodName, "methodName cannot be null");
        this.classname = Objects.requireNonNull(builder.classname, "classname cannot be null");
        this.callers = Collections.unmodifiableSet(new HashSet<>(builder.callers));
        this.callees = Collections.unmodifiableSet(new HashSet<>(builder.callees));
        this.algorithm = Objects.requireNonNull(builder.algorithm, "algorithm cannot be null");
        this.generationTime = builder.generationTime;
    }

    // Getters
    public String getMethodUri() {
        return methodUri;
    }

    public String getMethodName() {
        return methodName;
    }

    public String getClassname() {
        return classname;
    }

    public Set<CallGraphEdge> getCallers() {
        return callers;
    }

    public Set<CallGraphEdge> getCallees() {
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
                "methodUri='" + methodUri + '\'' +
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
        private String methodUri;
        private String methodName;
        private String classname;
        private Set<CallGraphEdge> callers = new HashSet<>();
        private Set<CallGraphEdge> callees = new HashSet<>();
        private String algorithm = "CHA";
        private long generationTime = 0;

        public Builder methodUri(String methodUri) {
            this.methodUri = methodUri;
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

        public Builder callers(Set<CallGraphEdge> callers) {
            this.callers = new HashSet<>(callers);
            return this;
        }

        public Builder addCaller(CallGraphEdge caller) {
            this.callers.add(caller);
            return this;
        }

        public Builder callees(Set<CallGraphEdge> callees) {
            this.callees = new HashSet<>(callees);
            return this;
        }

        public Builder addCallee(CallGraphEdge callee) {
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
