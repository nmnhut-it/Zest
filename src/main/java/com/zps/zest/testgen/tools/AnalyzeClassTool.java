package com.zps.zest.testgen.tools;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.zps.zest.ClassAnalyzer;
import com.zps.zest.testgen.util.ClassResolutionUtil;
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
        - filePathOrClassName: Can be one of:
          * Fully qualified name: "com.example.service.UserService" (preferred for precision)
          * Full file path: "/src/main/java/com/example/UserService.java"
          * Simple class name: "UserService" (may find multiple matches)
          
        The tool tries multiple resolution strategies in order of specificity.
        
        Returns: Detailed analysis of the class structure or an error message if the class cannot be found.
        
        Example usage:
        - analyzeClass("com.example.service.UserService") (FQN - most reliable)
        - analyzeClass("/src/main/java/com/example/UserService.java") (file path)  
        - analyzeClass("UserService") (simple name - fallback)
        """)
    public String analyzeClass(String filePathOrClassName) {
        return ApplicationManager.getApplication().runReadAction((Computable<String>) () -> {
            try {
                PsiFile psiFile = ClassResolutionUtil.findClassFile(project, filePathOrClassName);
                if (psiFile == null) {
                    return ClassResolutionUtil.generateNotFoundMessage(filePathOrClassName);
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

}