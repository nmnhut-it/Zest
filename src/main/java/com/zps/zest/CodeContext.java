package com.zps.zest;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiFile;

/**
 * Context object to maintain state between pipeline stages.
 * Enhanced with test framework utilities accessed via static methods.
 */
public class CodeContext {
    // Core context
    private AnActionEvent event;
    private Project project;
    private PsiFile psiFile;
    private PsiClass targetClass;
    private com.intellij.openapi.editor.Editor editor;
    private ConfigurationManager config;
    private String selectedText;
    private String currentStageType;

    // Class information
    private String packageName;
    private String className;
    private String imports;
    private String classContext;

    // Test generation context
    private String prompt;
    private String apiResponse;
    private String testCode;
    private String testFilePath;
    private boolean useTestWrightModel = true;

    // Test class structure information
    private PsiClass existingTestClass;
    private String testClassStructure;
    private String testSubclassStructures;

    // Legacy compatibility fields (deprecated but kept for backward compatibility)
    @Deprecated
    private String junitVersion;
    @Deprecated
    private boolean isMockitoPresent;

    // Core context getters and setters
    public AnActionEvent getEvent() { return event; }
    public void setEvent(AnActionEvent event) { this.event = event; }

    public Project getProject() { return project; }
    public void setProject(Project project) { this.project = project; }

    public PsiFile getPsiFile() { return psiFile; }
    public void setPsiFile(PsiFile psiFile) { this.psiFile = psiFile; }

    public PsiClass getTargetClass() { return targetClass; }
    public void setTargetClass(PsiClass targetClass) { this.targetClass = targetClass; }

    public com.intellij.openapi.editor.Editor getEditor() { return editor; }
    public void setEditor(com.intellij.openapi.editor.Editor editor) { this.editor = editor; }

    public ConfigurationManager getConfig() { return config; }
    public void setConfig(ConfigurationManager config) { this.config = config; }

    public String getSelectedText() { return selectedText; }
    public void setSelectedText(String selectedText) { this.selectedText = selectedText; }

    public String getCurrentStageType() { return currentStageType; }
    public void setCurrentStageType(String currentStageType) { this.currentStageType = currentStageType; }

    // Class information getters and setters
    public String getPackageName() { return packageName; }
    public void setPackageName(String packageName) { this.packageName = packageName; }

    public String getClassName() { return className; }
    public void setClassName(String className) { this.className = className; }

    public String getImports() { return imports; }
    public void setImports(String imports) { this.imports = imports; }

    public String getClassContext() { return classContext; }
    public void setClassContext(String classContext) { this.classContext = classContext; }

    // Test generation context getters and setters
    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }

    public String getApiResponse() { return apiResponse; }
    public void setApiResponse(String apiResponse) { this.apiResponse = apiResponse; }

    public String getTestCode() { return testCode; }
    public void setTestCode(String testCode) { this.testCode = testCode; }

    public String getTestFilePath() { return testFilePath; }
    public void setTestFilePath(String testFilePath) { this.testFilePath = testFilePath; }

    public boolean isUsingTestWrightModel() { return useTestWrightModel; }
    public void useTestWrightModel(boolean useTestWrightModel) { this.useTestWrightModel = useTestWrightModel; }

    public String getModel(ConfigurationManager config) {
        return useTestWrightModel ? config.getTestModel() : config.getCodeModel();
    }

    // Test class structure information
    public PsiClass getExistingTestClass() { return existingTestClass; }
    public void setExistingTestClass(PsiClass existingTestClass) { this.existingTestClass = existingTestClass; }

    public String getTestClassStructure() { return testClassStructure; }
    public void setTestClassStructure(String testClassStructure) { this.testClassStructure = testClassStructure; }

    public String getTestSubclassStructures() { return testSubclassStructures; }
    public void setTestSubclassStructures(String testSubclassStructures) { this.testSubclassStructures = testSubclassStructures; }

    // Legacy compatibility methods (deprecated - use TestFrameworkUtils instead)
    @Deprecated
    public String getJunitVersion() {
        if (project != null) {
            return TestFrameworkUtils.detectJUnitVersion(project);
        }
        return junitVersion != null ? junitVersion : "JUnit 5";
    }

    @Deprecated
    public void setJunitVersion(String junitVersion) {
        this.junitVersion = junitVersion;
    }

    @Deprecated
    public boolean isMockitoPresent() {
        if (project != null) {
            return TestFrameworkUtils.isMockitoAvailable(project);
        }
        return isMockitoPresent;
    }

    @Deprecated
    public void setMockitoPresent(boolean hasMockito) {
        this.isMockitoPresent = hasMockito;
    }

    // Convenience methods that delegate to TestFrameworkUtils

    /**
     * Gets JUnit version using TestFrameworkUtils.
     */
    public String detectJUnitVersion() {
        return project != null ? TestFrameworkUtils.detectJUnitVersion(project) : "JUnit 5";
    }

    /**
     * Checks if Mockito is available using TestFrameworkUtils.
     */
    public boolean checkMockitoAvailable() {
        return project != null ? TestFrameworkUtils.isMockitoAvailable(project) : false;
    }

    /**
     * Gets framework summary using TestFrameworkUtils.
     */
    public String getFrameworksSummary() {
        return project != null ? TestFrameworkUtils.getFrameworksSummary(project) : "No frameworks detected";
    }

    /**
     * Gets recommended assertion style using TestFrameworkUtils.
     */
    public String getRecommendedAssertionStyle() {
        return project != null ? TestFrameworkUtils.getRecommendedAssertionStyle(project) : "Standard Assertions";
    }

    /**
     * Gets build tool using TestFrameworkUtils.
     */
    public String getBuildTool() {
        return project != null ? TestFrameworkUtils.detectBuildTool(project) : "Unknown";
    }

    /**
     * Gets complete framework information using TestFrameworkUtils.
     */
    public String getCompleteFrameworkInfo() {
        return project != null ? TestFrameworkUtils.getCompleteFrameworkInfo(project) : "Framework information not available";
    }

    /**
     * Gets essential framework information using TestFrameworkUtils.
     */
    public String getEssentialFrameworkInfo() {
        return project != null ? TestFrameworkUtils.getEssentialFrameworkInfo(project) : "No framework info";
    }

    /**
     * Utility method to populate test class structure information.
     */
    public void populateTestClassStructureInfo() {
        if (project != null && testFilePath != null) {
            try {
                // Find the test class
                PsiClass testClass = ClassAnalyzer.findTestClass(project, testFilePath);
                setExistingTestClass(testClass);

                if (testClass != null) {
                    // Collect structure information
                    String structure = ClassAnalyzer.collectTestClassStructure(testClass);
                    setTestClassStructure(structure);

                    // Collect subclass structures
                    String subclassStructures = ClassAnalyzer.collectTestSubclassStructures(project, testClass);
                    setTestSubclassStructures(subclassStructures);
                }
            } catch (Exception e) {
                // Handle silently, structure info will remain null
            }
        }
    }

    /**
     * Get a formatted string of test class structure information for templates.
     */
    public String getFormattedTestClassStructure() {
        if (existingTestClass == null) {
            if (testFilePath != null && !testFilePath.isEmpty()) {
                return "Test class will be created at: " + testFilePath;
            } else {
                return "Test class will be created.";
            }
        }

        StringBuilder formatted = new StringBuilder();
        formatted.append("## Existing Test Class Structure\n\n");
        formatted.append("```java\n");
        formatted.append(testClassStructure != null ? testClassStructure : "// Structure could not be analyzed");
        formatted.append("\n```\n\n");

        if (testSubclassStructures != null && !testSubclassStructures.isEmpty()) {
            formatted.append("## Test Subclasses\n\n");
            formatted.append(testSubclassStructures);
        }

        return formatted.toString();
    }
}