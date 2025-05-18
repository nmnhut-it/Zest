package com.zps.zest.refactoring;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a testability issue identified during refactoring analysis.
 */
public class RefactoringIssue {
    private int id;
    private String title;
    private String description;
    private String category;
    private String impact;
    private String reasoning;
    private List<RefactoringStep> steps;
    
    public RefactoringIssue() {
        this.steps = new ArrayList<>();
    }
    
    public RefactoringIssue(int id, String title, String description, String category) {
        this();
        this.id = id;
        this.title = title;
        this.description = description;
        this.category = category;
    }
    
    // Getters and setters
    public int getId() {
        return id;
    }
    
    public void setId(int id) {
        this.id = id;
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
    
    public String getCategory() {
        return category;
    }
    
    public void setCategory(String category) {
        this.category = category;
    }
    
    public String getImpact() {
        return impact;
    }
    
    public void setImpact(String impact) {
        this.impact = impact;
    }
    
    public String getReasoning() {
        return reasoning;
    }
    
    public void setReasoning(String reasoning) {
        this.reasoning = reasoning;
    }
    
    public List<RefactoringStep> getSteps() {
        return steps;
    }
    
    public void setSteps(List<RefactoringStep> steps) {
        this.steps = steps;
    }
    
    public void addStep(RefactoringStep step) {
        this.steps.add(step);
    }
}
