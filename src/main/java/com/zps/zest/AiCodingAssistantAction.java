package com.zps.zest;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * A simple AI Coding Assistant action that can read code and write/update files.
 * This action provides the core functionality needed for an AI agent to interact with code.
 */
public class AiCodingAssistantAction extends AnAction {
    private static final Logger LOG = Logger.getInstance(AiCodingAssistantAction.class);

    public AiCodingAssistantAction() {
        super("AI Coding Assistant", "Get help from AI with your code", AllIcons.Actions.StartDebugger);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        Editor editor = e.getData(CommonDataKeys.EDITOR);

        if (project == null || editor == null) {
            Messages.showErrorDialog("Please open a file in the editor first", "AI Assistant Error");
            return;
        }

        try {
            // 1. Read the current code
            String currentCode = readCurrentCode(editor);
            
            // 2. Get the selected text (if any)
            String selectedText = getSelectedText(editor);
            
            // 3. Get context information
            Map<String, String> context = gatherContext(project, editor);
            
            // 4. Create the prompt from context and code
            String prompt = createPrompt(currentCode, selectedText, context);
            
            // 5. Get response from LLM
            String aiResponse = callLlmApi(project, prompt);
            
            // 6. Process the response and apply changes if needed
            processResponse(project, editor, aiResponse);
            
        } catch (Exception ex) {
            LOG.error("Error in AI Coding Assistant", ex);
            Messages.showErrorDialog(
                    "Error: " + ex.getMessage(), 
                    "AI Assistant Error");
        }
    }

    /**
     * Reads code from the current editor.
     */
    private String readCurrentCode(Editor editor) {
        return editor.getDocument().getText();
    }
    
    /**
     * Gets the selected text from the editor.
     */
    private String getSelectedText(Editor editor) {
        return editor.getSelectionModel().hasSelection() ? 
               editor.getSelectionModel().getSelectedText() : "";
    }
    
    /**
     * Gathers context information from the project and editor.
     */
    private Map<String, String> gatherContext(Project project, Editor editor) {
        Map<String, String> context = new HashMap<>();
        
        // Get current file information
        VirtualFile currentFile = FileDocumentManager.getInstance().getFile(editor.getDocument());
        if (currentFile != null) {
            context.put("currentFile", currentFile.getPath());
            context.put("fileName", currentFile.getName());
            context.put("fileExtension", currentFile.getExtension());
        }
        
        // Get project information
        context.put("projectName", project.getName());
        context.put("projectBasePath", project.getBasePath());
        
        // Add cursor position
        int offset = editor.getCaretModel().getOffset();
        context.put("cursorOffset", String.valueOf(offset));
        
        // Get line and column number
        int lineNumber = editor.getDocument().getLineNumber(offset);
        int column = offset - editor.getDocument().getLineStartOffset(lineNumber);
        context.put("cursorLine", String.valueOf(lineNumber + 1));
        context.put("cursorColumn", String.valueOf(column + 1));
        
        return context;
    }
    
    /**
     * Creates a prompt for the LLM based on code and context.
     */
    private String createPrompt(String currentCode, String selectedText, Map<String, String> context) {
        StringBuilder prompt = new StringBuilder();
        
        // System instructions
        prompt.append("You are an AI coding assistant integrated into IntelliJ IDEA. ")
              .append("You help programmers write, understand, and improve code. ")
              .append("Be concise, precise, and helpful. ")
              .append("When suggesting changes, use {{REPLACE_SELECTION:code}} or {{INSERT_AT_CURSOR:code}} ")
              .append("to indicate code that should replace the selection or be inserted at the cursor.\n\n");
        
        // Context information
        prompt.append("CONTEXT:\n");
        for (Map.Entry<String, String> entry : context.entrySet()) {
            prompt.append("- ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
        }
        prompt.append("\n");
        
        // Code information
        if (selectedText != null && !selectedText.isEmpty()) {
            prompt.append("SELECTED CODE:\n```\n").append(selectedText).append("\n```\n\n");
            prompt.append("The user would like help with this selected code. ")
                  .append("Analyze it and suggest improvements or explain it.\n\n");
        } else {
            prompt.append("CURRENT FILE CODE:\n```\n").append(currentCode).append("\n```\n\n");
            prompt.append("The user would like help with this file. ")
                  .append("Analyze it and provide assistance.\n\n");
        }
        
        // Request for help
        prompt.append("What would you like me to help with? ")
              .append("I can explain code, suggest improvements, ")
              .append("or help implement new features.\n");
        
        return prompt.toString();
    }
    
    /**
     * Calls the LLM API with the prompt and returns the response.
     */
    private String callLlmApi(Project project, String prompt) throws PipelineExecutionException {
        ConfigurationManager config = new ConfigurationManager(project);
        
        // Create a temporary context for the API call
        CodeContext context = new CodeContext();
        context.setProject(project);
        context.setConfig(config);
        context.setPrompt(prompt);
        
        // Call the LLM API using the existing implementation
        LlmApiCallStage apiCallStage = new LlmApiCallStage();
        apiCallStage.process(context);
        
        return context.getApiResponse();
    }
    
    /**
     * Processes the LLM response and applies any code changes.
     */
    private void processResponse(Project project, Editor editor, String response) {
        if (response == null || response.isEmpty()) {
            Messages.showErrorDialog("Received empty response from AI", "AI Assistant Error");
            return;
        }
        
        // Check for code replacement command
        if (response.contains("{{REPLACE_SELECTION:")) {
            handleReplaceSelection(project, editor, response);
        } 
        // Check for code insertion command
        else if (response.contains("{{INSERT_AT_CURSOR:")) {
            handleInsertAtCursor(project, editor, response);
        }
        // Check for file creation command
        else if (response.contains("{{CREATE_FILE:")) {
            handleCreateFile(project, response);
        }
        // Otherwise just show the response
        else {
            showResponse(project, response);
        }
    }
    
    /**
     * Handles replacing selected text with code from the response.
     */
    private void handleReplaceSelection(Project project, Editor editor, String response) {
        // Parse the code to replace from the response
        String replaceCommand = "{{REPLACE_SELECTION:";
        int startIndex = response.indexOf(replaceCommand) + replaceCommand.length();
        int endIndex = response.indexOf("}}", startIndex);
        
        if (startIndex >= replaceCommand.length() && endIndex > startIndex) {
            String codeToInsert = response.substring(startIndex, endIndex);
            
            // Apply the replacement
            WriteCommandAction.runWriteCommandAction(project, () -> {
                if (editor.getSelectionModel().hasSelection()) {
                    int start = editor.getSelectionModel().getSelectionStart();
                    int end = editor.getSelectionModel().getSelectionEnd();
                    editor.getDocument().replaceString(start, end, codeToInsert);
                } else {
                    // If no selection, insert at cursor
                    int offset = editor.getCaretModel().getOffset();
                    editor.getDocument().insertString(offset, codeToInsert);
                }
            });
            
            // Show the explanation part of the response
            String explanation = response.replace(replaceCommand + codeToInsert + "}}", "");
            showResponse(project, "Changes applied. " + explanation.trim());
        } else {
            showResponse(project, response);
        }
    }
    
    /**
     * Handles inserting code at the cursor position.
     */
    private void handleInsertAtCursor(Project project, Editor editor, String response) {
        // Parse the code to insert from the response
        String insertCommand = "{{INSERT_AT_CURSOR:";
        int startIndex = response.indexOf(insertCommand) + insertCommand.length();
        int endIndex = response.indexOf("}}", startIndex);
        
        if (startIndex >= insertCommand.length() && endIndex > startIndex) {
            String codeToInsert = response.substring(startIndex, endIndex);
            
            // Apply the insertion
            WriteCommandAction.runWriteCommandAction(project, () -> {
                int offset = editor.getCaretModel().getOffset();
                editor.getDocument().insertString(offset, codeToInsert);
            });
            
            // Show the explanation part of the response
            String explanation = response.replace(insertCommand + codeToInsert + "}}", "");
            showResponse(project, "Code inserted. " + explanation.trim());
        } else {
            showResponse(project, response);
        }
    }
    
    /**
     * Handles creating a new file from the response.
     */
    private void handleCreateFile(Project project, String response) {
        try {
            // Parse the file creation command
            String createCommand = "{{CREATE_FILE:";
            int pathStartIndex = response.indexOf(createCommand) + createCommand.length();
            int pathEndIndex = response.indexOf(":", pathStartIndex);
            
            if (pathStartIndex >= createCommand.length() && pathEndIndex > pathStartIndex) {
                String filePath = response.substring(pathStartIndex, pathEndIndex);
                int contentStartIndex = pathEndIndex + 1;
                int contentEndIndex = response.indexOf("}}", contentStartIndex);
                
                if (contentEndIndex > contentStartIndex) {
                    String fileContent = response.substring(contentStartIndex, contentEndIndex);
                    
                    // Create the file
                    boolean success = createNewFile(project, filePath, fileContent);
                    
                    if (success) {
                        // Show the explanation part of the response
                        String explanation = response.replace(createCommand + filePath + ":" + fileContent + "}}", "");
                        showResponse(project, "File created at: " + filePath + ". " + explanation.trim());
                    } else {
                        showResponse(project, "Failed to create file: " + filePath);
                    }
                } else {
                    showResponse(project, response);
                }
            } else {
                showResponse(project, response);
            }
        } catch (Exception e) {
            LOG.error("Error creating file", e);
            showResponse(project, "Error creating file: " + e.getMessage());
        }
    }
    
    /**
     * Creates a new file with the specified content.
     */
    private boolean createNewFile(Project project, String relativePath, String content) {
        try {
            // Make sure path is relative to project
            String basePath = project.getBasePath();
            String fullPath = basePath + "/" + relativePath;
            
            // Create directories if needed
            Path path = Paths.get(fullPath);
            Files.createDirectories(path.getParent());
            
            // Write file content
            Files.write(path, content.getBytes(StandardCharsets.UTF_8));
            
            // Refresh VFS
            VirtualFile file = ApplicationManager.getApplication().runReadAction(
                    (Computable<VirtualFile>) () -> 
                        LocalFileSystem.getInstance().refreshAndFindFileByPath(fullPath));
            
            if (file != null) {
                // Open the file in editor
                ApplicationManager.getApplication().invokeLater(() -> {
                    FileEditorManager.getInstance(project).openFile(file, true);
                });
                return true;
            }
            return false;
        } catch (IOException e) {
            LOG.error("Error creating file", e);
            return false;
        }
    }
    
    /**
     * Shows the response in a dialog.
     */
    private void showResponse(Project project, String response) {
        Messages.showInfoMessage(project, response, "AI Assistant Response");
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        // Enable the action only when an editor is available
        Project project = e.getProject();
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        
        e.getPresentation().setEnabledAndVisible(project != null && editor != null);
    }
}