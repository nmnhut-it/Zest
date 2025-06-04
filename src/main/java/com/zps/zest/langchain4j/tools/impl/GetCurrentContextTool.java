package com.zps.zest.langchain4j.tools.impl;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.zps.zest.langchain4j.tools.BaseCodeExplorationTool;
import org.jetbrains.annotations.NotNull;

/**
 * Tool for getting information about the current context (open file, cursor position, etc).
 */
public class GetCurrentContextTool extends BaseCodeExplorationTool {
    
    public GetCurrentContextTool(@NotNull Project project) {
        super(project, "get_current_context", 
            "Get information about the current IDE context (open file, selected element, cursor position). " +
            "NO PARAMETERS REQUIRED - this tool reads the current IDE state. " +
            "Example: get_current_context() or get_current_context({}) - both work. " +
            "Returns: current file path, cursor position, element at cursor, and list of open files.");
    }
    
    @Override
    public JsonObject getParameterSchema() {
        JsonObject schema = new JsonObject();
        schema.add("properties", new JsonObject());
        schema.add("required", new JsonArray());
        return schema;
    }
    
    @Override
    protected ToolResult doExecute(JsonObject parameters) {
        // No parameters needed, ignore any provided
        try {
            FileEditorManager editorManager = FileEditorManager.getInstance(project);
            VirtualFile[] openFiles = editorManager.getOpenFiles();
            
            StringBuilder content = new StringBuilder();
            JsonObject metadata = createMetadata();
            
            content.append("# Current Context\n\n");
            
            if (openFiles.length == 0) {
                content.append("No files are currently open.\n");
                return ToolResult.success(content.toString(), metadata);
            }
            
            // Get current file
            VirtualFile currentFile = editorManager.getSelectedFiles()[0];
            Editor editor = editorManager.getSelectedTextEditor();
            
            content.append("## Current File\n");
            content.append("**Path:** `").append(currentFile.getPath()).append("`\n");
            content.append("**Name:** ").append(currentFile.getName()).append("\n");
            
            metadata.addProperty("currentFile", currentFile.getPath());
            metadata.addProperty("openFileCount", openFiles.length);
            
            // Get PSI information
            PsiFile psiFile = PsiManager.getInstance(project).findFile(currentFile);
            if (psiFile != null) {
                content.append("**Language:** ").append(psiFile.getLanguage().getDisplayName()).append("\n");
                content.append("**File Type:** ").append(psiFile.getFileType().getName()).append("\n");
                
                if (psiFile instanceof PsiJavaFile) {
                    PsiJavaFile javaFile = (PsiJavaFile) psiFile;
                    content.append("**Package:** `").append(javaFile.getPackageName()).append("`\n");
                    
                    PsiClass[] classes = javaFile.getClasses();
                    if (classes.length > 0) {
                        content.append("\n### Classes in File\n");
                        for (PsiClass psiClass : classes) {
                            content.append("- ").append(psiClass.getQualifiedName()).append("\n");
                        }
                    }
                }
            }
            
            // Get cursor position and element at cursor
            if (editor != null && psiFile != null) {
                int offset = editor.getCaretModel().getOffset();
                PsiElement elementAtCaret = psiFile.findElementAt(offset);
                
                content.append("\n## Cursor Position\n");
                content.append("**Line:** ").append(editor.getCaretModel().getLogicalPosition().line + 1).append("\n");
                content.append("**Column:** ").append(editor.getCaretModel().getLogicalPosition().column + 1).append("\n");
                
                if (elementAtCaret != null) {
                    content.append("\n## Element at Cursor\n");
                    PsiElement meaningfulElement = findMeaningfulElement(elementAtCaret);
                    
                    if (meaningfulElement instanceof PsiMethod) {
                        PsiMethod method = (PsiMethod) meaningfulElement;
                        content.append("**Type:** Method\n");
                        content.append("**Name:** `").append(method.getName()).append("`\n");
                        content.append("**Signature:** `").append(getMethodSignature(method)).append("`\n");
                    } else if (meaningfulElement instanceof PsiClass) {
                        PsiClass psiClass = (PsiClass) meaningfulElement;
                        content.append("**Type:** ").append(psiClass.isInterface() ? "Interface" : "Class").append("\n");
                        content.append("**Name:** `").append(psiClass.getQualifiedName()).append("`\n");
                    } else if (meaningfulElement instanceof PsiField) {
                        PsiField field = (PsiField) meaningfulElement;
                        content.append("**Type:** Field\n");
                        content.append("**Name:** `").append(field.getName()).append("`\n");
                        content.append("**Field Type:** `").append(field.getType().getPresentableText()).append("`\n");
                    } else if (meaningfulElement instanceof PsiVariable) {
                        PsiVariable variable = (PsiVariable) meaningfulElement;
                        content.append("**Type:** Variable\n");
                        content.append("**Name:** `").append(variable.getName()).append("`\n");
                        content.append("**Variable Type:** `").append(variable.getType().getPresentableText()).append("`\n");
                    }
                }
            }
            
            // List other open files
            if (openFiles.length > 1) {
                content.append("\n## Other Open Files\n");
                for (VirtualFile file : openFiles) {
                    if (!file.equals(currentFile)) {
                        content.append("- ").append(file.getName()).append(" (`").append(file.getPath()).append("`)\n");
                    }
                }
            }
            
            return ToolResult.success(content.toString(), metadata);
            
        } catch (Exception e) {
            return ToolResult.error("Failed to get current context: " + e.getMessage());
        }
    }
    
    private PsiElement findMeaningfulElement(PsiElement element) {
        PsiElement current = element;
        while (current != null) {
            if (current instanceof PsiMethod || 
                current instanceof PsiClass || 
                current instanceof PsiField ||
                current instanceof PsiVariable) {
                return current;
            }
            current = current.getParent();
        }
        return element;
    }
    
    private String getMethodSignature(PsiMethod method) {
        StringBuilder sb = new StringBuilder();
        sb.append(method.getName()).append("(");
        
        PsiParameter[] parameters = method.getParameterList().getParameters();
        for (int i = 0; i < parameters.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(parameters[i].getType().getPresentableText());
        }
        sb.append(")");
        
        PsiType returnType = method.getReturnType();
        if (returnType != null) {
            sb.append(": ").append(returnType.getPresentableText());
        }
        
        return sb.toString();
    }
}
