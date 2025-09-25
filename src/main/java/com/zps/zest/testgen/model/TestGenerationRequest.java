package com.zps.zest.testgen.model;

import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Request model for test generation.
 * Updated to support method selection and improved workflow.
 */
public class TestGenerationRequest {
    private final PsiFile targetFile;
    private final List<PsiMethod> targetMethods; // Multiple target methods
    private final String selectedCode; // Selected code snippet
    private final TestType testType;
    private final Map<String, String> additionalContext;
    private final List<String> userProvidedFiles; // User-selected related files
    private final String userProvidedCode; // User-provided code snippets
    
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
    
    /**
     * New constructor for improved workflow with method selection.
     */
    public TestGenerationRequest(@NotNull PsiFile targetFile,
                                 @NotNull List<PsiMethod> targetMethods,
                                @Nullable String selectedCode,
                                @NotNull TestType testType,
                                @Nullable Map<String, String> additionalContext) {
        this(targetFile, targetMethods, selectedCode, testType, additionalContext, null, null);
    }

    /**
     * Full constructor with user-provided context support.
     */
    public TestGenerationRequest(@NotNull PsiFile targetFile,
                                 @NotNull List<PsiMethod> targetMethods,
                                @Nullable String selectedCode,
                                @NotNull TestType testType,
                                @Nullable Map<String, String> additionalContext,
                                @Nullable List<String> userProvidedFiles,
                                @Nullable String userProvidedCode) {
        this.targetFile = targetFile;
        this.targetMethods = targetMethods != null ? new ArrayList<>(targetMethods) : new ArrayList<>();
        this.selectedCode = selectedCode;
        this.testType = testType;
        this.additionalContext = additionalContext != null ? additionalContext : Map.of();
        this.userProvidedFiles = userProvidedFiles != null ? new ArrayList<>(userProvidedFiles) : new ArrayList<>();
        this.userProvidedCode = userProvidedCode;
    }
    
    @NotNull
    public PsiFile getTargetFile() {
        return targetFile;
    }

    
    @NotNull
    public List<PsiMethod> getTargetMethods() {
        return new ArrayList<>(targetMethods);
    }
    
    @Nullable
    public String getSelectedCode() {
        return selectedCode;
    }
    
    @NotNull
    public TestType getTestType() {
        return testType;
    }
    
    @NotNull
    public Map<String, String> getAdditionalContext() {
        return additionalContext;
    }
    
    public boolean hasSelection() {
        return selectedCode != null && !selectedCode.isEmpty();
    }

    @NotNull
    public List<String> getUserProvidedFiles() {
        return new ArrayList<>(userProvidedFiles);
    }

    @Nullable
    public String getUserProvidedCode() {
        return userProvidedCode;
    }

    public boolean hasUserProvidedContext() {
        return !userProvidedFiles.isEmpty() || (userProvidedCode != null && !userProvidedCode.trim().isEmpty());
    }

    @Nullable
    public String getOption(@NotNull String key) {
        return additionalContext.get(key);
    }

    public boolean getBooleanOption(@NotNull String key, boolean defaultValue) {
        String value = additionalContext.get(key);
        return value != null ? Boolean.parseBoolean(value) : defaultValue;
    }
    
    @Override
    public String toString() {
        return "TestGenerationRequest{" +
               "file=" + targetFile.getName() +
               ", targetMethods=" + targetMethods.size() +
               ", hasSelection=" + hasSelection() +
               ", type=" + testType +
               '}';
    }
}