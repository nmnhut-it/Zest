package com.zps.zest.langchain4j.tools.impl;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.AllClassesSearch;
import com.zps.zest.langchain4j.tools.ThreadSafeCodeExplorationTool;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Tool for finding methods in a class or interface.
 */
public class FindMethodsTool extends ThreadSafeCodeExplorationTool {
    
    public FindMethodsTool(@NotNull Project project) {
        super(project, "find_methods", 
            "Find all methods in a class or interface. " +
            "Examples: " +
            "- find_methods({\"className\": \"ArrayList\"}) - finds methods in java.util.ArrayList " +
            "- find_methods({\"className\": \"java.util.HashMap\", \"includeInherited\": true}) - includes Object methods " +
            "- find_methods({\"className\": \"MyService\"}) - finds methods in your MyService class " +
            "NOTE: For ambiguous names (e.g., 'List'), use fully qualified name to avoid wrong class. " +
            "Params: className (string, required), includeInherited (boolean, optional, default false)");
    }
    
    @Override
    public JsonObject getParameterSchema() {
        JsonObject schema = new JsonObject();
        JsonObject properties = new JsonObject();
        
        JsonObject className = new JsonObject();
        className.addProperty("type", "string");
        className.addProperty("description", "Name of the class or interface (simple or fully qualified)");
        properties.add("className", className);
        
        JsonObject includeInherited = new JsonObject();
        includeInherited.addProperty("type", "boolean");
        includeInherited.addProperty("description", "Include methods inherited from parent classes/interfaces");
        includeInherited.addProperty("default", false);
        properties.add("includeInherited", includeInherited);
        
        schema.add("properties", properties);
        JsonArray required = new JsonArray();
        required.add("className");
        schema.add("required", required);
        
        return schema;
    }
    
    @Override
    protected boolean requiresReadAction() {
        // PSI access requires read action
        return true;
    }
    
    @Override
    protected ToolResult doExecuteInReadAction(JsonObject parameters) {
        String className = getRequiredString(parameters, "className");
        boolean includeInherited = parameters.has("includeInherited") && 
                                  parameters.get("includeInherited").getAsBoolean();
        
        try {
            PsiClass psiClass = findClass(className);
            if (psiClass == null) {
                return ToolResult.error("Class not found: " + className);
            }
            
            StringBuilder content = new StringBuilder();
            JsonObject metadata = createMetadata();
            metadata.addProperty("className", psiClass.getQualifiedName());
            metadata.addProperty("isInterface", psiClass.isInterface());
            metadata.addProperty("isAbstract", psiClass.hasModifierProperty(PsiModifier.ABSTRACT));
            
            content.append("Methods in ").append(psiClass.isInterface() ? "interface" : "class")
                   .append(" '").append(psiClass.getQualifiedName()).append("':\n\n");
            
            // Get methods
            PsiMethod[] methods = includeInherited ? 
                psiClass.getAllMethods() : psiClass.getMethods();
            
            List<MethodInfo> methodInfos = new ArrayList<>();
            for (PsiMethod method : methods) {
                methodInfos.add(new MethodInfo(method, psiClass));
            }
            
            // Group by visibility
            appendMethodsByVisibility(content, methodInfos, "Public", PsiModifier.PUBLIC);
            appendMethodsByVisibility(content, methodInfos, "Protected", PsiModifier.PROTECTED);
            appendMethodsByVisibility(content, methodInfos, "Package-private", null);
            appendMethodsByVisibility(content, methodInfos, "Private", PsiModifier.PRIVATE);
            
            metadata.addProperty("methodCount", methods.length);
            
            // Add constructor info
            PsiMethod[] constructors = psiClass.getConstructors();
            if (constructors.length > 0) {
                content.append("\n### Constructors (").append(constructors.length).append(")\n");
                for (PsiMethod constructor : constructors) {
                    content.append("- ").append(formatMethod(constructor)).append("\n");
                }
            }
            
            return ToolResult.success(content.toString(), metadata);
            
        } catch (Exception e) {
            return ToolResult.error("Failed to find methods: " + e.getMessage());
        }
    }
    
    private PsiClass findClass(String className) {
        // Try to find by fully qualified name first
        JavaPsiFacade facade = JavaPsiFacade.getInstance(project);
        PsiClass psiClass = facade.findClass(className, GlobalSearchScope.projectScope(project));
        
        if (psiClass == null) {
            // Try to find by simple name
            List<PsiClass> classes = new ArrayList<>();
            AllClassesSearch.search(GlobalSearchScope.projectScope(project), project)
                .forEach(cls -> {
                    if (cls.getName() != null && cls.getName().equals(className)) {
                        classes.add(cls);
                    }
                });
            
            if (classes.isEmpty()) {
                return null;
            }
            
            if (classes.size() > 1) {
                // Multiple matches - create error with all options
                StringBuilder error = new StringBuilder();
                error.append("Multiple classes found with name '").append(className).append("':\n\n");
                
                for (int i = 0; i < classes.size(); i++) {
                    PsiClass cls = classes.get(i);
                    error.append(i + 1).append(". ").append(cls.getQualifiedName()).append("\n");
                }
                
                error.append("\nPlease use the fully qualified class name.");
                error.append("\nExample: find_methods({\"className\": \"").append(classes.get(0).getQualifiedName()).append("\"})");
                
                throw new RuntimeException(error.toString());
            }
            
            psiClass = classes.get(0);
        }
        
        return psiClass;
    }
    
    private void appendMethodsByVisibility(StringBuilder content, List<MethodInfo> methods, 
                                         String visibility, String modifier) {
        List<MethodInfo> filtered = methods.stream()
            .filter(m -> {
                if (modifier == null) {
                    // Package-private: no public, protected, or private modifier
                    return !m.method.hasModifierProperty(PsiModifier.PUBLIC) &&
                           !m.method.hasModifierProperty(PsiModifier.PROTECTED) &&
                           !m.method.hasModifierProperty(PsiModifier.PRIVATE);
                } else {
                    return m.method.hasModifierProperty(modifier);
                }
            })
            .toList();
        
        if (!filtered.isEmpty()) {
            content.append("\n### ").append(visibility).append(" Methods (").append(filtered.size()).append(")\n");
            for (MethodInfo info : filtered) {
                content.append("- ").append(formatMethod(info.method));
                if (info.isInherited()) {
                    content.append(" *(inherited from ").append(info.getDeclaringClass()).append(")*");
                }
                content.append("\n");
            }
        }
    }
    
    private String formatMethod(PsiMethod method) {
        StringBuilder sb = new StringBuilder();
        
        // Add modifiers (except visibility which is already grouped)
        if (method.hasModifierProperty(PsiModifier.STATIC)) sb.append("static ");
        if (method.hasModifierProperty(PsiModifier.FINAL)) sb.append("final ");
        if (method.hasModifierProperty(PsiModifier.ABSTRACT)) sb.append("abstract ");
        if (method.hasModifierProperty(PsiModifier.SYNCHRONIZED)) sb.append("synchronized ");
        
        // Return type
        PsiType returnType = method.getReturnType();
        if (returnType != null) {
            sb.append(returnType.getPresentableText()).append(" ");
        }
        
        // Method name
        sb.append("**").append(method.getName()).append("**");
        
        // Parameters
        sb.append("(");
        PsiParameter[] parameters = method.getParameterList().getParameters();
        for (int i = 0; i < parameters.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(parameters[i].getType().getPresentableText());
            sb.append(" ").append(parameters[i].getName());
        }
        sb.append(")");
        
        // Exceptions
        PsiClassType[] exceptions = method.getThrowsList().getReferencedTypes();
        if (exceptions.length > 0) {
            sb.append(" throws ");
            for (int i = 0; i < exceptions.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(exceptions[i].getPresentableText());
            }
        }
        
        return sb.toString();
    }
    
    private static class MethodInfo {
        final PsiMethod method;
        final PsiClass containingClass;
        final PsiClass declaringClass;
        
        MethodInfo(PsiMethod method, PsiClass containingClass) {
            this.method = method;
            this.containingClass = containingClass;
            this.declaringClass = method.getContainingClass();
        }
        
        boolean isInherited() {
            return declaringClass != null && !declaringClass.equals(containingClass);
        }
        
        String getDeclaringClass() {
            return declaringClass != null ? declaringClass.getName() : "unknown";
        }
    }
}
