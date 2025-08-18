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
    private final long createdAt;
    
    private Status status;
    protected TestPlan testPlan;
    private TestContext context;
    private TestGenerationResult testGenerationResult;
    private MergedTestClass mergedTestClass; // Complete merged test class
    private ValidationResult validationResult;
    private final List<String> errors;
    private LocalDateTime endTime;
    
    public enum Status {
        INITIALIZING("Initializing..."),
        PLANNING("Planning test strategy..."),
        AWAITING_USER_SELECTION("Waiting for test selection..."),
        GATHERING_CONTEXT("Gathering context..."),
        GENERATING("Generating tests..."),
        MERGING("Merging with existing tests..."),
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
            return this == INITIALIZING || this == PLANNING || this == GATHERING_CONTEXT || 
                   this == GENERATING || this == MERGING || this == VALIDATING;
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
        this.createdAt = System.currentTimeMillis();
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
    
    public long getCreatedAt() {
        return createdAt;
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
    public TestGenerationResult getTestGenerationResult() {
        return testGenerationResult;
    }
    
    public void setTestGenerationResult(@NotNull TestGenerationResult result) {
        this.testGenerationResult = result;
    }
    
    @Nullable
    public ValidationResult getValidationResult() {
        return validationResult;
    }
    
    public void setValidationResult(@NotNull ValidationResult validationResult) {
        this.validationResult = validationResult;
    }
    
    @Nullable
    public MergedTestClass getMergedTestClass() {
        return mergedTestClass;
    }
    
    public void setMergedTestClass(@NotNull MergedTestClass mergedTestClass) {
        this.mergedTestClass = mergedTestClass;
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
        return testGenerationResult != null ? testGenerationResult.getMethodCount() : 0;
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