package com.zps.zest.testgen.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class TestGenerationSession {
    private final String sessionId;
    private final TestGenerationRequest request;
    private final LocalDateTime startTime;
    
    private Status status;
    private TestPlan testPlan;
    private TestContext context;
    private List<GeneratedTest> generatedTests;
    private ValidationResult validationResult;
    private final List<String> errors;
    private LocalDateTime endTime;
    
    public enum Status {
        PLANNING("Planning test strategy..."),
        GATHERING_CONTEXT("Gathering context..."),
        GENERATING("Generating tests..."),
        VALIDATING("Validating tests..."),
        COMPLETED("Completed"),
        COMPLETED_WITH_ISSUES("Completed with issues"),
        FAILED("Failed"),
        CANCELLED("Cancelled");
        
        private final String description;
        
        Status(String description) {
            this.description = description;
        }
        
        public String getDescription() {
            return description;
        }
        
        public boolean isActive() {
            return this == PLANNING || this == GATHERING_CONTEXT || 
                   this == GENERATING || this == VALIDATING;
        }
        
        public boolean isCompleted() {
            return this == COMPLETED || this == COMPLETED_WITH_ISSUES;
        }
        
        public boolean isFailed() {
            return this == FAILED || this == CANCELLED;
        }
    }
    
    public TestGenerationSession(@NotNull String sessionId,
                               @NotNull TestGenerationRequest request,
                               @NotNull Status status) {
        this.sessionId = sessionId;
        this.request = request;
        this.status = status;
        this.startTime = LocalDateTime.now();
        this.errors = new ArrayList<>();
    }
    
    @NotNull
    public String getSessionId() {
        return sessionId;
    }
    
    @NotNull
    public TestGenerationRequest getRequest() {
        return request;
    }
    
    @NotNull
    public Status getStatus() {
        return status;
    }
    
    public void setStatus(@NotNull Status status) {
        this.status = status;
        if (status.isFailed() || status.isCompleted()) {
            this.endTime = LocalDateTime.now();
        }
    }
    
    @NotNull
    public LocalDateTime getStartTime() {
        return startTime;
    }
    
    @Nullable
    public LocalDateTime getEndTime() {
        return endTime;
    }
    
    @Nullable
    public TestPlan getTestPlan() {
        return testPlan;
    }
    
    public void setTestPlan(@NotNull TestPlan testPlan) {
        this.testPlan = testPlan;
    }
    
    @Nullable
    public TestContext getContext() {
        return context;
    }
    
    public void setContext(@NotNull TestContext context) {
        this.context = context;
    }
    
    @Nullable
    public List<GeneratedTest> getGeneratedTests() {
        return generatedTests;
    }
    
    public void setGeneratedTests(@NotNull List<GeneratedTest> generatedTests) {
        this.generatedTests = new ArrayList<>(generatedTests);
    }
    
    @Nullable
    public ValidationResult getValidationResult() {
        return validationResult;
    }
    
    public void setValidationResult(@NotNull ValidationResult validationResult) {
        this.validationResult = validationResult;
    }
    
    @NotNull
    public List<String> getErrors() {
        return new ArrayList<>(errors);
    }
    
    public void addError(@NotNull String error) {
        this.errors.add(error);
    }
    
    public boolean hasErrors() {
        return !errors.isEmpty();
    }
    
    public int getTestCount() {
        return generatedTests != null ? generatedTests.size() : 0;
    }
    
    public long getDurationMillis() {
        if (endTime != null) {
            return java.time.Duration.between(startTime, endTime).toMillis();
        }
        return java.time.Duration.between(startTime, LocalDateTime.now()).toMillis();
    }
    
    @Override
    public String toString() {
        return "TestGenerationSession{" +
               "sessionId='" + sessionId + '\'' +
               ", status=" + status +
               ", testCount=" + getTestCount() +
               ", duration=" + getDurationMillis() + "ms" +
               '}';
    }
}