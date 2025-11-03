package com.zps.zest.testgen.planning;

import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;

import java.util.*;

public class CodePatternAnalyzer {

    public static class AnalysisResult {
        private final Map<String, Object> insights = new LinkedHashMap<>();

        public void addInsight(String key, Object value) {
            insights.put(key, value);
        }

        public Object getInsight(String key) {
            return insights.get(key);
        }

        public Map<String, Object> getAllInsights() {
            return Collections.unmodifiableMap(insights);
        }

        public String toPromptSection() {
            if (insights.isEmpty()) {
                return "";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("\n## CODE ANALYSIS\n\n");

            for (Map.Entry<String, Object> entry : insights.entrySet()) {
                sb.append("- **").append(entry.getKey()).append("**: ")
                  .append(entry.getValue()).append("\n");
            }

            return sb.toString();
        }
    }

    public static AnalysisResult analyzeMethod(PsiMethod method) {
        AnalysisResult result = new AnalysisResult();

        analyzeControlFlow(method, result);
        analyzeInputDomains(method, result);
        analyzeStatePatterns(method, result);
        analyzeErrorHandling(method, result);
        suggestTestingTechniques(result);

        return result;
    }

    private static void analyzeControlFlow(PsiMethod method, AnalysisResult result) {
        PsiCodeBlock body = method.getBody();
        if (body == null) return;

        int ifStatements = PsiTreeUtil.collectElementsOfType(body, PsiIfStatement.class).size();
        int switchStatements = PsiTreeUtil.collectElementsOfType(body, PsiSwitchStatement.class).size();
        int loops = PsiTreeUtil.collectElementsOfType(body, PsiLoopStatement.class).size();

        List<String> branches = new ArrayList<>();
        if (ifStatements > 0) {
            branches.add(ifStatements + " if/else branch" + (ifStatements > 1 ? "es" : ""));
        }
        if (switchStatements > 0) {
            branches.add(switchStatements + " switch statement" + (switchStatements > 1 ? "s" : ""));
        }
        if (loops > 0) {
            branches.add(loops + " loop" + (loops > 1 ? "s" : ""));
        }

        if (!branches.isEmpty()) {
            result.addInsight("Control Flow Complexity", String.join(", ", branches));

            int estimatedPaths = calculateEstimatedPaths(ifStatements, switchStatements, loops);
            result.addInsight("Estimated Execution Paths", estimatedPaths);
        }
    }

    private static int calculateEstimatedPaths(int ifs, int switches, int loops) {
        int paths = 1;
        paths *= Math.pow(2, Math.min(ifs, 4));
        paths *= (switches > 0 ? 3 : 1);
        paths *= (loops > 0 ? 2 : 1);
        return Math.min(paths, 50);
    }

    private static void analyzeInputDomains(PsiMethod method, AnalysisResult result) {
        PsiParameterList parameterList = method.getParameterList();
        if (parameterList.getParametersCount() == 0) return;

        List<String> inputAnalysis = new ArrayList<>();
        for (PsiParameter param : parameterList.getParameters()) {
            PsiType type = param.getType();
            String typeAnalysis = analyzeParameterType(type);
            if (!typeAnalysis.isEmpty()) {
                inputAnalysis.add(param.getName() + ": " + typeAnalysis);
            }
        }

        if (!inputAnalysis.isEmpty()) {
            result.addInsight("Input Domain Partitions", String.join("; ", inputAnalysis));
        }
    }

    private static String analyzeParameterType(PsiType type) {
        String typeName = type.getPresentableText();

        if (type instanceof PsiPrimitiveType) {
            if (typeName.equals("int") || typeName.equals("long")) {
                return "numeric (test: zero, negative, positive, boundaries)";
            } else if (typeName.equals("boolean")) {
                return "boolean (test: true, false)";
            } else if (typeName.equals("double") || typeName.equals("float")) {
                return "floating-point (test: zero, NaN, infinity, precision)";
            }
        } else if (typeName.equals("String")) {
            return "string (test: null, empty, whitespace, special chars, long)";
        } else if (typeName.contains("List") || typeName.contains("Collection")) {
            return "collection (test: null, empty, single, multiple)";
        } else if (typeName.contains("Map")) {
            return "map (test: null, empty, populated)";
        } else if (typeName.contains("Optional")) {
            return "optional (test: empty, present)";
        }

        return "object (test: null, valid instance)";
    }

    private static void analyzeStatePatterns(PsiMethod method, AnalysisResult result) {
        PsiClass containingClass = method.getContainingClass();
        if (containingClass == null) return;

        PsiField[] fields = containingClass.getFields();
        int mutableFields = 0;
        List<String> stateFields = new ArrayList<>();

        for (PsiField field : fields) {
            if (!field.hasModifierProperty(PsiModifier.FINAL) &&
                !field.hasModifierProperty(PsiModifier.STATIC)) {
                mutableFields++;
                if (stateFields.size() < 5) {
                    stateFields.add(field.getName());
                }
            }
        }

        if (mutableFields > 0) {
            result.addInsight("Stateful Object", "Contains " + mutableFields + " mutable field(s): " +
                    String.join(", ", stateFields) + (mutableFields > 5 ? "..." : ""));
        }

        PsiCodeBlock body = method.getBody();
        if (body != null) {
            int assignments = PsiTreeUtil.collectElementsOfType(body, PsiAssignmentExpression.class).size();
            if (assignments > 0) {
                result.addInsight("State Modifications", assignments + " assignment(s) detected");
            }
        }
    }

    private static void analyzeErrorHandling(PsiMethod method, AnalysisResult result) {
        PsiCodeBlock body = method.getBody();
        if (body == null) return;

        int tryBlocks = PsiTreeUtil.collectElementsOfType(body, PsiTryStatement.class).size();
        PsiReferenceList throwsList = method.getThrowsList();
        int declaredExceptions = throwsList.getReferenceElements().length;

        List<String> errorHandling = new ArrayList<>();
        if (tryBlocks > 0) {
            errorHandling.add(tryBlocks + " try-catch block(s)");
        }
        if (declaredExceptions > 0) {
            errorHandling.add("throws " + declaredExceptions + " exception type(s)");
        }

        if (!errorHandling.isEmpty()) {
            result.addInsight("Error Handling", String.join(", ", errorHandling));
        }
    }

    private static void suggestTestingTechniques(AnalysisResult result) {
        List<String> techniques = new ArrayList<>();

        if (result.getInsight("Control Flow Complexity") != null) {
            techniques.add("Decision Table Testing (for branch combinations)");
            techniques.add("Path Coverage Analysis (ensure all paths tested)");
        }

        if (result.getInsight("Input Domain Partitions") != null) {
            techniques.add("Equivalence Partitioning (group similar inputs)");
            techniques.add("Boundary Value Analysis (test edge values)");
        }

        if (result.getInsight("Stateful Object") != null) {
            techniques.add("State Transition Testing (test state changes)");
        }

        if (result.getInsight("Error Handling") != null) {
            techniques.add("Error Condition Testing (trigger exception paths)");
        }

        if (!techniques.isEmpty()) {
            result.addInsight("Recommended Testing Techniques", String.join(", ", techniques));
        }
    }
}
