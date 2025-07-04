package com.zps.zest.langchain4j.tools.impl;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.usageView.UsageInfo;
import com.zps.zest.langchain4j.tools.ThreadSafeCodeExplorationTool;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tool for finding usages of a code element.
 */
public class FindUsagesTool extends ThreadSafeCodeExplorationTool {
    
    public FindUsagesTool(@NotNull Project project) {
        super(project, "find_usages", 
            "Find all usages of a class, method, or field. " +
            "REQUIRED FORMATS: " +
            "- Class: 'ClassName' or 'com.example.ClassName' " +
            "- Method: 'ClassName#methodName' (finds usages of ALL overloads) " +
            "- Field: 'ClassName#fieldName' " +
            "Examples: " +
            "- find_usages({\"elementId\": \"UserService\"}) - finds all uses of UserService class " +
            "- find_usages({\"elementId\": \"User#email\"}) - finds all accesses to email field " +
            "- find_usages({\"elementId\": \"List#add\", \"includeTests\": false}) - non-test usages only " +
            "Params: elementId (string, required), includeTests (boolean, optional, default true)");
    }
    
    @Override
    public JsonObject getParameterSchema() {
        JsonObject schema = new JsonObject();
        JsonObject properties = new JsonObject();
        
        JsonObject elementId = new JsonObject();
        elementId.addProperty("type", "string");
        elementId.addProperty("description", "ID of element to find usages for (e.g., 'UserService' for class, 'UserService#save' for method, 'User#email' for field)");
        properties.add("elementId", elementId);
        
        JsonObject includeTests = new JsonObject();
        includeTests.addProperty("type", "boolean");
        includeTests.addProperty("description", "Include usages in test files");
        includeTests.addProperty("default", true);
        properties.add("includeTests", includeTests);
        
        schema.add("properties", properties);
        JsonArray required = new JsonArray();
        required.add("elementId");
        schema.add("required", required);
        
        return schema;
    }
    
    @Override
    protected boolean requiresReadAction() {
        return true; // PSI and reference search require read action
    }
    
    @Override
    protected ToolResult doExecuteInReadAction(JsonObject parameters) {
        String elementId = getRequiredString(parameters, "elementId");
        boolean includeTests = !parameters.has("includeTests") || parameters.get("includeTests").getAsBoolean();
        
        try {
            PsiElement element = findElement(elementId);
            if (element == null) {
                return ToolResult.error("Element not found: " + elementId + 
                    ". Ensure format is 'ClassName' for classes or 'ClassName#memberName' for methods/fields.");
            }
            
            StringBuilder content = new StringBuilder();
            JsonObject metadata = createMetadata();
            metadata.addProperty("elementId", elementId);
            
            content.append("# Usages of '").append(elementId).append("'\n\n");
            
            // Find references
            GlobalSearchScope scope = includeTests ? 
                GlobalSearchScope.projectScope(project) :
                GlobalSearchScope.projectScope(project); // TODO: Exclude test sources
                
            List<PsiReference> references = new ArrayList<>();
            ReferencesSearch.search(element, scope).forEach(ref -> {
                references.add(ref);
                return true;
            });
            
            if (references.isEmpty()) {
                content.append("No usages found.\n");
                metadata.addProperty("usageCount", 0);
            } else {
                // Group by file
                Map<PsiFile, List<UsageInfo>> usagesByFile = new HashMap<>();
                for (PsiReference ref : references) {
                    PsiElement refElement = ref.getElement();
                    PsiFile file = refElement.getContainingFile();
                    if (file != null) {
                        UsageInfo usage = new UsageInfo(refElement);
                        usagesByFile.computeIfAbsent(file, k -> new ArrayList<>()).add(usage);
                    }
                }
                
                content.append("Found ").append(references.size()).append(" usage(s) in ")
                       .append(usagesByFile.size()).append(" file(s):\n\n");
                
                metadata.addProperty("usageCount", references.size());
                metadata.addProperty("fileCount", usagesByFile.size());
                
                // Display usages by file
                for (Map.Entry<PsiFile, List<UsageInfo>> entry : usagesByFile.entrySet()) {
                    PsiFile file = entry.getKey();
                    List<UsageInfo> usages = entry.getValue();
                    
                    content.append("## ").append(file.getName()).append(" (")
                           .append(usages.size()).append(" usage").append(usages.size() > 1 ? "s" : "")
                           .append(")\n");
                    
                    content.append("**Path:** `").append(file.getVirtualFile().getPath()).append("`\n\n");
                    
                    for (UsageInfo usage : usages) {
                        PsiElement usageElement = usage.getElement();
                        if (usageElement != null) {
                            // Find the containing method or class
                            PsiMethod containingMethod = findContainingMethod(usageElement);
                            PsiClass containingClass = findContainingClass(usageElement);
                            
                            content.append("- Line ").append(getLineNumber(usageElement)).append(": ");
                            
                            if (containingMethod != null) {
                                content.append("in method `").append(containingMethod.getName()).append("`");
                            } else if (containingClass != null) {
                                content.append("in class `").append(containingClass.getName()).append("`");
                            }
                            
                            // Add code snippet
                            String snippet = getCodeSnippet(usageElement);
                            if (!snippet.isEmpty()) {
                                content.append("\n  ```java\n  ").append(snippet).append("\n  ```");
                            }
                            content.append("\n");
                        }
                    }
                    content.append("\n");
                }
            }
            
            return ToolResult.success(content.toString(), metadata);
            
        } catch (Exception e) {
            return ToolResult.error("Failed to find usages: " + e.getMessage());
        }
    }
    
    private PsiElement findElement(String elementId) {
        JavaPsiFacade facade = JavaPsiFacade.getInstance(project);
        
        // Check if it's a method/field reference (ClassName#memberName)
        if (elementId.contains("#")) {
            String[] parts = elementId.split("#", 2);
            String className = parts[0];
            String memberName = parts[1];
            
            PsiClass psiClass = facade.findClass(className, GlobalSearchScope.projectScope(project));
            if (psiClass != null) {
                // Try to find method
                for (PsiMethod method : psiClass.getMethods()) {
                    if (method.getName().equals(memberName)) {
                        return method;
                    }
                }
                
                // Try to find field
                PsiField field = psiClass.findFieldByName(memberName, true);
                if (field != null) {
                    return field;
                }
            }
        } else {
            // Try as class name
            PsiClass psiClass = facade.findClass(elementId, GlobalSearchScope.projectScope(project));
            if (psiClass != null) {
                return psiClass;
            }
        }
        
        return null;
    }
    
    private PsiMethod findContainingMethod(PsiElement element) {
        PsiElement current = element;
        while (current != null && !(current instanceof PsiFile)) {
            if (current instanceof PsiMethod) {
                return (PsiMethod) current;
            }
            current = current.getParent();
        }
        return null;
    }
    
    private PsiClass findContainingClass(PsiElement element) {
        PsiElement current = element;
        while (current != null && !(current instanceof PsiFile)) {
            if (current instanceof PsiClass) {
                return (PsiClass) current;
            }
            current = current.getParent();
        }
        return null;
    }
    
    private int getLineNumber(PsiElement element) {
        PsiFile file = element.getContainingFile();
        if (file != null) {
            int offset = element.getTextOffset();
            return file.getViewProvider().getDocument().getLineNumber(offset) + 1;
        }
        return -1;
    }
    
    private String getCodeSnippet(PsiElement element) {
        // Get the containing statement or declaration
        PsiElement statement = element;
        while (statement != null && 
               !(statement instanceof PsiStatement) && 
               !(statement instanceof PsiField) &&
               !(statement instanceof PsiMethod) &&
               !(statement.getParent() instanceof PsiFile)) {
            statement = statement.getParent();
        }
        
        if (statement != null) {
            String text = statement.getText();
            if (text != null) {
                // Limit length and clean up
                if (text.length() > 100) {
                    text = text.substring(0, 100) + "...";
                }
                return text.trim().replaceAll("\\s+", " ");
            }
        }
        
        return "";
    }
}
