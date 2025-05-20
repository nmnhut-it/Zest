package com.zps.zest;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiFile; /**
 * Context object to maintain state between pipeline stages.
 */
public class CodeContext {
    private AnActionEvent event;
    private Project project;
    private PsiFile psiFile;
    private PsiClass targetClass;
    private String packageName;
    private String className;
    private String imports;
    private String classContext;
    private String prompt;
    private String apiResponse;
    private String testCode;
    private String testFilePath;
    private String junitVersion;
    private com.intellij.openapi.editor.Editor editor;
    private ConfigurationManager config;
    private boolean isMockitoPresent;
    private boolean useTestWrightModel = true;
    private String selectedText;

    // Getters and setters
    public AnActionEvent getEvent() { return event; }
    public void setEvent(AnActionEvent event) { this.event = event; }

    public Project getProject() { return project; }
    public void setProject(Project project) { this.project = project; }

    public PsiFile getPsiFile() { return psiFile; }
    public void setPsiFile(PsiFile psiFile) { this.psiFile = psiFile; }

    public PsiClass getTargetClass() { return targetClass; }
    public void setTargetClass(PsiClass targetClass) { this.targetClass = targetClass; }

    public String getPackageName() { return packageName; }
    public void setPackageName(String packageName) { this.packageName = packageName; }

    public String getClassName() { return className; }
    public void setClassName(String className) { this.className = className; }

    public String getImports() { return imports; }
    public void setImports(String imports) { this.imports = imports; }

    public String getClassContext() { return classContext; }
    public void setClassContext(String classContext) { this.classContext = classContext; }

    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }

    public String getApiResponse() { return apiResponse; }
    public void setApiResponse(String apiResponse) { this.apiResponse = apiResponse; }

    public String getTestCode() { return testCode; }
    public void setTestCode(String testCode) { this.testCode = testCode; }

    public String getTestFilePath() { return testFilePath; }
    public void setTestFilePath(String testFilePath) { this.testFilePath = testFilePath; }

    public String getJunitVersion() { return junitVersion; }
    public void setJunitVersion(String junitVersion) { this.junitVersion = junitVersion; }

    public com.intellij.openapi.editor.Editor getEditor() { return editor; }
    public void setEditor(com.intellij.openapi.editor.Editor editor) { this.editor = editor; }

    public ConfigurationManager getConfig() { return config; }
    public void setConfig(ConfigurationManager config) { this.config = config; }

    public void setMockitoPresent(boolean hasMockito) {
        this.isMockitoPresent = hasMockito;
    }

    public boolean isMockitoPresent() {
        return isMockitoPresent;
    }

    public String getModel(ConfigurationManager config) {
        return useTestWrightModel ? config.getTestModel() : config.getCodeModel();

    }

    public void useTestWrightModel(boolean b) {
        this.useTestWrightModel = b;
    }

    public void setSelectedText(String selectedText) {
        this.selectedText = selectedText;
    }

    public String getSelectedText(){
        return  this.selectedText ;
    }

    public boolean isUsingTestWrightModel() {
        return useTestWrightModel;
    }
}
