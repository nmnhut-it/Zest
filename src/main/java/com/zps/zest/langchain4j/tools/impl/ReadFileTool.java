package com.zps.zest.langchain4j.tools.impl;

import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.zps.zest.langchain4j.tools.ThreadSafeCodeExplorationTool;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;

/**
 * Tool for reading file contents.
 */
public class ReadFileTool extends ThreadSafeCodeExplorationTool {
    
    public ReadFileTool(@NotNull Project project) {
        super(project, "read_file", "Read the contents of a file");
    }
    
    @Override
    public JsonObject getParameterSchema() {
        JsonObject schema = new JsonObject();
        JsonObject properties = new JsonObject();
        
        JsonObject filePath = new JsonObject();
        filePath.addProperty("type", "string");
        filePath.addProperty("description", "Path to the file (relative to project root or absolute)");
        properties.add("filePath", filePath);
        
        schema.add("properties", properties);
        schema.addProperty("required", "[\"filePath\"]");
        
        return schema;
    }
    
    @Override
    protected boolean requiresReadAction() {
        // Reading files and PSI access requires read action
        return true;
    }
    
    @Override
    protected ToolResult doExecuteInReadAction(JsonObject parameters) {
        String filePath = getRequiredString(parameters, "filePath");
        
        try {
            VirtualFile file = findFile(filePath);
            if (file == null) {
                return ToolResult.error("File not found: " + filePath);
            }
            
            if (file.isDirectory()) {
                return ToolResult.error("Path is a directory, not a file: " + filePath);
            }
            
            // Read file content
            String content = new String(file.contentsToByteArray(), StandardCharsets.UTF_8);
            
            // Create metadata
            JsonObject metadata = createMetadata();
            metadata.addProperty("filePath", file.getPath());
            metadata.addProperty("fileName", file.getName());
            metadata.addProperty("fileSize", file.getLength());
            metadata.addProperty("lastModified", file.getTimeStamp());
            
            // Get PSI information if available
            PsiFile psiFile = PsiManager.getInstance(project).findFile(file);
            if (psiFile != null) {
                metadata.addProperty("language", psiFile.getLanguage().getDisplayName());
                metadata.addProperty("fileType", psiFile.getFileType().getName());
            }
            
            StringBuilder result = new StringBuilder();
            result.append("File: ").append(file.getPath()).append("\n");
            result.append("Size: ").append(file.getLength()).append(" bytes\n");
            if (psiFile != null) {
                result.append("Language: ").append(psiFile.getLanguage().getDisplayName()).append("\n");
            }
            result.append("\n```").append(getFileExtension(file.getName())).append("\n");
            result.append(content);
            result.append("\n```");
            
            return ToolResult.success(result.toString(), metadata);
            
        } catch (Exception e) {
            return ToolResult.error("Failed to read file: " + e.getMessage());
        }
    }
    
    private VirtualFile findFile(String filePath) {
        // Try as absolute path first
        VirtualFile file = VirtualFileManager.getInstance().findFileByUrl("file://" + filePath);
        if (file != null && file.exists()) {
            return file;
        }
        
        // Try relative to project base path
        VirtualFile baseDir = project.getBaseDir();
        if (baseDir != null) {
            file = baseDir.findFileByRelativePath(filePath);
            if (file != null && file.exists()) {
                return file;
            }
        }
        
        return null;
    }
    
    private String getFileExtension(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot > 0 && lastDot < fileName.length() - 1) {
            return fileName.substring(lastDot + 1).toLowerCase();
        }
        return "";
    }
}
