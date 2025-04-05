package com.zps.zest.tools;

import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;

public class CreateFileTool extends BaseAgentTool {
    private static final Logger LOG = Logger.getInstance(CreateFileTool.class);
    private final Project project;

    public CreateFileTool(Project project) {
        super("create_file", "Creates a new file with the specified path and content");
        this.project = project;
    }

    @Override
    protected String doExecute(JsonObject params) throws Exception {
        String filePath = getStringParam(params, "filePath", null);
        String content = getStringParam(params, "content", null);

        if (filePath == null || filePath.isEmpty()) {
            return "Error: File path is required";
        }
        if (content == null) {
            return "Error: File content is required";
        }

        return createFile(filePath, content);
    }

    @Override
    public JsonObject getExampleParams() {
        JsonObject params = new JsonObject();
        params.addProperty("filePath", "path/to/file.java");
        params.addProperty("content", "file content here");
        return params;
    }

    private String createFile(String filePath, String content) {
        try {
            // Make sure we have both path and content
            String finalFilePath = filePath;
            return WriteCommandAction.runWriteCommandAction(project, (Computable<String>) () -> {
                // Get project base path
                String basePath = project.getBasePath();
                if (basePath == null) {
                    return "No base directory found for the project.";
                }
                // Handle relative or absolute path
                Path fullPath;
                if (new File(finalFilePath).isAbsolute()) {
                    fullPath = Paths.get(finalFilePath);
                } else {
                    fullPath = Paths.get(basePath, finalFilePath);
                }
                // Ensure parent directories exist
                File parentDir = fullPath.getParent().toFile();
                if (!parentDir.exists() && !parentDir.mkdirs()) {
                    return "Failed to create parent directories for: " + finalFilePath;
                }
                // Create or update the file in VFS
                String fullPathStr = fullPath.toString();
                VirtualFile parentVFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(parentDir.getAbsolutePath());
                if (parentVFile == null) {
                    return "Failed to find parent directory in VFS: " + parentDir.getAbsolutePath();
                }
                try {
                    // Check if file exists
                    String fileName = fullPath.getFileName().toString();
                    VirtualFile existingFile = parentVFile.findChild(fileName);
                    if (existingFile != null) {
                        // Update existing file
                        existingFile.setBinaryContent(content.getBytes(StandardCharsets.UTF_8));
                        return "File updated successfully: " + finalFilePath;
                    } else {
                        // Create new file
                        VirtualFile newFile = parentVFile.createChildData(this, fileName);
                        newFile.setBinaryContent(content.getBytes(StandardCharsets.UTF_8));
                        // Open the file in editor (optional)
                        ApplicationManager.getApplication().invokeLater(() -> {
                            FileEditorManager.getInstance(project).openFile(newFile, true);
                        });
                        return "File created successfully: " + finalFilePath;
                    }
                } catch (Exception e) {
                    LOG.error("Error creating/updating file: " + finalFilePath, e);
                    return "Error creating/updating file: " + e.getMessage();
                }
            });
        } catch (Exception e) {
            LOG.error("Error processing file creation request", e);
            return "Error processing file creation request: " + e.getMessage();
        }
    }
}