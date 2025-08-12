package com.zps.zest.testgen.model;

public class TestGenerationStatistics {
    private final int totalSessions;
    private final int completedSessions;
    private final int failedSessions;
    private final int totalTestsGenerated;
    
    public TestGenerationStatistics(int totalSessions,
                                  int completedSessions,
                                  int failedSessions,
                                  int totalTestsGenerated) {
        this.totalSessions = totalSessions;
        this.completedSessions = completedSessions;
        this.failedSessions = failedSessions;
        this.totalTestsGenerated = totalTestsGenerated;
    }
    
    public int getTotalSessions() {
        return totalSessions;
    }
    
    public int getCompletedSessions() {
        return completedSessions;
    }
    
    public int getFailedSessions() {
        return failedSessions;
    }
    
    public int getActiveSessions() {
        return totalSessions - completedSessions - failedSessions;
    }
    
    public int getTotalTestsGenerated() {
        return totalTestsGenerated;
    }
    
    public double getSuccessRate() {
        if (totalSessions == 0) return 0.0;
        return (double) completedSessions / totalSessions * 100.0;
    }
    
    public double getAverageTestsPerSession() {
        if (completedSessions == 0) return 0.0;
        return (double) totalTestsGenerated / completedSessions;
    }
    
    @Override
    public String toString() {
        return "TestGenerationStatistics{" +
               "totalSessions=" + totalSessions +
               ", completedSessions=" + completedSessions +
               ", failedSessions=" + failedSessions +
               ", activeSessions=" + getActiveSessions() +
               ", totalTestsGenerated=" + totalTestsGenerated +
               ", successRate=" + String.format("%.1f", getSuccessRate()) + "%" +
               '}';
    }
}