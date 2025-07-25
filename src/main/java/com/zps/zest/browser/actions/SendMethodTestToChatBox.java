package com.zps.zest.browser.actions;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.psi.*;
import com.intellij.psi.search.PsiShortNamesCache;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiTreeUtil;
import com.zps.zest.*;
import com.zps.zest.browser.utils.ChatboxUtilities;
import com.zps.zest.completion.context.ZestMethodContextCollector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Utility to send a single method test generation request to the chat box
 * Uses the full test generation pipeline for proper analysis
 */
public class SendMethodTestToChatBox {
    private static final Logger LOG = Logger.getInstance(SendMethodTestToChatBox.class);

    /**
     * Send test generation request for a specific method using the full pipeline
     */
    public static void sendMethodTestPrompt(
            @NotNull Project project,
            @NotNull Editor editor,
            @NotNull ZestMethodContextCollector.MethodContext methodContext
    ) {
        // Create a synthetic action event for the pipeline
        AnActionEvent syntheticEvent = createSyntheticActionEvent(project, editor);
        
        // Create the pipeline context
        CodeContext context = new CodeContext();
        context.setEvent(syntheticEvent);
        context.setProject(project);
        context.setEditor(editor);
        context.setPsiFile(PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument()));
        
        // Set the method context for single method mode
        context.setSingleMethodMode(true);
        context.setTargetMethodContext(methodContext);
        
        // Execute the pipeline in a background task
        executeMethodTestPipeline(context);
    }
    
    /**
     * Create a synthetic action event for the pipeline
     */
    private static AnActionEvent createSyntheticActionEvent(Project project, Editor editor) {
        // Create a data context using DataContext.builder() for newer API
        com.intellij.openapi.actionSystem.DataContext dataContext = 
            com.intellij.openapi.actionSystem.impl.SimpleDataContext.builder()
                .add(CommonDataKeys.PROJECT, project)
                .add(CommonDataKeys.EDITOR, editor)
                .add(CommonDataKeys.PSI_FILE, PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument()))
                .build();
        
        return new AnActionEvent(
            null,
            dataContext,
            "MethodTestGeneration",
            new com.intellij.openapi.actionSystem.Presentation(),
            com.intellij.openapi.actionSystem.ActionManager.getInstance(),
            0
        );
    }
    
    /**
     * Execute the test generation pipeline for a single method
     */
    private static void executeMethodTestPipeline(CodeContext context) {
        Project project = context.getProject();
        if (project == null) return;

        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Preparing Method Test Generation", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(false);

                try {
                    // Create pipeline with proper stages
                    TestGenerationPipeline pipeline = new TestGenerationPipeline()
                            .addStage(new ConfigurationStage());
                    
                    boolean isJsFile = context.isJavaScriptFile();
                    
                    if (isJsFile) {
                        // JS/TS pipeline
                        pipeline.addStage(new JsTargetDetectionStage())
                                .addStage(new JsCodeAnalysisStage())
                                .addStage(new JsMethodTestPromptCreationStage()); // Custom stage for method
                    } else {
                        // Java/Kotlin pipeline
                        pipeline.addStage(new TargetClassDetectionStage())
                                .addStage(new ClassAnalysisStage())
                                .addStage(new MethodTestPromptCreationStage()); // Custom stage for method
                    }

                    // Execute pipeline stages
                    int totalStages = pipeline.getStageCount();
                    for (int i = 0; i < totalStages; i++) {
                        PipelineStage stage = pipeline.getStage(i);
                        String stageName = stage.getClass().getSimpleName()
                                .replace("Stage", "")
                                .replaceAll("([A-Z])", " $1").trim();

                        indicator.setText("Stage " + (i+1) + "/" + totalStages + ": " + stageName);
                        indicator.setFraction((double) i / totalStages);

                        stage.process(context);
                        indicator.setFraction((double) (i+1) / totalStages);
                    }

                    // Get the generated prompt
                    String prompt = context.getPrompt();
                    if (prompt == null || prompt.isEmpty()) {
                        throw new PipelineExecutionException("Failed to generate prompt");
                    }

                    // Send to chat box
                    indicator.setText("Sending prompt to chat box...");
                    boolean success = sendPromptToChatBoxAndSubmit(project, prompt);
                    if (!success) {
                        throw new PipelineExecutionException("Failed to send prompt to chat box");
                    }

                    indicator.setText("Method test generation prompt sent successfully!");
                    indicator.setFraction(1.0);

                } catch (PipelineExecutionException e) {
                    showError(project, e);
                } catch (Exception e) {
                    showError(project, new PipelineExecutionException(e.getMessage(), e));
                }
            }
        });
    }
    
    /**
     * Shows an error message on the UI thread.
     */
    private static void showError(Project project, PipelineExecutionException e) {
        LOG.error("Method test generation error: " + e.getMessage(), e);
        
        if (project != null && !project.isDisposed()) {
            ApplicationManager.getApplication().invokeLater(() -> {
                Messages.showErrorDialog(project, "Error: " + e.getMessage(), "Method Test Generation Failed");
            });
        }
    }

    /**
     * Generate test prompt for a specific method
     */
    private static String generateMethodTestPrompt(
            @NotNull Project project,
            @NotNull Editor editor,
            @NotNull ZestMethodContextCollector.MethodContext methodContext
    ) {
        StringBuilder prompt = new StringBuilder();
        
        // Get file info
        PsiFile psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument());
        if (psiFile == null) {
            return "Generate unit tests for the selected method.";
        }

        String fileName = psiFile.getVirtualFile() != null ? psiFile.getVirtualFile().getName() : "Unknown";
        String className = findContainingClass(psiFile, methodContext.getMethodStartOffset());
        
        prompt.append("Generate comprehensive unit tests for the following method:\n\n");
        
        // Add context
        prompt.append("File: ").append(fileName).append("\n");
        if (className != null) {
            prompt.append("Class: ").append(className).append("\n");
        }
        prompt.append("Method: ").append(methodContext.getMethodName()).append("\n\n");
        
        // Add method code
        prompt.append("```").append(getFileExtension(fileName)).append("\n");
        prompt.append(methodContext.getMethodContent());
        prompt.append("\n```\n\n");
        
        // Add test requirements
        prompt.append("Requirements:\n");
        prompt.append("1. Create comprehensive unit tests covering all code paths\n");
        prompt.append("2. Include edge cases and error scenarios\n");
        prompt.append("3. Use appropriate mocking for dependencies\n");
        prompt.append("4. Follow the project's existing test patterns\n");
        prompt.append("5. Include descriptive test names\n");
        
        // Check for existing test class
        String existingTestClass = findExistingTestClass(project, psiFile, className);
        if (existingTestClass != null) {
            prompt.append("\nNote: Add these tests to the existing test class: ").append(existingTestClass).append("\n");
        } else {
            prompt.append("\nNote: Create a new test class following the project's naming conventions.\n");
        }
        
        return prompt.toString();
    }

    /**
     * Find the class containing the method
     */
    @Nullable
    private static String findContainingClass(PsiFile psiFile, int offset) {
        PsiElement element = psiFile.findElementAt(offset);
        if (element == null) return null;
        
        PsiClass psiClass = PsiTreeUtil.getParentOfType(element, PsiClass.class);
        return psiClass != null ? psiClass.getName() : null;
    }

    /**
     * Get file extension for syntax highlighting
     */
    private static String getFileExtension(String fileName) {
        if (fileName.endsWith(".java")) return "java";
        if (fileName.endsWith(".kt")) return "kotlin";
        if (fileName.endsWith(".js")) return "javascript";
        if (fileName.endsWith(".ts")) return "typescript";
        return "";
    }

    /**
     * Try to find existing test class for the given class
     */
    @Nullable
    private static String findExistingTestClass(Project project, PsiFile sourceFile, String className) {
        if (className == null) return null;
        
        // Common test class naming patterns
        String[] testClassNames = {
            className + "Test",
            className + "Tests",
            className + "Spec",
            "Test" + className
        };
        
        // Search for test files
        for (String testClassName : testClassNames) {
            PsiClass[] testClasses = PsiShortNamesCache.getInstance(project)
                    .getClassesByName(testClassName, GlobalSearchScope.projectScope(project));
            if (testClasses.length > 0) {
                return testClassName;
            }
        }
        
        return null;
    }

    /**
     * Send the generated prompt to the chat box and submit it
     */
    private static boolean sendPromptToChatBoxAndSubmit(Project project, String prompt) {
        LOG.info("Sending method test generation prompt to chat box");

        // Activate browser tool window and send prompt
        ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow("ZPS Chat");
        if (toolWindow != null) {
            ApplicationManager.getApplication().invokeLater(() -> {
                toolWindow.activate(() -> {
                    ChatboxUtilities.clickNewChatButton(project);
                    ChatboxUtilities.sendTextAndSubmit(
                        project, 
                        prompt, 
                        true,
                        ConfigurationManager.getInstance(project).getOpenWebUISystemPromptForCode(), 
                        false, 
                        ChatboxUtilities.EnumUsage.CHAT_WRITE_TESTS
                    );
                });
            });
            return true;
        }
        
        return false;
    }
}