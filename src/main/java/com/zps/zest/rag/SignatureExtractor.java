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
                // Include constructors and regular methods
                if (method.isConstructor()) {
                    signatures.add(extractJavaConstructorSignature(method));
                } else {
                    signatures.add(extractJavaMethodSignature(method));
                }
                super.visitMethod(method);
            }
            
            @Override
            public void visitField(PsiField field) {
                // Handle enum constants specially
                if (field instanceof PsiEnumConstant) {
                    signatures.add(extractJavaEnumConstantSignature((PsiEnumConstant) field));
                } else {
                    signatures.add(extractJavaFieldSignature(field));
                }
                super.visitField(field);
            }
            
            @Override
            public void visitClassInitializer(PsiClassInitializer initializer) {
                signatures.add(extractJavaInitializerSignature(initializer));
                super.visitClassInitializer(initializer);
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
        
        // Generate ID - handle cases where getQualifiedName() returns null
        String id = generateClassId(clazz);
        
        return new CodeSignature(
            id,
            signature,
            metadata.toString(),
            clazz.getContainingFile().getVirtualFile().getPath()
        );
    }
    
    private String generateClassId(PsiClass clazz) {
        // Try qualified name first
        String qualifiedName = clazz.getQualifiedName();
        if (qualifiedName != null && !qualifiedName.isEmpty()) {
            return qualifiedName;
        }
        
        // For anonymous classes, generate based on location
        if (clazz.getName() == null) {
            PsiElement parent = clazz.getParent();
            if (parent instanceof PsiNewExpression) {
                PsiType type = ((PsiNewExpression) parent).getType();
                if (type != null) {
                    return "anonymous:" + type.getPresentableText() + "@" + clazz.hashCode();
                }
            }
            return "anonymous:class@" + clazz.hashCode();
        }
        
        // For local classes, use containing method/class info
        PsiMethod containingMethod = PsiTreeUtil.getParentOfType(clazz, PsiMethod.class);
        if (containingMethod != null) {
            PsiClass containingClass = containingMethod.getContainingClass();
            if (containingClass != null && containingClass.getQualifiedName() != null) {
                return containingClass.getQualifiedName() + "$" + containingMethod.getName() + "$" + clazz.getName();
            }
        }
        
        // For classes in default package or other edge cases
        PsiFile file = clazz.getContainingFile();
        String fileName = file.getName().replace(".java", "");
        return fileName + "." + clazz.getName();
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
        
        // Generate ID - handle cases where containing class might be null or anonymous
        String id = generateMethodId(method, containingClass);
            
        return new CodeSignature(
            id,
            signature,
            metadata.toString(),
            method.getContainingFile().getVirtualFile().getPath()
        );
    }
    
    private String generateMethodId(PsiMethod method, PsiClass containingClass) {
        if (containingClass != null) {
            String classId = generateClassId(containingClass);
            return classId + "#" + method.getName();
        }
        
        // Fallback for methods without containing class (shouldn't normally happen)
        PsiFile file = method.getContainingFile();
        String fileName = file.getName().replace(".java", "");
        return fileName + "#" + method.getName();
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
        
        // Generate ID - handle cases where containing class might be null or anonymous
        String id = generateFieldId(field, containingClass);
            
        return new CodeSignature(
            id,
            signature,
            metadata.toString(),
            field.getContainingFile().getVirtualFile().getPath()
        );
    }
    
    private String generateFieldId(PsiField field, PsiClass containingClass) {
        if (containingClass != null) {
            String classId = generateClassId(containingClass);
            return classId + "." + field.getName();
        }
        
        // Fallback for fields without containing class (shouldn't normally happen)
        PsiFile file = field.getContainingFile();
        String fileName = file.getName().replace(".java", "");
        return fileName + "." + field.getName();
    }
    
    private CodeSignature extractJavaConstructorSignature(PsiMethod constructor) {
        PsiClass containingClass = constructor.getContainingClass();
        
        JsonObject metadata = new JsonObject();
        metadata.addProperty("type", "constructor");
        metadata.addProperty("name", constructor.getName());
        metadata.addProperty("class", containingClass != null ? containingClass.getQualifiedName() : "");
        
        // Add javadoc if present
        String javadoc = extractJavadoc(constructor);
        if (javadoc != null) {
            metadata.addProperty("javadoc", javadoc);
        }
        
        String signature = buildJavaConstructorSignature(constructor);
        
        // Generate ID for constructor
        String id = generateConstructorId(constructor, containingClass);
        
        return new CodeSignature(
            id,
            signature,
            metadata.toString(),
            constructor.getContainingFile().getVirtualFile().getPath()
        );
    }
    
    private String generateConstructorId(PsiMethod constructor, PsiClass containingClass) {
        if (containingClass != null) {
            String classId = generateClassId(containingClass);
            // Include parameter count to handle overloaded constructors
            int paramCount = constructor.getParameterList().getParametersCount();
            return classId + "#<init>" + paramCount;
        }
        return "unknown#<init>";
    }
    
    private String buildJavaConstructorSignature(PsiMethod constructor) {
        StringBuilder sb = new StringBuilder();
        
        // Modifiers
        if (constructor.hasModifierProperty(PsiModifier.PUBLIC)) sb.append("public ");
        if (constructor.hasModifierProperty(PsiModifier.PROTECTED)) sb.append("protected ");
        if (constructor.hasModifierProperty(PsiModifier.PRIVATE)) sb.append("private ");
        
        // Constructor name (class name)
        sb.append(constructor.getName());
        
        // Parameters
        sb.append("(");
        PsiParameter[] parameters = constructor.getParameterList().getParameters();
        for (int i = 0; i < parameters.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(parameters[i].getType().getPresentableText());
            sb.append(" ");
            sb.append(parameters[i].getName());
        }
        sb.append(")");
        
        // Throws clause
        PsiClassType[] throwsTypes = constructor.getThrowsList().getReferencedTypes();
        if (throwsTypes.length > 0) {
            sb.append(" throws ");
            for (int i = 0; i < throwsTypes.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(throwsTypes[i].getPresentableText());
            }
        }
        
        return sb.toString();
    }
    
    private CodeSignature extractJavaEnumConstantSignature(PsiEnumConstant enumConstant) {
        PsiClass containingClass = enumConstant.getContainingClass();
        
        JsonObject metadata = new JsonObject();
        metadata.addProperty("type", "enum_constant");
        metadata.addProperty("name", enumConstant.getName());
        metadata.addProperty("class", containingClass != null ? containingClass.getQualifiedName() : "");
        
        // Add javadoc if present
        String javadoc = extractJavadoc(enumConstant);
        if (javadoc != null) {
            metadata.addProperty("javadoc", javadoc);
        }
        
        String signature = "enum constant " + enumConstant.getName();
        
        String id = generateFieldId(enumConstant, containingClass);
        
        return new CodeSignature(
            id,
            signature,
            metadata.toString(),
            enumConstant.getContainingFile().getVirtualFile().getPath()
        );
    }
    
    private CodeSignature extractJavaInitializerSignature(PsiClassInitializer initializer) {
        PsiClass containingClass = PsiTreeUtil.getParentOfType(initializer, PsiClass.class);
        
        JsonObject metadata = new JsonObject();
        metadata.addProperty("type", "initializer");
        metadata.addProperty("isStatic", initializer.hasModifierProperty(PsiModifier.STATIC));
        metadata.addProperty("class", containingClass != null ? containingClass.getQualifiedName() : "");
        
        String signature = initializer.hasModifierProperty(PsiModifier.STATIC) 
            ? "static { ... }" 
            : "{ ... }";
        
        // Generate ID for initializer
        String id = generateInitializerId(initializer, containingClass);
        
        return new CodeSignature(
            id,
            signature,
            metadata.toString(),
            initializer.getContainingFile().getVirtualFile().getPath()
        );
    }
    
    private String generateInitializerId(PsiClassInitializer initializer, PsiClass containingClass) {
        if (containingClass != null) {
            String classId = generateClassId(containingClass);
            String type = initializer.hasModifierProperty(PsiModifier.STATIC) ? "static" : "instance";
            // Use text offset to differentiate multiple initializers
            return classId + "#<" + type + "-init-" + initializer.getTextOffset() + ">";
        }
        return "unknown#<init>";
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
