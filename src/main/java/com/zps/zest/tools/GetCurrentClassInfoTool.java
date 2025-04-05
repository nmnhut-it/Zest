package com.zps.zest.tools;

import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiUtilBase;

public class GetCurrentClassInfoTool extends BaseAgentTool {
    private static final Logger LOG = Logger.getInstance(GetCurrentClassInfoTool.class);
    private final Project project;

    public GetCurrentClassInfoTool(Project project) {
        super("get_current_class_info", "Retrieves information about the current class in the editor");
        this.project = project;
    }

    @Override
    protected String doExecute(JsonObject params) throws Exception {
        return getCurrentClassInfo();
    }

    @Override
    public JsonObject getExampleParams() {
        return new JsonObject(); // No parameters required
    }

    private String getCurrentClassInfo() {
        try {
            return ApplicationManager.getApplication().runReadAction((Computable<String>) () -> {
                Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
                if (editor == null) {
                    return "No active editor";
                }
                PsiFile psiFile = PsiUtilBase.getPsiFileInEditor(editor, project);
                if (!(psiFile instanceof PsiJavaFile)) {
                    return "Not a Java file";
                }
                StringBuilder result = new StringBuilder();
                PsiClass[] classes = ((PsiJavaFile) psiFile).getClasses();
                if (classes.length == 0) {
                    return "No classes found in the current file";
                }
                for (PsiClass psiClass : classes) {
                    result.append("Class: ").append(psiClass.getQualifiedName()).append("\n\n");
                    // Super class
                    if (psiClass.getSuperClass() != null && !psiClass.getSuperClass().getName().equals("Object")) {
                        result.append("Extends: ").append(psiClass.getSuperClass().getQualifiedName()).append("\n");
                    }
                    // Interfaces
                    PsiClassType[] interfaces = psiClass.getImplementsListTypes();
                    if (interfaces.length > 0) {
                        result.append("Implements: ");
                        for (int i = 0; i < interfaces.length; i++) {
                            result.append(interfaces[i].getClassName());
                            if (i < interfaces.length - 1) {
                                result.append(", ");
                            }
                        }
                        result.append("\n");
                    }
                    // Fields
                    result.append("\nFields:\n");
                    for (PsiField field : psiClass.getFields()) {
                        result.append("- ").append(field.getType().getPresentableText())
                                .append(" ").append(field.getName()).append("\n");
                    }
                    // Methods
                    result.append("\nMethods:\n");
                    for (PsiMethod method : psiClass.getMethods()) {
                        result.append("- ").append(method.getName())
                                .append(method.getParameterList().getText());
                        if (method.getReturnType() != null) {
                            result.append(" : ").append(method.getReturnType().getPresentableText());
                        }
                        result.append("\n");
                    }
                    result.append("\n");
                }
                return result.toString();
            });
        } catch (Exception e) {
            LOG.error("Error getting current class info", e);
            return "Error getting current class info: " + e.getMessage();
        }
    }
}