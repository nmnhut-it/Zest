package com.zps.zest.testgen.batch;

import com.intellij.psi.*;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Detects public API methods in Java/Kotlin files.
 * Excludes constructors, test methods, and simple getters/setters.
 */
public class PublicApiDetector {

    /**
     * Find all public API methods in a file.
     * Excludes: constructors, test methods, simple accessors.
     */
    @NotNull
    public static List<PsiMethod> findPublicMethods(@NotNull PsiFile file, boolean excludeSimpleAccessors) {
        List<PsiMethod> publicMethods = new ArrayList<>();

        file.accept(new PsiRecursiveElementVisitor() {
            @Override
            public void visitElement(@NotNull PsiElement element) {
                if (element instanceof PsiClass) {
                    PsiClass psiClass = (PsiClass) element;
                    for (PsiMethod method : psiClass.getMethods()) {
                        if (isPublicApiMethod(method, excludeSimpleAccessors)) {
                            publicMethods.add(method);
                        }
                    }
                }
                super.visitElement(element);
            }
        });

        return publicMethods;
    }

    /**
     * Count public API methods in a file.
     */
    public static int countPublicMethods(@NotNull PsiFile file, boolean excludeSimpleAccessors) {
        return findPublicMethods(file, excludeSimpleAccessors).size();
    }

    /**
     * Check if file has any public API methods.
     */
    public static boolean hasPublicApi(@NotNull PsiFile file, boolean excludeSimpleAccessors) {
        return countPublicMethods(file, excludeSimpleAccessors) > 0;
    }

    /**
     * Check if a method is a public API method.
     */
    private static boolean isPublicApiMethod(@NotNull PsiMethod method, boolean excludeSimpleAccessors) {
        // Exclude constructors
        if (method.isConstructor()) {
            return false;
        }

        // Must be public
        PsiModifierList modifierList = method.getModifierList();
        if (!modifierList.hasModifierProperty(PsiModifier.PUBLIC)) {
            return false;
        }

        // Exclude test methods
        if (isTestMethod(method)) {
            return false;
        }

        // Optionally exclude simple accessors
        if (excludeSimpleAccessors && isSimpleAccessor(method)) {
            return false;
        }

        return true;
    }

    /**
     * Check if method is a test method.
     */
    private static boolean isTestMethod(@NotNull PsiMethod method) {
        String name = method.getName();

        // Check name patterns
        if (name.startsWith("test") || name.contains("Test")) {
            return true;
        }

        // Check annotations
        PsiModifierList modifierList = method.getModifierList();
        for (PsiAnnotation annotation : modifierList.getAnnotations()) {
            String qualifiedName = annotation.getQualifiedName();
            if (qualifiedName != null &&
                (qualifiedName.contains("Test") ||
                 qualifiedName.contains("Before") ||
                 qualifiedName.contains("After"))) {
                return true;
            }
        }

        return false;
    }

    /**
     * Check if method is a simple getter/setter (≤1 statement).
     */
    private static boolean isSimpleAccessor(@NotNull PsiMethod method) {
        String name = method.getName();

        // Check naming pattern
        boolean isAccessorNamed = name.startsWith("get") ||
                                 name.startsWith("set") ||
                                 name.startsWith("is");

        if (!isAccessorNamed) {
            return false;
        }

        // Check body size
        PsiCodeBlock body = method.getBody();
        if (body == null) {
            return true; // Abstract/interface method
        }

        PsiStatement[] statements = body.getStatements();
        return statements.length <= 1;
    }
}
