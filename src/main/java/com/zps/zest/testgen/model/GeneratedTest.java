package com.zps.zest.testgen.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public class GeneratedTest {
    private final String testName;
    private final String testClassName;
    private final String testContent;
    private final TestPlan.TestScenario scenario;
    private final String fileName;
    private final String packageName;
    private final List<String> imports;
    private final List<String> annotations;
    private final String framework;
    
    public GeneratedTest(@NotNull String testName,
                        @NotNull String testClassName,
                        @NotNull String testContent,
                        @NotNull TestPlan.TestScenario scenario,
                        @NotNull String fileName,
                        @NotNull String packageName,
                        @NotNull List<String> imports,
                        @NotNull List<String> annotations,
                        @NotNull String framework) {
        this.testName = testName;
        this.testClassName = testClassName;
        this.testContent = testContent;
        this.scenario = scenario;
        this.fileName = fileName;
        this.packageName = packageName;
        this.imports = new ArrayList<>(imports);
        this.annotations = new ArrayList<>(annotations);
        this.framework = framework;
    }
    
    @NotNull
    public String getTestName() {
        return testName;
    }
    
    @NotNull
    public String getTestClassName() {
        return testClassName;
    }
    
    @NotNull
    public String getTestContent() {
        return testContent;
    }
    
    @NotNull
    public TestPlan.TestScenario getScenario() {
        return scenario;
    }
    
    @NotNull
    public String getFileName() {
        return fileName;
    }
    
    @NotNull
    public String getPackageName() {
        return packageName;
    }
    
    @NotNull
    public List<String> getImports() {
        return new ArrayList<>(imports);
    }
    
    @NotNull
    public List<String> getAnnotations() {
        return new ArrayList<>(annotations);
    }
    
    @NotNull
    public String getFramework() {
        return framework;
    }
    
    @NotNull
    public String getTestCode() {
        return testContent;
    }
    
    @NotNull
    public String getFullContent() {
        StringBuilder content = new StringBuilder();
        
        // Package declaration
        if (!packageName.isEmpty()) {
            content.append("package ").append(packageName).append(";\n\n");
        }
        
        // Imports
        if (!imports.isEmpty()) {
            for (String importStmt : imports) {
                content.append("import ").append(importStmt).append(";\n");
            }
            content.append("\n");
        }
        
        // Class content (which should include class declaration and methods)
        content.append(testContent);
        
        return content.toString();
    }
    
    public boolean isUnitTest() {
        return scenario.getType() == TestPlan.TestScenario.Type.UNIT ||
               scenario.getType() == TestPlan.TestScenario.Type.EDGE_CASE ||
               scenario.getType() == TestPlan.TestScenario.Type.ERROR_HANDLING;
    }
    
    public boolean isIntegrationTest() {
        return scenario.getType() == TestPlan.TestScenario.Type.INTEGRATION;
    }
    
    @Override
    public String toString() {
        return "GeneratedTest{" +
               "testName='" + testName + '\'' +
               ", testClassName='" + testClassName + '\'' +
               ", fileName='" + fileName + '\'' +
               ", framework='" + framework + '\'' +
               ", scenario=" + scenario.getName() +
               '}';
    }
}