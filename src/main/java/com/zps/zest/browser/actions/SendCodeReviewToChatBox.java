package com.zps.zest.browser.actions;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.zps.zest.completion.metrics.ActionMetricsHelper;
import com.zps.zest.completion.metrics.FeatureType;
import com.zps.zest.explanation.tools.RipgrepCodeTool;
import com.zps.zest.testgen.tools.AnalyzeClassTool;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Action that sends the current code to the chat for review.
 * Simplified version without the pipeline framework.
 */
public class SendCodeReviewToChatBox extends BaseChatAction {

    @Override
    protected boolean isActionAvailable(@NotNull AnActionEvent e) {
        return e.getData(CommonDataKeys.EDITOR) != null && e.getData(CommonDataKeys.PSI_FILE) != null;
    }

    @Override
    protected void showUnavailableMessage(Project project) {
        Messages.showWarningDialog(project, "Please open a file to review", "No File Selected");
    }

    @Override
    protected String getTaskTitle() {
        return "Preparing Code Review";
    }

    @Override
    protected String getErrorTitle() {
        return "Code Review Failed";
    }

    @Override
    protected String getChatPreparationMethod() {
        return "prepareForCodeReview";
    }

    @Override
    protected String getNotificationTitle() {
        return "Code Review Started";
    }

    @Override
    protected String getNotificationMessage() {
        return "Code review request has been sent to the AI chat.";
    }
    @Override
    protected String createPrompt(@NotNull AnActionEvent e) throws Exception {
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        PsiFile psiFile = e.getData(CommonDataKeys.PSI_FILE);
        Project project = e.getProject();

        if (editor == null || psiFile == null || project == null) {
            throw new Exception("No file or editor available");
        }

        // Track feature usage
        Map<String, String> metricsContext = new HashMap<>();
        metricsContext.put("file", psiFile.getName());
        ActionMetricsHelper.INSTANCE.trackAction(
                project,
                FeatureType.CODE_REVIEW_CHAT,
                "Zest.SendCodeReviewToChatBox",
                e,
                metricsContext
        );

        // Get the code to review
        String codeContent = ReadAction.compute(() -> {
            String selectedText = editor.getSelectionModel().getSelectedText();
            if (selectedText != null && !selectedText.isEmpty()) {
                return selectedText;
            }

            PsiElement element = psiFile.findElementAt(editor.getCaretModel().getOffset());
            PsiClass psiClass = PsiTreeUtil.getParentOfType(element, PsiClass.class);
            if (psiClass != null) {
                return psiClass.getText();
            }

            return psiFile.getText();
        });

        // Gather context for better review
        CodeReviewContext context = gatherCodeContext(project, psiFile, codeContent);

        return createEnhancedReviewPrompt(psiFile.getName(), psiFile.getFileType().getDefaultExtension(),
                                         codeContent, context);
    }
    
    /**
     * Gather relevant context for code review using tools similar to ContextAgent
     */
    private CodeReviewContext gatherCodeContext(Project project, PsiFile psiFile, String codeContent) {
        CodeReviewContext context = new CodeReviewContext();

        try {
            // Initialize tools
            RipgrepCodeTool ripgrepTool = new RipgrepCodeTool(project, new HashSet<>(), new ArrayList<>());
            AnalyzeClassTool analyzeClassTool = new AnalyzeClassTool(project, new HashMap<>(), new HashMap<>());

            // Extract key identifiers from the code
            Set<String> classNames = extractClassNames(codeContent);
            Set<String> methodNames = extractMethodNames(codeContent);

            // Find test files for the class
            if (!classNames.isEmpty()) {
                String className = classNames.iterator().next();
                String testSearchResult = ripgrepTool.findFiles("*" + className + "Test*");
                context.testFiles = parseFileList(testSearchResult);

                // Search for usage patterns
                String usageResult = ripgrepTool.searchCode(className, "*.java", null, 2, 2, false);
                context.usagePatterns = extractUsageExamples(usageResult, className);
            }

            // Search for similar patterns in the codebase
            if (!methodNames.isEmpty()) {
                for (String methodName : methodNames) {
                    String similarResult = ripgrepTool.searchCode(methodName, "*.java", null, 1, 1, false);
                    context.similarImplementations.add("Method '" + methodName + "' found in: " +
                                                     countOccurrences(similarResult));
                }
            }

            // Get class structure if reviewing a class
            ReadAction.run(() -> {
                PsiElement element = psiFile.findElementAt(0);
                PsiClass psiClass = PsiTreeUtil.getParentOfType(element, PsiClass.class);
                if (psiClass != null) {
                    String classAnalysis = analyzeClassTool.analyzeClass(psiClass.getQualifiedName());
                    context.classStructure = extractClassSummary(classAnalysis);

                    // Get imports
                    PsiImportList importList = ((PsiJavaFile) psiFile).getImportList();
                    if (importList != null) {
                        context.dependencies = Arrays.stream(importList.getImportStatements())
                            .map(PsiImportStatement::getQualifiedName)
                            .filter(Objects::nonNull)
                            .collect(Collectors.toList());
                    }
                }
            });

        } catch (Exception ex) {
            // Log but don't fail - context is helpful but not required
            LOG.warn("Failed to gather full context for code review", ex);
        }

        return context;
    }

    /**
     * Create enhanced review prompt with gathered context
     */
    private String createEnhancedReviewPrompt(String fileName, String fileType, String codeContent,
                                             CodeReviewContext context) {
        StringBuilder prompt = new StringBuilder();

        prompt.append("## Code Review Context\n\n");
        prompt.append("**File:** ").append(fileName).append("\n");

        if (!context.testFiles.isEmpty()) {
            prompt.append("**Related Test Files:** ").append(String.join(", ", context.testFiles)).append("\n");
        }

        if (!context.dependencies.isEmpty()) {
            prompt.append("**Key Dependencies:** \n");
            context.dependencies.stream().limit(5).forEach(dep ->
                prompt.append("- ").append(dep).append("\n"));
        }

        if (!context.usagePatterns.isEmpty()) {
            prompt.append("**Usage Examples Found:** ").append(context.usagePatterns.size()).append(" locations\n");
        }

        if (!context.similarImplementations.isEmpty()) {
            prompt.append("**Similar Patterns in Codebase:**\n");
            prompt.append("```\n");
            context.similarImplementations.forEach(impl ->
                prompt.append("- ").append(impl).append("\n"));
            prompt.append("```\n");
        }

        if (context.classStructure != null && !context.classStructure.isEmpty()) {
            prompt.append("**Class Structure:** ").append(context.classStructure).append("\n");
        }

        prompt.append("\n## Code to Review\n\n");
        prompt.append("```").append(fileType).append("\n");
        prompt.append(codeContent).append("\n");
        prompt.append("```\n\n");

        prompt.append("## Review Focus\n\n");
        prompt.append("Please analyze this code for:\n");
        prompt.append("1. **Correctness**: Logic errors, edge cases, null safety\n");
        prompt.append("2. **Performance**: Bottlenecks, unnecessary operations, resource leaks\n");
        prompt.append("3. **Security**: Input validation, injection risks, data exposure\n");
        prompt.append("4. **Testability**: Hard-to-test patterns such as tight-coupling\n");
        prompt.append("5. **Code Reuse**: Duplicate logic that exists elsewhere (check similar patterns above)\n");
        prompt.append("6. **Best Practices**: Design patterns, SOLID principles, clean code\n");
        prompt.append("\n");
        prompt.append("**Instructions:**\n");
        prompt.append("- Use tools strategically to understand the codebase better\n");
        prompt.append("- Reference specific line numbers when pointing out issues\n");
        prompt.append("- Suggest concrete improvements with code examples\n");
        prompt.append("- Prioritize issues by severity (critical → major → minor)\n");
        prompt.append("- Be concise but thorough - focus on actionable feedback\n");

        return prompt.toString();
    }

    // Helper methods for context extraction
    private Set<String> extractClassNames(String code) {
        Set<String> classNames = new HashSet<>();
        String[] patterns = {"class\\s+(\\w+)", "interface\\s+(\\w+)", "enum\\s+(\\w+)"};

        for (String pattern : patterns) {
            java.util.regex.Pattern p = java.util.regex.Pattern.compile(pattern);
            java.util.regex.Matcher m = p.matcher(code);
            while (m.find()) {
                classNames.add(m.group(1));
            }
        }
        return classNames;
    }

    private Set<String> extractMethodNames(String code) {
        Set<String> methodNames = new HashSet<>();
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
            "(public|private|protected|static|\\s)+[\\w<>\\[\\]]+\\s+(\\w+)\\s*\\([^)]*\\)");
        java.util.regex.Matcher m = p.matcher(code);
        while (m.find()) {
            String name = m.group(2);
            if (!name.equals("if") && !name.equals("for") && !name.equals("while") &&
                !name.equals("switch") && !name.equals("catch")) {
                methodNames.add(name);
            }
        }
        return methodNames;
    }

    private List<String> parseFileList(String fileListOutput) {
        List<String> files = new ArrayList<>();
        if (fileListOutput != null && !fileListOutput.isEmpty()) {
            String[] lines = fileListOutput.split("\n");
            for (String line : lines) {
                if (line.trim().endsWith(".java") || line.trim().endsWith(".kt")) {
                    files.add(line.trim());
                }
            }
        }
        return files;
    }

    private List<String> extractUsageExamples(String searchResult, String className) {
        List<String> examples = new ArrayList<>();
        if (searchResult != null && !searchResult.isEmpty()) {
            String[] lines = searchResult.split("\n");
            for (String line : lines) {
                if (line.contains(className) && !line.contains("class " + className)) {
                    examples.add(line.trim());
                    if (examples.size() >= 3) break; // Limit to 3 examples
                }
            }
        }
        return examples;
    }

    private String countOccurrences(String searchResult) {
        if (searchResult == null || searchResult.isEmpty()) {
            return "0 occurrences";
        }
        String[] lines = searchResult.split("\n");
        Set<String> files = new HashSet<>();
        for (String line : lines) {
            if (line.contains(":")) {
                files.add(line.substring(0, line.indexOf(":")));
            }
        }
        return files.size() + " file(s)";
    }

    private String extractClassSummary(String classAnalysis) {
        if (classAnalysis == null || classAnalysis.isEmpty()) {
            return null;
        }
        // Extract first meaningful line from class analysis
        String[] lines = classAnalysis.split("\n");
        for (String line : lines) {
            if (line.contains("methods") || line.contains("fields") || line.contains("extends")) {
                return line.trim();
            }
        }
        return null;
    }

    /**
     * Container for code review context
     */
    private static class CodeReviewContext {
        List<String> testFiles = new ArrayList<>();
        List<String> dependencies = new ArrayList<>();
        List<String> usagePatterns = new ArrayList<>();
        List<String> similarImplementations = new ArrayList<>();
        String classStructure;
    }


    @Override
    public void update(@NotNull AnActionEvent e) {
        // Enable only when a file is open in the editor
        Project project = e.getProject();
        boolean hasEditor = e.getData(CommonDataKeys.EDITOR) != null;
        boolean hasPsiFile = e.getData(CommonDataKeys.PSI_FILE) != null;
        
        e.getPresentation().setEnabled(project != null && hasEditor && hasPsiFile);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}