package com.zps.zest.testgen.tools;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiShortNamesCache;
import dev.langchain4j.agent.tool.Tool;

/**
 * Tool for looking up class implementation from PSI (works with bundled JARs and libraries).
 * Unlike ripgrep which only searches project source files, this can find classes in:
 * - Project classes
 * - Library dependencies (JARs)
 * - JDK classes
 */
public class LookupClassTool {

    private final Project project;

    public LookupClassTool(Project project) {
        this.project = project;
    }

    @Tool("""
        Look up class implementation using fully qualified class name.
        Works with project classes, library JARs, and JDK classes (unlike ripgrep which only searches source files).

        Parameters:
        - className: Fully qualified class name
          * Outer class: "com.example.UserService"
          * INNER CLASS: Use $ separator: "com.example.TestPlan$TestScenario" (NOT dot notation)
          * Nested inner: "com.example.Outer$Middle$Inner"

        Returns: Class signature with modifiers, type parameters, superclass, interfaces, inner classes, and member summary

        Examples:
        - lookupClass("java.util.ArrayList") → finds ArrayList class details
        - lookupClass("com.example.UserService") → finds UserService in project
        - lookupClass("org.junit.jupiter.api.Test") → finds JUnit Test annotation
        - lookupClass("com.zps.zest.testgen.model.TestPlan$TestScenario") → finds inner class

        Use this when:
        - You need class structure from libraries
        - Ripgrep can't find the class (it's in a JAR)
        - You need to understand class hierarchy and members
        - You need to inspect inner classes or enums
        """)
    public String lookupClass(String className) {
        return ApplicationManager.getApplication().runReadAction(
            (Computable<String>) () -> {
                try {
                    JavaPsiFacade facade = JavaPsiFacade.getInstance(project);
                    PsiClass psiClass = facade.findClass(className, GlobalSearchScope.allScope(project));

                    if (psiClass == null) {
                        return "❌ Class not found: " + className + "\n\n" +
                               suggestFullyQualifiedNames(className);
                    }

                    StringBuilder result = new StringBuilder();
                    result.append("✅ Found class: ").append(className).append("\n\n");

                    // Location
                    PsiFile containingFile = psiClass.getContainingFile();
                    if (containingFile != null) {
                        result.append("📁 Location: ").append(containingFile.getName()).append("\n\n");
                    }

                    // Build class signature
                    result.append("## Class Signature\n\n");

                    // Modifiers
                    PsiModifierList classModifiers = psiClass.getModifierList();
                    if (classModifiers != null) {
                        if (classModifiers.hasModifierProperty(PsiModifier.PUBLIC)) result.append("public ");
                        if (classModifiers.hasModifierProperty(PsiModifier.PROTECTED)) result.append("protected ");
                        if (classModifiers.hasModifierProperty(PsiModifier.PRIVATE)) result.append("private ");
                        if (classModifiers.hasModifierProperty(PsiModifier.ABSTRACT)) result.append("abstract ");
                        if (classModifiers.hasModifierProperty(PsiModifier.FINAL)) result.append("final ");
                        if (classModifiers.hasModifierProperty(PsiModifier.STATIC)) result.append("static ");
                    }

                    // Class type
                    if (psiClass.isInterface()) {
                        result.append("interface ");
                    } else if (psiClass.isEnum()) {
                        result.append("enum ");
                    } else if (psiClass.isAnnotationType()) {
                        result.append("@interface ");
                    } else {
                        result.append("class ");
                    }

                    // Class name
                    result.append(psiClass.getName());

                    // Type parameters
                    PsiTypeParameter[] typeParameters = psiClass.getTypeParameters();
                    if (typeParameters.length > 0) {
                        result.append("<");
                        for (int i = 0; i < typeParameters.length; i++) {
                            if (i > 0) result.append(", ");
                            result.append(typeParameters[i].getName());

                            // Type parameter bounds
                            PsiClassType[] bounds = typeParameters[i].getExtendsListTypes();
                            if (bounds.length > 0) {
                                result.append(" extends ");
                                for (int j = 0; j < bounds.length; j++) {
                                    if (j > 0) result.append(" & ");
                                    result.append(bounds[j].getPresentableText());
                                }
                            }
                        }
                        result.append(">");
                    }

                    // Superclass
                    if (!psiClass.isInterface() && !psiClass.isEnum()) {
                        PsiClassType[] superTypes = psiClass.getExtendsListTypes();
                        if (superTypes.length > 0) {
                            result.append(" extends ").append(superTypes[0].getPresentableText());
                        }
                    }

                    // Interfaces
                    PsiClassType[] interfaces = psiClass.getImplementsListTypes();
                    if (interfaces.length > 0) {
                        if (psiClass.isInterface()) {
                            result.append(" extends ");
                        } else {
                            result.append(" implements ");
                        }
                        for (int i = 0; i < interfaces.length; i++) {
                            if (i > 0) result.append(", ");
                            result.append(interfaces[i].getPresentableText());
                        }
                    }

                    result.append("\n\n");

                    // Fields summary
                    PsiField[] fields = psiClass.getFields();
                    if (fields.length > 0) {
                        result.append("## Fields (").append(fields.length).append(")\n\n");
                        for (PsiField field : fields) {
                            result.append("  ");
                            PsiModifierList fieldModifiers = field.getModifierList();
                            if (fieldModifiers != null) {
                                if (fieldModifiers.hasModifierProperty(PsiModifier.PUBLIC)) result.append("public ");
                                if (fieldModifiers.hasModifierProperty(PsiModifier.PROTECTED)) result.append("protected ");
                                if (fieldModifiers.hasModifierProperty(PsiModifier.PRIVATE)) result.append("private ");
                                if (fieldModifiers.hasModifierProperty(PsiModifier.STATIC)) result.append("static ");
                                if (fieldModifiers.hasModifierProperty(PsiModifier.FINAL)) result.append("final ");
                            }
                            result.append(field.getType().getPresentableText())
                                  .append(" ")
                                  .append(field.getName())
                                  .append("\n");
                        }
                        result.append("\n");
                    }

                    // Methods summary
                    PsiMethod[] methods = psiClass.getMethods();
                    if (methods.length > 0) {
                        result.append("## Methods (").append(methods.length).append(")\n\n");
                        int displayLimit = 15; // Show first 15 methods
                        for (int i = 0; i < Math.min(methods.length, displayLimit); i++) {
                            PsiMethod method = methods[i];
                            result.append("  ");

                            PsiModifierList methodModifiers = method.getModifierList();
                            if (methodModifiers.hasModifierProperty(PsiModifier.PUBLIC)) result.append("public ");
                            if (methodModifiers.hasModifierProperty(PsiModifier.PROTECTED)) result.append("protected ");
                            if (methodModifiers.hasModifierProperty(PsiModifier.PRIVATE)) result.append("private ");
                            if (methodModifiers.hasModifierProperty(PsiModifier.STATIC)) result.append("static ");
                            if (methodModifiers.hasModifierProperty(PsiModifier.ABSTRACT)) result.append("abstract ");
                            if (methodModifiers.hasModifierProperty(PsiModifier.FINAL)) result.append("final ");

                            if (method.getReturnType() != null) {
                                result.append(method.getReturnType().getPresentableText()).append(" ");
                            } else {
                                result.append("void ");
                            }

                            result.append(method.getName()).append("(");
                            PsiParameter[] params = method.getParameterList().getParameters();
                            for (int j = 0; j < params.length; j++) {
                                if (j > 0) result.append(", ");
                                result.append(params[j].getType().getPresentableText());
                            }
                            result.append(")\n");
                        }

                        if (methods.length > displayLimit) {
                            result.append("  ... and ").append(methods.length - displayLimit).append(" more methods\n");
                        }
                        result.append("\n");
                    }

                    // Inner classes
                    PsiClass[] innerClasses = psiClass.getInnerClasses();
                    if (innerClasses.length > 0) {
                        result.append("## Inner Classes (").append(innerClasses.length).append(")\n\n");
                        for (PsiClass innerClass : innerClasses) {
                            result.append("  ");
                            if (innerClass.getModifierList() != null) {
                                if (innerClass.getModifierList().hasModifierProperty(PsiModifier.PUBLIC)) result.append("public ");
                                if (innerClass.getModifierList().hasModifierProperty(PsiModifier.PROTECTED)) result.append("protected ");
                                if (innerClass.getModifierList().hasModifierProperty(PsiModifier.PRIVATE)) result.append("private ");
                                if (innerClass.getModifierList().hasModifierProperty(PsiModifier.STATIC)) result.append("static ");
                            }
                            if (innerClass.isInterface()) {
                                result.append("interface ");
                            } else if (innerClass.isEnum()) {
                                result.append("enum ");
                            } else {
                                result.append("class ");
                            }
                            result.append(innerClass.getName()).append("\n");
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
        result.append("   lookupClass(\"").append(matches[0].getQualifiedName()).append("\")");

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