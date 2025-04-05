package com.zps.zest.tools;

import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.VirtualFile;
import com.zps.zest.CodeProblemsAnalyzer;

public class QuickAnalyzeCurrentFileTool extends BaseAgentTool {
    private static final Logger LOG = Logger.getInstance(QuickAnalyzeCurrentFileTool.class);
    private final Project project;

    public QuickAnalyzeCurrentFileTool(Project project) {
        super("quick_analyze_current_file", "Quick analyze of the current file for code problems");
        this.project = project;
    }

    @Override
    protected String doExecute(JsonObject params) throws Exception {
        return quickAnalyzeCurrentFile();
    }

    @Override
    public JsonObject getExampleParams() {
        return new JsonObject(); // No parameters required
    }

    private String quickAnalyzeCurrentFile() {
        try {
            // Get current editor
            Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
            if (editor == null) {
                return "No file is currently open.";
            }
            // Get current file path
            VirtualFile file = FileDocumentManager.getInstance().getFile(editor.getDocument());
            if (file == null) {
                return "Cannot determine current file.";
            }
            String filePath = file.getPath();
            // Create analyzer
            CodeProblemsAnalyzer analyzer = new CodeProblemsAnalyzer(project);
            // Analyze current file
            return analyzer.analyzeCode("current_file", filePath);
        } catch (Exception e) {
            return "Error analyzing current file: " + e.getMessage();
        }
    }
}