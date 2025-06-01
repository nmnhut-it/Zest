package com.zps.zest.rag;

import com.google.gson.JsonObject;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.kotlin.psi.*;
import org.jetbrains.kotlin.lexer.KtTokens;

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
        } else if (file instanceof KtFile) {
            extractFromKotlinFile((KtFile) file, signatures);
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
    
    private void extractFromKotlinFile(KtFile file, List<CodeSignature> signatures) {
        // Extract Kotlin classes
        List<KtClass> classes = PsiTreeUtil.findChildrenOfType(file, KtClass.class)
            .stream()
            .filter(c -> !c.isEnum())
            .collect(Collectors.toList());
            
        for (KtClass ktClass : classes) {
            signatures.add(extractKotlinClassSignature(ktClass));
            
            // Extract methods
            List<KtNamedFunction> functions = PsiTreeUtil.findChildrenOfType(ktClass, KtNamedFunction.class)
                .stream()
                .collect(Collectors.toList());
                
            for (KtNamedFunction function : functions) {
                signatures.add(extractKotlinFunctionSignature(function, ktClass));
            }
            
            // Extract properties
            List<KtProperty> properties = PsiTreeUtil.findChildrenOfType(ktClass, KtProperty.class)
                .stream()
                .collect(Collectors.toList());
                
            for (KtProperty property : properties) {
                signatures.add(extractKotlinPropertySignature(property, ktClass));
            }
        }
        
        // Extract top-level functions
        List<KtNamedFunction> topLevelFunctions = file.getDeclarations().stream()
            .filter(d -> d instanceof KtNamedFunction)
            .map(d -> (KtNamedFunction) d)
            .collect(Collectors.toList());
            
        for (KtNamedFunction function : topLevelFunctions) {
            signatures.add(extractKotlinFunctionSignature(function, null));
        }
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
    
    private CodeSignature extractKotlinClassSignature(KtClass ktClass) {
        JsonObject metadata = new JsonObject();
        metadata.addProperty("type", "class");
        metadata.addProperty("name", ktClass.getName());
        metadata.addProperty("qualifiedName", ktClass.getFqName() != null ? ktClass.getFqName().toString() : ktClass.getName());
        metadata.addProperty("isInterface", ktClass.isInterface());
        metadata.addProperty("isData", ktClass.hasModifier(KtTokens.DATA_KEYWORD));
        metadata.addProperty("isSealed", ktClass.hasModifier(KtTokens.SEALED_KEYWORD));
        
        String signature = buildKotlinClassSignature(ktClass);
        
        return new CodeSignature(
            ktClass.getFqName() != null ? ktClass.getFqName().toString() : ktClass.getName(),
            signature,
            metadata.toString(),
            ktClass.getContainingFile().getVirtualFile().getPath()
        );
    }
    
    private CodeSignature extractKotlinFunctionSignature(KtNamedFunction function, KtClass containingClass) {
        JsonObject metadata = new JsonObject();
        metadata.addProperty("type", "method");
        metadata.addProperty("name", function.getName());
        metadata.addProperty("class", containingClass != null && containingClass.getFqName() != null 
            ? containingClass.getFqName().toString() : "");
        metadata.addProperty("isSuspend", function.hasModifier(KtTokens.SUSPEND_KEYWORD));
        metadata.addProperty("isInline", function.hasModifier(KtTokens.INLINE_KEYWORD));
        
        String signature = buildKotlinFunctionSignature(function);
        
        String id = containingClass != null && containingClass.getFqName() != null
            ? containingClass.getFqName().toString() + "#" + function.getName()
            : function.getName();
            
        return new CodeSignature(
            id,
            signature,
            metadata.toString(),
            function.getContainingFile().getVirtualFile().getPath()
        );
    }
    
    private CodeSignature extractKotlinPropertySignature(KtProperty property, KtClass containingClass) {
        JsonObject metadata = new JsonObject();
        metadata.addProperty("type", "field");
        metadata.addProperty("name", property.getName());
        metadata.addProperty("class", containingClass != null && containingClass.getFqName() != null 
            ? containingClass.getFqName().toString() : "");
        metadata.addProperty("isVar", property.isVar());
        
        String signature = buildKotlinPropertySignature(property);
        
        String id = containingClass != null && containingClass.getFqName() != null
            ? containingClass.getFqName().toString() + "." + property.getName()
            : property.getName();
            
        return new CodeSignature(
            id,
            signature,
            metadata.toString(),
            property.getContainingFile().getVirtualFile().getPath()
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
    
    private String buildKotlinClassSignature(KtClass ktClass) {
        StringBuilder sb = new StringBuilder();
        
        // Modifiers
        if (ktClass.hasModifier(KtTokens.PUBLIC_KEYWORD)) sb.append("public ");
        if (ktClass.hasModifier(KtTokens.OPEN_KEYWORD)) sb.append("open ");
        if (ktClass.hasModifier(KtTokens.ABSTRACT_KEYWORD)) sb.append("abstract ");
        if (ktClass.hasModifier(KtTokens.DATA_KEYWORD)) sb.append("data ");
        if (ktClass.hasModifier(KtTokens.SEALED_KEYWORD)) sb.append("sealed ");
        
        // Class/Interface
        if (ktClass.isInterface()) {
            sb.append("interface ");
        } else {
            sb.append("class ");
        }
        
        sb.append(ktClass.getName());
        
        return sb.toString();
    }
    
    private String buildKotlinFunctionSignature(KtNamedFunction function) {
        StringBuilder sb = new StringBuilder();
        
        // Modifiers
        if (function.hasModifier(KtTokens.PUBLIC_KEYWORD)) sb.append("public ");
        if (function.hasModifier(KtTokens.PRIVATE_KEYWORD)) sb.append("private ");
        if (function.hasModifier(KtTokens.SUSPEND_KEYWORD)) sb.append("suspend ");
        if (function.hasModifier(KtTokens.INLINE_KEYWORD)) sb.append("inline ");
        
        sb.append("fun ");
        sb.append(function.getName());
        
        // Parameters
        sb.append("(");
        List<KtParameter> parameters = function.getValueParameters();
        for (int i = 0; i < parameters.size(); i++) {
            if (i > 0) sb.append(", ");
            KtParameter param = parameters.get(i);
            sb.append(param.getName());
            if (param.getTypeReference() != null) {
                sb.append(": ").append(param.getTypeReference().getText());
            }
        }
        sb.append(")");
        
        // Return type
        if (function.getTypeReference() != null) {
            sb.append(": ").append(function.getTypeReference().getText());
        }
        
        return sb.toString();
    }
    
    private String buildKotlinPropertySignature(KtProperty property) {
        StringBuilder sb = new StringBuilder();
        
        // Modifiers
        if (property.hasModifier(KtTokens.PUBLIC_KEYWORD)) sb.append("public ");
        if (property.hasModifier(KtTokens.PRIVATE_KEYWORD)) sb.append("private ");
        
        sb.append(property.isVar() ? "var " : "val ");
        sb.append(property.getName());
        
        // Type
        if (property.getTypeReference() != null) {
            sb.append(": ").append(property.getTypeReference().getText());
        }
        
        return sb.toString();
    }
    
    private String getJavaPackageName(PsiClass clazz) {
        PsiFile file = clazz.getContainingFile();
        if (file instanceof PsiJavaFile) {
            return ((PsiJavaFile) file).getPackageName();
        }
        return "";
    }
}
