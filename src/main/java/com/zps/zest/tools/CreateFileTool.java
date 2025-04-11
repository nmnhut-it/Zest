package com.zps.zest.tools;

import com.google.gson.JsonObject;
import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.contents.DocumentContent;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.zps.zest.EnhancedTodoDiffComponent;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class CreateFileTool extends BaseAgentTool {
    private static final Logger LOG = Logger.getInstance(CreateFileTool.class);
    private final Project project;

    public CreateFileTool(Project project) {
        super("create_file", "Creates a new file with the specified path and content. Shows diff before applying changes.");
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

        return createFileWithDiff(filePath, content);
    }

    @Override
    public JsonObject getExampleParams() {
        JsonObject params = new JsonObject();
        params.addProperty("filePath", "path/to/file.java");
        params.addProperty("content", "file content here");
        return params;
    }

    private String createFileWithDiff(String filePath, String content) {
        try {
            // Handle relative or absolute path
            String basePath = project.getBasePath();
            if (basePath == null) {
                return "No base directory found for the project.";
            }

            Path fullPath;
            if (new File(filePath).isAbsolute()) {
                fullPath = Paths.get(filePath);
            } else {
                fullPath = Paths.get(basePath, filePath);
            }

            // Ensure parent directories exist
            File parentDir = fullPath.getParent().toFile();
            if (!parentDir.exists() && !parentDir.mkdirs()) {
                return "Failed to create parent directories for: " + filePath;
            }

            // Check if file exists and get current content
            File targetFile = fullPath.toFile();
            String originalContent = "";
            boolean fileExists = targetFile.exists();

            if (fileExists) {
                try {
                    originalContent = Files.readString(fullPath);
                } catch (Exception e) {
                    LOG.warn("Could not read existing file content: " + e.getMessage(), e);
                    originalContent = ""; // Fall back to empty string if can't read
                }
            }

            // Create diff contents
            DiffContentFactory diffFactory = DiffContentFactory.getInstance();
            DocumentContent leftContent = diffFactory.create(fileExists ? originalContent : "");
            DocumentContent rightContent = diffFactory.create(content);

            // Create diff title
            String diffTitle = fileExists ?
                    "Update File: " + filePath :
                    "Create New File: " + filePath;

            // Create diff labels
            String leftLabel = fileExists ? "Existing File" : "Empty File (Will Be Created)";
            String rightLabel = "New Content";

            // Create diff request
            SimpleDiffRequest diffRequest = new SimpleDiffRequest(
                    diffTitle,
                    leftContent,
                    rightContent,
                    leftLabel,
                    rightLabel);

            // Show diff and get user confirmation
            boolean[] applyChanges = new boolean[1]; // Using array to modify in lambda
            EnhancedTodoDiffComponent diffComponent = new EnhancedTodoDiffComponent(
                    project,
                    null, // No editor needed for this use case
                    fileExists ? originalContent : "",
                    content,
                    0, // No selection needed
                    0);

            // Show diff and wait for user decision
            diffComponent.showDiff();

            // Ask user for confirmation
            int option = Messages.showYesNoDialog(
                    project,
                    "Do you want to apply the changes to " + filePath + "?",
                    "Confirm File Changes",
                    "Apply Changes",
                    "Cancel",
                    null);

            applyChanges[0] = (option == Messages.YES);

            if (!applyChanges[0]) {
                return "Operation cancelled by user.";
            }

            // Apply changes
            return WriteCommandAction.runWriteCommandAction(project, (Computable<String>) () -> {
                try {
                    String fullPathStr = fullPath.toString();
                    VirtualFile parentVFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(parentDir.getAbsolutePath());

                    if (parentVFile == null) {
                        return "Failed to find parent directory in VFS: " + parentDir.getAbsolutePath();
                    }

                    // Check if file exists
                    String fileName = fullPath.getFileName().toString();
                    VirtualFile existingFile = parentVFile.findChild(fileName);

                    if (existingFile != null) {
                        // Update existing file
                        existingFile.setBinaryContent(content.getBytes(StandardCharsets.UTF_8));

                        // Open the file in editor
                        ApplicationManager.getApplication().invokeLater(() -> {
                            FileEditorManager.getInstance(project).openFile(existingFile, true);
                        });

                        return "File updated successfully: " + filePath;
                    } else {
                        // Create new file
                        VirtualFile newFile = parentVFile.createChildData(this, fileName);
                        newFile.setBinaryContent(content.getBytes(StandardCharsets.UTF_8));

                        // Open the file in editor
                        ApplicationManager.getApplication().invokeLater(() -> {
                            FileEditorManager.getInstance(project).openFile(newFile, true);
                        });

                        return "File created successfully: " + filePath;
                    }
                } catch (Exception e) {
                    LOG.error("Error creating/updating file: " + filePath, e);
                    return "Error creating/updating file: " + e.getMessage();
                }
            });
        } catch (Exception e) {
            LOG.error("Error processing file creation request", e);
            return "Error processing file creation request: " + e.getMessage();
        }
    }
}