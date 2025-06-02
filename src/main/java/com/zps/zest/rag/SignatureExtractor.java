package com.zps.zest.rag;

import com.google.gson.JsonObject;
import com.intellij.psi.*;
import com.intellij.psi.javadoc.PsiDocComment;
import com.intellij.psi.util.PsiTreeUtil; 

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Extracts code signatures from Java and Kotlin files.
 * Includes interfaces and javadoc documentation.
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
                // Include all classes, interfaces, enums, and annotations
                signatures.add(extractJavaClassSignature(aClass));
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
        metadata.addProperty("type", getClassType(clazz));
        metadata.addProperty("name", clazz.getName());
        metadata.addProperty("qualifiedName", clazz.getQualifiedName());
        metadata.addProperty("package", getJavaPackageName(clazz));
        metadata.addProperty("isInterface", clazz.isInterface());
        metadata.addProperty("isEnum", clazz.isEnum());
        metadata.addProperty("isAnnotationType", clazz.isAnnotationType());
        metadata.addProperty("isAbstract", clazz.hasModifierProperty(PsiModifier.ABSTRACT));
        
        // Add javadoc if present
        String javadoc = extractJavadoc(clazz);
        if (javadoc != null) {
            metadata.addProperty("javadoc", javadoc);
        }
        
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
        
        // Add javadoc if present
        String javadoc = extractJavadoc(method);
        if (javadoc != null) {
            metadata.addProperty("javadoc", javadoc);
        }
        
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
        
        // Add javadoc if present
        String javadoc = extractJavadoc(field);
        if (javadoc != null) {
            metadata.addProperty("javadoc", javadoc);
        }
        
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

    private String getClassType(PsiClass clazz) {
        if (clazz.isInterface()) {
            return "interface";
        } else if (clazz.isEnum()) {
            return "enum";
        } else if (clazz.isAnnotationType()) {
            return "annotation";
        } else {
            return "class";
        }
    }

    private String extractJavadoc(PsiJavaDocumentedElement element) {
        PsiDocComment docComment = element.getDocComment();
        if (docComment != null) {
            String text = docComment.getText();
            // Clean up the javadoc text
            text = text.replaceAll("^/\\*\\*", "")
                      .replaceAll("\\*/$", "")
                      .replaceAll("(?m)^\\s*\\*\\s?", "")
                      .trim();
            return text.isEmpty() ? null : text;
        }
        return null;
    }

    private String buildJavaClassSignature(PsiClass clazz) {
        StringBuilder sb = new StringBuilder();
        
        // Modifiers
        if (clazz.hasModifierProperty(PsiModifier.PUBLIC)) sb.append("public ");
        if (clazz.hasModifierProperty(PsiModifier.ABSTRACT) && !clazz.isInterface()) sb.append("abstract ");
        if (clazz.hasModifierProperty(PsiModifier.FINAL)) sb.append("final ");
        
        // Class/Interface/Enum/Annotation
        if (clazz.isInterface()) {
            sb.append("interface ");
        } else if (clazz.isEnum()) {
            sb.append("enum ");
        } else if (clazz.isAnnotationType()) {
            sb.append("@interface ");
        } else {
            sb.append("class ");
        }
        
        sb.append(clazz.getName());
        
        // Type parameters
        PsiTypeParameter[] typeParameters = clazz.getTypeParameters();
        if (typeParameters.length > 0) {
            sb.append("<");
            for (int i = 0; i < typeParameters.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(typeParameters[i].getName());
                PsiClassType[] bounds = typeParameters[i].getExtendsList().getReferencedTypes();
                if (bounds.length > 0 && !bounds[0].equalsToText("java.lang.Object")) {
                    sb.append(" extends ");
                    for (int j = 0; j < bounds.length; j++) {
                        if (j > 0) sb.append(" & ");
                        sb.append(bounds[j].getPresentableText());
                    }
                }
            }
            sb.append(">");
        }
        
        // Extends (not for interfaces, enums, or annotations)
        if (!clazz.isInterface() && !clazz.isEnum() && !clazz.isAnnotationType()) {
            PsiClass superClass = clazz.getSuperClass();
            if (superClass != null && !superClass.getQualifiedName().equals("java.lang.Object")) {
                sb.append(" extends ").append(superClass.getName());
            }
        }
        
        // Implements
        PsiClass[] interfaces = clazz.getInterfaces();
        if (interfaces.length > 0 && !clazz.isAnnotationType()) {
            if (clazz.isInterface()) {
                sb.append(" extends ");
            } else {
                sb.append(" implements ");
            }
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
        if (method.hasModifierProperty(PsiModifier.SYNCHRONIZED)) sb.append("synchronized ");
        
        // Type parameters
        PsiTypeParameter[] typeParameters = method.getTypeParameters();
        if (typeParameters.length > 0) {
            sb.append("<");
            for (int i = 0; i < typeParameters.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(typeParameters[i].getName());
            }
            sb.append("> ");
        }
        
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
        
        // Throws clause
        PsiClassType[] throwsTypes = method.getThrowsList().getReferencedTypes();
        if (throwsTypes.length > 0) {
            sb.append(" throws ");
            for (int i = 0; i < throwsTypes.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(throwsTypes[i].getPresentableText());
            }
        }
        
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
        if (field.hasModifierProperty(PsiModifier.VOLATILE)) sb.append("volatile ");
        if (field.hasModifierProperty(PsiModifier.TRANSIENT)) sb.append("transient ");
        
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
