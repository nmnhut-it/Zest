package com.zps.zest.testgen.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents an individual test method with all its metadata.
 * This is used to capture structured test data from the LLM without string concatenation.
 */
public class GeneratedTestMethod {
    private final String methodName;
    private final String methodBody;
    private final List<String> annotations;
    private final List<String> requiredImports;
    private final List<String> mockedDependencies;
    private final TestPlan.TestScenario associatedScenario;
    private final String setupCode;
    private final String teardownCode;
    
    private GeneratedTestMethod(Builder builder) {
        this.methodName = builder.methodName;
        this.methodBody = builder.methodBody;
        this.annotations = new ArrayList<>(builder.annotations);
        this.requiredImports = new ArrayList<>(builder.requiredImports);
        this.mockedDependencies = new ArrayList<>(builder.mockedDependencies);
        this.associatedScenario = builder.associatedScenario;
        this.setupCode = builder.setupCode;
        this.teardownCode = builder.teardownCode;
    }
    
    @NotNull
    public String getMethodName() {
        return methodName;
    }
    
    @NotNull
    public String getMethodBody() {
        return methodBody;
    }
    
    @NotNull
    public List<String> getAnnotations() {
        return new ArrayList<>(annotations);
    }
    
    @NotNull
    public List<String> getRequiredImports() {
        return new ArrayList<>(requiredImports);
    }
    
    @NotNull
    public List<String> getMockedDependencies() {
        return new ArrayList<>(mockedDependencies);
    }
    
    @Nullable
    public TestPlan.TestScenario getAssociatedScenario() {
        return associatedScenario;
    }
    
    @Nullable
    public String getSetupCode() {
        return setupCode;
    }
    
    @Nullable
    public String getTeardownCode() {
        return teardownCode;
    }
    
    /**
     * Get the complete method code including annotations
     */
    @NotNull
    public String getCompleteMethodCode() {
        StringBuilder code = new StringBuilder();
        
        // Add annotations
        for (String annotation : annotations) {
            if (!annotation.startsWith("@")) {
                code.append("@");
            }
            code.append(annotation).append("\n");
        }
        
        // Add method signature and body
        code.append("public void ").append(methodName).append("() {\n");
        
        // Add indented method body
        String[] lines = methodBody.split("\n");
        for (String line : lines) {
            code.append("    ").append(line).append("\n");
        }
        
        code.append("}");
        
        return code.toString();
    }
    
    @Override
    public String toString() {
        return "GeneratedTestMethod{" +
               "methodName='" + methodName + '\'' +
               ", scenario=" + (associatedScenario != null ? associatedScenario.getName() : "none") +
               ", imports=" + requiredImports.size() +
               ", mocks=" + mockedDependencies.size() +
               '}';
    }
    
    public static class Builder {
        private String methodName;
        private String methodBody;
        private final List<String> annotations = new ArrayList<>();
        private final List<String> requiredImports = new ArrayList<>();
        private final List<String> mockedDependencies = new ArrayList<>();
        private TestPlan.TestScenario associatedScenario;
        private String setupCode;
        private String teardownCode;
        
        public Builder(@NotNull String methodName) {
            this.methodName = methodName;
        }
        
        public Builder methodBody(@NotNull String methodBody) {
            this.methodBody = methodBody;
            return this;
        }
        
        public Builder addAnnotation(@NotNull String annotation) {
            this.annotations.add(annotation);
            return this;
        }
        
        public Builder addImport(@NotNull String importStatement) {
            this.requiredImports.add(importStatement);
            return this;
        }
        
        public Builder addMockedDependency(@NotNull String dependency) {
            this.mockedDependencies.add(dependency);
            return this;
        }
        
        public Builder scenario(@Nullable TestPlan.TestScenario scenario) {
            this.associatedScenario = scenario;
            return this;
        }
        
        public Builder setupCode(@Nullable String setupCode) {
            this.setupCode = setupCode;
            return this;
        }
        
        public Builder teardownCode(@Nullable String teardownCode) {
            this.teardownCode = teardownCode;
            return this;
        }
        
        public GeneratedTestMethod build() {
            if (methodName == null || methodName.isEmpty()) {
                throw new IllegalStateException("Method name is required");
            }
            if (methodBody == null || methodBody.isEmpty()) {
                throw new IllegalStateException("Method body is required");
            }
            return new GeneratedTestMethod(this);
        }
    }
}