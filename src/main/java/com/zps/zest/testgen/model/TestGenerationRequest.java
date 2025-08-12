package com.zps.zest.testgen.model;

import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

public class TestGenerationRequest {
    private final PsiFile targetFile;
    private final int selectionStart;
    private final int selectionEnd;
    private final String userDescription;
    private final TestType testType;
    private final Map<String, String> options;
    
    public enum TestType {
        UNIT_TESTS("Generate unit tests"),
        INTEGRATION_TESTS("Generate integration tests"),
        BOTH("Generate both unit and integration tests"),
        AUTO_DETECT("Auto-detect best test type");
        
        private final String description;
        
        TestType(String description) {
            this.description = description;
        }
        
        public String getDescription() {
            return description;
        }
    }
    
    public TestGenerationRequest(@NotNull PsiFile targetFile,
                               int selectionStart,
                               int selectionEnd,
                               @Nullable String userDescription,
                               @NotNull TestType testType,
                               @Nullable Map<String, String> options) {
        this.targetFile = targetFile;
        this.selectionStart = selectionStart;
        this.selectionEnd = selectionEnd;
        this.userDescription = userDescription != null ? userDescription : "";
        this.testType = testType;
        this.options = options != null ? options : Map.of();
    }
    
    @NotNull
    public PsiFile getTargetFile() {
        return targetFile;
    }
    
    public int getSelectionStart() {
        return selectionStart;
    }
    
    public int getSelectionEnd() {
        return selectionEnd;
    }
    
    @NotNull
    public String getUserDescription() {
        return userDescription;
    }
    
    @NotNull
    public TestType getTestType() {
        return testType;
    }
    
    @NotNull
    public Map<String, String> getOptions() {
        return options;
    }
    
    public boolean hasSelection() {
        return selectionEnd > selectionStart;
    }
    
    @Nullable
    public String getOption(@NotNull String key) {
        return options.get(key);
    }
    
    public boolean getBooleanOption(@NotNull String key, boolean defaultValue) {
        String value = options.get(key);
        return value != null ? Boolean.parseBoolean(value) : defaultValue;
    }
    
    @Override
    public String toString() {
        return "TestGenerationRequest{" +
               "file=" + targetFile.getName() +
               ", selection=" + selectionStart + "-" + selectionEnd +
               ", type=" + testType +
               ", description='" + userDescription + '\'' +
               '}';
    }
}