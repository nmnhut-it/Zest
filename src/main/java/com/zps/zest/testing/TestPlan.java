package com.zps.zest.testing;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Represents a complete test plan with identified scenarios and test cases.
 */
public class TestPlan {
    private String name;
    private String targetClass;
    private String description;
    private String testFilePath;
    private Date createdDate;
    private List<TestScenario> scenarios;
    
    public TestPlan() {
        this.scenarios = new ArrayList<>();
        this.createdDate = new Date();
    }
    
    public TestPlan(String name, String targetClass, String description) {
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
    
    public String getTestFilePath() {
        return testFilePath;
    }
    
    public void setTestFilePath(String testFilePath) {
        this.testFilePath = testFilePath;
    }
    
    public Date getCreatedDate() {
        return createdDate;
    }
    
    public void setCreatedDate(Date createdDate) {
        this.createdDate = createdDate;
    }
    
    public List<TestScenario> getScenarios() {
        return scenarios;
    }
    
    public void setScenarios(List<TestScenario> scenarios) {
        this.scenarios = scenarios;
    }
    
    public void addScenario(TestScenario scenario) {
        this.scenarios.add(scenario);
    }
    
    /**
     * Gets the total number of test cases in this test plan.
     */
    public int getTotalTestCaseCount() {
        int count = 0;
        for (TestScenario scenario : scenarios) {
            count += scenario.getTestCases().size();
        }
        return count;
    }
}
