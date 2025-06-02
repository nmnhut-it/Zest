package com.zps.zest.rag;

import com.google.gson.JsonObject;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil; 

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Extracts code signatures from Java and Kotlin files.
 */
public class SignatureExtractor {
    
    public List<CodeSignature> extractFromFile(PsiFile file) {
        List<CodeSignature> signatures = new ArrayList<>();
        
        if (file instanceof PsiJavaFile) {
            extractFromJavaFile((PsiJavaFile) file, signatures);
        }
        
        return signatures;
    }
    
    private void extractFromJavaFile(PsiJavaFile file, List<CodeSignature> signatures) {
        file.accept(new JavaRecursiveElementVisitor() {
            @Override
            public void visitClass(PsiClass aClass) {
                if (!aClass.isEnum() && !aClass.isAnnotationType()) {
                    signatures.add(extractJavaClassSignature(aClass));
                }
                super.visitClass(aClass);
            }
            
            @Override
            public void visitMethod(PsiMethod method) {
                if (!method.isConstructor()) {
                    signatures.add(extractJavaMethodSignature(method));
                }
                super.visitMethod(method);
            }
            
            @Override
            public void visitField(PsiField field) {
                signatures.add(extractJavaFieldSignature(field));
                super.visitField(field);
            }
        });
    }

    private CodeSignature extractJavaClassSignature(PsiClass clazz) {
        JsonObject metadata = new JsonObject();
        metadata.addProperty("type", "class");
        metadata.addProperty("name", clazz.getName());
        metadata.addProperty("qualifiedName", clazz.getQualifiedName());
        metadata.addProperty("package", getJavaPackageName(clazz));
        metadata.addProperty("isInterface", clazz.isInterface());
        metadata.addProperty("isAbstract", clazz.hasModifierProperty(PsiModifier.ABSTRACT));
        
        String signature = buildJavaClassSignature(clazz);
        
        return new CodeSignature(
            clazz.getQualifiedName(),
            signature,
            metadata.toString(),
            clazz.getContainingFile().getVirtualFile().getPath()
        );
    }
    
    private CodeSignature extractJavaMethodSignature(PsiMethod method) {
        PsiClass containingClass = method.getContainingClass();
        
        JsonObject metadata = new JsonObject();
        metadata.addProperty("type", "method");
        metadata.addProperty("name", method.getName());
        metadata.addProperty("class", containingClass != null ? containingClass.getQualifiedName() : "");
        metadata.addProperty("returnType", method.getReturnType() != null ? method.getReturnType().getPresentableText() : "void");
        metadata.addProperty("isStatic", method.hasModifierProperty(PsiModifier.STATIC));
        metadata.addProperty("isAbstract", method.hasModifierProperty(PsiModifier.ABSTRACT));
        
        String signature = buildJavaMethodSignature(method);
        
        String id = containingClass != null 
            ? containingClass.getQualifiedName() + "#" + method.getName()
            : method.getName();
            
        return new CodeSignature(
            id,
            signature,
            metadata.toString(),
            method.getContainingFile().getVirtualFile().getPath()
        );
    }
    
    private CodeSignature extractJavaFieldSignature(PsiField field) {
        PsiClass containingClass = field.getContainingClass();
        
        JsonObject metadata = new JsonObject();
        metadata.addProperty("type", "field");
        metadata.addProperty("name", field.getName());
        metadata.addProperty("class", containingClass != null ? containingClass.getQualifiedName() : "");
        metadata.addProperty("fieldType", field.getType().getPresentableText());
        metadata.addProperty("isStatic", field.hasModifierProperty(PsiModifier.STATIC));
        metadata.addProperty("isFinal", field.hasModifierProperty(PsiModifier.FINAL));
        
        String signature = buildJavaFieldSignature(field);
        
        String id = containingClass != null 
            ? containingClass.getQualifiedName() + "." + field.getName()
            : field.getName();
            
        return new CodeSignature(
            id,
            signature,
            metadata.toString(),
            field.getContainingFile().getVirtualFile().getPath()
        );
    }


    private String buildJavaClassSignature(PsiClass clazz) {
        StringBuilder sb = new StringBuilder();
        
        // Modifiers
        if (clazz.hasModifierProperty(PsiModifier.PUBLIC)) sb.append("public ");
        if (clazz.hasModifierProperty(PsiModifier.ABSTRACT)) sb.append("abstract ");
        if (clazz.hasModifierProperty(PsiModifier.FINAL)) sb.append("final ");
        
        // Class/Interface
        if (clazz.isInterface()) {
            sb.append("interface ");
        } else {
            sb.append("class ");
        }
        
        sb.append(clazz.getName());
        
        // Extends
        PsiClass superClass = clazz.getSuperClass();
        if (superClass != null && !superClass.getQualifiedName().equals("java.lang.Object")) {
            sb.append(" extends ").append(superClass.getName());
        }
        
        // Implements
        PsiClass[] interfaces = clazz.getInterfaces();
        if (interfaces.length > 0) {
            sb.append(" implements ");
            for (int i = 0; i < interfaces.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(interfaces[i].getName());
            }
        }
        
        return sb.toString();
    }
    
    private String buildJavaMethodSignature(PsiMethod method) {
        StringBuilder sb = new StringBuilder();
        
        // Modifiers
        if (method.hasModifierProperty(PsiModifier.PUBLIC)) sb.append("public ");
        if (method.hasModifierProperty(PsiModifier.PROTECTED)) sb.append("protected ");
        if (method.hasModifierProperty(PsiModifier.PRIVATE)) sb.append("private ");
        if (method.hasModifierProperty(PsiModifier.STATIC)) sb.append("static ");
        if (method.hasModifierProperty(PsiModifier.FINAL)) sb.append("final ");
        if (method.hasModifierProperty(PsiModifier.ABSTRACT)) sb.append("abstract ");
        
        // Return type
        PsiType returnType = method.getReturnType();
        sb.append(returnType != null ? returnType.getPresentableText() : "void");
        sb.append(" ");
        
        // Method name
        sb.append(method.getName());
        
        // Parameters
        sb.append("(");
        PsiParameter[] parameters = method.getParameterList().getParameters();
        for (int i = 0; i < parameters.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(parameters[i].getType().getPresentableText());
            sb.append(" ");
            sb.append(parameters[i].getName());
        }
        sb.append(")");
        
        return sb.toString();
    }
    
    private String buildJavaFieldSignature(PsiField field) {
        StringBuilder sb = new StringBuilder();
        
        // Modifiers
        if (field.hasModifierProperty(PsiModifier.PUBLIC)) sb.append("public ");
        if (field.hasModifierProperty(PsiModifier.PROTECTED)) sb.append("protected ");
        if (field.hasModifierProperty(PsiModifier.PRIVATE)) sb.append("private ");
        if (field.hasModifierProperty(PsiModifier.STATIC)) sb.append("static ");
        if (field.hasModifierProperty(PsiModifier.FINAL)) sb.append("final ");
        
        // Type and name
        sb.append(field.getType().getPresentableText());
        sb.append(" ");
        sb.append(field.getName());
        
        return sb.toString();
    }

    
    private String getJavaPackageName(PsiClass clazz) {
        if (clazz == null) return "";
        
        PsiFile file = clazz.getContainingFile();
        if (file instanceof PsiJavaFile) {
            String packageName = ((PsiJavaFile) file).getPackageName();
            return packageName != null ? packageName : "";
        }
        return "";
    }
}
