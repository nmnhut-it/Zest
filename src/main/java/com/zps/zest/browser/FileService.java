package com.zps.zest.browser;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.zps.zest.completion.diff.FileDiffDialog;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.zps.zest.langchain4j.tools.impl.ReplaceInFileTool;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Service for handling file operations from JavaScript bridge.
 * This includes file replacement operations and batch processing.
 * PRESERVES ORIGINAL IMPLEMENTATIONS from JavaScriptBridge.
 */
public class FileService {
    private static final Logger LOG = Logger.getInstance(FileService.class);
    
    private final Project project;
    private final Gson gson = new Gson();
    
    public FileService(@NotNull Project project) {
        this.project = project;
    }
    
    /**
     * Handles replace in file requests.
     */
    public String replaceInFile(JsonObject data) {
        try {
            String filePath = data.get("filePath").getAsString();
            String searchText = data.get("search").getAsString();
            String replaceText = data.get("replace").getAsString();
            boolean useRegex = data.has("regex") && data.get("regex").getAsBoolean();
            boolean caseSensitive = !data.has("caseSensitive") || data.get("caseSensitive").getAsBoolean();
            
            // Run async - don't wait for result
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                handleReplaceInFileInternal(filePath, searchText, replaceText, useRegex, caseSensitive);
            });
            
            JsonObject response = new JsonObject();
            response.addProperty("success", true);
            return gson.toJson(response);
        } catch (Exception e) {
            LOG.error("Error handling replace in file", e);
            JsonObject response = new JsonObject();
            response.addProperty("success", false);
            response.addProperty("error", e.getMessage());
            return gson.toJson(response);
        }
    }
    
    /**
     * Handles batch replace in file requests.
     */
    public String batchReplaceInFile(JsonObject data) {
        try {
            String batchFilePath = data.get("filePath").getAsString();
            JsonArray replacements = data.getAsJsonArray("replacements");
            
            // Run async - don't wait for result
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                handleBatchReplaceInFileInternal(batchFilePath, replacements);
            });
            
            JsonObject response = new JsonObject();
            response.addProperty("success", true);
            return gson.toJson(response);
        } catch (Exception e) {
            LOG.error("Error handling batch replace in file", e);
            JsonObject response = new JsonObject();
            response.addProperty("success", false);
            response.addProperty("error", e.getMessage());
            return gson.toJson(response);
        }
    }
    
    /**
     * Handles replace in file requests from JavaScript by delegating to the ReplaceInFileTool
     * This method is now asynchronous and doesn't block the UI.
     * ORIGINAL IMPLEMENTATION from JavaScriptBridge preserved.
     */
    private boolean handleReplaceInFileInternal(String filePath, String searchText, String replaceText, boolean useRegex, boolean caseSensitive) {
        LOG.info("Handling replace in file request for: " + filePath);

        try {
            // Create a JSON object with the parameters for the ReplaceInFileTool
            JsonObject params = new JsonObject();
            params.addProperty("filePath", filePath);
            params.addProperty("search", searchText);
            params.addProperty("replace", replaceText);
            params.addProperty("regex", useRegex);
            params.addProperty("caseSensitive", caseSensitive);

            ReplaceInFileTool tool = new ReplaceInFileTool(project);
            
            // Execute the tool
            ReplaceInFileTool.ToolResult result = tool.execute(params);

            // Show result asynchronously
            ApplicationManager.getApplication().invokeLater(() -> {
                String message = result.isSuccess() ? result.getContent() : result.getError();
                Messages.showInfoMessage("Replace in file result: " + message, "Replace in File Result");
            });

            // Log the result
            String message = result.isSuccess() ? result.getContent() : result.getError();
            LOG.info("Replace in file result: " + message);

            // Return success based on the tool result
            return result.isSuccess();
        } catch (Exception e) {
            LOG.error("Error handling replace in file request", e);
            return false;
        }
    }
    
    /**
     * Handles batch replace in file requests from JavaScript by delegating to the ReplaceInFileTool multiple times
     * This method is now fully asynchronous and doesn't block the UI.
     * ORIGINAL IMPLEMENTATION from JavaScriptBridge preserved.
     */
    private boolean handleBatchReplaceInFileInternal(String filePath, JsonArray replacements) {
        LOG.info("Handling batch replace in file request for: " + filePath + " with " + replacements.size() + " replacements");

        // Always run on background thread
        try {
            // 1. Resolve file
            File targetFile = new File(filePath);
            if (!targetFile.exists()) {
                String basePath = project.getBasePath();
                if (basePath != null)
                    targetFile = new File(basePath, filePath);
            }
            if (!targetFile.exists() || !targetFile.isFile()) {
                LOG.error("File not found: " + filePath);
                return false;
            }
            Path inputPath = targetFile.toPath();

            // 2. Start with original lines/content
            List<String> currentLines = Files.readAllLines(inputPath, StandardCharsets.UTF_8);
            String originalContent = String.join("\n", currentLines);

            int totalReplacementCount = 0;

            for (int i = 0; i < replacements.size(); i++) {
                JsonObject replacement = replacements.get(i).getAsJsonObject();
                String searchText = replacement.get("search").getAsString();
                String replaceText = replacement.get("replace").getAsString();
                boolean caseSensitive = !replacement.has("caseSensitive") || replacement.get("caseSensitive").getAsBoolean();
                boolean ignoreWhitespace = replacement.has("ignoreWhitespace") && replacement.get("ignoreWhitespace").getAsBoolean();
                boolean useRegex = replacement.has("regex") && replacement.get("regex").getAsBoolean();

                if (useRegex) {
                    LOG.warn("Regex mode is not supported in batchReplaceInFile when using performSearchAndReplace. Skipping this replacement.");
                    continue;
                }

                // Write currentLines to a temp file for replace
                Path tempInput = Files.createTempFile("batch_replace_", ".tmp");
                Files.write(tempInput, currentLines, StandardCharsets.UTF_8);

                // Call performSearchAndReplace
                ReplaceInFileTool.ReplaceResult result =
                        ReplaceInFileTool.performSearchAndReplace(
                                tempInput,
                                searchText,
                                replaceText,
                                caseSensitive,
                                ignoreWhitespace,
                                new EmptyProgressIndicator()
                        );
                totalReplacementCount += result.replacementCount;
                currentLines = Arrays.asList(result.modifiedContent.split("\n", -1));

                // Clean up temp file
                Files.deleteIfExists(tempInput);
            }

            String modifiedContent = String.join("\n", currentLines);

            if (originalContent.equals(modifiedContent)) {
                LOG.info("No changes made to file content after applying all replacements");
                return true;
            }

            // Show diff and handle user interaction on EDT
            final File finalTargetFile = targetFile;
            final String finalModifiedContent = modifiedContent;
            final int finalTotalReplacementCount = totalReplacementCount;

            ApplicationManager.getApplication().invokeLater(() -> {
                try {
                    // Use the new FileDiffDialog with Accept/Reject buttons
                    VirtualFile vFile = LocalFileSystem.getInstance().findFileByPath(finalTargetFile.getPath());
                    
                    FileDiffDialog.Companion.show(
                            project,
                            vFile,
                            originalContent,
                            finalModifiedContent,
                            "Batch Changes to " + finalTargetFile.getName() + " (" + finalTotalReplacementCount + " replacements)",
                            new kotlin.jvm.functions.Function0<kotlin.Unit>() {
                                @Override
                                public kotlin.Unit invoke() {
                                    // Accept callback - apply changes
                                    if (vFile == null) {
                                        LOG.error("Could not find file in virtual file system: " + filePath);
                                        return kotlin.Unit.INSTANCE;
                                    }

                                    WriteCommandAction.runWriteCommandAction(project, () -> {
                                        try {
                                            vFile.refresh(false, false);
                                            vFile.setBinaryContent(finalModifiedContent.getBytes(StandardCharsets.UTF_8));
                                            FileEditorManager.getInstance(project).openFile(vFile, true);
                                            LOG.info("Batch changes applied successfully");
                                        } catch (Exception e) {
                                            LOG.error("Error updating tfile: " + filePath, e);
                                        }
                                    });
                                    return kotlin.Unit.INSTANCE;
                                }
                            },
                            new kotlin.jvm.functions.Function0<kotlin.Unit>() {
                                @Override
                                public kotlin.Unit invoke() {
                                    // Reject callback - cancel changes
                                    LOG.info("Batch changes were not applied - discarded by user");
                                    return kotlin.Unit.INSTANCE;
                                }
                            },
                            true  // showButtons = true
                    );
                } catch (Exception e) {
                    LOG.error("Error showing diff dialog", e);
                }
            });

            return true; // Return immediately, don't wait for user interaction
        } catch (Exception e) {
            LOG.error("Error handling batch replace in file request", e);
            return false;
        }
    }
    
    /**
     * Disposes of any resources.
     */
    public void dispose() {
        // Currently no resources to dispose
    }
}
