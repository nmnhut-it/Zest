package com.zps.zest.mcp.refactor;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.coverage.*;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.RunManager;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.execution.configurations.RunProfile;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.junit.JUnitConfiguration;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;

import java.util.HashMap;
import java.util.Map;

/**
 * Handler for test execution and coverage measurement via IntelliJ APIs.
 */
public class TestCoverageToolHandler {
    private static final Logger LOG = Logger.getInstance(TestCoverageToolHandler.class);

    /**
     * Get current test coverage data for a class.
     *
     * @param project The IntelliJ project
     * @param className Fully qualified class name
     * @return Coverage data as JSON
     */
    public static JsonObject getCoverageData(Project project, String className) {
        return ApplicationManager.getApplication().runReadAction((Computable<JsonObject>) () -> {
            try {
                // Get coverage data suite
                CoverageDataManager coverageManager = CoverageDataManager.getInstance(project);
                CoverageSuitesBundle currentSuite = coverageManager.getCurrentSuitesBundle();

                if (currentSuite == null) {
                    JsonObject result = new JsonObject();
                    result.addProperty("hasCoverage", false);
                    result.addProperty("message", "No coverage data available. Run tests with coverage first.");
                    return result;
                }

                // Find the class
                PsiClass psiClass = findClass(project, className);
                if (psiClass == null) {
                    JsonObject error = new JsonObject();
                    error.addProperty("error", "Class not found: " + className);
                    return error;
                }

                PsiFile psiFile = psiClass.getContainingFile();
                if (psiFile == null) {
                    JsonObject error = new JsonObject();
                    error.addProperty("error", "File not found for class: " + className);
                    return error;
                }

                // Get coverage data
                CoverageEngine coverageEngine = CoverageEngine.EP_NAME.findExtension(JavaCoverageEngine.class);
                if (coverageEngine == null) {
                    JsonObject error = new JsonObject();
                    error.addProperty("error", "Java coverage engine not available");
                    return error;
                }

                // Compute coverage statistics
                JsonObject result = new JsonObject();
                result.addProperty("hasCoverage", true);
                result.addProperty("className", className);

                // Get class coverage
                String qualifiedName = psiClass.getQualifiedName();
                if (qualifiedName != null) {
                    JavaCoverageAnnotator annotator = JavaCoverageAnnotator.getInstance(project);

                    // Class-level coverage
                    CoverageLineMarkerRenderer classRenderer = annotator.getRenderer(psiClass, null, currentSuite);
                    if (classRenderer != null) {
                        String coverageInfo = classRenderer.getLinesCoverageInformationString(psiClass);
                        result.addProperty("classCoverage", coverageInfo != null ? coverageInfo : "N/A");
                    }

                    // Method-level coverage
                    JsonArray methodsCoverage = new JsonArray();
                    for (PsiMethod method : psiClass.getMethods()) {
                        if (method.getBody() != null) {
                            JsonObject methodCov = new JsonObject();
                            methodCov.addProperty("methodName", method.getName());

                            CoverageLineMarkerRenderer methodRenderer = annotator.getRenderer(method, null, currentSuite);
                            if (methodRenderer != null) {
                                String methodCoverageInfo = methodRenderer.getLinesCoverageInformationString(method);
                                methodCov.addProperty("coverage", methodCoverageInfo != null ? methodCoverageInfo : "0%");
                            } else {
                                methodCov.addProperty("coverage", "0%");
                            }

                            methodsCoverage.add(methodCov);
                        }
                    }
                    result.add("methodsCoverage", methodsCoverage);
                }

                // Add suite info
                JsonObject suiteInfo = new JsonObject();
                CoverageSuite[] suites = currentSuite.getSuites();
                if (suites.length > 0) {
                    suiteInfo.addProperty("suiteName", suites[0].getPresentableName());
                    suiteInfo.addProperty("timestamp", suites[0].getLastCoverageTimeStamp());
                }
                result.add("suiteInfo", suiteInfo);

                return result;

            } catch (Exception e) {
                LOG.error("Error getting coverage data", e);
                JsonObject error = new JsonObject();
                error.addProperty("error", "Failed to get coverage data: " + e.getMessage());
                return error;
            }
        });
    }

    /**
     * Analyze test coverage and provide suggestions for improvement.
     *
     * @param project The IntelliJ project
     * @param className Fully qualified class name
     * @return Analysis with suggestions
     */
    public static JsonObject analyzeCoverage(Project project, String className) {
        JsonObject coverageData = getCoverageData(project, className);

        if (!coverageData.has("hasCoverage") || !coverageData.get("hasCoverage").getAsBoolean()) {
            return coverageData; // Return error/no coverage message
        }

        JsonObject analysis = new JsonObject();
        analysis.addProperty("className", className);

        // Analyze method coverage
        JsonArray suggestions = new JsonArray();
        JsonArray uncoveredMethods = new JsonArray();

        if (coverageData.has("methodsCoverage")) {
            JsonArray methods = coverageData.getAsJsonArray("methodsCoverage");
            int totalMethods = methods.size();
            int coveredMethods = 0;

            for (int i = 0; i < methods.size(); i++) {
                JsonObject method = methods.get(i).getAsJsonObject();
                String coverage = method.get("coverage").getAsString();
                String methodName = method.get("methodName").getAsString();

                if (coverage.equals("0%") || coverage.equals("N/A")) {
                    JsonObject uncovered = new JsonObject();
                    uncovered.addProperty("methodName", methodName);
                    uncovered.addProperty("suggestion", "Add unit test for " + methodName + "()");
                    uncoveredMethods.add(uncovered);
                } else {
                    coveredMethods++;
                }
            }

            // Calculate coverage percentage
            double coveragePercent = totalMethods > 0 ? (coveredMethods * 100.0 / totalMethods) : 0;
            analysis.addProperty("methodCoveragePercent", Math.round(coveragePercent));
            analysis.addProperty("coveredMethods", coveredMethods);
            analysis.addProperty("totalMethods", totalMethods);
        }

        analysis.add("uncoveredMethods", uncoveredMethods);

        // Generate suggestions based on coverage
        if (uncoveredMethods.size() > 0) {
            suggestions.add("Add tests for " + uncoveredMethods.size() + " uncovered methods");
        }

        if (analysis.has("methodCoveragePercent")) {
            int coverage = analysis.get("methodCoveragePercent").getAsInt();
            if (coverage < 50) {
                suggestions.add("Coverage is low (" + coverage + "%). Consider adding more comprehensive tests.");
            } else if (coverage < 80) {
                suggestions.add("Coverage is moderate (" + coverage + "%). Add tests for uncovered methods to reach 80%.");
            } else {
                suggestions.add("Good coverage (" + coverage + "%). Focus on edge cases and integration tests.");
            }
        }

        analysis.add("suggestions", suggestions);

        return analysis;
    }

    /**
     * Get information about available test configurations.
     *
     * @param project The IntelliJ project
     * @param className Class name to find tests for
     * @return Test configuration info
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
                    result.addProperty("testFilePath", testClass.getContainingFile().getVirtualFile().getPath());

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
                    result.addProperty("suggestion", "No test class found. Create " + className + "Test.java");
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
}
