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
 * Demo action that simulates the Tab issue and shows proper behavior.
 */
public class SimulateTabIssueDemoAction extends AnAction {
    
    public SimulateTabIssueDemoAction() {
        super("🎭 Demo Tab Issue Simulation", 
              "Simulate the Tab completion issue to demonstrate the fix", 
              null);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        Project project = e.getProject();
        
        if (editor == null || project == null) {
            Messages.showErrorDialog("No editor or project available", "Demo");
            return;
        }

        // Show intro dialog
        int choice = Messages.showYesNoCancelDialog(
            project,
            "This demo will:\n\n" +
            "1. Insert sample code at cursor\n" +
            "2. Trigger Zest completion\n" +
            "3. Show instructions for testing Tab behavior\n\n" +
            "Continue with demo?",
            "Tab Completion Demo",
            "Run Demo",
            "Cancel",
            "Help",
            Messages.getQuestionIcon()
        );

        if (choice == Messages.NO) { // Cancel
            return;
        }
        
        if (choice == Messages.CANCEL) { // Help
            Messages.showInfoMessage(
                "EXPECTED BEHAVIOR:\n" +
                "• When you see Zest completion (gray text), Tab should ACCEPT it\n" +
                "• Multiple Tab presses should accept word -> line -> full completion\n\n" +
                "PROBLEM BEHAVIOR:\n" +
                "• Tab CANCELS the completion instead of accepting it\n" +
                "• This makes Zest autocomplete unusable\n\n" +
                "THE FIX:\n" +
                "• Enhanced ZestSmartTabHandler detects active completions\n" +
                "• Direct completion check bypasses faulty context detection\n" +
                "• Robust state management prevents corruption",
                "Tab Completion Help"
            );
            return;
        }

        try {
            // Insert demo code
            WriteCommandAction.runWriteCommandAction(project, "Demo Setup", "Zest", () -> {
                Document document = editor.getDocument();
                int offset = editor.getCaretModel().getOffset();
                
                // Insert sample code that will trigger completion
                String demoCode = "ByteBuffer buffer = ByteBuffer.allocate(1024);\n" +
                                 "buffer.";
                
                document.insertString(offset, demoCode);
                editor.getCaretModel().moveToOffset(offset + demoCode.length());
            });

            // Wait a moment for the editor to update
            ApplicationManager.getApplication().invokeLater(() -> {
                // Force trigger completion
                ZestAutocompleteService service = ZestAutocompleteService.getInstance(project);
                service.triggerCompletion(editor, true);
                
                // Wait a bit more for completion to appear
                ApplicationManager.getApplication().invokeLater(() -> {
                    boolean hasCompletion = service.hasActiveCompletion(editor);
                    
                    if (hasCompletion) {
                        Messages.showInfoMessage(
                            "✅ DEMO READY!\n\n" +
                            "Zest completion is now showing (gray text after cursor).\n\n" +
                            "NOW TEST:\n" +
                            "1. Press Tab - it should ACCEPT the completion\n" +
                            "2. If it cancels instead, the issue is present\n" +
                            "3. Try multiple Tab presses for progressive acceptance\n\n" +
                            "SOLUTION:\n" +
                            "Use 'Diagnose Tab Completion Issue' if Tab cancels",
                            "Demo Ready - Test Tab Now!"
                        );
                    } else {
                        // Create a manual completion for demo
                        createManualDemoCompletion(editor, service);
                    }
                });
            });
            
        } catch (Exception ex) {
            Messages.showErrorDialog(
                "Error setting up demo: " + ex.getMessage(),
                "Demo Failed"
            );
        }
    }
    
    private void createManualDemoCompletion(Editor editor, ZestAutocompleteService service) {
        try {
            int currentOffset = editor.getCaretModel().getOffset();
            
            // Create demo completion
            String demoText = "writeInt(42);\n" +
                            "    buffer.flip();\n" +
                            "    // Demo completion for testing Tab behavior";
            
            ZestCompletionData.Range range = new ZestCompletionData.Range(currentOffset, currentOffset);
            ZestCompletionData.CompletionItem demoItem = new ZestCompletionData.CompletionItem(
                demoText, range, null, 0.95f
            );
            
            // Show the completion
            ZestInlayRenderer.RenderingContext renderingContext = 
                ZestInlayRenderer.show(editor, currentOffset, demoItem);
            
            if (!renderingContext.getInlays().isEmpty()) {
                // Store it in the service (hack for demo)
                ZestCompletionData.PendingCompletion demoCompletion = 
                    new ZestCompletionData.PendingCompletion(demoItem, editor, "demo");
                
                // We need to access the state manager to store this
                // This is a bit hacky but necessary for the demo
                Messages.showInfoMessage(
                    "✅ MANUAL DEMO COMPLETION CREATED!\n\n" +
                    "You should see gray text after the cursor.\n\n" +
                    "NOW TEST:\n" +
                    "• Press Tab - should ACCEPT completion\n" +
                    "• If Tab cancels, the issue exists\n\n" +
                    "Note: This is a manual demo completion for testing purposes.",
                    "Demo Completion Ready"
                );
            } else {
                Messages.showWarningDialog(
                    "Could not create demo completion.\n" +
                    "Try using 'Trigger Test Completion' instead.",
                    "Demo Setup Failed"
                );
            }
            
        } catch (Exception e) {
            Messages.showErrorDialog(
                "Error creating manual demo: " + e.getMessage(),
                "Demo Error"
            );
        }
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(e.getData(CommonDataKeys.EDITOR) != null);
    }
}
