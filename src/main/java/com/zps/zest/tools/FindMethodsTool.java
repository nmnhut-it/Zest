package com.zps.zest.tools;

import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiShortNamesCache;
import com.intellij.util.Processor;

import java.util.ArrayList;
import java.util.List;

public class FindMethodsTool extends BaseAgentTool {
    private static final Logger LOG = Logger.getInstance(FindMethodsTool.class);
    private final Project project;

    public FindMethodsTool(Project project) {
        super("find_methods",
                "Finds methods across the project that match a search term. " +
                        "Example: find_methods({\"searchTerm\": \"save\", \"scope\": \"project\", \"exactMatch\": false}) - finds all methods containing 'save'. " +
                        "Params: searchTerm (string, required), scope (string, optional: 'project', 'module', or 'dependencies'), exactMatch (boolean, optional)");
        this.project = project;
    }
    @Override
    protected String doExecute(JsonObject params) throws Exception {
        String searchTerm = getStringParam(params, "searchTerm", "");
        if (searchTerm.isEmpty()) {
            return "Error: Search term is required";
        }

        String scope = getStringParam(params, "scope", "project"); // "project", "module", "dependencies"
        boolean exactMatch = getBooleanParam(params, "exactMatch", false);

        List<String> methods = findMethods(searchTerm, scope, exactMatch);

        if (methods.isEmpty()) {
            return "No methods found matching: " + searchTerm;
        }

        return String.join("\n\n", methods);
    }

    @Override
    public JsonObject getExampleParams() {
        JsonObject params = new JsonObject();
        params.addProperty("searchTerm", "exampleMethod");
        params.addProperty("scope", "project"); // optional: "project", "module", "dependencies"
        params.addProperty("exactMatch", false); // optional: true for exact name match
        return params;
    }

    private List<String> findMethods(String searchTerm, String scopeType, boolean exactMatch) {
        List<String> results = new ArrayList<>();

        try {
            ApplicationManager.getApplication().runReadAction(() -> {
                // Determine search scope
                GlobalSearchScope searchScope = getSearchScope(scopeType);

                // Get PsiShortNamesCache for method search
                PsiShortNamesCache cache = PsiShortNamesCache.getInstance(project);

                if (exactMatch) {
                    // Search for exact method name
                    PsiMethod[] methods = cache.getMethodsByName(searchTerm, searchScope);
                    for (PsiMethod method : methods) {
                        results.add(formatMethodResult(method));
                    }
                } else {
                    // Search for methods containing the search term
                    cache.processMethodsWithName(searchTerm, searchScope, new Processor<PsiMethod>() {
                        @Override
                        public boolean process(PsiMethod method) {
                            results.add(formatMethodResult(method));
                            return true; // continue processing
                        }
                    });

                    // Also search for partial matches if not exact match
                    String[] allMethodNames = cache.getAllMethodNames();
                    for (String methodName : allMethodNames) {
                        if (methodName.toLowerCase().contains(searchTerm.toLowerCase()) &&
                                !methodName.equals(searchTerm)) {
                            PsiMethod[] methods = cache.getMethodsByName(methodName, searchScope);
                            for (PsiMethod method : methods) {
                                results.add(formatMethodResult(method));
                            }
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

    private GlobalSearchScope getSearchScope(String scopeType) {
        switch (scopeType.toLowerCase()) {
            case "module":
                return GlobalSearchScope.projectScope(project);
            case "dependencies":
                return GlobalSearchScope.allScope(project);
            case "project":
            default:
                return GlobalSearchScope.projectScope(project);
        }
    }

    private String formatMethodResult(PsiMethod method) {
        StringBuilder result = new StringBuilder();

        // Add file location
        PsiFile containingFile = method.getContainingFile();
        if (containingFile != null) {
            result.append("File: ").append(containingFile.getVirtualFile().getPath()).append("\n");
        }

        // Add class name
        PsiClass containingClass = method.getContainingClass();
        if (containingClass != null) {
            result.append("Class: ").append(containingClass.getQualifiedName()).append("\n");
        }

        // Add method signature
        result.append("Method: ").append(method.getName());

        // Add parameters
        result.append("(");
        PsiParameter[] parameters = method.getParameterList().getParameters();
        for (int i = 0; i < parameters.length; i++) {
            if (i > 0) result.append(", ");
            result.append(parameters[i].getType().getPresentableText());
            result.append(" ").append(parameters[i].getName());
        }
        result.append(")");

        // Add return type
        PsiType returnType = method.getReturnType();
        if (returnType != null) {
            result.append(" : ").append(returnType.getPresentableText());
        }

        // Add method body preview (first few lines)
        result.append("\n");
        PsiCodeBlock body = method.getBody();
        if (body != null) {
            String bodyText = body.getText();
            String[] lines = bodyText.split("\n");
            int linesToShow = Math.min(5, lines.length);
            for (int i = 0; i < linesToShow; i++) {
                result.append("  ").append(lines[i].trim()).append("\n");
            }
            if (lines.length > 5) {
                result.append("  ...\n");
            }
        }

        return result.toString();
    }

}