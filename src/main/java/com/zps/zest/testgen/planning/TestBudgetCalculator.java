package com.zps.zest.testgen.planning;

import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;

public class TestBudgetCalculator {

    private static final int BASE_TESTS_PER_METHOD = 5;
    private static final int MIN_TESTS = 3;
    private static final int MAX_TESTS = 20;

    public static int calculateTestBudget(PsiMethod method, boolean autoAdjust) {
        if (!autoAdjust) {
            return BASE_TESTS_PER_METHOD;
        }

        int budget = BASE_TESTS_PER_METHOD;

        budget += analyzeComplexity(method);
        budget += analyzeParameters(method);
        budget += analyzeStateManagement(method);
        budget += analyzeExceptionHandling(method);
        budget += analyzeRiskFactors(method);

        return Math.min(Math.max(budget, MIN_TESTS), MAX_TESTS);
    }

    private static int analyzeComplexity(PsiMethod method) {
        PsiCodeBlock body = method.getBody();
        if (body == null) return 0;

        int complexityPoints = 0;

        int ifStatements = PsiTreeUtil.collectElementsOfType(body, PsiIfStatement.class).size();
        complexityPoints += Math.min(ifStatements * 2, 6);

        int switchStatements = PsiTreeUtil.collectElementsOfType(body, PsiSwitchStatement.class).size();
        complexityPoints += switchStatements * 3;

        int loops = PsiTreeUtil.collectElementsOfType(body, PsiLoopStatement.class).size();
        complexityPoints += Math.min(loops * 2, 4);

        int ternaryOps = countTernaryOperators(body);
        complexityPoints += ternaryOps;

        return complexityPoints;
    }

    private static int analyzeParameters(PsiMethod method) {
        PsiParameterList parameterList = method.getParameterList();
        int paramCount = parameterList.getParametersCount();

        if (paramCount == 0) return -1;
        if (paramCount <= 2) return 0;
        if (paramCount <= 4) return 2;
        return 4;
    }

    private static int analyzeStateManagement(PsiMethod method) {
        PsiCodeBlock body = method.getBody();
        if (body == null) return 0;

        int statePoints = 0;

        PsiClass containingClass = method.getContainingClass();
        if (containingClass != null) {
            PsiField[] fields = containingClass.getFields();
            int mutableFields = 0;
            for (PsiField field : fields) {
                if (!field.hasModifierProperty(PsiModifier.FINAL) &&
                    !field.hasModifierProperty(PsiModifier.STATIC)) {
                    mutableFields++;
                }
            }
            if (mutableFields > 0) {
                statePoints += 2;
            }
        }

        int assignmentCount = PsiTreeUtil.collectElementsOfType(body, PsiAssignmentExpression.class).size();
        if (assignmentCount > 3) {
            statePoints += 2;
        }

        return statePoints;
    }

    private static int analyzeExceptionHandling(PsiMethod method) {
        PsiCodeBlock body = method.getBody();
        if (body == null) return 0;

        int exceptionPoints = 0;

        int tryBlocks = PsiTreeUtil.collectElementsOfType(body, PsiTryStatement.class).size();
        exceptionPoints += Math.min(tryBlocks * 2, 4);

        PsiReferenceList throwsList = method.getThrowsList();
        int declaredExceptions = throwsList.getReferenceElements().length;
        exceptionPoints += Math.min(declaredExceptions * 2, 4);

        return exceptionPoints;
    }

    private static int analyzeRiskFactors(PsiMethod method) {
        PsiCodeBlock body = method.getBody();
        if (body == null) return 0;

        int riskPoints = 0;

        String methodText = body.getText().toLowerCase();

        if (containsSecurityKeywords(methodText)) {
            riskPoints += 3;
        }

        if (containsFinancialKeywords(methodText)) {
            riskPoints += 3;
        }

        if (containsConcurrencyKeywords(methodText)) {
            riskPoints += 2;
        }

        return riskPoints;
    }

    private static boolean containsSecurityKeywords(String text) {
        return text.contains("password") || text.contains("auth") ||
               text.contains("token") || text.contains("encrypt") ||
               text.contains("decrypt") || text.contains("security");
    }

    private static boolean containsFinancialKeywords(String text) {
        return text.contains("payment") || text.contains("transaction") ||
               text.contains("money") || text.contains("price") ||
               text.contains("amount") || text.contains("balance");
    }

    private static boolean containsConcurrencyKeywords(String text) {
        return text.contains("synchronized") || text.contains("lock") ||
               text.contains("concurrent") || text.contains("thread") ||
               text.contains("volatile") || text.contains("atomic");
    }

    private static int countTernaryOperators(PsiCodeBlock body) {
        return PsiTreeUtil.collectElementsOfType(body, PsiConditionalExpression.class).size();
    }
}
