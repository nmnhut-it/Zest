package com.zps.zest;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.ThrowableComputable;
import com.intellij.psi.*;
import com.intellij.psi.javadoc.PsiDocComment;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiShortNamesCache;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Map;
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
            context.append("File path: `").append(javaFile.getVirtualFile().getPath()).append("`\n\n");

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
     * Finds a test class by its file path.
     *
     * @param project The project to search in
     * @param testFilePath The path to the test file
     * @return The test class if found, null otherwise
     */
    @Nullable
    public static PsiClass findTestClass(Project project, String testFilePath) {
        return ApplicationManager.getApplication().runReadAction((Computable<PsiClass>) () -> {
            try {
                // Find the file by path
                com.intellij.openapi.vfs.VirtualFile virtualFile =
                        com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(testFilePath);

                if (virtualFile == null) {
                    return null;
                }

                // Get the PSI file
                PsiFile psiFile = com.intellij.psi.PsiManager.getInstance(project).findFile(virtualFile);
                if (!(psiFile instanceof PsiJavaFile)) {
                    return null;
                }

                PsiJavaFile javaFile = (PsiJavaFile) psiFile;
                PsiClass[] classes = javaFile.getClasses();

                // Return the first public class (typically the test class)
                for (PsiClass psiClass : classes) {
                    if (psiClass.hasModifierProperty(PsiModifier.PUBLIC)) {
                        return psiClass;
                    }
                }

                // If no public class, return the first class
                return classes.length > 0 ? classes[0] : null;

            } catch (Exception e) {
                return null;
            }
        });
    }

    /**
     * Collects the structure of a test class (methods and fields without body).
     *
     * @param testClass The test class to analyze
     * @return A string representation of the test class structure
     */
    public static String collectTestClassStructure(PsiClass testClass) {
        return ApplicationManager.getApplication().runReadAction((Computable<String>) () -> {
            StringBuilder structure = new StringBuilder();

            // Add class declaration
            structure.append("public class ").append(testClass.getName());

            // Add extends
            PsiClass superClass = testClass.getSuperClass();
            if (superClass != null && !Objects.equals(superClass.getName(), "Object")) {
                structure.append(" extends ").append(superClass.getName());
            }

            // Add implements
            PsiClassType[] implementsTypes = testClass.getImplementsListTypes();
            if (implementsTypes.length > 0) {
                structure.append(" implements ");
                for (int i = 0; i < implementsTypes.length; i++) {
                    structure.append(implementsTypes[i].getClassName());
                    if (i < implementsTypes.length - 1) {
                        structure.append(", ");
                    }
                }
            }
            structure.append(" {\n\n");

            // Add fields
            PsiField[] fields = testClass.getFields();
            if (fields.length > 0) {
                structure.append("    // Fields\n");
                for (PsiField field : fields) {
                    structure.append("    ");
                    if (field.hasModifierProperty(PsiModifier.PRIVATE)) structure.append("private ");
                    if (field.hasModifierProperty(PsiModifier.PROTECTED)) structure.append("protected ");
                    if (field.hasModifierProperty(PsiModifier.PUBLIC)) structure.append("public ");
                    if (field.hasModifierProperty(PsiModifier.STATIC)) structure.append("static ");
                    if (field.hasModifierProperty(PsiModifier.FINAL)) structure.append("final ");

                    structure.append(field.getType().getPresentableText())
                            .append(" ")
                            .append(field.getName())
                            .append(";\n");
                }
                structure.append("\n");
            }

            // Add methods (signatures only)
            PsiMethod[] methods = testClass.getMethods();
            if (methods.length > 0) {
                structure.append("    // Methods\n");
                for (PsiMethod method : methods) {
                    // Skip inherited Object methods
                    if (method.getContainingClass() != testClass) {
                        continue;
                    }

                    structure.append("    ");

                    // Add annotations (common test annotations)
                    PsiAnnotation[] annotations = method.getAnnotations();
                    for (PsiAnnotation annotation : annotations) {
                        String annotationName = annotation.getQualifiedName();
                        if (annotationName != null && (
                                annotationName.contains("Test") ||
                                        annotationName.contains("Before") ||
                                        annotationName.contains("After") ||
                                        annotationName.contains("Setup") ||
                                        annotationName.contains("TearDown"))) {
                            structure.append("@").append(getSimpleName(annotationName)).append(" ");
                        }
                    }

                    // Add modifiers
                    if (method.hasModifierProperty(PsiModifier.PRIVATE)) structure.append("private ");
                    if (method.hasModifierProperty(PsiModifier.PROTECTED)) structure.append("protected ");
                    if (method.hasModifierProperty(PsiModifier.PUBLIC)) structure.append("public ");
                    if (method.hasModifierProperty(PsiModifier.STATIC)) structure.append("static ");
                    if (method.hasModifierProperty(PsiModifier.FINAL)) structure.append("final ");

                    // Add return type
                    PsiType returnType = method.getReturnType();
                    if (returnType != null) {
                        structure.append(returnType.getPresentableText()).append(" ");
                    }

                    // Add method name and parameters
                    structure.append(method.getName()).append("(");

                    PsiParameter[] parameters = method.getParameterList().getParameters();
                    for (int i = 0; i < parameters.length; i++) {
                        PsiParameter param = parameters[i];
                        structure.append(param.getType().getPresentableText())
                                .append(" ")
                                .append(param.getName());
                        if (i < parameters.length - 1) {
                            structure.append(", ");
                        }
                    }
                    structure.append(");\n");
                }
            }

            structure.append("}");
            return structure.toString();
        });
    }

    /**
     * Finds and collects structures of test subclasses.
     *
     * @param project The project to search in
     * @param testClass The test class to find subclasses for
     * @return A string representation of subclass structures
     */
    public static String collectTestSubclassStructures(Project project, PsiClass testClass) {
        return ApplicationManager.getApplication().runReadAction((Computable<String>) () -> {
            StringBuilder structures = new StringBuilder();

            try {
                // Find all classes that extend the test class
                com.intellij.psi.search.searches.ClassInheritorsSearch.SearchParameters searchParams =
                        new com.intellij.psi.search.searches.ClassInheritorsSearch.SearchParameters(
                                testClass, GlobalSearchScope.projectScope(project), true, false, false);

                java.util.Collection<PsiClass> subclasses =
                        com.intellij.psi.search.searches.ClassInheritorsSearch.search(searchParams).findAll();

                if (!subclasses.isEmpty()) {
                    for (PsiClass subclass : subclasses) {
                        if (subclass.equals(testClass)) continue; // Skip the class itself

                        structures.append("Subclass: ").append(subclass.getName()).append("\n");
                        structures.append("```java\n");
                        structures.append(collectTestClassStructure(subclass));
                        structures.append("\n```\n\n");
                    }
                }
            } catch (Exception e) {
                // If search fails, just return empty
            }

            return structures.toString();
        });
    }

    /**
     * Helper method to get simple name from qualified name.
     */
    private static String getSimpleName(String qualifiedName) {
        int lastDot = qualifiedName.lastIndexOf('.');
        return lastDot >= 0 ? qualifiedName.substring(lastDot + 1) : qualifiedName;
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
        contextInfo.append("File path: `").append(targetClass.getContainingFile().getVirtualFile().getPath()).append("`\n\n");

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
        if (element == null)
            return "";
        return ApplicationManager.getApplication().runReadAction(
                (ThrowableComputable<String, RuntimeException>) () -> element.getText());
    }


    /**
     * Collects full implementations of related classes, focusing on shorter classes.
     *
     * @param psiFile The file containing the selection
     * @param selectionStart The start offset of the selection
     * @param selectionEnd The end offset of the selection
     * @return A map of class names to their full implementations
     */
    static java.util.Map<String, String> collectRelatedClassImplementations(PsiFile psiFile, int selectionStart, int selectionEnd) {
        java.util.Map<String, String> classImpls = new java.util.HashMap<>();

        if (!(psiFile instanceof PsiJavaFile)) {
            return classImpls;
        }

        // Get the element at selection
        PsiElement startElement = psiFile.findElementAt(selectionStart);
        if (startElement == null) {
            return classImpls;
        }

        // Find containing method
        PsiMethod containingMethod = PsiTreeUtil.getParentOfType(startElement, PsiMethod.class);
        if (containingMethod == null) {
            return classImpls;
        }

        // Find containing class
        PsiClass containingClass = containingMethod.getContainingClass();
        if (containingClass == null) {
            return classImpls;
        }

        // Collect references to classes in the method
        java.util.Set<PsiClass> referencedClasses = new java.util.HashSet<>();
        containingMethod.accept(new JavaRecursiveElementVisitor() {
            @Override
            public void visitReferenceExpression(PsiReferenceExpression expression) {
                super.visitReferenceExpression(expression);
                PsiElement resolved = expression.resolve();
                if (resolved instanceof PsiClass) {
                    referencedClasses.add((PsiClass) resolved);
                }
            }

            @Override
            public void visitNewExpression(PsiNewExpression expression) {
                super.visitNewExpression(expression);
                PsiJavaCodeReferenceElement classRef = expression.getClassReference();
                if (classRef != null) {
                    PsiElement resolved = classRef.resolve();
                    if (resolved instanceof PsiClass) {
                        referencedClasses.add((PsiClass) resolved);
                    }
                }
            }

            @Override
            public void visitTypeElement(PsiTypeElement type) {
                super.visitTypeElement(type);
                if (type.getType() instanceof PsiClassType) {
                    PsiClass resolved = ((PsiClassType) type.getType()).resolve();
                    if (resolved != null) {
                        referencedClasses.add(resolved);
                    }
                }
            }
        });

        // Check field types in the containing class
        for (PsiField field : containingClass.getFields()) {
            PsiType fieldType = field.getType();
            if (fieldType instanceof PsiClassType) {
                PsiClass fieldClass = ((PsiClassType) fieldType).resolve();
                if (fieldClass != null) {
                    referencedClasses.add(fieldClass);
                }
            }
        }

        // For each referenced class, get the full implementation if it's not too large
        final int MAX_CLASS_SIZE = 2000; // Characters
        for (PsiClass psiClass : referencedClasses) {
            // Skip standard Java classes
            if (psiClass.getQualifiedName() != null &&
                    (psiClass.getQualifiedName().startsWith("java.") ||
                            psiClass.getQualifiedName().startsWith("javax."))) {
                continue;
            }

            // Get the full text of the class
            String classText = psiClass.getText();

            // Only include if it's not too large
            if (classText.length() <= MAX_CLASS_SIZE) {
                classImpls.put(psiClass.getName(), classText);
            }
        }

        return classImpls;
    }
}