package com.zps.zest;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.SelectionModel;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.PsiErrorElementUtil;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Enhanced action for implementing TODOs in selected code using LLM with improved UX.
 * This action is triggered from the editor context menu when code with TODOs is selected.
 * It uses an enhanced diff viewer with additional features for better developer experience.
 */
public class ImplementTodosAction extends AnAction {
    private static final Logger LOG = Logger.getInstance(ImplementTodosAction.class);
    private static final Pattern TODO_PATTERN = Pattern.compile(
            "//\\s*(TODO|FIXME|HACK|XXX)\\s*:?\\s*(.*?)\\s*$|/\\*\\s*(TODO|FIXME|HACK|XXX)\\s*:?\\s*(.*?)\\s*\\*/",
            Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);

    public ImplementTodosAction() {
        super("Ai code?!: Implement TODOs ", "Replace TODOs with implementation using LLM", AllIcons.General.TodoDefault);
    }
    @Override
    public void update(@NotNull AnActionEvent e) {
        // Enable/disable action based on context
      boolean enabled =  ReadAction.compute(()->{
            Editor editor = e.getData(CommonDataKeys.EDITOR);
            PsiFile psiFile = e.getData(CommonDataKeys.PSI_FILE);

            boolean __enabled = false;

            if (editor != null && psiFile != null) {
                SelectionModel selectionModel = editor.getSelectionModel();
                if (selectionModel.hasSelection()) {
                    String selectedText = selectionModel.getSelectedText();
                    if (selectedText != null && containsTodo(selectedText)) {
                        __enabled = true;
                    }
                }
            }
            return __enabled;
        });

        e.getPresentation().setEnabledAndVisible(enabled);

    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {

        Editor editor = e.getData(CommonDataKeys.EDITOR);
        if (editor == null) {
            LOG.info("No editor available");
            return;
        }

        SelectionModel selectionModel = editor.getSelectionModel();
        if (!selectionModel.hasSelection()) {
            LOG.info("No code selection");
            showNotification(e.getProject(), "Please select a code block with TODOs");
            return;
        }

        // Get the selected text
        String selectedText = selectionModel.getSelectedText();
        if (selectedText == null || !containsTodo(selectedText)) {
            LOG.info("No TODOs in selection");
            showNotification(e.getProject(), "Selected code doesn't contain any TODOs");
            return;
        }

        // Get PSI file
        PsiFile psiFile = e.getData(CommonDataKeys.PSI_FILE);
        if (!(psiFile instanceof PsiJavaFile)) {
            LOG.info("Not a Java file");
            showNotification(e.getProject(), "This action only works with Java files");
            return;
        }

        // Check for syntax errors in the selection
        if (hasSyntaxErrors(psiFile, selectionModel.getSelectionStart(), selectionModel.getSelectionEnd())) {
            int result = Messages.showYesNoDialog(
                    e.getProject(),
                    "The selected code contains syntax errors. Proceeding might lead to unexpected results. Continue anyway?",
                    "Syntax Errors Detected",
                    "Continue",
                    "Cancel",
                    AllIcons.General.Warning);

            if (result != Messages.YES) {
                return;
            }
        }

        // Execute in background
        ProgressManager.getInstance().run(new Task.Backgroundable(e.getProject(), "Implementing TODOs", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    indicator.setIndeterminate(false);

                    // Step 1: Analyze code context
                    indicator.setText("Analyzing code context...");
                    indicator.setFraction(0.2);
                    String codeContext = ReadAction.compute(() -> {
                        return gatherCodeContext(psiFile, selectionModel.getSelectionStart(), selectionModel.getSelectionEnd());
                    });

                    // Get information about the TODOs
                    int todoCount = countTodos(selectedText);
                    indicator.setText("Found " + todoCount + " TODOs to implement...");

                    // Step 2: Call LLM
                    indicator.setText("Generating implementation...");
                    indicator.setFraction(0.5);
                    String implementedCode = callLlmForImplementation(selectedText, codeContext, e.getProject());

                    // Handle result
//                    if (implementedCode == null || implementedCode.isEmpty()) {
//                        throw new PipelineExecutionException("Failed to generate implementation");
//                    }
//
//                    indicator.setText("Implementation complete");
//                    indicator.setFraction(1.0);
//
//                    // Show enhanced diff and apply changes if accepted
//                    ApplicationManager.getApplication().invokeLater(() -> {
//                        showEnhancedDiff(e.getProject(), editor, selectedText, implementedCode, selectionModel.getSelectionStart(), selectionModel.getSelectionEnd());
//                    });
                } catch (Exception ex) {
                    LOG.error("Error implementing TODOs", ex);
                    ApplicationManager.getApplication().invokeLater(() -> {
                        Messages.showErrorDialog(e.getProject(), "Error: " + ex.getMessage(), "Failed to Implement TODOs");
                    });
                }
            }
        });
    }

    /**
     * Checks if the selected text contains TODOs.
     */
    private boolean containsTodo(String text) {
        return TODO_PATTERN.matcher(text).find();
    }

    /**
     * Counts the number of TODOs in the selected text.
     */
    private int countTodos(String text) {
        Matcher matcher = TODO_PATTERN.matcher(text);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    /**
     * Checks if the selected code contains syntax errors.
     */
    private boolean hasSyntaxErrors(PsiFile file, int startOffset, int endOffset) {
        return ReadAction.compute(() -> {
            // Find the PSI element at the selection
            PsiElement startElement = file.findElementAt(startOffset);
            PsiElement endElement = file.findElementAt(endOffset - 1);

            if (startElement == null || endElement == null) {
                return false;
            }

            // Find a common parent that encompasses the selection
            PsiElement parent = PsiTreeUtil.findCommonParent(startElement, endElement);
            if (parent == null) {
                return false;
            }

            // Check for error elements in the selection
            return PsiErrorElementUtil.hasErrors(file.getProject(), file.getVirtualFile());
        });
    }

    /**
     * Gathers comprehensive code context for the LLM to understand the surrounding code,
     * including related classes used in the method or surrounding class.
     */
    private String gatherCodeContext(PsiFile psiFile, int selectionStart, int selectionEnd) {

        // Use the shared ClassAnalyzer utility for basic context collection
        String baseContext = ClassAnalyzer.collectSelectionContext(psiFile, selectionStart, selectionEnd);

        return baseContext;
    }

    private String callLlmForImplementation(String selectedText, String codeContext, Project project) throws PipelineExecutionException {
        try {
            // Create configuration and context for LLM API call
            ConfigurationManager config = ConfigurationManager.getInstance(project);
            CodeContext context = new CodeContext();
            context.useTestWrightModel(false);
            context.setProject(project);
            context.setConfig(config);

            // Get the PsiFile and editor from context if available
            PsiFile psiFile = context.getPsiFile();
            Editor editor = context.getEditor();

            // Collect related class implementations directly using ClassAnalyzer
            Map<String, String> relatedClassImpls = new HashMap<>();

            if (psiFile != null && editor != null && editor.getSelectionModel().hasSelection()) {
                SelectionModel selectionModel = editor.getSelectionModel();
                relatedClassImpls = ClassAnalyzer.collectRelatedClassImplementations(
                        psiFile,
                        selectionModel.getSelectionStart(),
                        selectionModel.getSelectionEnd()
                );
            }

            // Create specialized prompt using TodoPromptDrafter
            String prompt = TodoPromptDrafter.createTodoImplementationPrompt(
                    selectedText,
                    codeContext,
                    relatedClassImpls
            );

            // Set the prompt in the context
            context.setPrompt(prompt);
            System.out.println(prompt);

            // Log the prompt for debugging purposes
            LOG.debug("TODO implementation prompt: " + prompt);

            // Call LLM API
            ChatboxLlmApiCallStage apiCallStage = new ChatboxLlmApiCallStage(false);
            apiCallStage.process(context);

            // Extract code from response
//            CodeExtractionStage extractionStage = new CodeExtractionStage();
//            extractionStage.process(context);

            String implementedCode = context.getApiResponse(); // This will contain the extracted code

            if (implementedCode == null || implementedCode.isEmpty()) {
                throw new PipelineExecutionException("LLM returned empty implementation");
            }

            // Validate that the implementation contains the expected code structure
            if (!validateImplementation(selectedText, implementedCode)) {
                LOG.warn("LLM implementation may have altered code structure unexpectedly");
            }

            return implementedCode;
        } catch (Exception e) {
            LOG.error("Error calling LLM for TODO implementation", e);
            throw new PipelineExecutionException("Failed to call LLM: " + e.getMessage(), e);
        }
    }
    /**
     * Validates that the implemented code maintains the overall structure of the original code.
     * This helps ensure the LLM hasn't drastically altered the code beyond replacing TODOs.
     */
    private boolean validateImplementation(String originalCode, String implementedCode) {
        // Remove whitespace and comments for comparison
        String normalizedOriginal = normalizeForComparison(originalCode);
        String normalizedImplemented = normalizeForComparison(implementedCode);

        // Check if the implemented code has roughly similar structure
        // by comparing size (allowing for reasonable expansion)
        int originalSize = normalizedOriginal.length();
        int implementedSize = normalizedImplemented.length();

        // Implementation should not be smaller than original
        if (implementedSize < originalSize * 0.9) {
            return false;
        }

        // Implementation should not be drastically larger (allowing for reasonable expansion)
        if (implementedSize > originalSize * 3) {
            return false;
        }

        // Check that non-TODO lines are preserved
        // This is a simple approach - we could do more sophisticated diffing if needed
        String[] origLines = originalCode.split("\n");
        String[] implLines = implementedCode.split("\n");

        int matchingLines = 0;
        for (String origLine : origLines) {
            if (!origLine.contains("TODO") && !origLine.trim().isEmpty()) {
                boolean foundMatch = false;
                for (String implLine : implLines) {
                    if (implLine.trim().equals(origLine.trim())) {
                        foundMatch = true;
                        matchingLines++;
                        break;
                    }
                }
                if (!foundMatch) {
                    LOG.debug("Original line not found in implementation: " + origLine);
                }
            }
        }

        // Ensure we have a reasonable number of matching non-TODO lines
        int nonTodoLines = (int) java.util.Arrays.stream(origLines)
                .filter(line -> !line.contains("TODO") && !line.trim().isEmpty())
                .count();

        return matchingLines >= nonTodoLines * 0.8; // At least 80% of non-TODO lines should match
    }

    /**
     * Normalizes code for comparison by removing whitespace and comments.
     */
    private String normalizeForComparison(String code) {
        // Remove comments
        code = code.replaceAll("//.*?$", "").replaceAll("/\\*.*?\\*/", "");

        // Remove whitespace
        return code.replaceAll("\\s+", "");
    }
    /**
     * Shows the enhanced diff component for comparing original and implemented code.
     */
    private void showEnhancedDiff(Project project, Editor editor, String originalCode, String implementedCode, int selectionStart, int selectionEnd) {
        EnhancedTodoDiffComponent diffComponent = new EnhancedTodoDiffComponent(
                project,
                editor,
                originalCode,
                implementedCode,
                selectionStart,
                selectionEnd
        );

        diffComponent.showDiff();
    }

    /**
     * Shows a notification.
     */
    private void showNotification(Project project, String message) {
        ApplicationManager.getApplication().invokeLater(() -> {
            Messages.showInfoMessage(project, message, "TODO Implementation");
        });
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT; 
    }
}