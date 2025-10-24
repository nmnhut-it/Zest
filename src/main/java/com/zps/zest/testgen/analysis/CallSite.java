package com.zps.zest.testgen.analysis;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Represents a location where a method is called.
 * Captures context about how the method is used, which informs test generation.
 */
public class CallSite {
    private final String callerClass;
    private final String callerMethod;
    private final String filePath;
    private final int lineNumber;
    private final CallContext context;
    private final boolean hasErrorHandling;
    private final String errorHandlingType; // try-catch, if-null-check, etc.
    private final String codeSnippet;

    public enum CallContext {
        CONTROLLER("HTTP Controller - validates user input"),
        SERVICE("Service Layer - business logic"),
        REPOSITORY("Data Access - database operations"),
        TEST("Existing Test - example usage"),
        BACKGROUND_JOB("Background/Scheduled Job"),
        EVENT_LISTENER("Event Handler"),
        CONFIGURATION("Configuration/Initialization"),
        UNKNOWN("Unknown Context");

        private final String description;

        CallContext(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    private CallSite(Builder builder) {
        this.callerClass = builder.callerClass;
        this.callerMethod = builder.callerMethod;
        this.filePath = builder.filePath;
        this.lineNumber = builder.lineNumber;
        this.context = builder.context;
        this.hasErrorHandling = builder.hasErrorHandling;
        this.errorHandlingType = builder.errorHandlingType;
        this.codeSnippet = builder.codeSnippet;
    }

    @NotNull
    public String getCallerClass() {
        return callerClass;
    }

    @NotNull
    public String getCallerMethod() {
        return callerMethod;
    }

    @NotNull
    public String getFilePath() {
        return filePath;
    }

    public int getLineNumber() {
        return lineNumber;
    }

    @NotNull
    public CallContext getContext() {
        return context;
    }

    public boolean hasErrorHandling() {
        return hasErrorHandling;
    }

    @Nullable
    public String getErrorHandlingType() {
        return errorHandlingType;
    }

    @Nullable
    public String getCodeSnippet() {
        return codeSnippet;
    }

    @NotNull
    public String getCallerDescription() {
        return String.format("%s.%s() [%s:%d]", callerClass, callerMethod,
                           filePath.substring(filePath.lastIndexOf('/') + 1), lineNumber);
    }

    @NotNull
    public String getContextDescription() {
        return context.getDescription();
    }

    public static class Builder {
        private String callerClass = "Unknown";
        private String callerMethod = "unknown";
        private String filePath = "";
        private int lineNumber = 0;
        private CallContext context = CallContext.UNKNOWN;
        private boolean hasErrorHandling = false;
        private String errorHandlingType = null;
        private String codeSnippet = null;

        public Builder callerClass(String callerClass) {
            this.callerClass = callerClass;
            return this;
        }

        public Builder callerMethod(String callerMethod) {
            this.callerMethod = callerMethod;
            return this;
        }

        public Builder filePath(String filePath) {
            this.filePath = filePath;
            return this;
        }

        public Builder lineNumber(int lineNumber) {
            this.lineNumber = lineNumber;
            return this;
        }

        public Builder context(CallContext context) {
            this.context = context;
            return this;
        }

        public Builder errorHandling(boolean hasErrorHandling, String type) {
            this.hasErrorHandling = hasErrorHandling;
            this.errorHandlingType = type;
            return this;
        }

        public Builder codeSnippet(String codeSnippet) {
            this.codeSnippet = codeSnippet;
            return this;
        }

        public CallSite build() {
            return new CallSite(this);
        }
    }

    @Override
    public String toString() {
        return "CallSite{" +
               "caller=" + callerClass + "." + callerMethod +
               ", context=" + context +
               ", hasErrorHandling=" + hasErrorHandling +
               '}';
    }
}
