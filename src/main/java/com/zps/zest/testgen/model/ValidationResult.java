package com.zps.zest.testgen.model;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public class ValidationResult {
    private final boolean successful;
    private final List<ValidationIssue> issues;
    private final List<GeneratedTest> fixedTests;
    private final List<String> appliedFixes;
    
    public ValidationResult(boolean successful,
                          @NotNull List<ValidationIssue> issues,
                          @NotNull List<GeneratedTest> fixedTests,
                          @NotNull List<String> appliedFixes) {
        this.successful = successful;
        this.issues = new ArrayList<>(issues);
        this.fixedTests = new ArrayList<>(fixedTests);
        this.appliedFixes = new ArrayList<>(appliedFixes);
    }
    
    public boolean isSuccessful() {
        return successful;
    }
    
    @NotNull
    public List<ValidationIssue> getIssues() {
        return new ArrayList<>(issues);
    }
    
    @NotNull
    public List<GeneratedTest> getFixedTests() {
        return new ArrayList<>(fixedTests);
    }
    
    @NotNull
    public List<String> getAppliedFixes() {
        return new ArrayList<>(appliedFixes);
    }
    
    public boolean hasErrors() {
        return issues.stream().anyMatch(issue -> issue.getSeverity() == ValidationIssue.Severity.ERROR);
    }
    
    public boolean hasWarnings() {
        return issues.stream().anyMatch(issue -> issue.getSeverity() == ValidationIssue.Severity.WARNING);
    }
    
    public boolean hasFixedTests() {
        return !fixedTests.isEmpty();
    }
    
    public int getErrorCount() {
        return (int) issues.stream().filter(issue -> issue.getSeverity() == ValidationIssue.Severity.ERROR).count();
    }
    
    public int getWarningCount() {
        return (int) issues.stream().filter(issue -> issue.getSeverity() == ValidationIssue.Severity.WARNING).count();
    }
    
    @Override
    public String toString() {
        return "ValidationResult{" +
               "successful=" + successful +
               ", errors=" + getErrorCount() +
               ", warnings=" + getWarningCount() +
               ", fixedTests=" + fixedTests.size() +
               ", appliedFixes=" + appliedFixes.size() +
               '}';
    }
    
    public static class ValidationIssue {
        private final String testName;
        private final String description;
        private final Severity severity;
        private final String fixSuggestion;
        private final boolean fixable;
        
        public enum Severity {
            ERROR("Error"),
            WARNING("Warning"),
            INFO("Info");
            
            private final String displayName;
            
            Severity(String displayName) {
                this.displayName = displayName;
            }
            
            public String getDisplayName() {
                return displayName;
            }
        }
        
        public ValidationIssue(@NotNull String testName,
                             @NotNull String description,
                             @NotNull Severity severity,
                             @NotNull String fixSuggestion,
                             boolean fixable) {
            this.testName = testName;
            this.description = description;
            this.severity = severity;
            this.fixSuggestion = fixSuggestion;
            this.fixable = fixable;
        }
        
        @NotNull
        public String getTestName() {
            return testName;
        }
        
        @NotNull
        public String getDescription() {
            return description;
        }
        
        @NotNull
        public Severity getSeverity() {
            return severity;
        }
        
        @NotNull
        public String getFixSuggestion() {
            return fixSuggestion;
        }
        
        public boolean isFixable() {
            return fixable;
        }
        
        @Override
        public String toString() {
            return "ValidationIssue{" +
                   "testName='" + testName + '\'' +
                   ", severity=" + severity +
                   ", description='" + description + '\'' +
                   ", fixable=" + fixable +
                   '}';
        }
    }
}