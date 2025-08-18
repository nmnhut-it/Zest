package com.zps.zest.testgen.tools;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiShortNamesCache;
import com.zps.zest.ClassAnalyzer;
import dev.langchain4j.agent.tool.Tool;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

/**
 * Tool for analyzing Java classes to extract structure, dependencies, and relationships.
 * This tool provides comprehensive analysis of Java classes for test generation context.
 */
public class AnalyzeClassTool {
    private final Project project;
    private final Map<String, String> analyzedClasses;

    public AnalyzeClassTool(@NotNull Project project, @NotNull Map<String, String> analyzedClasses) {
        this.project = project;
        this.analyzedClasses = analyzedClasses;
    }

    @Tool("""
        Analyze a Java class to extract its complete structure, dependencies, and relationships.
        This provides comprehensive information including:
        - Class hierarchy and interfaces
        - Public/private methods and their signatures
        - Field declarations and dependencies
        - Annotations and their parameters
        - Inner classes and enums
        - Direct dependencies on other classes
        
        Parameters:
        - filePathOrClassName: Either a full file path (e.g., "/path/to/MyClass.java") 
                              or a simple class name (e.g., "MyClass") to search in the project.
                              The tool will attempt to locate the class using both approaches.
        
        Returns: Detailed analysis of the class structure or an error message if the class cannot be found.
        
        Example usage:
        - analyzeClass("/src/main/java/com/example/UserService.java")
        - analyzeClass("UserService")
        """)
    public String analyzeClass(String filePathOrClassName) {
        return ApplicationManager.getApplication().runReadAction((Computable<String>) () -> {
            try {
                PsiFile psiFile = findClassFile(filePathOrClassName);
                if (psiFile == null) {
                    return "File or class not found: " + filePathOrClassName + 
                           "\nTry providing the full path or ensure the class name is correct.";
                }

                if (!(psiFile instanceof PsiJavaFile)) {
                    return "Not a Java file: " + filePathOrClassName + 
                           "\nThis tool only analyzes Java classes.";
                }

                PsiJavaFile javaFile = (PsiJavaFile) psiFile;
                PsiClass[] classes = javaFile.getClasses();
                if (classes.length == 0) {
                    return "No classes found in file: " + filePathOrClassName;
                }

                PsiClass targetClass = classes[0];
                String contextInfo = ClassAnalyzer.collectClassContext(targetClass);

                // Store the analyzed class with its canonical path
                String canonicalPath = psiFile.getVirtualFile().getPath();
                analyzedClasses.put(canonicalPath, contextInfo);

                return String.format("Analyzed class '%s' from file: %s\n\n%s",
                        targetClass.getName(),
                        canonicalPath,
                        contextInfo);
                        
            } catch (Exception e) {
                return "Error analyzing class: " + e.getMessage() + 
                       "\nPlease check the file path or class name and try again.";
            }
        });
    }

    /**
     * Attempts to find a PsiFile by file path or class name.
     * Tries multiple strategies to locate the file.
     */
    private PsiFile findClassFile(String filePathOrClassName) {
        // First try as a direct file path
        VirtualFile virtualFile = LocalFileSystem.getInstance().findFileByPath(filePathOrClassName);
        if (virtualFile != null && virtualFile.exists()) {
            return PsiManager.getInstance(project).findFile(virtualFile);
        }

        // Try relative to project base path
        String basePath = project.getBasePath();
        if (basePath != null) {
            virtualFile = LocalFileSystem.getInstance().findFileByPath(basePath + "/" + filePathOrClassName);
            if (virtualFile != null && virtualFile.exists()) {
                return PsiManager.getInstance(project).findFile(virtualFile);
            }
        }

        // Try to find by class name using IntelliJ's class index
        String className = extractClassName(filePathOrClassName);
        PsiClass[] classesByName = PsiShortNamesCache.getInstance(project)
                .getClassesByName(className, GlobalSearchScope.allScope(project));
        
        if (classesByName.length > 0) {
            return classesByName[0].getContainingFile();
        }

        return null;
    }

    /**
     * Extracts the class name from a path or returns the input if it's already a class name.
     */
    private String extractClassName(String filePathOrClassName) {
        // Remove .java extension if present
        String name = filePathOrClassName.replace(".java", "");
        
        // Extract just the class name from a path
        if (name.contains("/")) {
            name = name.substring(name.lastIndexOf("/") + 1);
        }
        if (name.contains("\\")) {
            name = name.substring(name.lastIndexOf("\\") + 1);
        }
        
        return name;
    }
}