package com.zps.zest.testgen.tools;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiShortNamesCache;
import dev.langchain4j.agent.tool.Tool;

/**
 * Tool for looking up method signatures from PSI (works with bundled JARs and libraries).
 * Unlike ripgrep which only searches project source files, this can find methods in:
 * - Project classes
 * - Library dependencies (JARs)
 * - JDK classes
 */
public class LookupMethodTool {

    private final Project project;

    public LookupMethodTool(Project project) {
        this.project = project;
    }

    @Tool("""
        Look up method signatures using fully qualified class name and method name.
        Works with project classes, library JARs, and JDK classes (unlike ripgrep which only searches source files).

        Parameters:
        - className: Fully qualified class name (e.g., "java.util.List", "com.example.UserService")
        - methodName: Method name to find (e.g., "add", "getUserById")

        Returns: All matching method signatures with return types and parameters

        Examples:
        - lookupMethod("java.util.List", "add") → finds List.add() signatures
        - lookupMethod("com.example.UserService", "findById") → finds findById() in UserService
        - lookupMethod("org.junit.jupiter.api.Assertions", "assertEquals") → finds JUnit assertion methods

        Use this when:
        - You need exact method signatures from libraries
        - Ripgrep can't find the method (it's in a JAR)
        - You need to verify method parameters/return types
        """)
    public String lookupMethod(String className, String methodName) {
        return ApplicationManager.getApplication().runReadAction(
            (Computable<String>) () -> {
                try {
                    JavaPsiFacade facade = JavaPsiFacade.getInstance(project);
                    PsiClass psiClass = facade.findClass(className, GlobalSearchScope.allScope(project));

                    if (psiClass == null) {
                        return "❌ Class not found: " + className + "\n\n" +
                               suggestFullyQualifiedNames(className);
                    }

                    PsiMethod[] methods = psiClass.findMethodsByName(methodName, true);
                    if (methods.length == 0) {
                        return "❌ Method not found: " + methodName + " in class " + className +
                               "\n\nAvailable methods in this class:\n" + listAvailableMethods(psiClass, 10);
                    }

                    StringBuilder result = new StringBuilder();
                    result.append("✅ Found ").append(methods.length).append(" signature(s) for ")
                          .append(className).append(".").append(methodName).append(":\n\n");

                    for (int i = 0; i < methods.length; i++) {
                        PsiMethod method = methods[i];
                        result.append(i + 1).append(". ");

                        // Modifiers
                        PsiModifierList modifiers = method.getModifierList();
                        if (modifiers.hasModifierProperty(PsiModifier.PUBLIC)) result.append("public ");
                        if (modifiers.hasModifierProperty(PsiModifier.PROTECTED)) result.append("protected ");
                        if (modifiers.hasModifierProperty(PsiModifier.PRIVATE)) result.append("private ");
                        if (modifiers.hasModifierProperty(PsiModifier.STATIC)) result.append("static ");
                        if (modifiers.hasModifierProperty(PsiModifier.FINAL)) result.append("final ");

                        // Return type
                        if (method.getReturnType() != null) {
                            result.append(method.getReturnType().getPresentableText()).append(" ");
                        } else {
                            result.append("void ");
                        }

                        // Method name and parameters
                        result.append(methodName).append("(");
                        PsiParameter[] params = method.getParameterList().getParameters();
                        for (int j = 0; j < params.length; j++) {
                            if (j > 0) result.append(", ");
                            result.append(params[j].getType().getPresentableText())
                                  .append(" ")
                                  .append(params[j].getName());
                        }
                        result.append(")");

                        // Exceptions
                        PsiClassType[] exceptions = method.getThrowsList().getReferencedTypes();
                        if (exceptions.length > 0) {
                            result.append(" throws ");
                            for (int j = 0; j < exceptions.length; j++) {
                                if (j > 0) result.append(", ");
                                result.append(exceptions[j].getPresentableText());
                            }
                        }

                        result.append("\n");

                        // Location
                        PsiFile containingFile = method.getContainingFile();
                        if (containingFile != null) {
                            result.append("   Location: ").append(containingFile.getName()).append("\n");
                        }

                        result.append("\n");
                    }

                    return result.toString();
                } catch (Exception e) {
                    return "❌ ERROR: " + e.getMessage();
                }
            }
        );
    }

    /**
     * Helper method to list available methods when the requested method is not found
     */
    private String listAvailableMethods(PsiClass psiClass, int limit) {
        PsiMethod[] allMethods = psiClass.getAllMethods();
        StringBuilder result = new StringBuilder();
        int count = 0;
        for (PsiMethod method : allMethods) {
            if (count >= limit) {
                result.append("... and ").append(allMethods.length - limit).append(" more\n");
                break;
            }
            result.append("  - ").append(method.getName()).append("()\n");
            count++;
        }
        return result.toString();
    }

    /**
     * Suggest fully qualified names when class is not found.
     * Uses PsiShortNamesCache to search by simple class name.
     */
    private String suggestFullyQualifiedNames(String className) {
        // Extract simple name (last part after dot, or whole string if no dot)
        String simpleName = className.contains(".") ?
            className.substring(className.lastIndexOf('.') + 1) :
            className;

        // Search using PsiShortNamesCache
        PsiShortNamesCache cache = PsiShortNamesCache.getInstance(project);
        PsiClass[] matches = cache.getClassesByName(simpleName, GlobalSearchScope.allScope(project));

        if (matches.length == 0) {
            return "💡 No classes found with name '" + simpleName + "'.\n" +
                   "   → This might be a missing dependency or typo.\n" +
                   "   → Check if the class exists in your project or dependencies.";
        }

        // Build suggestion list
        StringBuilder result = new StringBuilder();
        result.append("💡 Did you mean one of these?\n\n");

        for (int i = 0; i < Math.min(matches.length, 5); i++) {
            PsiClass match = matches[i];
            String fqn = match.getQualifiedName();
            String location = isFromLibrary(match) ? "library" : "project";
            result.append("   ").append(i + 1).append(". ").append(fqn)
                  .append(" [").append(location).append("]\n");
        }

        if (matches.length > 5) {
            result.append("   ... and ").append(matches.length - 5).append(" more\n");
        }

        result.append("\n💡 Try again with the fully qualified name, e.g.:\n");
        result.append("   lookupMethod(\"").append(matches[0].getQualifiedName()).append("\", \"methodName\")");

        return result.toString();
    }

    /**
     * Check if a class is from a library (JAR) or from project source
     */
    private boolean isFromLibrary(PsiClass psiClass) {
        PsiFile containingFile = psiClass.getContainingFile();
        if (containingFile == null || containingFile.getVirtualFile() == null) {
            return false;
        }
        String filePath = containingFile.getVirtualFile().getPath();
        return filePath.contains(".jar") || filePath.contains(".class");
    }
}