package com.zps.zest.autocomplete.actions;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.zps.zest.autocomplete.ZestAutocompleteService;
import com.zps.zest.autocomplete.ZestCompletionData;
import com.zps.zest.autocomplete.ZestInlayRenderer;
import org.jetbrains.annotations.NotNull;

/**
 * Test action specifically for progressive Tab acceptance (word → line → full).
 */
public class TestProgressiveTabAction extends AnAction {
    
    public TestProgressiveTabAction() {
        super("🔄 Test Progressive Tab Acceptance", 
              "Test word → line → full Tab progression", 
              null);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        Project project = e.getProject();
        
        if (editor == null || project == null) {
            Messages.showErrorDialog("No editor or project available", "Test Progressive Tab");
            return;
        }

        // Show intro dialog
        int choice = Messages.showYesNoDialog(
            project,
            "This will create a multi-line completion to test progressive Tab acceptance.\n\n" +
            "Expected behavior:\n" +
            "• 1st Tab: Accept first word\n" +
            "• 2nd Tab: Accept next word (continuation)\n" +
            "• 3rd Tab: Accept next word or line\n" +
            "• Final Tab: Accept all remaining\n\n" +
            "Continue?",
            "Test Progressive Tab",
            "Create Test",
            "Cancel",
            Messages.getQuestionIcon()
        );

        if (choice != Messages.YES) {
            return;
        }

        try {
            // Clear any existing completion
            ZestAutocompleteService service = ZestAutocompleteService.getInstance(project);
            service.clearCompletion(editor);
            
            // Create multi-line test completion
            String testCompletion = "writeInt(42);\n" +
                                  "    buffer.flip();\n" +
                                  "    channel.write(buffer);\n" +
                                  "    buffer.clear();";
            
            int currentOffset = editor.getCaretModel().getOffset();
            ZestCompletionData.Range range = new ZestCompletionData.Range(currentOffset, currentOffset);
            ZestCompletionData.CompletionItem testItem = new ZestCompletionData.CompletionItem(
                testCompletion, range, null, 0.95f
            );
            
            // Display the completion
            ApplicationManager.getApplication().invokeLater(() -> {
                ZestInlayRenderer.RenderingContext renderingContext = 
                    ZestInlayRenderer.show(editor, currentOffset, testItem);
                
                if (!renderingContext.getInlays().isEmpty()) {
                    // Create and store the completion
                    ZestCompletionData.PendingCompletion testPendingCompletion = 
                        new ZestCompletionData.PendingCompletion(testItem, editor, "progressive-test");
                    
                    service.getStateManager().setCompletion(editor, testPendingCompletion, renderingContext);
                    
                    Messages.showInfoMessage(
                        "✅ PROGRESSIVE TAB TEST READY!\n\n" +
                        "You should see a multi-line completion.\n\n" +
                        "NOW TEST THE PROGRESSION:\n" +
                        "1️⃣ Press Tab → Should accept 'writeInt(42);'\n" +
                        "2️⃣ Press Tab → Should accept next word/line\n" +
                        "3️⃣ Press Tab → Should accept next part\n" +
                        "4️⃣ Continue until all is accepted\n\n" +
                        "Each Tab should show remaining completion!",
                        "Progressive Test Ready"
                    );
                } else {
                    Messages.showWarningDialog(
                        "Could not create test completion inlays.",
                        "Test Failed"
                    );
                }
            });
            
        } catch (Exception ex) {
            Messages.showErrorDialog(
                "Error creating progressive test: " + ex.getMessage(),
                "Test Failed"
            );
        }
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(e.getData(CommonDataKeys.EDITOR) != null);
    }
}
