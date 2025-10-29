package com.zps.zest.testgen.analysis;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.psi.*;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;

/**
 * Analyzes how methods are used throughout the project.
 * Discovers edge cases, test data examples, and integration patterns from actual usage.
 */
public class UsageAnalyzer {
    private static final Logger LOG = Logger.getInstance(UsageAnalyzer.class);
    private static final int MAX_CALL_SITES = 20; // Limit analysis for performance

    private final Project project;

    public UsageAnalyzer(@NotNull Project project) {
        this.project = project;
    }

    /**
     * Analyze how a method is used throughout the project.
     * This runs in a read action for PSI safety.
     */
    @NotNull
    public UsageContext analyzeMethod(@NotNull PsiMethod method) {
        return ApplicationManager.getApplication().runReadAction((Computable<UsageContext>) () -> {
            String methodName = method.getContainingClass() != null ?
                    method.getContainingClass().getName() + "." + method.getName() :
                    method.getName();

            UsageContext context = new UsageContext(methodName);

            try {
                // Find all references to this method
                Collection<PsiReference> references = ReferencesSearch.search(method).findAll();
                LOG.info("Found " + references.size() + " usages of " + methodName);

                int analyzed = 0;
                for (PsiReference reference : references) {
                    if (analyzed >= MAX_CALL_SITES) {
                        LOG.info("Reached max call sites limit, stopping analysis");
                        break;
                    }

                    PsiElement element = reference.getElement();
                    if (element != null) {
                        analyzeCallSite(element, context);
                        analyzed++;
                    }
                }

                LOG.info("Usage analysis complete for " + methodName + ": " +
                        context.getTotalUsages() + " call sites, " +
                        context.getDiscoveredEdgeCases().size() + " edge cases, " +
                        context.getTestDataExamples().size() + " test data examples");

            } catch (Exception e) {
                LOG.warn("Error analyzing usages for " + methodName + ": " + e.getMessage(), e);
            }

            return context;
        });
    }

    /**
     * Analyze a single call site to extract test-relevant information.
     */
    private void analyzeCallSite(@NotNull PsiElement callElement, @NotNull UsageContext context) {
        try {
            // Get the method that contains this call
            PsiMethod callerMethod = PsiTreeUtil.getParentOfType(callElement, PsiMethod.class);
            if (callerMethod == null) {
                return; // Call is not within a method (maybe field initializer)
            }

            PsiClass callerClass = callerMethod.getContainingClass();
            if (callerClass == null) {
                return;
            }

            // Extract code snippet with method signature and larger context (10 lines before, 10 lines after)
            String codeSnippet = extractCodeSnippetWithMethodSignature(callElement, callerMethod, 10, 10);

            // Build call site information
            CallSite.Builder callSiteBuilder = new CallSite.Builder()
                    .callerClass(callerClass.getName() != null ? callerClass.getName() : "Unknown")
                    .callerMethod(callerMethod.getName())
                    .filePath(callElement.getContainingFile().getVirtualFile().getPath())
                    .lineNumber(getLineNumber(callElement))
                    .codeSnippet(codeSnippet);

            // Check for error handling around this call
            ErrorHandlingInfo errorInfo = detectErrorHandling(callElement);
            if (errorInfo != null) {
                callSiteBuilder.errorHandling(true, errorInfo.type);
                // Add edge case for exception handling
                context.addEdgeCase(EdgeCase.exceptionHandled(
                        errorInfo.exceptionType,
                        callerClass.getName() + "." + callerMethod.getName()
                ));
            }

            // Check for null handling
            if (detectNullCheck(callElement)) {
                context.addEdgeCase(EdgeCase.nullCheck(
                        "return value",
                        callerClass.getName() + "." + callerMethod.getName()
                ));
            }

            // Check for Optional handling
            if (detectOptionalHandling(callElement)) {
                context.addEdgeCase(EdgeCase.optionalEmpty(
                        context.getTargetName(),
                        callerClass.getName() + "." + callerMethod.getName()
                ));
            }

            // Analyze integration context
            analyzeIntegrationContext(callerMethod, context.getIntegrationContext());

            // Extract test data from call arguments if this is a test
            if (isTestMethod(callerMethod)) {
                extractTestDataFromCall(callElement, context);
            }

            context.addCallSite(callSiteBuilder.build());

        } catch (Exception e) {
            LOG.debug("Error analyzing call site: " + e.getMessage());
        }
    }

    /**
     * Extract code snippet with method signature and context around the call site.
     * Shows the containing method signature, then N lines before and M lines after the call.
     */
    @NotNull
    private String extractCodeSnippetWithMethodSignature(@NotNull PsiElement callElement,
                                                          @NotNull PsiMethod containingMethod,
                                                          int linesBefore, int linesAfter) {
        try {
            PsiFile file = callElement.getContainingFile();
            if (file == null) return "";

            StringBuilder snippet = new StringBuilder();

            // Extract method signature
            String methodSignature = extractMethodSignature(containingMethod);
            if (methodSignature != null && !methodSignature.isEmpty()) {
                snippet.append(methodSignature).append("\n");

                // Calculate distance from method start to call
                int methodStartLine = getLineNumber(containingMethod);
                int callLine = getLineNumber(callElement);
                int lineDistance = callLine - methodStartLine;

                // If call is far from method signature (>10 lines), indicate gap
                if (lineDistance > linesBefore + 2) {
                    snippet.append("   ... (").append(lineDistance - linesBefore)
                           .append(" lines omitted)\n");
                }
            }

            // Extract context around the call
            String fileText = file.getText();
            int callOffset = callElement.getTextOffset();

            // Find start of line containing the call
            int lineStart = callOffset;
            while (lineStart > 0 && fileText.charAt(lineStart - 1) != '\n') {
                lineStart--;
            }

            // Go back N lines
            for (int i = 0; i < linesBefore && lineStart > 0; i++) {
                lineStart--;
                while (lineStart > 0 && fileText.charAt(lineStart - 1) != '\n') {
                    lineStart--;
                }
            }

            // Find end - go forward M lines from call line
            int lineEnd = callOffset;
            while (lineEnd < fileText.length() && fileText.charAt(lineEnd) != '\n') {
                lineEnd++;
            }

            for (int i = 0; i < linesAfter && lineEnd < fileText.length(); i++) {
                lineEnd++;
                while (lineEnd < fileText.length() && fileText.charAt(lineEnd) != '\n') {
                    lineEnd++;
                }
            }

            String codeContext = fileText.substring(lineStart, Math.min(lineEnd, fileText.length()));
            snippet.append(codeContext.trim());

            return snippet.toString();

        } catch (Exception e) {
            LOG.debug("Failed to extract code snippet: " + e.getMessage());
            return "";
        }
    }

    /**
     * Extract method signature with modifiers, return type, and parameters.
     */
    @Nullable
    private String extractMethodSignature(@NotNull PsiMethod method) {
        try {
            StringBuilder sig = new StringBuilder();

            // Modifiers (public, private, static, etc.)
            PsiModifierList modifiers = method.getModifierList();
            String modifierText = modifiers.getText();
            if (modifierText != null && !modifierText.isEmpty()) {
                sig.append(modifierText).append(" ");
            }

            // Return type
            PsiType returnType = method.getReturnType();
            if (returnType != null) {
                sig.append(returnType.getPresentableText()).append(" ");
            }

            // Method name
            sig.append(method.getName()).append("(");

            // Parameters
            PsiParameter[] params = method.getParameterList().getParameters();
            for (int i = 0; i < params.length; i++) {
                if (i > 0) sig.append(", ");
                PsiType paramType = params[i].getType();
                sig.append(paramType.getPresentableText());
                String paramName = params[i].getName();
                if (paramName != null) {
                    sig.append(" ").append(paramName);
                }
            }
            sig.append(")");

            // Exceptions
            PsiReferenceList throwsList = method.getThrowsList();
            PsiClassType[] exceptions = throwsList.getReferencedTypes();
            if (exceptions.length > 0) {
                sig.append(" throws ");
                for (int i = 0; i < exceptions.length; i++) {
                    if (i > 0) sig.append(", ");
                    sig.append(exceptions[i].getPresentableText());
                }
            }

            return sig.toString();

        } catch (Exception e) {
            LOG.debug("Failed to extract method signature: " + e.getMessage());
            return method.getName() + "(...)";
        }
    }

    /**
     * Detect error handling patterns around a method call.
     * Returns specific exception types being caught.
     */
    @Nullable
    private ErrorHandlingInfo detectErrorHandling(@NotNull PsiElement callElement) {
        // Check if call is inside a try-catch
        PsiTryStatement tryStatement = PsiTreeUtil.getParentOfType(callElement, PsiTryStatement.class);
        if (tryStatement != null) {
            PsiCatchSection[] catchSections = tryStatement.getCatchSections();
            if (catchSections.length > 0) {
                // Collect ALL caught exception types (there may be multiple catch blocks)
                java.util.List<String> exceptionTypes = new java.util.ArrayList<>();
                for (PsiCatchSection catchSection : catchSections) {
                    PsiParameter catchParam = catchSection.getParameter();
                    if (catchParam != null) {
                        // Get the actual exception type name
                        PsiType type = catchParam.getType();
                        String typeName = type.getPresentableText();
                        exceptionTypes.add(typeName);
                    }
                }

                // Combine all exception types with " | " separator
                String allTypes = String.join(" | ", exceptionTypes);
                return new ErrorHandlingInfo("try-catch", allTypes);
            }
        }

        return null;
    }

    /**
     * Detect if there's a null check after the method call.
     */
    private boolean detectNullCheck(@NotNull PsiElement callElement) {
        // Look for patterns like:
        // result = method(); if (result != null) ...
        // or: result = method(); result != null ? ... : ...

        PsiElement statement = PsiTreeUtil.getParentOfType(callElement, PsiStatement.class);
        if (statement == null) return false;

        PsiElement nextStatement = PsiTreeUtil.getNextSiblingOfType(statement, PsiStatement.class);
        if (nextStatement instanceof PsiIfStatement) {
            PsiIfStatement ifStmt = (PsiIfStatement) nextStatement;
            String conditionText = ifStmt.getCondition() != null ? ifStmt.getCondition().getText() : "";
            return conditionText.contains("!= null") || conditionText.contains("== null");
        }

        return false;
    }

    /**
     * Detect Optional handling patterns.
     */
    private boolean detectOptionalHandling(@NotNull PsiElement callElement) {
        // Look for .orElse(), .orElseGet(), .orElseThrow(), .isPresent() etc.
        PsiElement parent = callElement.getParent();
        while (parent != null && !(parent instanceof PsiStatement)) {
            String text = parent.getText();
            if (text.contains(".orElse") || text.contains(".isPresent") ||
                text.contains(".ifPresent") || text.contains(".orElseThrow")) {
                return true;
            }
            parent = parent.getParent();
        }
        return false;
    }

    /**
     * Analyze integration patterns (transactions, async, events).
     */
    private void analyzeIntegrationContext(@NotNull PsiMethod callerMethod, @NotNull IntegrationContext integrationContext) {
        PsiModifierList modifiers = callerMethod.getModifierList();
        String callerName = (callerMethod.getContainingClass() != null ?
                callerMethod.getContainingClass().getName() : "") + "." + callerMethod.getName();

        if (hasAnnotation(modifiers, "Transactional")) {
            integrationContext.addTransactionalCaller(callerName);
        }

        if (hasAnnotation(modifiers, "Async")) {
            integrationContext.addAsyncCaller(callerName);
        }

        if (hasAnnotation(modifiers, "EventListener", "KafkaListener", "RabbitListener")) {
            integrationContext.addEventListenerCaller(callerName);
        }

        if (hasAnnotation(modifiers, "Scheduled")) {
            integrationContext.addScheduledJobCaller(callerName);
        }

        // Check if the method contains loops
        boolean hasLoop = PsiTreeUtil.findChildOfType(callerMethod, PsiLoopStatement.class) != null;
        if (hasLoop) {
            integrationContext.setUsedInLoop(true);
        }
    }

    /**
     * Extract test data from method call arguments (when called from tests).
     */
    private void extractTestDataFromCall(@NotNull PsiElement callElement, @NotNull UsageContext context) {
        if (!(callElement.getParent() instanceof PsiMethodCallExpression)) {
            return;
        }

        PsiMethodCallExpression methodCall = (PsiMethodCallExpression) callElement.getParent();
        PsiExpression[] arguments = methodCall.getArgumentList().getExpressions();

        for (int i = 0; i < arguments.length; i++) {
            PsiExpression arg = arguments[i];
            String value = extractArgumentValue(arg);
            if (value != null && !value.isEmpty()) {
                String source = "Test: " + (methodCall.getContainingFile() != null ?
                        methodCall.getContainingFile().getName() : "unknown");
                boolean isValid = !value.equals("null") && !value.contains("invalid");

                context.addTestDataExample(new TestDataExample(
                        "arg" + i,
                        value,
                        arg.getType() != null ? arg.getType().getPresentableText() : "unknown",
                        source,
                        isValid
                ));
            }
        }
    }

    /**
     * Extract the value of an argument expression as a string.
     */
    @Nullable
    private String extractArgumentValue(@NotNull PsiExpression expression) {
        if (expression instanceof PsiLiteralExpression) {
            Object value = ((PsiLiteralExpression) expression).getValue();
            return value != null ? value.toString() : "null";
        }

        // For more complex expressions, just return the text (might be a builder, constructor, etc.)
        String text = expression.getText();
        if (text.length() > 100) {
            return text.substring(0, 100) + "..."; // Truncate long expressions
        }
        return text;
    }

    /**
     * Check if a method has @Test annotation.
     */
    private boolean isTestMethod(@NotNull PsiMethod method) {
        return hasAnnotation(method.getModifierList(), "Test");
    }

    /**
     * Check if a class is a test class.
     */
    private boolean isTestClass(@NotNull PsiClass clazz) {
        String name = clazz.getName();
        return name != null && (name.endsWith("Test") || name.endsWith("Tests") || name.contains("TestCase"));
    }

    /**
     * Check if a modifier list has any of the specified annotations.
     * Checks both fully qualified names and simple names for better detection.
     */
    private boolean hasAnnotation(@NotNull PsiModifierList modifiers, String... annotationNames) {
        for (String annotationName : annotationNames) {
            PsiAnnotation[] annotations = modifiers.getAnnotations();
            for (PsiAnnotation annotation : annotations) {
                String qualifiedName = annotation.getQualifiedName();
                if (qualifiedName != null) {
                    // Check fully qualified name
                    if (qualifiedName.equals(annotationName) || qualifiedName.endsWith("." + annotationName)) {
                        return true;
                    }

                    // Also check simple name (after last dot)
                    String simpleName = qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1);
                    if (simpleName.equals(annotationName)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Get line number of a PSI element.
     */
    private int getLineNumber(@NotNull PsiElement element) {
        PsiFile file = element.getContainingFile();
        if (file == null) return 0;

        String text = file.getText();
        int offset = element.getTextOffset();
        int line = 1;
        for (int i = 0; i < offset && i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    /**
     * Internal class to hold error handling information.
     */
    private static class ErrorHandlingInfo {
        final String type;
        final String exceptionType;

        ErrorHandlingInfo(String type, String exceptionType) {
            this.type = type;
            this.exceptionType = exceptionType;
        }
    }
}
