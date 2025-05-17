package com.zps.zest.refactoring;

import java.util.Date;
import java.util.HashSet;
import java.util.Set;

/**
 * Tracks the progress of a refactoring operation.
 */
public class RefactoringProgress {
    private String planName;
    private int currentIssueIndex;
    private int currentStepIndex;
    private Set<Integer> completedStepIds;
    private Set<Integer> skippedStepIds;
    private Set<Integer> failedStepIds;
    private Date startDate;
    private Date lastUpdateDate;
    private RefactoringStatus status;
    
    public RefactoringProgress() {
        this.completedStepIds = new HashSet<>();
        this.skippedStepIds = new HashSet<>();
        this.failedStepIds = new HashSet<>();
        this.startDate = new Date();
        this.lastUpdateDate = new Date();
        this.status = RefactoringStatus.IN_PROGRESS;
    }
    
    public RefactoringProgress(String planName) {
        this();
        this.planName = planName;
    }
    
    // Getters and setters
    public String getPlanName() {
        return planName;
    }
    
    public void setPlanName(String planName) {
        this.planName = planName;
    }
    
    public int getCurrentIssueIndex() {
        return currentIssueIndex;
    }
    
    public void setCurrentIssueIndex(int currentIssueIndex) {
        this.currentIssueIndex = currentIssueIndex;
    }
    
    public int getCurrentStepIndex() {
        return currentStepIndex;
    }
    
    public void setCurrentStepIndex(int currentStepIndex) {
        this.currentStepIndex = currentStepIndex;
    }
    
    public Set<Integer> getCompletedStepIds() {
        return completedStepIds;
    }
    
    public void setCompletedStepIds(Set<Integer> completedStepIds) {
        this.completedStepIds = completedStepIds;
    }
    
    public Set<Integer> getSkippedStepIds() {
        return skippedStepIds;
    }
    
    public void setSkippedStepIds(Set<Integer> skippedStepIds) {
        this.skippedStepIds = skippedStepIds;
    }
    
    public Set<Integer> getFailedStepIds() {
        return failedStepIds;
    }
    
    public void setFailedStepIds(Set<Integer> failedStepIds) {
        this.failedStepIds = failedStepIds;
    }
    
    public Date getStartDate() {
        return startDate;
    }
    
    public void setStartDate(Date startDate) {
        this.startDate = startDate;
    }
    
    public Date getLastUpdateDate() {
        return lastUpdateDate;
    }
    
    public void setLastUpdateDate(Date lastUpdateDate) {
        this.lastUpdateDate = lastUpdateDate;
    }
    
    public RefactoringStatus getStatus() {
        return status;
    }
    
    public void setStatus(RefactoringStatus status) {
        this.status = status;
    }
    
    /**
     * Marks a step as complete.
     */
    public void markStepComplete(int stepId) {
        completedStepIds.add(stepId);
        lastUpdateDate = new Date();
    }
    
    /**
     * Marks a step as skipped.
     */
    public void markStepSkipped(int stepId) {
        skippedStepIds.add(stepId);
        lastUpdateDate = new Date();
    }
    
    /**
     * Marks a step as failed.
     */
    public void markStepFailed(int stepId) {
        failedStepIds.add(stepId);
        lastUpdateDate = new Date();
    }
    
    /**
     * Gets the current step identifier string (e.g., "Issue 2, Step 3").
     */
    public String getCurrentStep() {
        return "Issue " + (currentIssueIndex + 1) + ", Step " + (currentStepIndex + 1);
    }
    
    /**
     * Marks the refactoring as complete.
     */
    public void markComplete() {
        this.status = RefactoringStatus.COMPLETED;
        this.lastUpdateDate = new Date();
    }
    
    /**
     * Marks the refactoring as aborted.
     */
    public void markAborted() {
        this.status = RefactoringStatus.ABORTED;
        this.lastUpdateDate = new Date();
    }
}
