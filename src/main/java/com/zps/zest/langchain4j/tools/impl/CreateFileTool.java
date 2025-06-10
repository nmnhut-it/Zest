package com.zps.zest.langchain4j.tools.impl;

import com.google.gson.JsonArray;
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
import com.zps.zest.langchain4j.tools.ThreadSafeCodeExplorationTool;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Tool for creating new files with diff preview.
 */
public class CreateFileTool extends ThreadSafeCodeExplorationTool {
    private static final Logger LOG = Logger.getInstance(CreateFileTool.class);

    public CreateFileTool(@NotNull Project project) {
        super(project, "create_file",
                "Creates a new file with the specified path and content. Shows diff before applying changes. " +
                "Will create parent directories if they don't exist (only within project root). " +
                "Examples: " +
                "- create_file({\"filePath\": \"src/main/java/User.java\", \"content\": \"public class User {...}\"}) " +
                "- create_file({\"filePath\": \"src/main/java/com/example/User.java\", \"content\": \"...\"}) - creates com/example dirs if needed " +
                "Params: filePath (string, required), content (string, required)");
    }

    @Override
    public JsonObject getParameterSchema() {
        JsonObject schema = new JsonObject();
        JsonObject properties = new JsonObject();

        JsonObject filePath = new JsonObject();
        filePath.addProperty("type", "string");
        filePath.addProperty("description", "Path where the file should be created (relative to project root or absolute)");
        properties.add("filePath", filePath);

        JsonObject content = new JsonObject();
        content.addProperty("type", "string");
        content.addProperty("description", "Content to write to the file");
        properties.add("content", content);

        schema.add("properties", properties);
        JsonArray required = new JsonArray();
        required.add("filePath");
        required.add("content");
        schema.add("required", required);

        return schema;
    }

    @Override
    protected boolean requiresReadAction() {
        return false; // File creation doesn't require read action
    }

    @Override
    protected ToolResult doExecuteInReadAction(JsonObject parameters) {
        String filePath = getRequiredString(parameters, "filePath");
        String content = getRequiredString(parameters, "content");

        return createFileWithDiff(filePath, content);
    }

    private ToolResult createFileWithDiff(String filePath, String content) {
        try {
            // Handle relative or absolute path
            String basePath = project.getBasePath();
            if (basePath == null) {
                return ToolResult.error("No base directory found for the project.");
            }

            Path fullPath;
            Path projectBasePath = Paths.get(basePath);
            
            if (new File(filePath).isAbsolute()) {
                fullPath = Paths.get(filePath);
                // Check if the absolute path is under project root
                if (!fullPath.startsWith(projectBasePath)) {
                    return ToolResult.error("Cannot create file outside of project directory: " + filePath);
                }
            } else {
                fullPath = Paths.get(basePath, filePath);
            }

            // Ensure parent directories exist (only if under project root)
            File parentDir = fullPath.getParent().toFile();
            if (!parentDir.exists()) {
                // Verify the parent directory would be under project root
                if (!fullPath.getParent().startsWith(projectBasePath)) {
                    return ToolResult.error("Parent directory would be outside project root: " + fullPath.getParent());
                }
                
                if (!parentDir.mkdirs()) {
                    return ToolResult.error("Failed to create parent directories for: " + filePath);
                }
                LOG.info("Created parent directories: " + parentDir.getAbsolutePath());
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
                return ToolResult.error("Operation cancelled by user.");
            }

            // Apply changes
            return WriteCommandAction.runWriteCommandAction(project, (Computable<ToolResult>) () -> {
                try {
                    String fullPathStr = fullPath.toString();
                    VirtualFile parentVFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(parentDir.getAbsolutePath());

                    if (parentVFile == null) {
                        return ToolResult.error("Failed to find parent directory in VFS: " + parentDir.getAbsolutePath());
                    }

                    // Check if file exists
                    String fileName = fullPath.getFileName().toString();
                    VirtualFile existingFile = parentVFile.findChild(fileName);

                    JsonObject metadata = createMetadata();
                    metadata.addProperty("filePath", filePath);
                    metadata.addProperty("fileSize", content.length());

                    if (existingFile != null) {
                        // Update existing file
                        existingFile.setBinaryContent(content.getBytes(StandardCharsets.UTF_8));

                        // Open the file in editor
                        ApplicationManager.getApplication().invokeLater(() -> {
                            FileEditorManager.getInstance(project).openFile(existingFile, true);
                        });

                        metadata.addProperty("action", "updated");
                        return ToolResult.success("File updated successfully: " + filePath, metadata);
                    } else {
                        // Create new file
                        VirtualFile newFile = parentVFile.createChildData(this, fileName);
                        newFile.setBinaryContent(content.getBytes(StandardCharsets.UTF_8));

                        // Open the file in editor
                        ApplicationManager.getApplication().invokeLater(() -> {
                            FileEditorManager.getInstance(project).openFile(newFile, true);
                        });

                        metadata.addProperty("action", "created");
                        return ToolResult.success("File created successfully: " + filePath, metadata);
                    }
                } catch (Exception e) {
                    LOG.error("Error creating/updating file: " + filePath, e);
                    return ToolResult.error("Error creating/updating file: " + e.getMessage());
                }
            });
        } catch (Exception e) {
            LOG.error("Error processing file creation request", e);
            return ToolResult.error("Error processing file creation request: " + e.getMessage());
        }
    }
}
