package com.zps.zest.browser;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Caret;
import com.intellij.openapi.editor.CaretModel;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.util.TextRange;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;

/**
 * Service for handling editor-related operations from JavaScript bridge.
 * This includes text selection, insertion, and editor state queries.
 */
public class EditorService {
    private static final Logger LOG = Logger.getInstance(EditorService.class);
    
    private final Project project;
    private final Gson gson = new Gson();
    
    public EditorService(@NotNull Project project) {
        this.project = project;
    }
    
    /**
     * Gets the currently selected text from the editor.
     */
    public String getSelectedText() {
        try {
            String selectedText = getSelectedTextFromEditor();
            
            JsonObject response = new JsonObject();
            response.addProperty("success", true);
            response.addProperty("result", selectedText);
            return gson.toJson(response);
        } catch (Exception e) {
            LOG.error("Error getting selected text", e);
            JsonObject response = new JsonObject();
            response.addProperty("success", false);
            response.addProperty("error", e.getMessage());
            return gson.toJson(response);
        }
    }
    
    /**
     * Inserts text at the current caret position.
     */
    public String insertText(JsonObject data) {
        try {
            String text = data.get("text").getAsString();
            
            // Run async - don't wait for result
            ApplicationManager.getApplication().invokeLater(() -> {
                insertTextToEditor(text);
            });
            
            JsonObject response = new JsonObject();
            response.addProperty("success", true);
            return gson.toJson(response);
        } catch (Exception e) {
            LOG.error("Error inserting text", e);
            JsonObject response = new JsonObject();
            response.addProperty("success", false);
            response.addProperty("error", e.getMessage());
            return gson.toJson(response);
        }
    }
    
    /**
     * Gets the current file name.
     */
    public String getCurrentFileName() {
        try {
            String fileName = getCurrentFileNameFromEditor();
            
            JsonObject response = new JsonObject();
            response.addProperty("success", true);
            response.addProperty("result", fileName);
            return gson.toJson(response);
        } catch (Exception e) {
            LOG.error("Error getting current file name", e);
            JsonObject response = new JsonObject();
            response.addProperty("success", false);
            response.addProperty("error", e.getMessage());
            return gson.toJson(response);
        }
    }
    
    /**
     * Gets comprehensive project information.
     * This is synchronous as it's needed immediately by the JavaScript bridge.
     */
    public String getProjectInfo() {
        try {
            JsonObject projectInfo = getProjectInfoInternal();
            
            JsonObject response = new JsonObject();
            response.addProperty("success", true);
            response.add("result", projectInfo);
            return gson.toJson(response);
        } catch (Exception e) {
            LOG.error("Error getting project info", e);
            JsonObject response = new JsonObject();
            response.addProperty("success", false);
            response.addProperty("error", e.getMessage());
            return gson.toJson(response);
        }
    }
    
    // Internal helper methods
    
    /**
     * Gets the selected text from the current editor.
     */
    public String getSelectedTextFromEditor() {
        return ReadAction.compute(() -> {
            Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
            if (editor == null) {
                return "";
            }
            
            String selectedText = editor.getSelectionModel().getSelectedText();
            return selectedText != null ? selectedText : "";
        });
    }
    
    /**
     * Inserts text at the current caret position in the editor.
     */
    public boolean insertTextToEditor(String text) {
        Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
        if (editor == null) {
            return false;
        }
        
        Document document = editor.getDocument();
        CaretModel caretModel = editor.getCaretModel();
        
        WriteCommandAction.runWriteCommandAction(project, () -> {
            for (Caret caret : caretModel.getAllCarets()) {
                int offset = caret.getOffset();
                document.insertString(offset, text);
            }
        });
        
        return true;
    }
    
    /**
     * Gets the name of the current file.
     */
    private String getCurrentFileNameFromEditor() {
        Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
        if (editor == null) {
            return "";
        }
        
        try {
            return FileEditorManager.getInstance(project).getSelectedFiles()[0].getName();
        } catch (Exception e) {
            return "";
        }
    }
    
    /**
     * Gets real-time information about the current project and editor state.
     * ORIGINAL IMPLEMENTATION from JavaScriptBridge preserved.
     */
    private JsonObject getProjectInfoInternal() {
        JsonObject info = new JsonObject();
        
        try {
            // Project info
            info.addProperty("projectName", project.getName());
            info.addProperty("projectFilePath (path/to/project/.idea/misc.xml )", project.getProjectFilePath());
            info.addProperty("sourceRoots", Arrays.toString(ProjectRootManager.getInstance(project).getContentSourceRoots()));
            
            // Editor info
            Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
            if (editor != null) {
                if (editor.getVirtualFile() != null) {
                    info.addProperty("currentOpenFile", editor.getVirtualFile().getPath());
                } else {
                    info.addProperty("currentOpenFile", "");
                }
                
                // Get code context
                String codeContext = getCodeAroundCaret(editor, 25);
                info.addProperty("codeContext", codeContext);
            } else {
                info.addProperty("currentOpenFile", "");
                info.addProperty("codeContext", "");
            }
        } catch (Exception e) {
            LOG.error("Error getting project info", e);
        }
        
        return info;
    }
    
    /**
     * Gets code surrounding the caret position.
     * ORIGINAL IMPLEMENTATION from JavaScriptBridge preserved.
     * @param editor Current editor
     * @param lineCount Number of lines before and after caret to include
     * @return String containing code context
     */
    private String getCodeAroundCaret(Editor editor, int lineCount) {
        if (editor == null) {
            return "";
        }
        
        return ReadAction.compute(() -> {
            Document document = editor.getDocument();
            
            int caretOffset = editor.getCaretModel().getOffset();
            int caretLine = document.getLineNumber(caretOffset);
            
            int startLine = Math.max(0, caretLine - lineCount);
            int endLine = Math.min(document.getLineCount() - 1, caretLine + lineCount);
            
            int startOffset = document.getLineStartOffset(startLine);
            int endOffset = document.getLineEndOffset(endLine);
            
            return document.getText(new TextRange(startOffset, endOffset));
        });
    }
    
    /**
     * Gets the current editor instance.
     */
    public Editor getCurrentEditor() {
        return FileEditorManager.getInstance(project).getSelectedTextEditor();
    }
    
    /**
     * Disposes of any resources.
     */
    public void dispose() {
        // Currently no resources to dispose
    }
}
