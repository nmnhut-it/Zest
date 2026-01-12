package com.zps.zest.mcp.refactor;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.coverage.CoverageDataManager;
import com.intellij.coverage.CoverageSuitesBundle;
import com.intellij.coverage.JavaCoverageAnnotator;
import com.intellij.coverage.PackageAnnotator;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;

import java.util.*;

/**
 * Handler for test execution and coverage measurement via IntelliJ APIs.
 *
 * Requires IntelliJ's coverage plugin to be enabled. Coverage data is available
 * after running tests with coverage (Run → Run with Coverage).
 */
public class TestCoverageToolHandler {
    private static final Logger LOG = Logger.getInstance(TestCoverageToolHandler.class);

    /**
     * Get current test coverage data for a class.
     *
     * Note: This requires coverage to be run first in IntelliJ.
     * Returns coverage metrics if available, or a message to run coverage.
     */
    public static JsonObject getCoverageData(Project project, String className) {
        return ApplicationManager.getApplication().runReadAction((Computable<JsonObject>) () -> {
            JsonObject result = new JsonObject();
            result.addProperty("className", className);

            try {
                // Get coverage data manager
                CoverageDataManager coverageManager = CoverageDataManager.getInstance(project);
                CoverageSuitesBundle currentSuite = coverageManager.getCurrentSuitesBundle();

                if (currentSuite == null) {
                    result.addProperty("hasCoverage", false);
                    result.addProperty("message", "No coverage data available. Run tests with coverage first (Run → Run with Coverage).");
                    return result;
                }

                // Find the class
                PsiClass psiClass = findClass(project, className);
                if (psiClass == null) {
                    result.addProperty("hasCoverage", false);
                    result.addProperty("error", "Class not found: " + className);
                    return result;
                }

                // Get coverage annotator
                PackageAnnotator.ClassCoverageInfo classCoverage =
                    currentSuite.getCoverageEngine() != null ?
                    getCoverageInfo(currentSuite, psiClass) : null;

                if (classCoverage != null) {
                    result.addProperty("hasCoverage", true);
                    result.addProperty("totalLineCount", classCoverage.totalLineCount);
                    result.addProperty("coveredLineCount", classCoverage.coveredLineCount);

                    if (classCoverage.totalLineCount > 0) {
                        double percentage = (double) classCoverage.coveredLineCount / classCoverage.totalLineCount * 100;
                        result.addProperty("coveragePercentage", String.format("%.2f%%", percentage));
                    }
                } else {
                    result.addProperty("hasCoverage", false);
                    result.addProperty("message", "Coverage data not available for this class. Try running tests with coverage for this class.");
                }

            } catch (Exception e) {
                LOG.warn("Error getting coverage data", e);
                result.addProperty("hasCoverage", false);
                result.addProperty("message", "Coverage plugin available but error occurred: " + e.getMessage());
            }

            return result;
        });
    }

    private static PackageAnnotator.ClassCoverageInfo getCoverageInfo(CoverageSuitesBundle suite, PsiClass psiClass) {
        try {
            JavaCoverageAnnotator annotator = (JavaCoverageAnnotator) suite.getAnnotator(psiClass.getProject());
            if (annotator != null) {
                String qualifiedName = psiClass.getQualifiedName();
                return annotator.getClassCoverageInfo(qualifiedName);
            }
        } catch (Exception e) {
            LOG.warn("Error getting class coverage info", e);
        }
        return null;
    }

    /**
     * Analyze test coverage and provide suggestions for improvement.
     */
    public static JsonObject analyzeCoverage(Project project, String className) {
        return ApplicationManager.getApplication().runReadAction((Computable<JsonObject>) () -> {
            JsonObject result = new JsonObject();
            result.addProperty("className", className);

            // First check if we have coverage data
            JsonObject coverageData = getCoverageData(project, className);
            boolean hasCoverage = coverageData.has("hasCoverage") && coverageData.get("hasCoverage").getAsBoolean();

            // Check if test class exists
            JsonObject testInfo = getTestInfo(project, className);
            boolean hasTestClass = testInfo.has("hasTestClass") && testInfo.get("hasTestClass").getAsBoolean();

            JsonArray suggestions = new JsonArray();

            if (hasCoverage) {
                // We have coverage data - provide detailed analysis
                int totalLines = coverageData.get("totalLineCount").getAsInt();
                int coveredLines = coverageData.get("coveredLineCount").getAsInt();
                String percentage = coverageData.get("coveragePercentage").getAsString();

                result.addProperty("hasCoverage", true);
                result.addProperty("totalLineCount", totalLines);
                result.addProperty("coveredLineCount", coveredLines);
                result.addProperty("coveragePercentage", percentage);

                // Provide suggestions based on coverage percentage
                double coveragePercent = (double) coveredLines / totalLines * 100;

                if (coveragePercent < 50) {
                    suggestions.add("Coverage is low (" + percentage + "). Consider adding more test cases.");
                    suggestions.add("Focus on testing critical business logic first.");
                } else if (coveragePercent < 80) {
                    suggestions.add("Coverage is moderate (" + percentage + "). Good progress!");
                    suggestions.add("Consider testing edge cases and error conditions.");
                } else {
                    suggestions.add("Coverage is good (" + percentage + "). Well done!");
                    suggestions.add("Ensure tests are meaningful, not just hitting lines.");
                }

            } else if (hasTestClass) {
                // We have tests but no coverage data
                int testMethodCount = testInfo.has("testMethodCount") ?
                    testInfo.get("testMethodCount").getAsInt() : 0;

                result.addProperty("hasCoverage", false);
                result.addProperty("hasTestClass", true);
                result.addProperty("testMethodCount", testMethodCount);

                suggestions.add("Test class found with " + testMethodCount + " test methods.");
                suggestions.add("Run tests with coverage to get detailed metrics (Run → Run with Coverage).");

            } else {
                // No tests at all
                result.addProperty("hasCoverage", false);
                result.addProperty("hasTestClass", false);

                suggestions.add("No test class found.");
                suggestions.add("Create " + getSimpleClassName(className) + "Test.java to start testing.");
                suggestions.add("Consider testing public methods and edge cases first.");
            }

            result.add("suggestions", suggestions);
            return result;
        });
    }

    /**
     * Get information about available test configurations.
     */
    public static JsonObject getTestInfo(Project project, String className) {
        return ApplicationManager.getApplication().runReadAction((Computable<JsonObject>) () -> {
            try {
                JsonObject result = new JsonObject();
                result.addProperty("className", className);

                // Find test class
                String testClassName = className + "Test";
                PsiClass testClass = findClass(project, testClassName);

                if (testClass == null) {
                    // Try other naming conventions
                    testClassName = className + "Tests";
                    testClass = findClass(project, testClassName);
                }

                if (testClass != null) {
                    result.addProperty("hasTestClass", true);
                    result.addProperty("testClassName", testClass.getQualifiedName());

                    PsiFile containingFile = testClass.getContainingFile();
                    if (containingFile != null && containingFile.getVirtualFile() != null) {
                        result.addProperty("testFilePath", containingFile.getVirtualFile().getPath());
                    }

                    // Count test methods
                    int testMethodCount = 0;
                    JsonArray testMethods = new JsonArray();

                    for (PsiMethod method : testClass.getMethods()) {
                        // Check for @Test annotation
                        if (hasTestAnnotation(method)) {
                            testMethodCount++;
                            JsonObject testMethod = new JsonObject();
                            testMethod.addProperty("name", method.getName());
                            testMethods.add(testMethod);
                        }
                    }

                    result.addProperty("testMethodCount", testMethodCount);
                    result.add("testMethods", testMethods);

                    // Check test framework
                    String framework = detectTestFramework(testClass);
                    result.addProperty("testFramework", framework);

                } else {
                    result.addProperty("hasTestClass", false);
                    result.addProperty("suggestion", "No test class found. Create " + getSimpleClassName(className) + "Test.java");
                }

                return result;

            } catch (Exception e) {
                LOG.error("Error getting test info", e);
                JsonObject error = new JsonObject();
                error.addProperty("error", "Failed to get test info: " + e.getMessage());
                return error;
            }
        });
    }

    private static PsiClass findClass(Project project, String className) {
        JavaPsiFacade facade = JavaPsiFacade.getInstance(project);
        return facade.findClass(className, GlobalSearchScope.allScope(project));
    }

    private static boolean hasTestAnnotation(PsiMethod method) {
        PsiModifierList modifierList = method.getModifierList();

        // Check for JUnit 5 @Test
        if (modifierList.findAnnotation("org.junit.jupiter.api.Test") != null) {
            return true;
        }

        // Check for JUnit 4 @Test
        if (modifierList.findAnnotation("org.junit.Test") != null) {
            return true;
        }

        // Check for TestNG @Test
        if (modifierList.findAnnotation("org.testng.annotations.Test") != null) {
            return true;
        }

        return false;
    }

    private static String detectTestFramework(PsiClass testClass) {
        for (PsiMethod method : testClass.getMethods()) {
            PsiModifierList modifierList = method.getModifierList();

            if (modifierList.findAnnotation("org.junit.jupiter.api.Test") != null) {
                return "JUnit 5";
            }
            if (modifierList.findAnnotation("org.junit.Test") != null) {
                return "JUnit 4";
            }
            if (modifierList.findAnnotation("org.testng.annotations.Test") != null) {
                return "TestNG";
            }
        }

        return "Unknown";
    }

    private static String getSimpleClassName(String qualifiedName) {
        if (qualifiedName == null) return "";
        int lastDot = qualifiedName.lastIndexOf('.');
        return lastDot >= 0 ? qualifiedName.substring(lastDot + 1) : qualifiedName;
    }
}
