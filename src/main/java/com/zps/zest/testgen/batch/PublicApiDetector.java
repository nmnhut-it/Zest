package com.zps.zest.testgen.batch;

import com.intellij.psi.*;
import com.intellij.psi.javadoc.PsiDocComment;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Detects public API methods in Java/Kotlin files.
 * Excludes constructors, test methods, and simple getters/setters.
 */
public class PublicApiDetector {

    /**
     * Data class containing method information for UI display.
     */
    public static class MethodInfo {
        private final String name;
        private final String signature;
        private final boolean isSimpleAccessor;
        private final String documentation;

        public MethodInfo(String name, String signature, boolean isSimpleAccessor, String documentation) {
            this.name = name;
            this.signature = signature;
            this.isSimpleAccessor = isSimpleAccessor;
            this.documentation = documentation;
        }

        public String getName() {
            return name;
        }

        public String getSignature() {
            return signature;
        }

        public boolean isSimpleAccessor() {
            return isSimpleAccessor;
        }

        public String getDocumentation() {
            return documentation;
        }
    }

    /**
     * Get detailed method information for UI display.
     * Returns information about all public methods including signatures and documentation.
     */
    @NotNull
    public static List<MethodInfo> getMethodDetails(@NotNull PsiFile file, boolean excludeSimpleAccessors) {
        List<MethodInfo> methodInfos = new ArrayList<>();

        file.accept(new PsiRecursiveElementVisitor() {
            @Override
            public void visitElement(@NotNull PsiElement element) {
                if (element instanceof PsiClass) {
                    PsiClass psiClass = (PsiClass) element;
                    for (PsiMethod method : psiClass.getMethods()) {
                        if (isPublicApiMethod(method, excludeSimpleAccessors)) {
                            String name = method.getName();
                            String signature = buildMethodSignature(method);
                            boolean isAccessor = isSimpleAccessor(method);
                            String documentation = extractDocumentation(method);

                            methodInfos.add(new MethodInfo(name, signature, isAccessor, documentation));
                        }
                    }
                }
                super.visitElement(element);
            }
        });

        return methodInfos;
    }

    /**
     * Build readable method signature for display.
     */
    private static String buildMethodSignature(@NotNull PsiMethod method) {
        StringBuilder signature = new StringBuilder();
        signature.append(method.getName()).append("(");

        PsiParameter[] parameters = method.getParameterList().getParameters();
        for (int i = 0; i < parameters.length; i++) {
            if (i > 0) {
                signature.append(", ");
            }
            PsiParameter param = parameters[i];
            String typeName = param.getType().getPresentableText();
            String paramName = param.getName();
            signature.append(typeName).append(" ").append(paramName);
        }

        signature.append(")");

        // Add return type
        PsiType returnType = method.getReturnType();
        if (returnType != null && !PsiType.VOID.equals(returnType)) {
            signature.append(" : ").append(returnType.getPresentableText());
        }

        return signature.toString();
    }

    /**
     * Extract documentation from method's Javadoc.
     */
    private static String extractDocumentation(@NotNull PsiMethod method) {
        PsiDocComment docComment = method.getDocComment();
        if (docComment == null) {
            return "";
        }

        // Get first sentence of documentation
        PsiElement[] descriptionElements = docComment.getDescriptionElements();
        if (descriptionElements.length == 0) {
            return "";
        }

        StringBuilder doc = new StringBuilder();
        for (PsiElement element : descriptionElements) {
            String text = element.getText().trim();
            if (!text.isEmpty()) {
                doc.append(text).append(" ");
                // Limit to first sentence
                if (text.endsWith(".")) {
                    break;
                }
            }
        }

        return doc.toString().trim();
    }

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
