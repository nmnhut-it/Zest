package com.zps.zest.mcp.refactor;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Stream;

/**
 * Handler for test discovery and coverage analysis.
 *
 * Supports:
 * - Test class and method discovery
 * - Test framework detection (JUnit 4, JUnit 5, TestNG)
 * - JaCoCo coverage report reading (if available)
 */
public class TestCoverageToolHandler {
    private static final Logger LOG = Logger.getInstance(TestCoverageToolHandler.class);

    /**
     * Get test coverage data for a class.
     *
     * Looks for JaCoCo XML reports in build/reports/jacoco/ or build/jacoco/
     * If not found, returns test info with instructions to run coverage.
     */
    public static JsonObject getCoverageData(Project project, String className) {
        JsonObject result = new JsonObject();
        result.addProperty("className", className);

        // Try to find JaCoCo XML report
        String projectPath = project.getBasePath();
        if (projectPath != null) {
            Path jacocoReport = findJaCoCoReport(projectPath);

            if (jacocoReport != null) {
                try {
                    JsonObject coverageInfo = parseJaCoCoReport(jacocoReport, className);
                    if (coverageInfo != null) {
                        result.addProperty("hasCoverage", true);
                        result.addProperty("source", "JaCoCo XML report");
                        result.addProperty("reportPath", jacocoReport.toString());
                        result.add("coverage", coverageInfo);
                        return result;
                    }
                } catch (Exception e) {
                    LOG.warn("Error parsing JaCoCo report", e);
                }
            }
        }

        // No coverage data found
        result.addProperty("hasCoverage", false);
        result.addProperty("message", "No coverage data available.");

        JsonArray suggestions = new JsonArray();
        suggestions.add("Run tests with JaCoCo: ./gradlew test jacocoTestReport");
        suggestions.add("Or run with IntelliJ coverage (Run → Run with Coverage)");
        suggestions.add("JaCoCo report will be generated at: build/reports/jacoco/test/jacocoTestReport.xml");
        result.add("suggestions", suggestions);

        return result;
    }

    /**
     * Find JaCoCo XML report in common locations.
     */
    private static Path findJaCoCoReport(String projectPath) {
        String[] possiblePaths = {
            "build/reports/jacoco/test/jacocoTestReport.xml",
            "build/jacoco/jacocoTestReport.xml",
            "target/site/jacoco/jacoco.xml"  // Maven
        };

        for (String relativePath : possiblePaths) {
            Path reportPath = Paths.get(projectPath, relativePath);
            if (Files.exists(reportPath)) {
                return reportPath;
            }
        }

        return null;
    }

    /**
     * Parse JaCoCo XML report for a specific class.
     * Returns null if class not found in report.
     */
    private static JsonObject parseJaCoCoReport(Path reportPath, String className) {
        try {
            String content = Files.readString(reportPath);

            // Simple XML parsing for class coverage
            // Format: <class name="com/example/MyClass" ... >
            String searchName = className.replace(".", "/");
            int classIdx = content.indexOf("name=\"" + searchName + "\"");

            if (classIdx == -1) {
                return null;  // Class not found in report
            }

            // Find the counter elements for this class
            // Look for LINE counter: <counter type="LINE" missed="X" covered="Y"/>
            int classStart = content.lastIndexOf("<class", classIdx);
            int classEnd = content.indexOf("</class>", classStart);

            if (classStart == -1 || classEnd == -1) {
                return null;
            }

            String classSection = content.substring(classStart, classEnd);

            JsonObject coverage = new JsonObject();

            // Parse LINE counter
            int lineCounterIdx = classSection.indexOf("<counter type=\"LINE\"");
            if (lineCounterIdx != -1) {
                int missed = extractCounterValue(classSection, lineCounterIdx, "missed");
                int covered = extractCounterValue(classSection, lineCounterIdx, "covered");
                int total = missed + covered;

                coverage.addProperty("totalLines", total);
                coverage.addProperty("coveredLines", covered);
                coverage.addProperty("missedLines", missed);

                if (total > 0) {
                    double percentage = (double) covered / total * 100;
                    coverage.addProperty("linePercentage", String.format("%.2f%%", percentage));
                }
            }

            // Parse BRANCH counter
            int branchCounterIdx = classSection.indexOf("<counter type=\"BRANCH\"");
            if (branchCounterIdx != -1) {
                int missed = extractCounterValue(classSection, branchCounterIdx, "missed");
                int covered = extractCounterValue(classSection, branchCounterIdx, "covered");
                int total = missed + covered;

                coverage.addProperty("totalBranches", total);
                coverage.addProperty("coveredBranches", covered);

                if (total > 0) {
                    double percentage = (double) covered / total * 100;
                    coverage.addProperty("branchPercentage", String.format("%.2f%%", percentage));
                }
            }

            return coverage.size() > 0 ? coverage : null;

        } catch (IOException e) {
            LOG.warn("Error reading JaCoCo report", e);
            return null;
        }
    }

    private static int extractCounterValue(String xml, int counterPos, String attribute) {
        int attrIdx = xml.indexOf(attribute + "=\"", counterPos);
        if (attrIdx == -1) return 0;

        int valueStart = attrIdx + attribute.length() + 2;
        int valueEnd = xml.indexOf("\"", valueStart);
        if (valueEnd == -1) return 0;

        try {
            return Integer.parseInt(xml.substring(valueStart, valueEnd));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Analyze test coverage and provide suggestions for improvement.
     */
    public static JsonObject analyzeCoverage(Project project, String className) {
        return ApplicationManager.getApplication().runReadAction((Computable<JsonObject>) () -> {
            JsonObject result = new JsonObject();
            result.addProperty("className", className);

            // Check if we have coverage data
            JsonObject coverageData = getCoverageData(project, className);
            boolean hasCoverage = coverageData.has("hasCoverage") && coverageData.get("hasCoverage").getAsBoolean();

            // Check if test class exists
            JsonObject testInfo = getTestInfo(project, className);
            boolean hasTestClass = testInfo.has("hasTestClass") && testInfo.get("hasTestClass").getAsBoolean();

            JsonArray suggestions = new JsonArray();

            if (hasCoverage) {
                // We have coverage data from JaCoCo - provide detailed analysis
                JsonObject coverage = coverageData.getAsJsonObject("coverage");

                result.addProperty("hasCoverage", true);
                result.addProperty("source", coverageData.get("source").getAsString());

                if (coverage.has("totalLines")) {
                    int totalLines = coverage.get("totalLines").getAsInt();
                    int coveredLines = coverage.get("coveredLines").getAsInt();
                    String percentage = coverage.get("linePercentage").getAsString();

                    result.addProperty("totalLines", totalLines);
                    result.addProperty("coveredLines", coveredLines);
                    result.addProperty("linePercentage", percentage);

                    // Provide suggestions based on coverage percentage
                    double coveragePercent = (double) coveredLines / totalLines * 100;

                    if (coveragePercent < 50) {
                        suggestions.add("Line coverage is low (" + percentage + "). Consider adding more test cases.");
                        suggestions.add("Focus on testing critical business logic first.");
                    } else if (coveragePercent < 80) {
                        suggestions.add("Line coverage is moderate (" + percentage + "). Good progress!");
                        suggestions.add("Consider testing edge cases and error conditions.");
                    } else {
                        suggestions.add("Line coverage is good (" + percentage + "). Well done!");
                    }
                }

                if (coverage.has("totalBranches")) {
                    int totalBranches = coverage.get("totalBranches").getAsInt();
                    int coveredBranches = coverage.get("coveredBranches").getAsInt();
                    String branchPercentage = coverage.get("branchPercentage").getAsString();

                    result.addProperty("totalBranches", totalBranches);
                    result.addProperty("coveredBranches", coveredBranches);
                    result.addProperty("branchPercentage", branchPercentage);

                    double branchPercent = (double) coveredBranches / totalBranches * 100;
                    if (branchPercent < 70) {
                        suggestions.add("Branch coverage is " + branchPercentage + ". Test more conditional paths.");
                    }
                }

                suggestions.add("Ensure tests are meaningful, not just hitting lines.");

            } else if (hasTestClass) {
                // We have tests but no coverage data
                int testMethodCount = testInfo.has("testMethodCount") ?
                    testInfo.get("testMethodCount").getAsInt() : 0;

                result.addProperty("hasCoverage", false);
                result.addProperty("hasTestClass", true);
                result.addProperty("testMethodCount", testMethodCount);

                suggestions.add("Test class found with " + testMethodCount + " test methods.");
                suggestions.add("Run: ./gradlew test jacocoTestReport");
                suggestions.add("Then call getCoverageData again to see metrics.");

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
