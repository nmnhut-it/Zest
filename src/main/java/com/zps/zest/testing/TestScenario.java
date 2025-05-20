package com.zps.zest.testing;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a test scenario identified during test analysis.
 */
public class TestScenario {
    private int id;
    private String title;
    private String description;
    private String category;
    private String testType;
    private String priority;
    private String reasoning;
    private List<TestCase> testCases;
    private String targetMethod;
    
    public TestScenario() {
        this.testCases = new ArrayList<>();
    }
    
    public TestScenario(int id, String title, String description, String category) {
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
    
    public String getTestType() {
        return testType;
    }
    
    public void setTestType(String testType) {
        this.testType = testType;
    }
    
    public String getPriority() {
        return priority;
    }
    
    public void setPriority(String priority) {
        this.priority = priority;
    }
    
    public String getReasoning() {
        return reasoning;
    }
    
    public void setReasoning(String reasoning) {
        this.reasoning = reasoning;
    }
    
    public List<TestCase> getTestCases() {
        return testCases;
    }
    
    public void setTestCases(List<TestCase> testCases) {
        this.testCases = testCases;
    }
    
    public void addTestCase(TestCase testCase) {
        this.testCases.add(testCase);
    }

    public String getTargetMethod() {
        return targetMethod;
    }

    public void setTargetMethod(String targetMethod) {
        this.targetMethod = targetMethod;
    }
}
