package com.zps.zest.browser;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.DiffDialogHints;
import com.intellij.diff.DiffManager;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.WindowWrapper;
import com.intellij.util.Consumer;
import org.jetbrains.annotations.NotNull;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.codeStyle.CodeStyleManager;

import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/**
 * Service for handling code-related operations from JavaScript bridge.
 * This includes code completion, extraction, and diff operations.
 * Using the original implementation with bug fixes.
 */
public class CodeService {
    private static final Logger LOG = Logger.getInstance(CodeService.class);
    
    private final Project project;
    private final Gson gson = new Gson();
    private final EditorService editorService;
    
    public CodeService(@NotNull Project project) {
        this.project = project;
        this.editorService = new EditorService(project);
    }
    
    /**
     * Handles code completion requests.
     */
    public String handleCodeComplete(JsonObject data) {
        try {
            String textToReplace = data.get("textToReplace").getAsString();
            String resultText = data.get("text").getAsString();
            
            // Run async - don't wait for result
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                handleCodeCompleteInternal(textToReplace, resultText);
            });
            
            JsonObject response = new JsonObject();
            response.addProperty("success", true);
            return gson.toJson(response);
        } catch (Exception e) {
            LOG.error("Error handling code complete", e);
            JsonObject response = new JsonObject();
            response.addProperty("success", false);
            response.addProperty("error", e.getMessage());
            return gson.toJson(response);
        }
    }
    
    /**
     * Handles extracted code from API responses.
     */
    public String handleExtractedCode(JsonObject data) {
        try {
            String codeText = data.get("code").getAsString();
            String language = data.has("language") ? data.get("language").getAsString() : "";
            String extractTextToReplace = data.get("textToReplace").getAsString();
            
            // Run async - don't wait for result
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                handleExtractedCodeInternal(extractTextToReplace, codeText, language);
            });
            
            JsonObject response = new JsonObject();
            response.addProperty("success", true);
            return gson.toJson(response);
        } catch (Exception e) {
            LOG.error("Error handling extracted code", e);
            JsonObject response = new JsonObject();
            response.addProperty("success", false);
            response.addProperty("error", e.getMessage());
            return gson.toJson(response);
        }
    }
    
    /**
     * Handles showing code diff and replace operations.
     */
    public String handleShowCodeDiffAndReplace(JsonObject data) {
        try {
            String codeContent = data.get("code").getAsString();
            String codeLanguage = data.has("language") ? data.get("language").getAsString() : "";
            String replaceTargetText = data.get("textToReplace").getAsString();
            
            LOG.info("Received showCodeDiffAndReplace request - Code length: " + codeContent.length() + 
                    ", Language: " + codeLanguage + ", TextToReplace: " + replaceTargetText);
            
            // Run async - don't wait for result
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                handleShowCodeDiffAndReplaceInternal(replaceTargetText, codeContent, codeLanguage);
            });
            
            JsonObject response = new JsonObject();
            response.addProperty("success", true);
            return gson.toJson(response);
        } catch (Exception e) {
            LOG.error("Error handling show code diff and replace", e);
            JsonObject response = new JsonObject();
            response.addProperty("success", false);
            response.addProperty("error", e.getMessage());
            return gson.toJson(response);
        }
    }
    
    // Internal implementation methods using the original code with bug fixes
    
    /**
     * Handles code completion by finding text to replace and showing a diff.
     * This method is now asynchronous and doesn't block the UI.
     * Original implementation from JavaScriptBridge with bug fixes.
     */
    private boolean handleCodeCompleteInternal(String textToReplace, String resultText) {
        Editor editor = editorService.getCurrentEditor();
        if (editor == null) {
            LOG.warn("No editor is currently open");
            return false;
        }

        Document document = editor.getDocument();
        String text = document.getText();

        // Find the text to replace
        int startOffset = text.indexOf(textToReplace);
        if (startOffset == -1) {
            return false;
        }

        int endOffset = startOffset + textToReplace.length();

        // Show diff in editor asynchronously
        ApplicationManager.getApplication().invokeLater(() -> {
            // Create a temporary document with the replaced content for diff
            String currentContent = document.getText();
            String newContent = currentContent.substring(0, startOffset) + resultText + currentContent.substring(endOffset);

            // Use IntelliJ's diff tool
            DiffManager diffManager = DiffManager.getInstance();
            SimpleDiffRequest diffRequest = new SimpleDiffRequest("Code Completion",
                    DiffContentFactory.getInstance().create(currentContent),
                    DiffContentFactory.getInstance().create(newContent),
                    "Current Code", "Code With Replacement");

            // Setup hints for modal dialog with custom callback
            DiffDialogHints hints = new DiffDialogHints(WindowWrapper.Mode.FRAME, editor.getComponent(), new Consumer<WindowWrapper>() {
                @Override
                public void consume(WindowWrapper wrapper) {
                    if (wrapper != null && wrapper.getWindow() != null) {
                        // Add a listener to detect when the diff window is closed
                        wrapper.getWindow().addWindowListener(new WindowAdapter() {
                            @Override
                            public void windowClosed(WindowEvent e) {
                                // Ask user if they want to apply the changes
                                int dialogResult = Messages.showYesNoDialog(
                                        project,
                                        "Do you want to apply these changes?",
                                        "Apply Code Completion",
                                        "Apply",
                                        "Cancel",
                                        Messages.getQuestionIcon()
                                );

                                if (dialogResult == Messages.YES) {
                                    // Apply the changes if accepted
                                    WriteCommandAction.runWriteCommandAction(project, () -> {
                                        // Replace the text
                                        document.replaceString(startOffset, endOffset, resultText);
                                        
                                        // Calculate new end offset after replacement
                                        int newEndOffset = startOffset + resultText.length();
                                        
                                        // Commit document changes to PSI
                                        PsiDocumentManager documentManager = PsiDocumentManager.getInstance(project);
                                        documentManager.commitDocument(document);
                                        
                                        // Get PSI file and reformat only the inserted range
                                        PsiFile psiFile = documentManager.getPsiFile(document);
                                        if (psiFile != null) {
                                            try {
                                                CodeStyleManager.getInstance(project).reformatRange(psiFile, startOffset, newEndOffset);
                                            } catch (Exception ex) {
                                                LOG.warn("Failed to reformat inserted code: " + ex.getMessage());
                                                // Continue anyway - the code is inserted, just not reformatted
                                            }
                                        }
                                    });
                                }
                            }
                        });
                    }
                }
            });

            // Show diff dialog
            diffManager.showDiff(project, diffRequest, hints);
        });

        return true; // Return immediately, don't wait for user interaction
    }
    
    /**
     * Handles code extracted from API responses
     * Original implementation from JavaScriptBridge with bug fixes.
     */
    private boolean handleExtractedCodeInternal(String textToReplace, String codeText, String language) {
        LOG.info("Handling extracted code from API response, language: " + language);

        try {
            // If special value is used, get selected text from editor
            if ("__##use_selected_text##__".equals(textToReplace)) {
                String selectedText = editorService.getSelectedTextFromEditor();
                if (selectedText != null && !selectedText.isEmpty()) {
                    textToReplace = selectedText;
                } else {
                    // No text selected, just insert the code
                    return false;
                }
            }

            // If we have valid text to replace, handle as code completion
            if (textToReplace != null && !textToReplace.isEmpty()) {
                return handleCodeCompleteInternal(textToReplace, codeText);
            } else {
                // No text to replace, just insert the code
                return false;
            }
        } catch (Exception e) {
            LOG.error("Error handling extracted code", e);
            return false;
        }
    }
    
    /**
     * Handles showing diff and applying code replacement from the "To IDE" button
     * Original implementation from JavaScriptBridge with bug fixes.
     */
    private boolean handleShowCodeDiffAndReplaceInternal(String textToReplace, String codeContent, String language) {
        LOG.info("Handling show code diff and replace from To IDE button, language: " + language);

        try {
            // If special value is used, get selected text from editor
            if ("__##use_selected_text##__".equals(textToReplace)) {
                String selectedText = editorService.getSelectedTextFromEditor();
                if (selectedText != null && !selectedText.isEmpty()) {
                    textToReplace = selectedText;
                } else {
                    // No text selected, offer to insert at cursor position
                    ApplicationManager.getApplication().invokeLater(() -> {
                        int option = Messages.showYesNoDialog(project,
                                "No text is selected in the editor.\n\n" +
                                        "Choose how to handle the code:\n" +
                                        "- Yes: Insert at current cursor position\n" +
                                        "- No: Show the code in a dialog for manual copying\n" +
                                        "- Cancel: Do nothing",
                                "No Text Selected",
                                "Insert at Cursor",
                                "Skip",
                                Messages.getQuestionIcon());

                        if (option == Messages.YES) {
                            // Insert at cursor position
                            editorService.insertTextToEditor(codeContent);
                            Messages.showInfoMessage(project,
                                    "Code inserted at cursor position successfully!",
                                    "Code Inserted");
                        } else if (option == Messages.NO) {
                            // Show code in a dialog for manual copying
                            showCodeDialog(codeContent, language);
                        }
                        // If CANCEL, do nothing
                    });
                    return true; // Return true since we handled the case
                }
            }

            // If we have valid text to replace, show diff and handle replacement
            if (textToReplace != null && !textToReplace.isEmpty()) {
                return handleAdvancedCodeReplace(textToReplace, codeContent, language);
            } else {
                // Fallback to insert at cursor if somehow textToReplace is empty
                ApplicationManager.getApplication().invokeLater(() -> {
                    int option = Messages.showYesNoDialog(project,
                            "Would you like to insert the code at the current cursor position?",
                            "Insert Code",
                            "Insert at Cursor",
                            "Cancel",
                            Messages.getQuestionIcon());

                    if (option == Messages.YES) {
                        editorService.insertTextToEditor(codeContent);
                    }
                });
                return false;
            }
        } catch (Exception e) {
            LOG.error("Error handling show code diff and replace", e);
            ApplicationManager.getApplication().invokeLater(() -> {
                Messages.showErrorDialog(project,
                        "Error processing code replacement: " + e.getMessage(),
                        "Code Replacement Error");
            });
            return false;
        }
    }
    
    /**
     * Advanced code replacement with enhanced diff display and confirmation
     * Original implementation from JavaScriptBridge with bug fixes.
     */
    private boolean handleAdvancedCodeReplace(String textToReplace, String newCode, String language) {
        Editor editor = editorService.getCurrentEditor();
        if (editor == null) {
            LOG.warn("No editor is currently open");
            ApplicationManager.getApplication().invokeLater(() -> {
                Messages.showWarningDialog(project,
                        "No editor is currently open.",
                        "No Editor Available");
            });
            return false;
        }

        Document document = editor.getDocument();
        String fullText = document.getText();

        // Find the text to replace - ORIGINAL IMPLEMENTATION WITH BUG FIXES
        int startOffset = -1;
        if (startOffset == -1) {
            // Try case-insensitive search
            String lowerFullText = fullText.toLowerCase();
            String lowerTextToReplace = textToReplace.toLowerCase();
            int lowerStartOffset = lowerFullText.indexOf(lowerTextToReplace);

            if (lowerStartOffset != -1) {
                // Found case-insensitive match, get the actual text
                startOffset = lowerStartOffset;
                textToReplace = fullText.substring(startOffset, startOffset + textToReplace.length());
            } else {
                startOffset = fullText.indexOf(textToReplace);
                String finalTextToReplace = textToReplace;
                ApplicationManager.getApplication().invokeLater(() -> {
                    Messages.showWarningDialog(project,
                            "The specified text was not found in the current file:\n\n" +
                                    finalTextToReplace.substring(0, Math.min(100, finalTextToReplace.length())) +
                                    (finalTextToReplace.length() > 100 ? "..." : ""),
                            "Text Not Found");
                });
                return false;
            }
        } else {
            startOffset = fullText.indexOf(textToReplace);
        }

        int endOffset = startOffset + textToReplace.length();

        // Show diff in editor asynchronously - ORIGINAL IMPLEMENTATION
        String finalTextToReplace1 = textToReplace;
        int finalStartOffset = startOffset;
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                // Create a temporary document with the replaced content for diff
                String currentContent = document.getText();
                String newContent = currentContent.substring(0, finalStartOffset) + newCode + currentContent.substring(endOffset);

                // Use IntelliJ's diff tool with enhanced titles
                DiffManager diffManager = DiffManager.getInstance();

                String fileName = "";
                if (editor.getVirtualFile() != null) {
                    fileName = editor.getVirtualFile().getName();
                }

                String diffTitle = "Code Replacement" + (fileName.isEmpty() ? "" : " in " + fileName);
                if (!language.isEmpty()) {
                    diffTitle += " (" + language + ")";
                }

                SimpleDiffRequest diffRequest = new SimpleDiffRequest(diffTitle,
                        DiffContentFactory.getInstance().create(currentContent),
                        DiffContentFactory.getInstance().create(newContent),
                        "Current Code", "Code with IDE Changes");

                // Setup hints for modal dialog with custom callback
                String finalFileName = fileName;
                DiffDialogHints hints = new DiffDialogHints(WindowWrapper.Mode.FRAME, editor.getComponent(), new Consumer<WindowWrapper>() {
                    @Override
                    public void consume(WindowWrapper wrapper) {
                        if (wrapper != null && wrapper.getWindow() != null) {
                            // Add a listener to detect when the diff window is closed
                            wrapper.getWindow().addWindowListener(new WindowAdapter() {
                                @Override
                                public void windowClosed(WindowEvent e) {
                                    // Ask user if they want to apply the changes with more context
                                    String confirmMessage = "Do you want to apply these changes?\n\n" +
                                            "Replacing " + finalTextToReplace1.length() + " characters with " + newCode.length() + " characters" +
                                            (finalFileName.isEmpty() ? "" : " in " + finalFileName);

                                    int dialogResult = Messages.showYesNoDialog(
                                            project,
                                            confirmMessage,
                                            "Apply Code Replacement",
                                            "Apply Changes",
                                            "Cancel",
                                            Messages.getQuestionIcon()
                                    );

                                    if (dialogResult == Messages.YES) {
                                        // Apply the changes if accepted
                                        WriteCommandAction.runWriteCommandAction(project, () -> {
                                            try {
                                                // Replace the text
                                                document.replaceString(finalStartOffset, endOffset, newCode);
                                                
                                                // Calculate new end offset after replacement
                                                int newEndOffset = finalStartOffset + newCode.length();
                                                
                                                // Commit document changes to PSI
                                                PsiDocumentManager documentManager = PsiDocumentManager.getInstance(project);
                                                documentManager.commitDocument(document);
                                                
                                                // Get PSI file and reformat only the inserted range
                                                PsiFile psiFile = documentManager.getPsiFile(document);
                                                if (psiFile != null) {
                                                    try {
                                                        CodeStyleManager.getInstance(project).reformatRange(psiFile, finalStartOffset, newEndOffset);
                                                    } catch (Exception refEx) {
                                                        LOG.warn("Failed to reformat inserted code: " + refEx.getMessage());
                                                        // Continue anyway - the code is inserted, just not reformatted
                                                    }
                                                }

                                                // Show success message
                                                ApplicationManager.getApplication().invokeLater(() -> {
                                                    Messages.showInfoMessage(project,
                                                            "Code replacement completed successfully!",
                                                            "Replacement Success");
                                                });
                                            } catch (Exception ex) {
                                                LOG.error("Error applying code replacement", ex);
                                                ApplicationManager.getApplication().invokeLater(() -> {
                                                    Messages.showErrorDialog(project,
                                                            "Error applying code replacement: " + ex.getMessage(),
                                                            "Replacement Error");
                                                });
                                            }
                                        });
                                    } else {
                                        LOG.info("Code replacement cancelled by user");
                                    }
                                }
                            });
                        }
                    }
                });

                // Show diff dialog
                diffManager.showDiff(project, diffRequest, hints);

            } catch (Exception e) {
                LOG.error("Error showing diff for code replacement", e);
                Messages.showErrorDialog(project,
                        "Error showing diff: " + e.getMessage(),
                        "Diff Error");
            }
        });

        return true; // Return immediately, don't wait for user interaction
    }
    
    /**
     * Shows code in a dialog for manual inspection and copying
     * Original implementation from JavaScriptBridge.
     */
    private void showCodeDialog(String code, String language) {
        ApplicationManager.getApplication().invokeLater(() -> {
            String title = "Generated Code" + (language.isEmpty() ? "" : " (" + language + ")");
            String message = "Here is the generated code:\n\n" + code + "\n\n" +
                    "You can copy this code manually and paste it where needed.";

            // Use a scrollable dialog for long code
            Messages.showInfoMessage(project, message, title);
        });
    }
    
    /**
     * Disposes of any resources.
     */
    public void dispose() {
        if (editorService != null) {
            editorService.dispose();
        }
    }
}
