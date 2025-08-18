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
    
    /**
     * Get the full content of this test.
     * For individual test methods, this returns just the method code.
     * For complete classes, this returns the full class content.
     */
    @NotNull
    public String getFullContent() {
        // If testContent already includes package/imports/class, return as-is
        if (testContent.contains("class " + testClassName)) {
            return testContent;
        }
        
        // Otherwise, this is just a method - return the method code
        return testContent;
    }
    
    /**
     * Check if this represents a complete test class or just a method
     */
    public boolean isCompleteClass() {
        return testContent.contains("class " + testClassName);
    }
    
    /**
     * Get just the method code (without class wrapper)
     */
    @NotNull
    public String getMethodCode() {
        if (isCompleteClass()) {
            // Extract method from complete class
            // This is a simplified approach - real implementation would use PSI
            int methodStart = testContent.indexOf("@Test");
            if (methodStart > 0) {
                return testContent.substring(methodStart);
            }
        }
        return testContent;
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