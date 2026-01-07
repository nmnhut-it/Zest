package com.zps.zest.core;

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
        
        // Add fields
        PsiField[] fields = cls.getFields();
        if (fields.length > 0) {
            context.append("    // Fields\n");
            for (PsiField field : fields) {
                // Skip synthetic fields
                if (field.getName().contains("$")) continue;
                
                context.append("    ");
                
                // Add modifiers
                if (field.hasModifierProperty(PsiModifier.PUBLIC)) context.append("public ");
                if (field.hasModifierProperty(PsiModifier.PROTECTED)) context.append("protected ");
                if (field.hasModifierProperty(PsiModifier.PRIVATE)) context.append("private ");
                if (field.hasModifierProperty(PsiModifier.STATIC)) context.append("static ");
                if (field.hasModifierProperty(PsiModifier.FINAL)) context.append("final ");
                
                // Add type and name
                PsiType fieldType = field.getType();
                context.append(fieldType.getPresentableText()).append(" ");
                context.append(field.getName());
                
                // Add initializer if it's a constant
                if (field.hasModifierProperty(PsiModifier.FINAL) && field.hasModifierProperty(PsiModifier.STATIC)) {
                    PsiExpression initializer = field.getInitializer();
                    if (initializer != null && initializer instanceof PsiLiteralExpression) {
                        context.append(" = ").append(initializer.getText());
                    }
                }
                
                context.append(";\n");
            }
            context.append("\n");
        }

        // Add constructors
        PsiMethod[] constructors = cls.getConstructors();
        if (constructors.length > 0) {
            context.append("    // Constructors\n");
            for (PsiMethod constructor : constructors) {
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
            context.append("\n");
        }

        // Add public method signatures
        context.append("    // Methods\n");
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
    public static java.util.Map<String, String> collectRelatedClassImplementations(PsiFile psiFile, int selectionStart, int selectionEnd) {
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
    
    /**
     * Pre-analyze entire file structure for context cache pre-population.
     * This method extracts all methods, classes, and their relationships for caching.
     */
    public static FileAnalysisResult analyzeFileStructure(PsiJavaFile javaFile) {
        return ApplicationManager.getApplication().runReadAction((Computable<FileAnalysisResult>) () -> {
            try {
                Set<String> allMethods = new HashSet<>();
                Set<String> allClasses = new HashSet<>();
                Map<String, String> methodBodies = new java.util.HashMap<>();
                Map<String, String> classContents = new java.util.HashMap<>();
                
                // Analyze all classes in the file
                for (PsiClass psiClass : javaFile.getClasses()) {
                    String className = psiClass.getName();
                    if (className != null) {
                        allClasses.add(className);
                        
                        // Store class structure (without method bodies for cache efficiency)
                        String classStructure = getClassSignatureOnly(psiClass);
                        classContents.put(className, classStructure);
                        
                        // Analyze all methods in the class
                        for (PsiMethod method : psiClass.getMethods()) {
                            String methodName = method.getName();
                            allMethods.add(methodName);
                            
                            // Store method signature and simplified body
                            String methodSignature = getMethodSignature(method);
                            methodBodies.put(methodName, methodSignature);
                        }
                    }
                }
                
                return new FileAnalysisResult(allMethods, allClasses, methodBodies, classContents);
                
            } catch (Exception e) {
                return new FileAnalysisResult(new HashSet<>(), new HashSet<>(), new java.util.HashMap<>(), new java.util.HashMap<>());
            }
        });
    }
    
    /**
     * Get class signature without method bodies for efficient caching
     */
    private static String getClassSignatureOnly(PsiClass psiClass) {
        StringBuilder signature = new StringBuilder();
        
        // Class declaration
        if (psiClass.getModifierList() != null) {
            signature.append(psiClass.getModifierList().getText()).append(" ");
        }
        signature.append("class ").append(psiClass.getName());
        
        // Extends/implements
        if (psiClass.getExtendsList() != null && psiClass.getExtendsList().getReferencedTypes().length > 0) {
            signature.append(" extends ").append(psiClass.getExtendsList().getText());
        }
        if (psiClass.getImplementsList() != null && psiClass.getImplementsList().getReferencedTypes().length > 0) {
            signature.append(" implements ").append(psiClass.getImplementsList().getText());
        }
        
        signature.append(" {\n");
        
        // Field declarations only
        for (PsiField field : psiClass.getFields()) {
            signature.append("    ").append(field.getText()).append("\n");
        }
        
        // Method signatures only (no bodies)
        for (PsiMethod method : psiClass.getMethods()) {
            signature.append("    ").append(getMethodSignature(method)).append("\n");
        }
        
        signature.append("}");
        return signature.toString();
    }
    
    /**
     * Get method signature without body
     */
    private static String getMethodSignature(PsiMethod method) {
        StringBuilder signature = new StringBuilder();
        
        // Modifiers
        if (method.getModifierList().getText() != null) {
            signature.append(method.getModifierList().getText()).append(" ");
        }
        
        // Return type
        if (method.getReturnType() != null) {
            signature.append(method.getReturnType().getPresentableText()).append(" ");
        }
        
        // Method name and parameters
        signature.append(method.getName()).append("(");
        PsiParameter[] parameters = method.getParameterList().getParameters();
        for (int i = 0; i < parameters.length; i++) {
            if (i > 0) signature.append(", ");
            signature.append(parameters[i].getType().getPresentableText())
                   .append(" ")
                   .append(parameters[i].getName());
        }
        signature.append(")");
        
        // Exceptions
        if (method.getThrowsList().getReferencedTypes().length > 0) {
            signature.append(" throws ").append(method.getThrowsList().getText());
        }
        
        signature.append(" { /* body */ }");
        return signature.toString();
    }
    
    /**
     * Result of file-level analysis for cache pre-population
     */
    public static class FileAnalysisResult {
        public final Set<String> allMethods;
        public final Set<String> allClasses;
        public final Map<String, String> methodBodies;
        public final Map<String, String> classContents;
        
        public FileAnalysisResult(Set<String> allMethods, Set<String> allClasses,
                                Map<String, String> methodBodies, Map<String, String> classContents) {
            this.allMethods = allMethods;
            this.allClasses = allClasses;
            this.methodBodies = methodBodies;
            this.classContents = classContents;
        }
    }

    /**
     * Detect external dependencies used by a class.
     * Helps determine if tests should be UNIT or INTEGRATION tests.
     *
     * @param targetClass The class to analyze for dependencies
     * @return Set of detected dependency types (JPA, HTTP, Kafka, etc.)
     */
    @NotNull
    public static Set<String> detectExternalDependencies(@NotNull PsiClass targetClass) {
        return ApplicationManager.getApplication().runReadAction((Computable<Set<String>>) () -> {
            Set<String> dependencies = new HashSet<>();

            // Check class-level annotations
            PsiModifierList classModifiers = targetClass.getModifierList();
            if (classModifiers != null) {
                // Database dependencies
                if (hasAnnotation(classModifiers, "Repository", "Entity", "Table")) {
                    dependencies.add("DATABASE_JPA");
                }
                if (hasAnnotation(classModifiers, "Transactional")) {
                    dependencies.add("DATABASE_TRANSACTION");
                }

                // HTTP/REST dependencies
                if (hasAnnotation(classModifiers, "RestController", "Controller")) {
                    dependencies.add("HTTP_CONTROLLER");
                }
                if (hasAnnotation(classModifiers, "FeignClient")) {
                    dependencies.add("HTTP_FEIGN_CLIENT");
                }

                // Messaging dependencies
                if (hasAnnotation(classModifiers, "KafkaListener")) {
                    dependencies.add("MESSAGING_KAFKA");
                }
                if (hasAnnotation(classModifiers, "RabbitListener")) {
                    dependencies.add("MESSAGING_RABBITMQ");
                }

                // Caching
                if (hasAnnotation(classModifiers, "Cacheable", "CacheEvict", "CachePut")) {
                    dependencies.add("CACHE");
                }
            }

            // Check method-level annotations
            for (PsiMethod method : targetClass.getMethods()) {
                PsiModifierList methodModifiers = method.getModifierList();

                if (hasAnnotation(methodModifiers, "Transactional")) {
                    dependencies.add("DATABASE_TRANSACTION");
                }
                if (hasAnnotation(methodModifiers, "Cacheable", "CacheEvict", "CachePut")) {
                    dependencies.add("CACHE");
                }
                if (hasAnnotation(methodModifiers, "GetMapping", "PostMapping", "PutMapping", "DeleteMapping", "RequestMapping")) {
                    dependencies.add("HTTP_ENDPOINT");
                }
                if (hasAnnotation(methodModifiers, "Scheduled")) {
                    dependencies.add("SCHEDULED_JOB");
                }
                if (hasAnnotation(methodModifiers, "Async")) {
                    dependencies.add("ASYNC_EXECUTION");
                }
            }

            // Check field types for injected dependencies
            for (PsiField field : targetClass.getFields()) {
                PsiType fieldType = field.getType();
                String typeName = fieldType.getPresentableText();

                // Database types
                if (typeName.contains("Repository") || typeName.contains("EntityManager") ||
                    typeName.contains("JdbcTemplate") || typeName.contains("DataSource")) {
                    dependencies.add("DATABASE_JPA");
                }

                // HTTP client types
                if (typeName.contains("RestTemplate") || typeName.contains("WebClient") ||
                    typeName.contains("HttpClient")) {
                    dependencies.add("HTTP_CLIENT");
                }

                // Messaging types
                if (typeName.contains("KafkaTemplate") || typeName.contains("KafkaProducer")) {
                    dependencies.add("MESSAGING_KAFKA");
                }
                if (typeName.contains("RabbitTemplate") || typeName.contains("AmqpTemplate")) {
                    dependencies.add("MESSAGING_RABBITMQ");
                }

                // Cache types
                if (typeName.contains("CacheManager") || typeName.contains("RedisTemplate")) {
                    dependencies.add("CACHE");
                }
            }

            return dependencies;
        });
    }

    /**
     * Check if a modifier list has any of the specified annotations.
     */
    private static boolean hasAnnotation(@NotNull PsiModifierList modifiers, String... annotationNames) {
        for (String annotationName : annotationNames) {
            PsiAnnotation[] annotations = modifiers.getAnnotations();
            for (PsiAnnotation annotation : annotations) {
                String qualifiedName = annotation.getQualifiedName();
                if (qualifiedName != null &&
                    (qualifiedName.endsWith("." + annotationName) || qualifiedName.equals(annotationName))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Format dependency information for test generation context.
     */
    @NotNull
    public static String formatDependenciesForTests(@NotNull Set<String> dependencies) {
        if (dependencies.isEmpty()) {
            return "No external dependencies detected → UNIT TEST recommended";
        }

        StringBuilder formatted = new StringBuilder();
        formatted.append("DETECTED DEPENDENCIES:\n");

        for (String dep : dependencies) {
            formatted.append("- ").append(dep);
            switch (dep) {
                case "DATABASE_JPA":
                case "DATABASE_TRANSACTION":
                    formatted.append(" → Use @DataJpaTest or TestContainers with database");
                    break;
                case "HTTP_CLIENT":
                    formatted.append(" → Use WireMock or MockWebServer");
                    break;
                case "HTTP_ENDPOINT":
                case "HTTP_CONTROLLER":
                    formatted.append(" → Use @WebMvcTest with MockMvc");
                    break;
                case "MESSAGING_KAFKA":
                    formatted.append(" → Use TestContainers with Kafka");
                    break;
                case "MESSAGING_RABBITMQ":
                    formatted.append(" → Use TestContainers with RabbitMQ");
                    break;
                case "CACHE":
                    formatted.append(" → Use @CacheConfig or TestContainers with Redis");
                    break;
                case "ASYNC_EXECUTION":
                    formatted.append(" → Test async behavior with await/verify");
                    break;
                case "SCHEDULED_JOB":
                    formatted.append(" → Test scheduled execution logic");
                    break;
            }
            formatted.append("\n");
        }

        formatted.append("\nRECOMMENDATION: INTEGRATION TEST");
        return formatted.toString();
    }
}