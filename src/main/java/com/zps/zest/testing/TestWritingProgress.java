package com.zps.zest.testing;

import java.util.*;

/**
 * Tracks the progress of a test writing operation.
 */
public class TestWritingProgress {
    private String planName;
    private int currentScenarioIndex;
    private int currentTestCaseIndex;
    private Set<Integer> completedTestCaseIds;
    private Set<Integer> skippedTestCaseIds;
    private Set<Integer> failedTestCaseIds;
    private Date startDate;
    private Date lastUpdateDate;
    private TestWritingStatus status;
    
    // For final report generation
    private List<String> completedTests;
    private Map<Integer, List<String>> issuesAfterScenario;
    private Map<Integer, Set<String>> affectedFiles;
    
    public TestWritingProgress() {
        this.completedTestCaseIds = new HashSet<>();
        this.skippedTestCaseIds = new HashSet<>();
        this.failedTestCaseIds = new HashSet<>();
        this.startDate = new Date();
        this.lastUpdateDate = new Date();
        this.status = TestWritingStatus.IN_PROGRESS;
        
        // Initialize report fields
        this.completedTests = new ArrayList<>();
        this.issuesAfterScenario = new HashMap<>();
        this.affectedFiles = new HashMap<>();
    }
    
    public TestWritingProgress(String planName) {
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
    
    public int getCurrentScenarioIndex() {
        return currentScenarioIndex;
    }
    
    public void setCurrentScenarioIndex(int currentScenarioIndex) {
        this.currentScenarioIndex = currentScenarioIndex;
    }
    
    public int getCurrentTestCaseIndex() {
        return currentTestCaseIndex;
    }
    
    public void setCurrentTestCaseIndex(int currentTestCaseIndex) {
        this.currentTestCaseIndex = currentTestCaseIndex;
    }
    
    public Set<Integer> getCompletedTestCaseIds() {
        return completedTestCaseIds;
    }
    
    public void setCompletedTestCaseIds(Set<Integer> completedTestCaseIds) {
        this.completedTestCaseIds = completedTestCaseIds;
    }
    
    public Set<Integer> getSkippedTestCaseIds() {
        return skippedTestCaseIds;
    }
    
    public void setSkippedTestCaseIds(Set<Integer> skippedTestCaseIds) {
        this.skippedTestCaseIds = skippedTestCaseIds;
    }
    
    public Set<Integer> getFailedTestCaseIds() {
        return failedTestCaseIds;
    }
    
    public void setFailedTestCaseIds(Set<Integer> failedTestCaseIds) {
        this.failedTestCaseIds = failedTestCaseIds;
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
    
    public TestWritingStatus getStatus() {
        return status;
    }
    
    public void setStatus(TestWritingStatus status) {
        this.status = status;
    }
    
    /**
     * Marks a test case as complete.
     */
    public void markTestCaseComplete(int testCaseId) {
        completedTestCaseIds.add(testCaseId);
        lastUpdateDate = new Date();
    }
    
    /**
     * Marks a test case as skipped.
     */
    public void markTestCaseSkipped(int testCaseId) {
        skippedTestCaseIds.add(testCaseId);
        lastUpdateDate = new Date();
    }
    
    /**
     * Marks a test case as failed.
     */
    public void markTestCaseFailed(int testCaseId) {
        failedTestCaseIds.add(testCaseId);
        lastUpdateDate = new Date();
    }
    
    /**
     * Gets the current test identifier string (e.g., "Scenario 2, Test Case 3").
     */
    public String getCurrentTest() {
        return "Scenario " + (currentScenarioIndex + 1) + ", Test Case " + (currentTestCaseIndex + 1);
    }
    
    /**
     * Marks the test writing as complete.
     */
    public void markComplete() {
        this.status = TestWritingStatus.COMPLETED;
        this.lastUpdateDate = new Date();
    }
    
    /**
     * Marks the test writing as aborted.
     */
    public void markAborted() {
        this.status = TestWritingStatus.ABORTED;
        this.lastUpdateDate = new Date();
    }
    
    /**
     * Adds information about a completed test for reporting.
     */
    public void addCompletedTest(String testDescription) {
        if (this.completedTests == null) {
            this.completedTests = new ArrayList<>();
        }
        this.completedTests.add(testDescription);
    }
    
    /**
     * Adds information about issues after completing a scenario.
     */
    public void addIssuesAfterScenario(int scenarioId, List<String> issues) {
        if (this.issuesAfterScenario == null) {
            this.issuesAfterScenario = new HashMap<>();
        }
        this.issuesAfterScenario.put(scenarioId, issues);
    }
    
    /**
     * Adds information about files affected by a scenario.
     */
    public void addAffectedFiles(int scenarioId, Set<String> fileNames) {
        if (this.affectedFiles == null) {
            this.affectedFiles = new HashMap<>();
        }
        this.affectedFiles.put(scenarioId, fileNames);
    }
    
    public List<String> getCompletedTests() {
        return completedTests;
    }
    
    public Map<Integer, List<String>> getIssuesAfterScenario() {
        return issuesAfterScenario;
    }
    
    public Map<Integer, Set<String>> getAffectedFiles() {
        return affectedFiles;
    }
}
