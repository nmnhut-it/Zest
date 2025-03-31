package com.zps.zest;

import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.DiffDialogHints;
import com.intellij.diff.DiffManager;
import com.intellij.diff.contents.DocumentContent;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.SelectionModel;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.WindowWrapper;
import com.intellij.openapi.util.Computable;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.Consumer;
import org.jetbrains.annotations.NotNull;

/**
 * Action for implementing TODOs in selected code using LLM.
 * This action is triggered from the editor context menu when code with TODOs is selected.
 */
public class ImplementTodosAction extends AnAction {
    private static final Logger LOG = Logger.getInstance(ImplementTodosAction.class);

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
        if (selectedText == null || (!selectedText.contains("TODO") && !selectedText.contains("todo") && !selectedText.contains("toDo"))) {
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

        // Execute in background
        ProgressManager.getInstance().run(new Task.Backgroundable(e.getProject(), "Implementing TODOs", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    indicator.setIndeterminate(false);

                    // Gather context
                    indicator.setText("Analyzing code context...");
                    indicator.setFraction(0.2);
                    String codeContext = ReadAction.compute(()->{
                        return gatherCodeContext(psiFile, selectionModel.getSelectionStart(), selectionModel.getSelectionEnd());
                    }) ;

                    // Call LLM
                    indicator.setText("Generating implementation...");
                    indicator.setFraction(0.5);
                    String implementedCode = callLlmForImplementation(selectedText, codeContext, e.getProject());

                    // Handle result
                    if (implementedCode == null || implementedCode.isEmpty()) {
                        throw new PipelineExecutionException("Failed to generate implementation");
                    }

                    indicator.setText("Implementation complete");
                    indicator.setFraction(1.0);

                    // Show diff and apply changes if accepted
                    ApplicationManager.getApplication().invokeLater(() -> {
                        showDiffAndApply(e.getProject(), editor, selectedText, implementedCode, selectionModel.getSelectionStart(), selectionModel.getSelectionEnd());
                    });
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
     * Gathers comprehensive code context for the LLM to understand the surrounding code,
     * including related classes used in the method or surrounding class.
     */
    private String gatherCodeContext(PsiFile psiFile, int selectionStart, int selectionEnd) {
        // Delegate to the shared ClassAnalyzer utility
        return ClassAnalyzer.collectSelectionContext(psiFile, selectionStart, selectionEnd);
    }
    /**
     * Gets just the method signature without the body.
     */
    private String getMethodSignature(PsiMethod method) {
        StringBuilder signature = new StringBuilder();

        // Add modifiers
        String[] modifiers = {PsiModifier.PUBLIC, PsiModifier.PRIVATE, PsiModifier.PROTECTED, PsiModifier.STATIC, PsiModifier.ABSTRACT, PsiModifier.FINAL, PsiModifier.NATIVE, PsiModifier.SYNCHRONIZED};

        for (String modifier : modifiers) {
            if (method.hasModifierProperty(modifier)) {
                signature.append(modifier).append(" ");
            }
        }

        // Add return type
        PsiType returnType = method.getReturnType();
        if (returnType != null) {
            signature.append(returnType.getPresentableText()).append(" ");
        }

        // Add name and parameters
        signature.append(method.getName()).append("(");
        PsiParameter[] parameters = method.getParameterList().getParameters();
        for (int i = 0; i < parameters.length; i++) {
            PsiParameter param = parameters[i];
            signature.append(param.getType().getPresentableText()).append(" ").append(param.getName());
            if (i < parameters.length - 1) {
                signature.append(", ");
            }
        }
        signature.append(")");

        // Add exceptions
        PsiReferenceList throwsList = method.getThrowsList();
        if (throwsList.getReferenceElements().length > 0) {
            signature.append(" throws ");
            PsiJavaCodeReferenceElement[] throwsRefs = throwsList.getReferenceElements();
            for (int i = 0; i < throwsRefs.length; i++) {
                signature.append(throwsRefs[i].getText());
                if (i < throwsRefs.length - 1) {
                    signature.append(", ");
                }
            }
        }

        return signature.toString();
    }

    /**
     * Calls the LLM to implement the TODOs in the selected code.
     */
    private String callLlmForImplementation(String selectedText, String codeContext, Project project) throws PipelineExecutionException {
        try {
            // Create configuration and context for LLM API call
            ConfigurationManager config = new ConfigurationManager(project);
            TestGenerationContext context = new TestGenerationContext();
            context.setProject(project);
            context.setConfig(config);

            // Create prompt
            String promptBuilder = "Implement the TODOs in the following Java code. Replace each TODO with appropriate code.\n\n" +
                    "CONTEXT:\n" + codeContext + "\n\n" +
                    "CODE WITH TODOS TO IMPLEMENT:\n```java\n" + selectedText + "\n```\n\n" +
                    "Requirements:\n" +
                    "1. Only replace the TODOs with implementation code\n" +
                    "2. Keep the rest of the code exactly the same\n" +
                    "3. Ensure the implementation matches the context and follows good practices\n" +
                    "4. Return ONLY the implemented code without explanations or markdown formatting\n";

            context.setPrompt(promptBuilder);

            // Call LLM API
            LlmApiCallStage apiCallStage = new LlmApiCallStage();
            apiCallStage.process(context);

            // Extract code from response
            CodeExtractionStage extractionStage = new CodeExtractionStage();
            extractionStage.process(context);

            return context.getTestCode(); // This will contain the extracted code
        } catch (Exception e) {
            LOG.error("Error calling LLM", e);
            throw new PipelineExecutionException("Failed to call LLM: " + e.getMessage(), e);
        }
    }

    /**
     * Shows a diff dialog and applies the changes if accepted.
     * Uses a custom dialog to ensure proper closing of the diff viewer.
     */
    private void showDiffAndApply(Project project, Editor editor, String originalCode, String implementedCode, int selectionStart, int selectionEnd) {
        // Create diff contents
        DiffContentFactory diffFactory = DiffContentFactory.getInstance();
        DocumentContent originalContent = diffFactory.create(originalCode);
        DocumentContent newContent = diffFactory.create(implementedCode);

        // Create diff request
        SimpleDiffRequest diffRequest = new SimpleDiffRequest("TODO Implementation", originalContent, newContent, "Original Code with TODOs", "Implemented Code");

        // Create a runnable to apply changes
        Runnable applyChanges = () -> {
            WriteCommandAction.runWriteCommandAction(project, () -> {
                editor.getDocument().replaceString(selectionStart, selectionEnd, implementedCode);
            });
        };

        // Show diff using DiffManager showDiff method
        DiffManager diffManager = DiffManager.getInstance();
        WindowWrapper[] wrapperHolder = new WindowWrapper[1]; // Array to hold reference

        DiffDialogHints dialogHints = new DiffDialogHints(WindowWrapper.Mode.NON_MODAL, editor.getComponent(), new Consumer<WindowWrapper>() {
            @Override
            public void consume(WindowWrapper wrapper) {

                // todo: set this in window wrapper so that we can close later
                wrapperHolder[0] = wrapper;
            }
        });

        diffManager.showDiff(project, diffRequest, dialogHints);


        // After showing diff, show dialog with apply option
        int result = Messages.showYesNoDialog(project, "Do you want to apply the implementation to your code?", "Apply Changes", "Apply", "Cancel", null);

        // Apply changes if agreed
        if (result == Messages.YES) {
            applyChanges.run();
        }
        // Close the diff dialog
        if (wrapperHolder[0] != null) {
            wrapperHolder[0].close();
        }
        // Ensure focus returns to editor
        if (editor.getComponent().isShowing()) {
            editor.getContentComponent().requestFocusInWindow();
        }
    }

    /**
     * Shows a notification.
     */
    private void showNotification(Project project, String message) {
        ApplicationManager.getApplication().invokeLater(() -> {
            Messages.showInfoMessage(project, message, "TODO Implementation");
        });
    }
}