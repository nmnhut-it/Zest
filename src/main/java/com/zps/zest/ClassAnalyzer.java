package com.zps.zest;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.ThrowableComputable;
import com.intellij.psi.*;
import com.intellij.psi.javadoc.PsiDocComment;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Utility class for analyzing Java class structures and collecting context information.
 * This centralizes code analysis logic that can be used by multiple components.
 */
public class ClassAnalyzer {

    /**
     * Collects class context information for the given class.
     * 
     * @param targetClass The class to analyze
     * @return A string representation of the class context
     */
    public static String collectClassContext(PsiClass targetClass) {
        return ApplicationManager.getApplication().runReadAction(
                (Computable<String>) () -> collectClassContextInternal(targetClass));
    }
    
    /**
     * Collects code context information around a selection within a file.
     * 
     * @param psiFile The file containing the selection
     * @param selectionStart The start offset of the selection
     * @param selectionEnd The end offset of the selection
     * @return A string representation of the context
     */
    public static String collectSelectionContext(PsiFile psiFile, int selectionStart, int selectionEnd) {
        return ApplicationManager.getApplication().runReadAction((Computable<String>) () -> {
            StringBuilder context = new StringBuilder();
            if (!(psiFile instanceof PsiJavaFile)) {
                return "Not a Java file";
            }
            
            PsiJavaFile javaFile = (PsiJavaFile) psiFile;

            // Add package info
            context.append("Package: ").append(javaFile.getPackageName()).append("\n\n");

            // Add imports
            context.append("Imports:\n");
            for (PsiImportStatement importStatement : javaFile.getImportList().getImportStatements()) {
                context.append(importStatement.getText()).append("\n");
            }
            context.append("\n");

            // Find containing method and class
            PsiElement startElement = psiFile.findElementAt(selectionStart);
            PsiElement endElement = psiFile.findElementAt(selectionEnd - 1);

            PsiMethod containingMethod = PsiTreeUtil.getParentOfType(startElement, PsiMethod.class);
            PsiClass containingClass = null;
            
            if (containingMethod != null) {
                containingClass = containingMethod.getContainingClass();
            } else {
                // If not in a method, try to find containing class directly
                containingClass = PsiTreeUtil.getParentOfType(startElement, PsiClass.class);
            }

            // Add class information if available
            if (containingClass != null) {
                context.append("Class: ").append(containingClass.getName()).append("\n\n");

                // Add class-level javadoc if available
                PsiDocComment classJavadoc = containingClass.getDocComment();
                if (classJavadoc != null) {
                    context.append("Class JavaDoc:\n```java\n");
                    context.append(getTextOfPsiElement(classJavadoc));
                    context.append("\n```\n\n");
                }

                // Add fields
                PsiField[] fields = containingClass.getFields();
                if (fields.length > 0) {
                    context.append("Class fields:\n```java\n");
                    for (PsiField field : fields) {
                        context.append(getTextOfPsiElement(field)).append("\n");
                    }
                    context.append("```\n\n");
                }

                // Add containing method if available
                if (containingMethod != null) {
                    context.append("Method containing selection:\n```java\n");
                    context.append(getTextOfPsiElement(containingMethod));
                    context.append("\n```\n\n");
                }

                // Add related classes
                Set<PsiClass> relatedClasses = new HashSet<>();
                collectRelatedClasses(containingClass, relatedClasses);
                
                if (containingMethod != null) {
                    collectClassesInMethod(containingMethod, relatedClasses);
                }

                if (!relatedClasses.isEmpty()) {
                    context.append("Related Classes:\n");
                    for (PsiClass cls : relatedClasses) {
                        if (cls.getQualifiedName() == null) continue;
                        if (cls.equals(containingClass)) continue; // Skip the containing class itself

                        context.append(cls.isInterface() ? "Interface: " : "Class: ")
                              .append(cls.getQualifiedName()).append("\n");

                        // Include class structure
                        context.append("```java\n");
                        appendClassStructure(context, cls);
                        context.append("\n```\n\n");
                    }
                }
            }

            return context.toString();
        });
    }

    /**
     * Internal implementation of class context collection.
     */
    private static String collectClassContextInternal(PsiClass targetClass) {
        StringBuilder contextInfo = new StringBuilder();

        // Class structure info
        contextInfo.append("Class structure:\n```java\n");
        contextInfo.append(getTextOfPsiElement(targetClass));
        contextInfo.append("\n```\n\n");

        // Add class-level javadoc if available
        PsiDocComment classJavadoc = targetClass.getDocComment();
        if (classJavadoc != null) {
            contextInfo.append("Class JavaDoc:\n```java\n");
            contextInfo.append(getTextOfPsiElement(classJavadoc));
            contextInfo.append("\n```\n\n");
        }

        // Collect related classes
        contextInfo.append("Related Classes:\n");
        Set<PsiClass> relatedClasses = new HashSet<>();
        collectRelatedClasses(targetClass, relatedClasses);

        // Add code snippets for each related class
        for (PsiClass cls : relatedClasses) {
            if (cls.getQualifiedName() == null) continue;
            if (cls.equals(targetClass)) continue; // Skip the target class itself

            contextInfo.append(cls.isInterface() ? "Interface: " : "Class: ")
                      .append(cls.getQualifiedName()).append("\n");

            // Include class structure
            contextInfo.append("```java\n");
            appendClassStructure(contextInfo, cls);
            contextInfo.append("\n```\n\n");
        }

        return contextInfo.toString();
    }

    /**
     * Collects classes related to the given class by examining interfaces, superclass, and fields.
     */
    public static void collectRelatedClasses(PsiClass targetClass, Set<PsiClass> relatedClasses) {
        // Add implemented interfaces
        PsiClassType[] interfaces = targetClass.getImplementsListTypes();
        for (PsiClassType interfaceType : interfaces) {
            PsiClass interfaceClass = interfaceType.resolve();
            if (interfaceClass != null) {
                relatedClasses.add(interfaceClass);
            }
        }

        // Add superclass
        PsiClass superClass = targetClass.getSuperClass();
        if (superClass != null && !superClass.getQualifiedName().startsWith("java.")) {
            relatedClasses.add(superClass);
        }

        // Add field types
        PsiField[] fields = targetClass.getFields();
        for (PsiField field : fields) {
            PsiType fieldType = field.getType();
            if (fieldType instanceof PsiClassType) {
                PsiClass fieldClass = ((PsiClassType) fieldType).resolve();
                if (fieldClass != null && !fieldClass.getQualifiedName().startsWith("java.")) {
                    relatedClasses.add(fieldClass);
                }
            }
        }

        // Find classes used in method bodies
        targetClass.accept(new JavaRecursiveElementVisitor() {
            @Override
            public void visitReferenceExpression(PsiReferenceExpression expression) {
                super.visitReferenceExpression(expression);
                PsiElement resolved = expression.resolve();
                if (resolved instanceof PsiClass) {
                    PsiClass resolvedClass = (PsiClass) resolved;
                    if (!resolvedClass.getQualifiedName().startsWith("java.") &&
                            !resolvedClass.getQualifiedName().startsWith("javax.")) {
                        relatedClasses.add(resolvedClass);
                    }
                }
            }

            @Override
            public void visitNewExpression(PsiNewExpression expression) {
                super.visitNewExpression(expression);
                PsiJavaCodeReferenceElement classRef = expression.getClassReference();
                if (classRef != null) {
                    PsiElement resolved = classRef.resolve();
                    if (resolved instanceof PsiClass) {
                        PsiClass resolvedClass = (PsiClass) resolved;
                        if (!resolvedClass.getQualifiedName().startsWith("java.") &&
                                !resolvedClass.getQualifiedName().startsWith("javax.")) {
                            relatedClasses.add(resolvedClass);
                        }
                    }
                }
            }

            @Override
            public void visitMethod(@NotNull PsiMethod method) {
                super.visitMethod(method);

                PsiParameter[] parameters = method.getParameterList().getParameters();
                for (PsiParameter parameter : parameters) {
                    PsiType paramType = parameter.getType();
                    if (paramType instanceof PsiClassType) {
                        PsiClass paramClass = ((PsiClassType) paramType).resolve();
                        if (paramClass != null &&
                                !paramClass.getQualifiedName().startsWith("java.") &&
                                !paramClass.getQualifiedName().startsWith("javax.")) {
                            relatedClasses.add(paramClass);
                        }
                    }
                }
            }
        });
    }

    /**
     * Collects classes specifically used within a method by analyzing references and expressions.
     */
    public static void collectClassesInMethod(PsiMethod method, Set<PsiClass> relatedClasses) {
        method.accept(new JavaRecursiveElementVisitor() {
            @Override
            public void visitReferenceExpression(PsiReferenceExpression expression) {
                super.visitReferenceExpression(expression);
                PsiElement resolved = expression.resolve();
                if (resolved instanceof PsiClass) {
                    PsiClass resolvedClass = (PsiClass) resolved;
                    if (!resolvedClass.getQualifiedName().startsWith("java.") &&
                            !resolvedClass.getQualifiedName().startsWith("javax.")) {
                        relatedClasses.add(resolvedClass);
                    }
                }
            }

            @Override
            public void visitNewExpression(PsiNewExpression expression) {
                super.visitNewExpression(expression);
                PsiJavaCodeReferenceElement classRef = expression.getClassReference();
                if (classRef != null) {
                    PsiElement resolved = classRef.resolve();
                    if (resolved instanceof PsiClass) {
                        PsiClass resolvedClass = (PsiClass) resolved;
                        if (!resolvedClass.getQualifiedName().startsWith("java.") &&
                                !resolvedClass.getQualifiedName().startsWith("javax.")) {
                            relatedClasses.add(resolvedClass);
                        }
                    }
                }
            }

            @Override
            public void visitParameter(@NotNull PsiParameter parameter) {
                super.visitParameter(parameter);
                PsiType paramType = parameter.getType();
                if (paramType instanceof PsiClassType) {
                    PsiClass paramClass = ((PsiClassType) paramType).resolve();
                    if (paramClass != null &&
                            !paramClass.getQualifiedName().startsWith("java.") &&
                            !paramClass.getQualifiedName().startsWith("javax.")) {
                        relatedClasses.add(paramClass);
                    }
                }
            }
        });
    }

    /**
     * Appends a simplified class structure to the context.
     */
    public static void appendClassStructure(StringBuilder context, PsiClass cls) {
        // Add class declaration
        context.append("public ");
        if (cls.isInterface()) context.append("interface ");
        else context.append("class ");
        context.append(cls.getName());

        // Add extends
        PsiClass superClassType = cls.getSuperClass();
        if (superClassType != null && !Objects.equals(superClassType.getName(), "Object")) {
            context.append(" extends ").append(superClassType.getName());
        }

        // Add implements
        PsiClassType[] implementsTypes = cls.getImplementsListTypes();
        if (implementsTypes.length > 0) {
            context.append(" implements ");
            for (int i = 0; i < implementsTypes.length; i++) {
                context.append(implementsTypes[i].getClassName());
                if (i < implementsTypes.length - 1) {
                    context.append(", ");
                }
            }
        }
        context.append(" {\n");

        // Add constructors
        for (PsiMethod constructor : cls.getConstructors()) {
            if (constructor.hasModifierProperty(PsiModifier.PUBLIC) ||
                    constructor.hasModifierProperty(PsiModifier.PROTECTED)) {
                context.append("    ");
                if (constructor.hasModifierProperty(PsiModifier.PUBLIC)) context.append("public ");
                if (constructor.hasModifierProperty(PsiModifier.PROTECTED)) context.append("protected ");
                context.append(cls.getName()).append("(");

                PsiParameter[] parameters = constructor.getParameterList().getParameters();
                for (int i = 0; i < parameters.length; i++) {
                    PsiParameter param = parameters[i];
                    context.append(param.getType().getPresentableText())
                            .append(" ")
                            .append(param.getName());
                    if (i < parameters.length - 1) {
                        context.append(", ");
                    }
                }
                context.append(");\n");
            }
        }

        // Add public method signatures
        for (PsiMethod method : cls.getMethods()) {
            if (!method.isConstructor() && method.hasModifierProperty(PsiModifier.PUBLIC)) {
                context.append("    ");
                if (method.hasModifierProperty(PsiModifier.PUBLIC)) context.append("public ");
                if (method.hasModifierProperty(PsiModifier.PROTECTED)) context.append("protected ");
                if (method.hasModifierProperty(PsiModifier.STATIC)) context.append("static ");
                if (method.hasModifierProperty(PsiModifier.FINAL)) context.append("final ");

                PsiType returnType = method.getReturnType();
                if (returnType != null) {
                    context.append(returnType.getPresentableText()).append(" ");
                }

                context.append(method.getName()).append("(");

                PsiParameter[] parameters = method.getParameterList().getParameters();
                for (int i = 0; i < parameters.length; i++) {
                    PsiParameter param = parameters[i];
                    context.append(param.getType().getPresentableText())
                            .append(" ")
                            .append(param.getName());
                    if (i < parameters.length - 1) {
                        context.append(", ");
                    }
                }
                context.append(");\n");
            }
        }
        context.append("}");
    }

    /**
     * Gets the text representation of a PSI element safely within a read action.
     */
    public static String getTextOfPsiElement(PsiElement element) {
        return ApplicationManager.getApplication().runReadAction(
                (ThrowableComputable<String, RuntimeException>) () -> element.getText());
    }
}