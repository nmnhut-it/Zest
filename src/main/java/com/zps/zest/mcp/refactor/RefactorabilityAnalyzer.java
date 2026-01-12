package com.zps.zest.mcp.refactor;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.codeInspection.*;
import com.intellij.codeInspection.ex.InspectionToolWrapper;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.profile.codeInspection.InspectionProjectProfileManager;
import com.zps.zest.rules.ZestRulesLoader;
import com.zps.zest.testgen.planning.CodePatternAnalyzer;

import java.util.*;

/**
 * Analyzes code for refactoring opportunities.
 * Uses existing PSI-based analyzers and IntelliJ inspections.
 */
public class RefactorabilityAnalyzer {

    /**
     * Analyze code for refactoring opportunities.
     *
     * @param project The IntelliJ project
     * @param className Fully qualified class name (if null, uses current file)
     * @param focusArea TESTABILITY | COMPLEXITY | CODE_SMELLS | ALL
     * @return JSON with findings categorized by impact
     */
    public static JsonObject analyze(Project project, String className, String focusArea) {
        return ApplicationManager.getApplication().runReadAction((Computable<JsonObject>) () -> {
            try {
                // Find the class
                PsiClass psiClass = findClass(project, className);
                if (psiClass == null) {
                    JsonObject error = new JsonObject();
                    error.addProperty("error", "Class not found: " + className);
                    return error;
                }

                // Load team rules
                ZestRulesLoader rulesLoader = new ZestRulesLoader(project);
                String teamRules = rulesLoader.loadCustomRules();

                // Analyze
                JsonObject result = new JsonObject();
                result.addProperty("className", psiClass.getQualifiedName());
                result.addProperty("filePath", psiClass.getContainingFile().getVirtualFile().getPath());

                // Add team rules
                if (teamRules != null && !teamRules.isEmpty()) {
                    JsonArray rulesArray = parseTeamRules(teamRules);
                    result.add("teamRules", rulesArray);
                }

                // Analyze methods
                List<Finding> findings = new ArrayList<>();

                // Run IntelliJ inspections first
                findings.addAll(runIntelliJInspections(project, psiClass, focusArea));

                for (PsiMethod method : psiClass.getMethods()) {
                    if (shouldAnalyzeMethod(method)) {
                        findings.addAll(analyzeMethod(method, focusArea));
                    }
                }

                // Analyze class-level issues
                findings.addAll(analyzeClass(psiClass, focusArea));

                // Sort by severity
                findings.sort(Comparator.comparing(Finding::getSeverity));

                // Convert to JSON
                JsonArray findingsArray = new JsonArray();
                for (Finding finding : findings) {
                    findingsArray.add(finding.toJson());
                }
                result.add("findings", findingsArray);

                // Add metrics
                result.add("metrics", computeMetrics(psiClass));

                return result;

            } catch (Exception e) {
                JsonObject error = new JsonObject();
                error.addProperty("error", "Analysis failed: " + e.getMessage());
                return error;
            }
        });
    }

    /**
     * Run IntelliJ's built-in inspections on the class.
     * This leverages IntelliJ Platform's static analysis instead of custom code.
     */
    private static List<Finding> runIntelliJInspections(Project project, PsiClass psiClass, String focusArea) {
        List<Finding> findings = new ArrayList<>();

        try {
            // Get inspection profile
            InspectionProjectProfileManager profileManager = InspectionProjectProfileManager.getInstance(project);
            InspectionProfile profile = profileManager.getCurrentProfile();

            // Get relevant inspections
            List<InspectionToolWrapper<?,?>> inspections = new ArrayList<>();

            // Add complexity inspections
            if (shouldAnalyze("COMPLEXITY", focusArea)) {
                addInspectionIfExists(profile, "MethodLength", inspections);
                addInspectionIfExists(profile, "OverlyComplexMethod", inspections);
                addInspectionIfExists(profile, "CyclomaticComplexity", inspections);
            }

            // Add testability/code quality inspections
            if (shouldAnalyze("TESTABILITY", focusArea) || shouldAnalyze("CODE_SMELLS", focusArea)) {
                addInspectionIfExists(profile, "StaticMethodOnlyUsedInOneClass", inspections);
                addInspectionIfExists(profile, "ClassWithTooManyMethods", inspections);
                addInspectionIfExists(profile, "ClassWithTooManyFields", inspections);
                addInspectionIfExists(profile, "MethodWithTooManyParameters", inspections);
            }

            // Run inspections on the class file
            PsiFile file = psiClass.getContainingFile();
            for (InspectionToolWrapper<?,?> wrapper : inspections) {
                LocalInspectionTool tool = (LocalInspectionTool) wrapper.getTool();
                ProblemsHolder holder = new ProblemsHolder(
                        InspectionManager.getInstance(project),
                        file,
                        false
                );

                // Visit the file with the inspection
                PsiElementVisitor visitor = tool.buildVisitor(holder, false);
                file.accept(new PsiRecursiveElementVisitor() {
                    @Override
                    public void visitElement(PsiElement element) {
                        element.accept(visitor);
                        super.visitElement(element);
                    }
                });

                // Collect problems
                for (ProblemDescriptor problem : holder.getResults()) {
                    PsiElement element = problem.getPsiElement();

                    // Only include problems from our target class
                    PsiClass containingClass = PsiTreeUtil.getParentOfType(element, PsiClass.class);
                    if (containingClass != null && containingClass.equals(psiClass)) {
                        String category = mapInspectionToCategory(wrapper.getShortName());
                        String severity = mapSeverity(problem.getHighlightType());

                        findings.add(new Finding(
                                category,
                                severity,
                                getLineNumber(element),
                                problem.getDescriptionTemplate(),
                                "IntelliJ inspection: " + wrapper.getDisplayName(),
                                "Follow IntelliJ's suggested fix",
                                "Improved code quality"
                        ));
                    }
                }
            }
        } catch (Exception e) {
            // Silently ignore - inspections are optional enhancement
        }

        return findings;
    }

    private static void addInspectionIfExists(InspectionProfile profile, String shortName, List<InspectionToolWrapper<?,?>> list) {
        try {
            InspectionToolWrapper<?,?> wrapper = profile.getInspectionTool(shortName, (Project) null);
            if (wrapper != null && wrapper.getTool() instanceof LocalInspectionTool) {
                list.add(wrapper);
            }
        } catch (Exception e) {
            // Inspection not available - skip it
        }
    }

    private static String mapInspectionToCategory(String inspectionName) {
        if (inspectionName.contains("Complexity") || inspectionName.contains("Length")) {
            return "COMPLEXITY";
        } else if (inspectionName.contains("Static") || inspectionName.contains("Methods") || inspectionName.contains("Fields")) {
            return "TESTABILITY";
        } else {
            return "CODE_SMELLS";
        }
    }

    private static String mapSeverity(ProblemHighlightType highlightType) {
        switch (highlightType) {
            case ERROR:
                return "HIGH";
            case GENERIC_ERROR_OR_WARNING:
            case WARNING:
                return "MEDIUM";
            case WEAK_WARNING:
            case INFORMATION:
            default:
                return "LOW";
        }
    }

    private static PsiClass findClass(Project project, String className) {
        if (className == null || className.isEmpty()) {
            // Use current file - would need to get from FileEditorManager
            return null;
        }

        JavaPsiFacade facade = JavaPsiFacade.getInstance(project);
        return facade.findClass(className, GlobalSearchScope.projectScope(project));
    }

    private static boolean shouldAnalyzeMethod(PsiMethod method) {
        // Skip constructors, getters, setters
        if (method.isConstructor()) return false;
        if (isSimpleGetter(method)) return false;
        if (isSimpleSetter(method)) return false;
        return true;
    }

    private static boolean isSimpleGetter(PsiMethod method) {
        String name = method.getName();
        if (!name.startsWith("get") && !name.startsWith("is")) return false;
        if (method.getParameterList().getParametersCount() != 0) return false;

        PsiCodeBlock body = method.getBody();
        if (body == null) return false;

        PsiStatement[] statements = body.getStatements();
        return statements.length == 1 && statements[0] instanceof PsiReturnStatement;
    }

    private static boolean isSimpleSetter(PsiMethod method) {
        String name = method.getName();
        if (!name.startsWith("set")) return false;
        if (method.getParameterList().getParametersCount() != 1) return false;

        PsiCodeBlock body = method.getBody();
        if (body == null) return false;

        PsiStatement[] statements = body.getStatements();
        return statements.length == 1 && statements[0] instanceof PsiExpressionStatement;
    }

    private static List<Finding> analyzeMethod(PsiMethod method, String focusArea) {
        List<Finding> findings = new ArrayList<>();

        CodePatternAnalyzer.AnalysisResult analysis = CodePatternAnalyzer.analyzeMethod(method);

        // Testability issues
        if (shouldAnalyze("TESTABILITY", focusArea)) {
            findings.addAll(findTestabilityIssues(method, analysis));
        }

        // Complexity issues
        if (shouldAnalyze("COMPLEXITY", focusArea)) {
            findings.addAll(findComplexityIssues(method, analysis));
        }

        // Code smells
        if (shouldAnalyze("CODE_SMELLS", focusArea)) {
            findings.addAll(findCodeSmells(method, analysis));
        }

        return findings;
    }

    private static List<Finding> analyzeClass(PsiClass psiClass, String focusArea) {
        List<Finding> findings = new ArrayList<>();

        // God class detection
        if (shouldAnalyze("CODE_SMELLS", focusArea)) {
            int methodCount = psiClass.getMethods().length;
            if (methodCount > 15) {
                findings.add(new Finding(
                        "CODE_SMELLS",
                        "MEDIUM",
                        0,
                        "God class: " + methodCount + " methods",
                        "Class has too many responsibilities",
                        "Split into smaller classes following Single Responsibility Principle",
                        "Improved maintainability and testability"
                ));
            }
        }

        // Mutable state
        if (shouldAnalyze("TESTABILITY", focusArea)) {
            int mutableFields = countMutableFields(psiClass);
            if (mutableFields > 8) {
                findings.add(new Finding(
                        "TESTABILITY",
                        "MEDIUM",
                        0,
                        "High mutable state: " + mutableFields + " mutable fields",
                        "Makes object state hard to reason about and test",
                        "Consider using immutable objects or reducing state",
                        "Easier testing and fewer bugs"
                ));
            }
        }

        return findings;
    }

    private static List<Finding> findTestabilityIssues(PsiMethod method, CodePatternAnalyzer.AnalysisResult analysis) {
        List<Finding> findings = new ArrayList<>();

        PsiCodeBlock body = method.getBody();
        if (body == null) return findings;

        // Static method calls
        Collection<PsiMethodCallExpression> calls = PsiTreeUtil.findChildrenOfType(body, PsiMethodCallExpression.class);
        for (PsiMethodCallExpression call : calls) {
            PsiMethod resolvedMethod = call.resolveMethod();
            if (resolvedMethod != null && resolvedMethod.hasModifierProperty(PsiModifier.STATIC)) {
                PsiClass containingClass = resolvedMethod.getContainingClass();
                if (containingClass != null && !isUtilityClass(containingClass)) {
                    int line = getLineNumber(call);
                    findings.add(new Finding(
                            "TESTABILITY",
                            "HIGH",
                            line,
                            "Static method call: " + containingClass.getName() + "." + resolvedMethod.getName() + "()",
                            "Cannot mock static methods with standard Mockito",
                            "Extract to dependency injection",
                            "Can mock dependencies in tests"
                    ));
                }
            }
        }

        return findings;
    }

    private static List<Finding> findComplexityIssues(PsiMethod method, CodePatternAnalyzer.AnalysisResult analysis) {
        List<Finding> findings = new ArrayList<>();

        Object complexityObj = analysis.getInsight("Estimated Execution Paths");
        if (complexityObj instanceof Integer) {
            int complexity = (Integer) complexityObj;
            if (complexity > 10) {
                findings.add(new Finding(
                        "COMPLEXITY",
                        complexity > 20 ? "HIGH" : "MEDIUM",
                        getLineNumber(method),
                        "Cyclomatic complexity " + complexity + " in " + method.getName() + "()",
                        "Complex methods are hard to understand and test",
                        "Extract pure methods to reduce complexity",
                        "Complexity reduced to < 10 per method"
                ));
            }
        }

        // Long method
        PsiCodeBlock body = method.getBody();
        if (body != null) {
            int lineCount = countLines(body);
            if (lineCount > 30) {
                findings.add(new Finding(
                        "COMPLEXITY",
                        "MEDIUM",
                        getLineNumber(method),
                        "Long method: " + lineCount + " lines in " + method.getName() + "()",
                        "Long methods are hard to understand and maintain",
                        "Extract smaller methods with clear responsibilities",
                        "Improved readability and testability"
                ));
            }
        }

        return findings;
    }

    private static List<Finding> findCodeSmells(PsiMethod method, CodePatternAnalyzer.AnalysisResult analysis) {
        List<Finding> findings = new ArrayList<>();

        // Too many parameters
        int paramCount = method.getParameterList().getParametersCount();
        if (paramCount > 5) {
            findings.add(new Finding(
                    "CODE_SMELLS",
                    "LOW",
                    getLineNumber(method),
                    "Too many parameters: " + paramCount + " parameters in " + method.getName() + "()",
                    "Methods with many parameters are hard to use and maintain",
                    "Introduce parameter object or builder pattern",
                    "Simpler method signature"
            ));
        }

        return findings;
    }

    private static boolean shouldAnalyze(String category, String focusArea) {
        if (focusArea == null || focusArea.equalsIgnoreCase("ALL")) {
            return true;
        }
        return focusArea.equalsIgnoreCase(category);
    }

    private static boolean isUtilityClass(PsiClass psiClass) {
        String name = psiClass.getName();
        return name != null && (name.endsWith("Utils") || name.endsWith("Helper") || name.equals("Math"));
    }

    private static int countMutableFields(PsiClass psiClass) {
        int count = 0;
        for (PsiField field : psiClass.getFields()) {
            if (!field.hasModifierProperty(PsiModifier.FINAL) &&
                    !field.hasModifierProperty(PsiModifier.STATIC)) {
                count++;
            }
        }
        return count;
    }

    private static int getLineNumber(PsiElement element) {
        // Simplified - in real implementation would use Document API
        return 0;
    }

    private static int countLines(PsiCodeBlock body) {
        String text = body.getText();
        return text.split("\n").length;
    }

    private static JsonObject computeMetrics(PsiClass psiClass) {
        JsonObject metrics = new JsonObject();

        // Average complexity
        int totalComplexity = 0;
        int maxComplexity = 0;
        int methodCount = 0;

        for (PsiMethod method : psiClass.getMethods()) {
            if (shouldAnalyzeMethod(method)) {
                CodePatternAnalyzer.AnalysisResult analysis = CodePatternAnalyzer.analyzeMethod(method);
                Object complexityObj = analysis.getInsight("Estimated Execution Paths");
                if (complexityObj instanceof Integer) {
                    int complexity = (Integer) complexityObj;
                    totalComplexity += complexity;
                    maxComplexity = Math.max(maxComplexity, complexity);
                    methodCount++;
                }
            }
        }

        if (methodCount > 0) {
            JsonObject complexityMetrics = new JsonObject();
            complexityMetrics.addProperty("avg", totalComplexity / methodCount);
            complexityMetrics.addProperty("max", maxComplexity);
            metrics.add("cyclomaticComplexity", complexityMetrics);
        }

        metrics.addProperty("mutableFields", countMutableFields(psiClass));

        // Count static calls
        int staticCalls = 0;
        for (PsiMethod method : psiClass.getMethods()) {
            PsiCodeBlock body = method.getBody();
            if (body != null) {
                Collection<PsiMethodCallExpression> calls = PsiTreeUtil.findChildrenOfType(body, PsiMethodCallExpression.class);
                for (PsiMethodCallExpression call : calls) {
                    PsiMethod resolvedMethod = call.resolveMethod();
                    if (resolvedMethod != null && resolvedMethod.hasModifierProperty(PsiModifier.STATIC)) {
                        staticCalls++;
                    }
                }
            }
        }
        metrics.addProperty("staticCalls", staticCalls);

        return metrics;
    }

    private static JsonArray parseTeamRules(String rulesText) {
        JsonArray rules = new JsonArray();
        String[] lines = rulesText.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.startsWith("-") || line.startsWith("*")) {
                String rule = line.substring(1).trim();
                if (!rule.isEmpty()) {
                    rules.add(rule);
                }
            }
        }
        return rules;
    }

    static class Finding {
        private final String category;
        private final String severity;
        private final int line;
        private final String issue;
        private final String reason;
        private final String suggestedFix;
        private final String estimatedImpact;

        public Finding(String category, String severity, int line, String issue, String reason, String suggestedFix, String estimatedImpact) {
            this.category = category;
            this.severity = severity;
            this.line = line;
            this.issue = issue;
            this.reason = reason;
            this.suggestedFix = suggestedFix;
            this.estimatedImpact = estimatedImpact;
        }

        public String getSeverity() {
            return severity;
        }

        public JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("category", category);
            json.addProperty("severity", severity);
            json.addProperty("line", line);
            json.addProperty("issue", issue);
            json.addProperty("reason", reason);
            json.addProperty("suggestedFix", suggestedFix);
            json.addProperty("estimatedImpact", estimatedImpact);
            return json;
        }
    }
}
