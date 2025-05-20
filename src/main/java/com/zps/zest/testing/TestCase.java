package com.zps.zest.testing;

/**
 * Represents a specific test case in the test writing process.
 */
public class TestCase {
    private int id;
    private int scenarioId;
    private String title;
    private String description;
    private String testFilePath;
    private String testMethodName;
    private String testCode;
    private String setup;
    private String assertions;
    private TestStatus status;
    
    public TestCase() {
        this.status = TestStatus.PENDING;
    }
    
    public TestCase(int id, int scenarioId, String title, String description) {
        this();
        this.id = id;
        this.scenarioId = scenarioId;
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
    
    public int getScenarioId() {
        return scenarioId;
    }
    
    public void setScenarioId(int scenarioId) {
        this.scenarioId = scenarioId;
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
    
    public String getTestFilePath() {
        return testFilePath;
    }
    
    public void setTestFilePath(String testFilePath) {
        this.testFilePath = testFilePath;
    }
    
    public String getTestMethodName() {
        return testMethodName;
    }
    
    public void setTestMethodName(String testMethodName) {
        this.testMethodName = testMethodName;
    }
    
    public String getTestCode() {
        return testCode;
    }
    
    public void setTestCode(String testCode) {
        this.testCode = testCode;
    }
    
    public String getSetup() {
        return setup;
    }
    
    public void setSetup(String setup) {
        this.setup = setup;
    }
    
    public String getAssertions() {
        return assertions;
    }
    
    public void setAssertions(String assertions) {
        this.assertions = assertions;
    }
    
    public TestStatus getStatus() {
        return status;
    }
    
    public void setStatus(TestStatus status) {
        this.status = status;
    }
    
    /**
     * Marks this test case as complete.
     */
    public void markComplete() {
        this.status = TestStatus.COMPLETED;
    }
    
    /**
     * Marks this test case as failed.
     */
    public void markFailed() {
        this.status = TestStatus.FAILED;
    }
    
    /**
     * Marks this test case as skipped.
     */
    public void markSkipped() {
        this.status = TestStatus.SKIPPED;
    }
}
