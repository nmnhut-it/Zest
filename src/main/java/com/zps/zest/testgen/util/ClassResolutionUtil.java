package com.zps.zest.testgen.util;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiShortNamesCache;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Utility class for resolving Java classes using various strategies:
 * - Fully Qualified Names (FQNs) like com.example.UserService
 * - Simple class names like UserService  
 * - File paths (absolute and relative)
 * 
 * This provides consistent class resolution logic across different tools.
 */
public class ClassResolutionUtil {

    /**
     * Comprehensive class resolution that handles FQNs, simple names, and file paths.
     * 
     * @param project The IntelliJ project
     * @param classNameOrPath Either a FQN (com.example.UserService), simple name (UserService), or file path
     * @return The PsiFile containing the class, or null if not found
     */
    @Nullable
    public static PsiFile findClassFile(@NotNull Project project, @NotNull String classNameOrPath) {
        // Strategy 1: Try as fully qualified name first (most specific)
        PsiFile psiFile = findByFullyQualifiedName(project, classNameOrPath);
        if (psiFile != null) {
            return psiFile;
        }

        // Strategy 2: Try as direct file path
        psiFile = findByFilePath(project, classNameOrPath);
        if (psiFile != null) {
            return psiFile;
        }

        // Strategy 3: Try as simple class name (fallback)
        psiFile = findBySimpleClassName(project, classNameOrPath);
        if (psiFile != null) {
            return psiFile;
        }

        return null;
    }

    /**
     * Find class by fully qualified name using JavaPsiFacade.
     * This is the most reliable method for FQNs like com.example.UserService.
     */
    @Nullable
    private static PsiFile findByFullyQualifiedName(@NotNull Project project, @NotNull String className) {
        try {
            // Check if it looks like a FQN (contains dots and no file separators)
            if (className.contains(".") && !className.contains("/") && !className.contains("\\") 
                && !className.endsWith(".java") && !className.endsWith(".kt")) {
                
                PsiClass psiClass = JavaPsiFacade.getInstance(project)
                        .findClass(className, GlobalSearchScope.allScope(project));
                
                if (psiClass != null) {
                    return psiClass.getContainingFile();
                }
            }
        } catch (Exception e) {
            // Ignore and continue with other strategies
        }
        return null;
    }

    /**
     * Find class by file path (absolute, relative, or project-relative).
     */
    @Nullable
    private static PsiFile findByFilePath(@NotNull Project project, @NotNull String filePath) {
        try {
            // Try direct absolute path
            VirtualFile virtualFile = LocalFileSystem.getInstance().findFileByPath(filePath);
            if (virtualFile != null && virtualFile.exists()) {
                return PsiManager.getInstance(project).findFile(virtualFile);
            }

            // Try relative to project base path
            String basePath = project.getBasePath();
            if (basePath != null) {
                virtualFile = LocalFileSystem.getInstance().findFileByPath(basePath + "/" + filePath);
                if (virtualFile != null && virtualFile.exists()) {
                    return PsiManager.getInstance(project).findFile(virtualFile);
                }
            }
        } catch (Exception e) {
            // Ignore and continue
        }
        return null;
    }

    /**
     * Find class by simple name using PsiShortNamesCache.
     * This is a fallback for cases where only the class name is provided.
     */
    @Nullable
    private static PsiFile findBySimpleClassName(@NotNull Project project, @NotNull String className) {
        try {
            // Extract simple class name from path or FQN
            String simpleClassName = extractSimpleClassName(className);
            
            PsiClass[] classesByName = PsiShortNamesCache.getInstance(project)
                    .getClassesByName(simpleClassName, GlobalSearchScope.allScope(project));
            
            if (classesByName.length > 0) {
                return classesByName[0].getContainingFile();
            }
        } catch (Exception e) {
            // Ignore and continue
        }
        return null;
    }

    /**
     * Extract simple class name from various input formats.
     */
    @NotNull
    private static String extractSimpleClassName(@NotNull String input) {
        String name = input;
        
        // Remove .java or .kt extension
        if (name.endsWith(".java") || name.endsWith(".kt")) {
            name = name.substring(0, name.lastIndexOf('.'));
        }
        
        // Extract class name from file path
        if (name.contains("/")) {
            name = name.substring(name.lastIndexOf("/") + 1);
        }
        if (name.contains("\\")) {
            name = name.substring(name.lastIndexOf("\\") + 1);
        }
        
        // Extract class name from FQN
        if (name.contains(".")) {
            name = name.substring(name.lastIndexOf(".") + 1);
        }
        
        return name;
    }

    /**
     * Check if the input looks like a fully qualified name.
     */
    public static boolean isFQN(@NotNull String input) {
        return input.contains(".") 
               && !input.contains("/") 
               && !input.contains("\\")
               && !input.endsWith(".java") 
               && !input.endsWith(".kt")
               && Character.isLowerCase(input.charAt(0)); // FQNs typically start with lowercase package
    }

    /**
     * Generate a descriptive error message for class resolution failures.
     */
    @NotNull
    public static String generateNotFoundMessage(@NotNull String input) {
        StringBuilder message = new StringBuilder();
        message.append("Class or file not found: ").append(input).append("\n\n");
        
        if (isFQN(input)) {
            message.append("Searched as fully qualified name: ").append(input).append("\n");
            message.append("Searched as simple class name: ").append(extractSimpleClassName(input)).append("\n");
        } else if (input.contains("/") || input.contains("\\")) {
            message.append("Searched as file path: ").append(input).append("\n");
            message.append("Searched as simple class name: ").append(extractSimpleClassName(input)).append("\n");
        } else {
            message.append("Searched as simple class name: ").append(input).append("\n");
        }
        
        message.append("\nSuggestions:\n");
        message.append("- For FQNs: Use format like 'com.example.service.UserService'\n");
        message.append("- For paths: Use full path like '/src/main/java/com/example/UserService.java'\n");
        message.append("- For simple names: Ensure the class exists in the project\n");
        
        return message.toString();
    }
}