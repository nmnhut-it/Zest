package com.zps.zest.testgen.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Represents a complete, merged test class ready to be written to file.
 * This is the final output after all test methods have been merged into a single class.
 * 
 * Unlike GeneratedTest which represents individual test methods,
 * MergedTestClass represents the complete, formatted Java file.
 */
public class MergedTestClass {
    private final String className;
    private final String packageName;
    private final String fullContent;  // Complete Java file content with package, imports, class, and all methods
    private final String fileName;     // e.g., "UserServiceTest.java"
    private final String fullFilePath; // Full path where the file should be written (inferred by AI)
    private final int methodCount;     // Number of test methods in the class
    private final String framework;    // JUnit4, JUnit5, TestNG, etc.
    
    public MergedTestClass(@NotNull String className,
                          @NotNull String packageName,
                          @NotNull String fullContent,
                          @NotNull String fileName,
                          @Nullable String fullFilePath,
                          int methodCount,
                          @NotNull String framework) {
        this.className = className;
        this.packageName = packageName;
        this.fullContent = fullContent;
        this.fileName = fileName;
        this.fullFilePath = fullFilePath;
        this.methodCount = methodCount;
        this.framework = framework;
    }
    
    @NotNull
    public String getClassName() {
        return className;
    }
    
    @NotNull
    public String getPackageName() {
        return packageName;
    }
    
    @NotNull
    public String getFullContent() {
        return fullContent;
    }
    
    @NotNull
    public String getFileName() {
        return fileName;
    }
    
    public int getMethodCount() {
        return methodCount;
    }
    
    @NotNull
    public String getFramework() {
        return framework;
    }
    
    /**
     * Get the full file path where this test should be written.
     * This path is inferred by the AI merger agents based on project structure analysis.
     */
    @Nullable
    public String getFullFilePath() {
        return fullFilePath;
    }
    
    /**
     * Check if a specific file path was inferred by the AI.
     */
    public boolean hasInferredPath() {
        return fullFilePath != null && !fullFilePath.trim().isEmpty();
    }
    
    /**
     * Get the suggested file path based on package and class name.
     * Does not include the project base path.
     */
    @NotNull
    public String getRelativeFilePath() {
        String packagePath = packageName.replace('.', '/');
        return packagePath.isEmpty() ? fileName : packagePath + "/" + fileName;
    }
    
    @Override
    public String toString() {
        return "MergedTestClass{" +
               "className='" + className + '\'' +
               ", packageName='" + packageName + '\'' +
               ", fileName='" + fileName + '\'' +
               ", methodCount=" + methodCount +
               ", framework='" + framework + '\'' +
               '}';
    }
}