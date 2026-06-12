package org.assertlab.context4docugen;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Represents the complete context for a single method.
 * Contains method body, javadocs, call graph, and class hierarchy information.
 */
public class MethodContext {
    private final String methodId;
    private final String methodName;
    private final String classname;
    private final String signature;           // Formatted: org.example.Class.foo(String, int)
    private final int lineNumber;             // Source line number (1-indexed)
    private final List<String> parameters;    // Parameter types: ["String", "int"]
    private final List<Map<String, Object>> parameterDetails;
    private final String methodBody;          // Full method source code
    private final String javadoc;             // JavaDoc comment
    private final String classJavadoc;        // Class-level JavaDoc
    private final String classHierarchy;      // Class inheritance chain
    private final Map<String, String> classMethods;  // Other methods in class
    private final CallGraphResult callGraph;  // Call graph from Phase 3
    private final int linesOfCode;
    private final int cyclomatic;             // McCabe complexity
    private final String originalDocstring;   // human-written docstring from inputs_selected.csv
    private final String testPrefix;          // test code from inputs_selected.csv
    private final List<String> annotations;
    private final List<String> thrownExceptions;
    private final List<String> fieldReads;
    private final List<String> fieldWrites;
    private final List<String> siblingMethods;
    private final List<String> overloadGroup;
    private final List<String> dynamicFeatures;
    private final Map<String, Object> javadocMetadata;
    private final Map<String, Object> documentationMetrics;
    private final String sourceBackend;
    private final String sourceBackendMode;
    private final String hierarchyResolution;
    private final String sourceSet;

    private MethodContext(Builder builder) {
        this.methodId = Objects.requireNonNull(builder.methodId, "methodId cannot be null");
        this.methodName = Objects.requireNonNull(builder.methodName, "methodName cannot be null");
        this.classname = Objects.requireNonNull(builder.classname, "classname cannot be null");
        this.signature = builder.signature != null ? builder.signature : "";
        this.lineNumber = builder.lineNumber;
        this.parameters = Collections.unmodifiableList(new ArrayList<>(builder.parameters));
        this.parameterDetails = Collections.unmodifiableList(new ArrayList<>(builder.parameterDetails));
        this.methodBody = builder.methodBody != null ? builder.methodBody : "";
        this.javadoc = builder.javadoc != null ? builder.javadoc : "";
        this.classJavadoc = builder.classJavadoc != null ? builder.classJavadoc : "";
        this.classHierarchy = builder.classHierarchy != null ? builder.classHierarchy : "";
        this.classMethods = Collections.unmodifiableMap(new HashMap<>(builder.classMethods));
        this.callGraph = builder.callGraph;
        this.linesOfCode = builder.linesOfCode;
        this.cyclomatic = builder.cyclomatic;
        this.originalDocstring = builder.originalDocstring != null ? builder.originalDocstring : "";
        this.testPrefix = builder.testPrefix != null ? builder.testPrefix : "";
        this.annotations = Collections.unmodifiableList(new ArrayList<>(builder.annotations));
        this.thrownExceptions = Collections.unmodifiableList(new ArrayList<>(builder.thrownExceptions));
        this.fieldReads = Collections.unmodifiableList(new ArrayList<>(builder.fieldReads));
        this.fieldWrites = Collections.unmodifiableList(new ArrayList<>(builder.fieldWrites));
        this.siblingMethods = Collections.unmodifiableList(new ArrayList<>(builder.siblingMethods));
        this.overloadGroup = Collections.unmodifiableList(new ArrayList<>(builder.overloadGroup));
        this.dynamicFeatures = Collections.unmodifiableList(new ArrayList<>(builder.dynamicFeatures));
        this.javadocMetadata = Collections.unmodifiableMap(new HashMap<>(builder.javadocMetadata));
        this.documentationMetrics = Collections.unmodifiableMap(new HashMap<>(builder.documentationMetrics));
        this.sourceBackend = builder.sourceBackend != null ? builder.sourceBackend : "";
        this.sourceBackendMode = builder.sourceBackendMode != null ? builder.sourceBackendMode : "";
        this.hierarchyResolution = builder.hierarchyResolution != null ? builder.hierarchyResolution : "";
        this.sourceSet = builder.sourceSet != null ? builder.sourceSet : "unknown";
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

    public String getSignature() {
        return signature;
    }

    public int getLineNumber() {
        return lineNumber;
    }

    public List<String> getParameters() {
        return parameters;
    }

    public List<Map<String, Object>> getParameterDetails() {
        return parameterDetails;
    }

    public String getMethodBody() {
        return methodBody;
    }

    public String getJavadoc() {
        return javadoc;
    }

    public String getClassJavadoc() {
        return classJavadoc;
    }

    public String getClassHierarchy() {
        return classHierarchy;
    }

    public Map<String, String> getClassMethods() {
        return classMethods;
    }

    public CallGraphResult getCallGraph() {
        return callGraph;
    }

    public int getLinesOfCode() {
        return linesOfCode;
    }

    public int getCyclomatic() {
        return cyclomatic;
    }

    public String getOriginalDocstring() {
        return originalDocstring;
    }

    public String getTestPrefix() {
        return testPrefix;
    }

    public List<String> getAnnotations() {
        return annotations;
    }

    public List<String> getThrownExceptions() {
        return thrownExceptions;
    }

    public List<String> getFieldReads() {
        return fieldReads;
    }

    public List<String> getFieldWrites() {
        return fieldWrites;
    }

    public List<String> getSiblingMethods() {
        return siblingMethods;
    }

    public List<String> getOverloadGroup() {
        return overloadGroup;
    }

    public List<String> getDynamicFeatures() {
        return dynamicFeatures;
    }

    public Map<String, Object> getJavadocMetadata() {
        return javadocMetadata;
    }

    public Map<String, Object> getDocumentationMetrics() {
        return documentationMetrics;
    }

    public String getSourceBackend() {
        return sourceBackend;
    }

    public String getSourceBackendMode() {
        return sourceBackendMode;
    }

    public String getHierarchyResolution() {
        return hierarchyResolution;
    }

    public String getSourceSet() {
        return sourceSet;
    }

    public boolean hasJavadoc() {
        return javadoc != null && !javadoc.isEmpty();
    }

    public boolean hasClassJavadoc() {
        return classJavadoc != null && !classJavadoc.isEmpty();
    }

    @Override
    public String toString() {
        return "MethodContext{" +
                "methodId='" + methodId + '\'' +
                ", methodName='" + methodName + '\'' +
                ", classname='" + classname + '\'' +
                ", lineNumber=" + lineNumber +
                ", linesOfCode=" + linesOfCode +
                ", cyclomatic=" + cyclomatic +
                ", hasJavadoc=" + hasJavadoc() +
                ", callerCount=" + (callGraph != null ? callGraph.getCallerCount() : 0) +
                ", calleeCount=" + (callGraph != null ? callGraph.getCalleeCount() : 0) +
                '}';
    }

    /**
     * Builder for MethodContext
     */
    public static class Builder {
        private String methodId;
        private String methodName;
        private String classname;
        private String signature = "";
        private int lineNumber = -1;
        private List<String> parameters = List.of();
        private List<Map<String, Object>> parameterDetails = List.of();
        private String methodBody;
        private String javadoc;
        private String classJavadoc = "";
        private String classHierarchy;
        private Map<String, String> classMethods = new HashMap<>();
        private CallGraphResult callGraph;
        private int linesOfCode = 0;
        private int cyclomatic = 1;
        private String originalDocstring = "";
        private String testPrefix = "";
        private List<String> annotations = List.of();
        private List<String> thrownExceptions = List.of();
        private List<String> fieldReads = List.of();
        private List<String> fieldWrites = List.of();
        private List<String> siblingMethods = List.of();
        private List<String> overloadGroup = List.of();
        private List<String> dynamicFeatures = List.of();
        private Map<String, Object> javadocMetadata = Map.of();
        private Map<String, Object> documentationMetrics = Map.of();
        private String sourceBackend = "";
        private String sourceBackendMode = "";
        private String hierarchyResolution = "";
        private String sourceSet = "unknown";

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

        public Builder signature(String signature) {
            this.signature = signature;
            return this;
        }

        public Builder lineNumber(int lineNumber) {
            this.lineNumber = lineNumber;
            return this;
        }

        public Builder parameters(List<String> parameters) {
            this.parameters = parameters != null ? new ArrayList<>(parameters) : List.of();
            return this;
        }

        public Builder parameterDetails(List<Map<String, Object>> parameterDetails) {
            this.parameterDetails = parameterDetails != null ? new ArrayList<>(parameterDetails) : List.of();
            return this;
        }

        public Builder methodBody(String methodBody) {
            this.methodBody = methodBody;
            return this;
        }

        public Builder javadoc(String javadoc) {
            this.javadoc = javadoc;
            return this;
        }

        public Builder classJavadoc(String classJavadoc) {
            this.classJavadoc = classJavadoc;
            return this;
        }

        public Builder classHierarchy(String classHierarchy) {
            this.classHierarchy = classHierarchy;
            return this;
        }

        public Builder classMethods(Map<String, String> classMethods) {
            this.classMethods = new HashMap<>(classMethods);
            return this;
        }

        public Builder addClassMethod(String methodName, String signature) {
            this.classMethods.put(methodName, signature);
            return this;
        }

        public Builder callGraph(CallGraphResult callGraph) {
            this.callGraph = callGraph;
            return this;
        }

        public Builder linesOfCode(int linesOfCode) {
            this.linesOfCode = linesOfCode;
            return this;
        }

        public Builder cyclomatic(int cyclomatic) {
            this.cyclomatic = cyclomatic;
            return this;
        }

        public Builder originalDocstring(String originalDocstring) {
            this.originalDocstring = originalDocstring;
            return this;
        }

        public Builder testPrefix(String testPrefix) {
            this.testPrefix = testPrefix;
            return this;
        }

        public Builder annotations(List<String> annotations) {
            this.annotations = annotations != null ? new ArrayList<>(annotations) : List.of();
            return this;
        }

        public Builder thrownExceptions(List<String> thrownExceptions) {
            this.thrownExceptions = thrownExceptions != null ? new ArrayList<>(thrownExceptions) : List.of();
            return this;
        }

        public Builder fieldReads(List<String> fieldReads) {
            this.fieldReads = fieldReads != null ? new ArrayList<>(fieldReads) : List.of();
            return this;
        }

        public Builder fieldWrites(List<String> fieldWrites) {
            this.fieldWrites = fieldWrites != null ? new ArrayList<>(fieldWrites) : List.of();
            return this;
        }

        public Builder siblingMethods(List<String> siblingMethods) {
            this.siblingMethods = siblingMethods != null ? new ArrayList<>(siblingMethods) : List.of();
            return this;
        }

        public Builder overloadGroup(List<String> overloadGroup) {
            this.overloadGroup = overloadGroup != null ? new ArrayList<>(overloadGroup) : List.of();
            return this;
        }

        public Builder dynamicFeatures(List<String> dynamicFeatures) {
            this.dynamicFeatures = dynamicFeatures != null ? new ArrayList<>(dynamicFeatures) : List.of();
            return this;
        }

        public Builder javadocMetadata(Map<String, Object> javadocMetadata) {
            this.javadocMetadata = javadocMetadata != null ? new HashMap<>(javadocMetadata) : Map.of();
            return this;
        }

        public Builder documentationMetrics(Map<String, Object> documentationMetrics) {
            this.documentationMetrics = documentationMetrics != null ? new HashMap<>(documentationMetrics) : Map.of();
            return this;
        }

        public Builder sourceBackend(String sourceBackend) {
            this.sourceBackend = sourceBackend;
            return this;
        }

        public Builder sourceBackendMode(String sourceBackendMode) {
            this.sourceBackendMode = sourceBackendMode;
            return this;
        }

        public Builder hierarchyResolution(String hierarchyResolution) {
            this.hierarchyResolution = hierarchyResolution;
            return this;
        }

        public Builder sourceSet(String sourceSet) {
            this.sourceSet = sourceSet;
            return this;
        }

        public MethodContext build() {
            return new MethodContext(this);
        }
    }
}
