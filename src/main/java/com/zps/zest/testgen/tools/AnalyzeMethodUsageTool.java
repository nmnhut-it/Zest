package com.zps.zest.testgen.tools;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiMethod;
import com.zps.zest.testgen.analysis.UsageAnalyzer;
import com.zps.zest.testgen.analysis.UsageContext;
import com.zps.zest.testgen.util.ClassResolutionUtil;
import dev.langchain4j.agent.tool.Tool;
import org.jetbrains.annotations.NotNull;

/**
 * Tool for analyzing how methods are used throughout the project.
 * This is the INVERSE of AnalyzeClassTool - while AnalyzeClassTool shows what a class USES,
 * this tool shows WHO USES a method and HOW.
 */
public class AnalyzeMethodUsageTool {
    private static final Logger LOG = Logger.getInstance(AnalyzeMethodUsageTool.class);
    private static final int DEFAULT_MAX_CALL_SITES = 20;
    private static final int ABSOLUTE_MAX_CALL_SITES = 50;

    private final Project project;
    private final UsageAnalyzer usageAnalyzer;

    public AnalyzeMethodUsageTool(@NotNull Project project) {
        this.project = project;
        this.usageAnalyzer = new UsageAnalyzer(project);
    }

    @Tool("""
        Analyze real-world usage patterns for a specific method.
        Finds ALL call sites, extracts usage context, and identifies patterns.

        This is the OPPOSITE of analyzeClass - while analyzeClass shows what a class USES,
        this tool shows WHO USES the method and HOW.

        Returns comprehensive usage information:
        - All callers with file paths and line numbers
        - Surrounding code context for each call site
        - Common usage patterns detected
        - Error handling approaches (try-catch, null checks, Optional handling)
        - Edge cases discovered from actual usage (null handling, validation failures, etc.)
        - Integration context (transactions, async operations, event listeners, loops)
        - Test data examples from existing tests
        - Categorized call sites (Controller, Service, Repository, Test, etc.)

        This information is CRITICAL for understanding:
        - How the method is actually used in practice
        - What error conditions callers expect
        - What test scenarios are important
        - What edge cases exist in the real codebase

        Parameters:
        - className: Fully qualified class name (e.g., "com.example.service.ProfileStorageService")
        - methodName: Method name to analyze (e.g., "getProfile")
        - maxCallSites: Optional limit on call sites to analyze (default: 20, max: 50)
                       Higher values provide more complete picture but take longer

        Example usage:
        - analyzeMethodUsage("com.example.service.ProfileStorageService", "getProfile", 20)
        - analyzeMethodUsage("com.example.handler.ProfileHandler", "handleGetProfile", 10)

        IMPORTANT: This tool performs deep analysis and may take a few seconds for popular methods.
        """)
    public String analyzeMethodUsage(String className, String methodName, Integer maxCallSites) {
        return ApplicationManager.getApplication().runReadAction((Computable<String>) () -> {
            try {
                // Validate and set default for maxCallSites
                final int effectiveMaxCallSites;
                if (maxCallSites == null) {
                    effectiveMaxCallSites = DEFAULT_MAX_CALL_SITES;
                } else {
                    effectiveMaxCallSites = Math.max(1, Math.min(maxCallSites, ABSOLUTE_MAX_CALL_SITES));
                }

                LOG.info("Analyzing usage for " + className + "." + methodName +
                        " (max call sites: " + effectiveMaxCallSites + ")");

                // Find the class file
                com.intellij.psi.PsiFile psiFile = ClassResolutionUtil.findClassFile(project, className);
                if (psiFile == null) {
                    return ClassResolutionUtil.generateNotFoundMessage(className);
                }

                if (!(psiFile instanceof com.intellij.psi.PsiJavaFile)) {
                    return "Error: Not a Java file: " + className + "\n\n" +
                           "This tool only analyzes Java classes. " +
                           "Please provide a Java class name.";
                }

                com.intellij.psi.PsiJavaFile javaFile = (com.intellij.psi.PsiJavaFile) psiFile;
                PsiClass[] classes = javaFile.getClasses();
                if (classes.length == 0) {
                    return "Error: No classes found in file: " + className;
                }

                PsiClass psiClass = classes[0]; // Use first class

                // Find the method in the class
                PsiMethod targetMethod = findMethod(psiClass, methodName);
                if (targetMethod == null) {
                    // List available methods to help user
                    StringBuilder availableMethods = new StringBuilder();
                    availableMethods.append("Error: Method '").append(methodName)
                                  .append("' not found in class ").append(className).append("\n\n");
                    availableMethods.append("Available methods in ").append(psiClass.getName()).append(":\n");

                    PsiMethod[] methods = psiClass.getMethods();
                    if (methods.length == 0) {
                        availableMethods.append("  (No methods found)\n");
                    } else {
                        for (PsiMethod method : methods) {
                            availableMethods.append("  - ").append(method.getName());
                            if (method.getParameterList().getParametersCount() > 0) {
                                availableMethods.append("(...)");
                            } else {
                                availableMethods.append("()");
                            }
                            availableMethods.append("\n");
                        }
                    }

                    return availableMethods.toString();
                }

                // Perform usage analysis
                UsageContext usageContext = usageAnalyzer.analyzeMethod(targetMethod);

                // Check if any usages were found
                if (usageContext.isEmpty()) {
                    return "No usages found for " + className + "." + methodName + "\n\n" +
                           "This could mean:\n" +
                           "- The method is new and not yet used\n" +
                           "- The method is private and only called internally\n" +
                           "- The method is dead code that could be removed\n" +
                           "- The method is only used in tests (check test scope)\n\n" +
                           "Consider searching for the method name manually to verify.";
                }

                // Format results for LLM consumption
                String formattedResults = usageContext.formatForLLM();

                // Add metadata summary at the end
                StringBuilder summary = new StringBuilder(formattedResults);
                summary.append("\n**ANALYSIS SUMMARY**\n");
                summary.append("```\n");
                summary.append("Method: ").append(className).append(".").append(methodName).append("\n");
                summary.append("Total call sites analyzed: ").append(usageContext.getTotalUsages()).append("\n");
                summary.append("Edge cases discovered: ").append(usageContext.getDiscoveredEdgeCases().size()).append("\n");
                summary.append("Test examples found: ").append(usageContext.getTestDataExamples().size()).append("\n");

                if (usageContext.getTotalUsages() >= effectiveMaxCallSites) {
                    summary.append("\n⚠️ Reached maximum call sites limit (").append(effectiveMaxCallSites).append(").\n");
                    summary.append("   There may be more usages. Increase maxCallSites if you need a more complete picture.\n");
                }
                summary.append("```\n");

                LOG.info("Usage analysis complete for " + className + "." + methodName + ": " +
                        usageContext.getTotalUsages() + " call sites, " +
                        usageContext.getDiscoveredEdgeCases().size() + " edge cases");

                return summary.toString();

            } catch (Exception e) {
                LOG.error("Error analyzing method usage for " + className + "." + methodName, e);
                return "Error analyzing method usage: " + e.getMessage() + "\n\n" +
                       "Please check:\n" +
                       "- Class name is fully qualified (e.g., 'com.example.MyClass')\n" +
                       "- Method name is spelled correctly\n" +
                       "- The class file is accessible in the project";
            }
        });
    }

    /**
     * Find a method by name in a class. Handles overloaded methods by returning the first match.
     */
    private PsiMethod findMethod(@NotNull PsiClass psiClass, @NotNull String methodName) {
        PsiMethod[] methods = psiClass.findMethodsByName(methodName, false);
        if (methods.length > 0) {
            if (methods.length > 1) {
                LOG.info("Found " + methods.length + " overloaded methods named '" + methodName +
                        "'. Analyzing the first one. Results will include all overloads.");
            }
            return methods[0]; // Return first match (could be any overload)
        }
        return null;
    }
}
