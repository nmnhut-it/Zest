package com.zps.zest.langchain4j.tools.impl;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.zps.zest.langchain4j.tools.ThreadSafeCodeExplorationTool;
import org.jetbrains.annotations.NotNull;

/**
 * Tool for getting detailed information about a class or interface.
 */
public class GetClassInfoTool extends ThreadSafeCodeExplorationTool {
    
    public GetClassInfoTool(@NotNull Project project) {
        super(project, "get_class_info", 
            "Get detailed information about a class or interface including fields, methods summary, and hierarchy. " +
            "Example: get_class_info({\"className\": \"java.util.HashMap\"}) - returns HashMap's structure and members. " +
            "Params: className (string, required, simple or fully qualified name)");
    }
    
    @Override
    public JsonObject getParameterSchema() {
        JsonObject schema = new JsonObject();
        JsonObject properties = new JsonObject();
        
        JsonObject className = new JsonObject();
        className.addProperty("type", "string");
        className.addProperty("description", "Name of the class or interface (e.g., 'HashMap' or 'java.util.HashMap')");
        properties.add("className", className);
        
        schema.add("properties", properties);
        JsonArray required = new JsonArray();
        required.add("className");
        schema.add("required", required);
        
        return schema;
    }
    
    @Override
    protected boolean requiresReadAction() {
        return true; // PSI access requires read action
    }
    
    @Override
    protected ToolResult doExecuteInReadAction(JsonObject parameters) {
        String className = getRequiredString(parameters, "className");
        
        try {
            JavaPsiFacade facade = JavaPsiFacade.getInstance(project);
            PsiClass psiClass = facade.findClass(className, GlobalSearchScope.projectScope(project));
            
            if (psiClass == null) {
                return ToolResult.error("Class not found: " + className);
            }
            
            StringBuilder content = new StringBuilder();
            JsonObject metadata = createMetadata();
            
            // Basic info
            content.append("# Class Information: ").append(psiClass.getQualifiedName()).append("\n\n");
            
            // Type and modifiers
            content.append("## Type\n");
            if (psiClass.isInterface()) {
                content.append("Interface");
            } else if (psiClass.isEnum()) {
                content.append("Enum");
            } else if (psiClass.isAnnotationType()) {
                content.append("Annotation");
            } else {
                content.append("Class");
            }
            content.append("\n\n");
            
            // Modifiers
            content.append("## Modifiers\n");
            PsiModifierList modifierList = psiClass.getModifierList();
            if (modifierList != null) {
                if (modifierList.hasModifierProperty(PsiModifier.PUBLIC)) content.append("- public\n");
                if (modifierList.hasModifierProperty(PsiModifier.ABSTRACT)) content.append("- abstract\n");
                if (modifierList.hasModifierProperty(PsiModifier.FINAL)) content.append("- final\n");
                if (modifierList.hasModifierProperty(PsiModifier.STATIC)) content.append("- static\n");
            }
            content.append("\n");
            
            // Package
            PsiJavaFile file = (PsiJavaFile) psiClass.getContainingFile();
            if (file != null) {
                content.append("## Package\n");
                content.append("`").append(file.getPackageName()).append("`\n\n");
                
                // Add absolute file path
                VirtualFile virtualFile = file.getVirtualFile();
                if (virtualFile != null) {
                    content.append("## File Path\n");
                    content.append("`").append(virtualFile.getPath()).append("`\n\n");
                }
            }
            
            // Hierarchy
            content.append("## Hierarchy\n");
            
            // Superclass
            PsiClass superClass = psiClass.getSuperClass();
            if (superClass != null && !"java.lang.Object".equals(superClass.getQualifiedName())) {
                content.append("**Extends:** `").append(superClass.getQualifiedName()).append("`\n");
            }
            
            // Interfaces
            PsiClass[] interfaces = psiClass.getInterfaces();
            if (interfaces.length > 0) {
                content.append("**Implements:**\n");
                for (PsiClass iface : interfaces) {
                    content.append("- `").append(iface.getQualifiedName()).append("`\n");
                }
            }
            content.append("\n");
            
            // Fields
            PsiField[] fields = psiClass.getFields();
            if (fields.length > 0) {
                content.append("## Fields (").append(fields.length).append(")\n");
                int publicFields = 0, privateFields = 0, protectedFields = 0;
                
                for (PsiField field : fields) {
                    if (field.hasModifierProperty(PsiModifier.PUBLIC)) publicFields++;
                    else if (field.hasModifierProperty(PsiModifier.PRIVATE)) privateFields++;
                    else if (field.hasModifierProperty(PsiModifier.PROTECTED)) protectedFields++;
                }
                
                content.append("- Public: ").append(publicFields).append("\n");
                content.append("- Protected: ").append(protectedFields).append("\n");
                content.append("- Private: ").append(privateFields).append("\n");
                content.append("- Package-private: ").append(fields.length - publicFields - privateFields - protectedFields).append("\n\n");
                
                // List important fields
                content.append("### Key Fields:\n");
                for (PsiField field : fields) {
                    if (field.hasModifierProperty(PsiModifier.PUBLIC) || 
                        field.hasModifierProperty(PsiModifier.PROTECTED)) {
                        content.append("- ");
                        if (field.hasModifierProperty(PsiModifier.STATIC)) content.append("static ");
                        if (field.hasModifierProperty(PsiModifier.FINAL)) content.append("final ");
                        content.append(field.getType().getPresentableText()).append(" ");
                        content.append("**").append(field.getName()).append("**\n");
                    }
                }
                content.append("\n");
            }
            
            // Methods summary
            PsiMethod[] methods = psiClass.getMethods();
            int constructors = 0;
            int publicMethods = 0;
            int privateMethods = 0;
            int protectedMethods = 0;
            
            for (PsiMethod method : methods) {
                if (method.isConstructor()) {
                    constructors++;
                } else {
                    if (method.hasModifierProperty(PsiModifier.PUBLIC)) publicMethods++;
                    else if (method.hasModifierProperty(PsiModifier.PRIVATE)) privateMethods++;
                    else if (method.hasModifierProperty(PsiModifier.PROTECTED)) protectedMethods++;
                }
            }
            
            content.append("## Methods Summary\n");
            content.append("- Constructors: ").append(constructors).append("\n");
            content.append("- Public methods: ").append(publicMethods).append("\n");
            content.append("- Protected methods: ").append(protectedMethods).append("\n");
            content.append("- Private methods: ").append(privateMethods).append("\n");
            content.append("- Total methods: ").append(methods.length).append("\n\n");
            
            // Inner classes
            PsiClass[] innerClasses = psiClass.getInnerClasses();
            if (innerClasses.length > 0) {
                content.append("## Inner Classes (").append(innerClasses.length).append(")\n");
                for (PsiClass inner : innerClasses) {
                    content.append("- ").append(inner.getName());
                    if (inner.hasModifierProperty(PsiModifier.STATIC)) content.append(" (static)");
                    content.append("\n");
                }
            }
            
            // Metadata
            metadata.addProperty("qualifiedName", psiClass.getQualifiedName());
            metadata.addProperty("isInterface", psiClass.isInterface());
            metadata.addProperty("isEnum", psiClass.isEnum());
            metadata.addProperty("isAnnotation", psiClass.isAnnotationType());
            metadata.addProperty("fieldCount", fields.length);
            metadata.addProperty("methodCount", methods.length);
            metadata.addProperty("constructorCount", constructors);
            
            return ToolResult.success(content.toString(), metadata);
            
        } catch (Exception e) {
            return ToolResult.error("Failed to get class info: " + e.getMessage());
        }
    }
}
