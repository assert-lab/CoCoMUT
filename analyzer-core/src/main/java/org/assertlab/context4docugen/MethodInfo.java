package org.assertlab.context4docugen;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Immutable data class representing a detected method in the project
 * Used by MethodIdentifier to store method information
 */
public class MethodInfo {
    private final String id;
    private final String classname;
    private final String methodName;
    private final String methodSignature;
    private final Path sourceFile;
    private final int lineNumber;
    private final int columnNumber;
    private final String visibility;  // "public", "private", "protected", "package-private"
    private final boolean isStatic;
    private final String returnType;
    private final String originalDocstring;  // human-written docstring from inputs_selected.csv (empty if scanned from source)
    private final String testPrefix;         // associated test code from inputs_selected.csv (empty if scanned from source)

    private MethodInfo(Builder builder) {
        this.id = Objects.requireNonNull(builder.id, "id cannot be null");
        this.classname = Objects.requireNonNull(builder.classname, "classname cannot be null");
        this.methodName = Objects.requireNonNull(builder.methodName, "methodName cannot be null");
        this.methodSignature = Objects.requireNonNull(builder.methodSignature, "methodSignature cannot be null");
        this.sourceFile = Objects.requireNonNull(builder.sourceFile, "sourceFile cannot be null");
        this.lineNumber = builder.lineNumber;
        this.columnNumber = builder.columnNumber;
        this.visibility = Objects.requireNonNull(builder.visibility, "visibility cannot be null");
        this.isStatic = builder.isStatic;
        this.returnType = Objects.requireNonNull(builder.returnType, "returnType cannot be null");
        this.originalDocstring = builder.originalDocstring != null ? builder.originalDocstring : "";
        this.testPrefix = builder.testPrefix != null ? builder.testPrefix : "";
    }

    // Getters
    public String getId() {
        return id;
    }

    public String getMethodUri() {
        return id;
    }

    public String getClassname() {
        return classname;
    }

    public String getMethodName() {
        return methodName;
    }

    public String getMethodSignature() {
        return methodSignature;
    }

    public Path getSourceFile() {
        return sourceFile;
    }

    public int getLineNumber() {
        return lineNumber;
    }

    public int getColumnNumber() {
        return columnNumber;
    }

    public String getVisibility() {
        return visibility;
    }

    public boolean isStatic() {
        return isStatic;
    }

    public String getReturnType() {
        return returnType;
    }

    public String getOriginalDocstring() {
        return originalDocstring;
    }

    public String getTestPrefix() {
        return testPrefix;
    }

    /**
     * Get as CSV row: method_uri|classname|methodname|filepath|line_number|signature
     */
    public String toCsvRow() {
        return String.format("%s|%s|%s|%s|%d|%s",
                id, classname, methodName, sourceFile, lineNumber, methodSignature);
    }

    @Override
    public String toString() {
        return "MethodInfo{" +
                "id='" + id + '\'' +
                ", classname='" + classname + '\'' +
                ", methodName='" + methodName + '\'' +
                ", sourceFile=" + sourceFile +
                ", lineNumber=" + lineNumber +
                ", visibility='" + visibility + '\'' +
                ", isStatic=" + isStatic +
                ", returnType='" + returnType + '\'' +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MethodInfo that = (MethodInfo) o;
        return Objects.equals(id, that.id) &&
                Objects.equals(classname, that.classname) &&
                Objects.equals(methodName, that.methodName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, classname, methodName);
    }

    /**
     * Builder for MethodInfo - fluent API for construction
     */
    public static class Builder {
        private String id;
        private String classname;
        private String methodName;
        private String methodSignature;
        private Path sourceFile;
        private int lineNumber;
        private int columnNumber;
        private String visibility = "package-private";
        private boolean isStatic = false;
        private String returnType = "void";
        private String originalDocstring = "";
        private String testPrefix = "";

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder classname(String classname) {
            this.classname = classname;
            return this;
        }

        public Builder methodName(String methodName) {
            this.methodName = methodName;
            return this;
        }

        public Builder methodSignature(String methodSignature) {
            this.methodSignature = methodSignature;
            return this;
        }

        public Builder sourceFile(Path sourceFile) {
            this.sourceFile = sourceFile;
            return this;
        }

        public Builder lineNumber(int lineNumber) {
            this.lineNumber = lineNumber;
            return this;
        }

        public Builder columnNumber(int columnNumber) {
            this.columnNumber = columnNumber;
            return this;
        }

        public Builder visibility(String visibility) {
            this.visibility = visibility;
            return this;
        }

        public Builder isStatic(boolean isStatic) {
            this.isStatic = isStatic;
            return this;
        }

        public Builder returnType(String returnType) {
            this.returnType = returnType;
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

        public MethodInfo build() {
            return new MethodInfo(this);
        }
    }
}
