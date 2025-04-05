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

import java.util.ArrayList;
import java.util.List;

public class FindMethodsTool extends BaseAgentTool {
    private static final Logger LOG = Logger.getInstance(FindMethodsTool.class);
    private final Project project;

    public FindMethodsTool(Project project) {
        super("find_methods", "Finds methods in the current file that match a search term");
        this.project = project;
    }

    @Override
    protected String doExecute(JsonObject params) throws Exception {
        String searchTerm = getStringParam(params, "searchTerm", "");
        if (searchTerm.isEmpty()) {
            return "Error: Search term is required";
        }
        List<String> methods = findMethods(searchTerm);
        return String.join("\n", methods);
    }

    @Override
    public JsonObject getExampleParams() {
        JsonObject params = new JsonObject();
        params.addProperty("searchTerm", "exampleMethod");
        return params;
    }

    private List<String> findMethods(String searchTerm) {
        List<String> results = new ArrayList<>();
        try {
            ApplicationManager.getApplication().runReadAction(() -> {
                Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
                if (editor == null) {
                    results.add("No active editor");
                    return;
                }
                PsiFile psiFile = PsiUtilBase.getPsiFileInEditor(editor, project);
                if (!(psiFile instanceof PsiJavaFile)) {
                    results.add("Not a Java file");
                    return;
                }
                PsiClass[] classes = ((PsiJavaFile) psiFile).getClasses();
                for (PsiClass psiClass : classes) {
                    PsiMethod[] methods = psiClass.getMethods();
                    for (PsiMethod method : methods) {
                        if (method.getName().toLowerCase().contains(searchTerm.toLowerCase()) ||
                            method.getText().toLowerCase().contains(searchTerm.toLowerCase())) {
                            results.add(method.getText());
                        }
                    }
                }
            });
        } catch (Exception e) {
            LOG.error("Error finding methods", e);
            results.add("Error finding methods: " + e.getMessage());
        }
        return results;
    }
}