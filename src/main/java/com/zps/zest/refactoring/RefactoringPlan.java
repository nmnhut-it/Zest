package com.zps.zest.refactoring;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Represents a complete refactoring plan with identified issues and steps to resolve them.
 */
public class RefactoringPlan {
    private String name;
    private String targetClass;
    private String description;
    private Date createdDate;
    private List<RefactoringIssue> issues;
    
    public RefactoringPlan() {
        this.issues = new ArrayList<>();
        this.createdDate = new Date();
    }
    
    public RefactoringPlan(String name, String targetClass, String description) {
        this();
        this.name = name;
        this.targetClass = targetClass;
        this.description = description;
    }
    
    // Getters and setters
    public String getName() {
        return name;
    }
    
    public void setName(String name) {
        this.name = name;
    }
    
    public String getTargetClass() {
        return targetClass;
    }
    
    public void setTargetClass(String targetClass) {
        this.targetClass = targetClass;
    }
    
    public String getDescription() {
        return description;
    }
    
    public void setDescription(String description) {
        this.description = description;
    }
    
    public Date getCreatedDate() {
        return createdDate;
    }
    
    public void setCreatedDate(Date createdDate) {
        this.createdDate = createdDate;
    }
    
    public List<RefactoringIssue> getIssues() {
        return issues;
    }
    
    public void setIssues(List<RefactoringIssue> issues) {
        this.issues = issues;
    }
    
    public void addIssue(RefactoringIssue issue) {
        this.issues.add(issue);
    }
    
    /**
     * Gets the total number of steps in this refactoring plan.
     */
    public int getTotalStepCount() {
        int count = 0;
        for (RefactoringIssue issue : issues) {
            count += issue.getSteps().size();
        }
        return count;
    }
}
