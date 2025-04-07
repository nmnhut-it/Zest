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
import com.intellij.psi.search.PsiShortNamesCache;
import com.intellij.util.Processor;

import java.util.ArrayList;
import java.util.List;

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
        boolean detailed = getBooleanParam(params, "detailed", false);
        int limit = getIntParam(params, "limit", 20);

        if (searchTerm.isEmpty()) {
            return "Error: Search term is required";
        }
        return searchClasses(searchTerm, detailed, limit);
    }

    private int getIntParam(JsonObject params, String paramName, int defaultValue) {
        if (params != null && params.has(paramName)) {
            try {
                return params.get(paramName).getAsInt();
            } catch (Exception e) {
                // Fall back to default if parsing fails
            }
        }
        return defaultValue;
    }

    @Override
    public JsonObject getExampleParams() {
        JsonObject params = new JsonObject();
        params.addProperty("searchTerm", "exampleClass");
        params.addProperty("detailed", false);
        params.addProperty("limit", 20);
        return params;
    }

    private String searchClasses(String searchTerm, boolean detailed, int limit) {
        try {
            return ApplicationManager.getApplication().runReadAction((Computable<String>) () -> {
                StringBuilder result = new StringBuilder();
                List<PsiClass> matchedClasses = new ArrayList<>();
                final int[] matchCount = {0};

                // Try exact qualified name search first
                try {
                    PsiClass[] exactMatches = JavaPsiFacade.getInstance(project)
                            .findClasses(searchTerm, GlobalSearchScope.projectScope(project));

                    if (exactMatches.length > 0) {
                        for (PsiClass cls : exactMatches) {
                            matchedClasses.add(cls);
                            matchCount[0]++;
                            if (matchCount[0] >= limit) break;
                        }
                    }
                } catch (Exception e) {
                    // Ignore exceptions from exact match search
                    LOG.info("No exact match found for: " + searchTerm);
                }

                // If we haven't reached the limit, try short name search
                if (matchCount[0] < limit) {
                    // Use PsiShortNamesCache for more flexible searching
                    PsiShortNamesCache namesCache = PsiShortNamesCache.getInstance(project);

                    // Try to find classes with names containing the search term
                    namesCache.processAllClassNames(name -> {
                        if (matchCount[0] >= limit) return false;

                        if (name.toLowerCase().contains(searchTerm.toLowerCase())) {
                            PsiClass[] classes = namesCache.getClassesByName(name, GlobalSearchScope.projectScope(project));
                            for (PsiClass cls : classes) {
                                if (!matchedClasses.contains(cls)) {
                                    matchedClasses.add(cls);
                                    matchCount[0]++;
                                    if (matchCount[0] >= limit) return false;
                                }
                            }
                        }
                        return true;
                    }, GlobalSearchScope.projectScope(project), null);
                }

                // Output the results
                if (matchedClasses.isEmpty()) {
                    return "No classes found matching: " + searchTerm;
                }

                result.append("Found ").append(matchedClasses.size())
                        .append(" classes matching '").append(searchTerm).append("':\n\n");

                for (PsiClass cls : matchedClasses) {
                    appendClassInfo(cls, result, detailed);
                }

                return result.toString();
            });
        } catch (Exception e) {
            LOG.error("Error searching classes", e);
            return "Error searching classes: " + e.getMessage();
        }
    }

    private void appendClassInfo(PsiClass cls, StringBuilder result, boolean detailed) {
        result.append("Class: ").append(cls.getQualifiedName()).append("\n");

        if (detailed) {
            // Add superclass info if available
            PsiClass superClass = cls.getSuperClass();
            if (superClass != null && !superClass.getQualifiedName().equals("java.lang.Object")) {
                result.append("  Extends: ").append(superClass.getQualifiedName()).append("\n");
            }

            // Add implemented interfaces
            PsiClass[] interfaces = cls.getInterfaces();
            if (interfaces.length > 0) {
                result.append("  Implements: ");
                for (int i = 0; i < interfaces.length; i++) {
                    result.append(interfaces[i].getQualifiedName());
                    if (i < interfaces.length - 1) {
                        result.append(", ");
                    }
                }
                result.append("\n");
            }
        }

        // Add methods
        result.append("  Methods:\n");
        for (PsiMethod method : cls.getMethods()) {
            result.append("    - ").append(method.getName())
                    .append(method.getParameterList().getText());

            if (detailed && method.getReturnType() != null) {
                result.append(" : ").append(method.getReturnType().getPresentableText());
            }

            result.append("\n");
        }

        result.append("\n");
    }
}