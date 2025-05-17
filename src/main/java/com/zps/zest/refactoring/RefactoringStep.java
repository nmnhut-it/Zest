package com.zps.zest.refactoring;

/**
 * Represents a specific step in the refactoring process.
 */
public class RefactoringStep {
    private int id;
    private int issueId;
    private String title;
    private String description;
    private String filePath;
    private String codeChangeDescription;
    private String before;
    private String after;
    private RefactoringStepStatus status;
    
    public RefactoringStep() {
        this.status = RefactoringStepStatus.PENDING;
    }
    
    public RefactoringStep(int id, int issueId, String title, String description) {
        this();
        this.id = id;
        this.issueId = issueId;
        this.title = title;
        this.description = description;
    }
    
    // Getters and setters
    public int getId() {
        return id;
    }
    
    public void setId(int id) {
        this.id = id;
    }
    
    public int getIssueId() {
        return issueId;
    }
    
    public void setIssueId(int issueId) {
        this.issueId = issueId;
    }
    
    public String getTitle() {
        return title;
    }
    
    public void setTitle(String title) {
        this.title = title;
    }
    
    public String getDescription() {
        return description;
    }
    
    public void setDescription(String description) {
        this.description = description;
    }
    
    public String getFilePath() {
        return filePath;
    }
    
    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }
    
    public String getCodeChangeDescription() {
        return codeChangeDescription;
    }
    
    public void setCodeChangeDescription(String codeChangeDescription) {
        this.codeChangeDescription = codeChangeDescription;
    }
    
    public String getBefore() {
        return before;
    }
    
    public void setBefore(String before) {
        this.before = before;
    }
    
    public String getAfter() {
        return after;
    }
    
    public void setAfter(String after) {
        this.after = after;
    }
    
    public RefactoringStepStatus getStatus() {
        return status;
    }
    
    public void setStatus(RefactoringStepStatus status) {
        this.status = status;
    }
    
    /**
     * Marks this step as complete.
     */
    public void markComplete() {
        this.status = RefactoringStepStatus.COMPLETED;
    }
    
    /**
     * Marks this step as failed.
     */
    public void markFailed() {
        this.status = RefactoringStepStatus.FAILED;
    }
    
    /**
     * Marks this step as skipped.
     */
    public void markSkipped() {
        this.status = RefactoringStepStatus.SKIPPED;
    }
}
