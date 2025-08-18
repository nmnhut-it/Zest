package com.zps.zest.testgen.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Complete result of test generation containing all structured data.
 * This replaces the List<GeneratedTest> return type to preserve all metadata
 * through the entire test generation pipeline.
 */
public class TestGenerationResult {
    // Class-level information
    private final String packageName;
    private final String className;
    private final String framework;
    
    // All imports collected (deduplicated at generation time)
    private final List<String> imports;
    
    // Field declarations for the test class
    private final List<String> fieldDeclarations;
    
    // Setup and teardown code
    private final String beforeAllCode;
    private final String afterAllCode;
    private final String beforeEachCode;
    private final String afterEachCode;
    
    // Test methods with their metadata
    private final List<GeneratedTestMethod> testMethods;
    
    // Original test plan and context
    private final TestPlan testPlan;
    private final TestContext testContext;
    
    public TestGenerationResult(@NotNull String packageName,
                               @NotNull String className,
                               @NotNull String framework,
                               @NotNull List<String> imports,
                               @NotNull List<String> fieldDeclarations,
                               @Nullable String beforeAllCode,
                               @Nullable String afterAllCode,
                               @Nullable String beforeEachCode,
                               @Nullable String afterEachCode,
                               @NotNull List<GeneratedTestMethod> testMethods,
                               @NotNull TestPlan testPlan,
                               @Nullable TestContext testContext) {
        this.packageName = packageName;
        this.className = className;
        this.framework = framework;
        this.imports = new ArrayList<>(imports);
        this.fieldDeclarations = new ArrayList<>(fieldDeclarations);
        this.beforeAllCode = beforeAllCode;
        this.afterAllCode = afterAllCode;
        this.beforeEachCode = beforeEachCode;
        this.afterEachCode = afterEachCode;
        this.testMethods = new ArrayList<>(testMethods);
        this.testPlan = testPlan;
        this.testContext = testContext;
    }
    
    // Getters for all fields
    @NotNull
    public String getPackageName() {
        return packageName;
    }
    
    @NotNull
    public String getClassName() {
        return className;
    }
    
    @NotNull
    public String getFramework() {
        return framework;
    }
    
    @NotNull
    public List<String> getImports() {
        return new ArrayList<>(imports);
    }
    
    @NotNull
    public List<String> getFieldDeclarations() {
        return new ArrayList<>(fieldDeclarations);
    }
    
    @Nullable
    public String getBeforeAllCode() {
        return beforeAllCode;
    }
    
    @Nullable
    public String getAfterAllCode() {
        return afterAllCode;
    }
    
    @Nullable
    public String getBeforeEachCode() {
        return beforeEachCode;
    }
    
    @Nullable
    public String getAfterEachCode() {
        return afterEachCode;
    }
    
    @NotNull
    public List<GeneratedTestMethod> getTestMethods() {
        return new ArrayList<>(testMethods);
    }
    
    @NotNull
    public TestPlan getTestPlan() {
        return testPlan;
    }
    
    @Nullable
    public TestContext getTestContext() {
        return testContext;
    }
    
    // Convenience methods
    public int getMethodCount() {
        return testMethods.size();
    }
    
    public boolean hasSetupCode() {
        return beforeAllCode != null || beforeEachCode != null;
    }
    
    public boolean hasTeardownCode() {
        return afterAllCode != null || afterEachCode != null;
    }
    
    public boolean hasFieldDeclarations() {
        return !fieldDeclarations.isEmpty();
    }
    
    /**
     * Get the target class being tested
     */
    @NotNull
    public String getTargetClass() {
        return testPlan.getTargetClass();
    }
    
    /**
     * Get the target methods being tested
     */
    @NotNull
    public List<String> getTargetMethods() {
        return testPlan.getTargetMethods();
    }
    
    /**
     * Get the test file name
     */
    @NotNull
    public String getFileName() {
        return className + ".java";
    }
    
    /**
     * Check if this is for unit tests
     */
    public boolean isUnitTest() {
        return testPlan.getRecommendedTestType() == TestGenerationRequest.TestType.UNIT_TESTS;
    }
    
    /**
     * Check if this is for integration tests
     */
    public boolean isIntegrationTest() {
        return testPlan.getRecommendedTestType() == TestGenerationRequest.TestType.INTEGRATION_TESTS;
    }
    
    @Override
    public String toString() {
        return "TestGenerationResult{" +
               "className='" + className + '\'' +
               ", packageName='" + packageName + '\'' +
               ", framework='" + framework + '\'' +
               ", methods=" + testMethods.size() +
               ", imports=" + imports.size() +
               ", fields=" + fieldDeclarations.size() +
               ", hasSetup=" + hasSetupCode() +
               ", hasTeardown=" + hasTeardownCode() +
               '}';
    }
}