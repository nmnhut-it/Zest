package com.zps.zest.tools;

import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.search.GlobalSearchScope;

public class SearchClassesTool extends BaseAgentTool {
    private static final Logger LOG = Logger.getInstance(SearchClassesTool.class);
    private final Project project;

    public SearchClassesTool(Project project) {
        super("search_classes", "Searches for classes related to a search term");
        this.project = project;
    }

    @Override
    protected String doExecute(JsonObject params) throws Exception {
        String searchTerm = getStringParam(params, "searchTerm", "");
        if (searchTerm.isEmpty()) {
            return "Error: Search term is required";
        }
        return searchClasses(searchTerm);
    }

    @Override
    public JsonObject getExampleParams() {
        JsonObject params = new JsonObject();
        params.addProperty("searchTerm", "exampleClass");
        return params;
    }

    private String searchClasses(String searchTerm) {
        try {
            return ApplicationManager.getApplication().runReadAction((Computable<String>) () -> {
                StringBuilder result = new StringBuilder();
                // Find classes by name
                PsiClass[] classes = JavaPsiFacade.getInstance(project)
                        .findClasses(searchTerm, GlobalSearchScope.projectScope(project));
                if (classes.length == 0) {
                    // Try a more flexible search
                    PsiClass[] allClasses = JavaPsiFacade.getInstance(project)
                            .findClasses("_", GlobalSearchScope.projectScope(project));
                    for (PsiClass cls : allClasses) {
                        if (cls.getName() != null && cls.getName().toLowerCase()
                                .contains(searchTerm.toLowerCase())) {
                            appendClassInfo(cls, result);
                        }
                    }
                } else {
                    // Process exact matches
                    for (PsiClass cls : classes) {
                        appendClassInfo(cls, result);
                    }
                }
                return result.length() > 0 ? result.toString() : "No classes found matching: " + searchTerm;
            });
        } catch (Exception e) {
            LOG.error("Error searching classes", e);
            return "Error searching classes: " + e.getMessage();
        }
    }

    private void appendClassInfo(PsiClass cls, StringBuilder result) {
        result.append("Class: ").append(cls.getQualifiedName()).append("\n");
        result.append("  Methods:\n");
        for (PsiMethod method : cls.getMethods()) {
            result.append("    - ").append(method.getName())
                    .append(method.getParameterList().getText()).append("\n");
        }
        result.append("\n");
    }
}