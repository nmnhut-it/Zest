package com.zps.zest.langchain4j.tools.impl;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.zps.zest.langchain4j.tools.ThreadSafeCodeExplorationTool;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.nio.charset.StandardCharsets;

/**
 * Tool for reading file contents.
 */
public class ReadFileTool extends ThreadSafeCodeExplorationTool {
    
    public ReadFileTool(@NotNull Project project) {
        super(project, "read_file", 
            "Read the contents of a file. " +
            "Example: read_file({\"filePath\": \"src/main/java/User.java\"}) - returns the full content of User.java. " +
            "Supports: absolute paths, paths relative to project root, paths relative to source roots, and filename search. " +
            "Params: filePath (string, required)");
    }
    
    @Override
    public JsonObject getParameterSchema() {
        JsonObject schema = new JsonObject();
        JsonObject properties = new JsonObject();
        
        JsonObject filePath = new JsonObject();
        filePath.addProperty("type", "string");
        filePath.addProperty("description", "Path to the file (absolute, relative to project root, relative to source roots, or just filename)");
        properties.add("filePath", filePath);
        
        schema.add("properties", properties);
        JsonArray required = new JsonArray();
        required.add("filePath");
        schema.add("required", required);
        
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
                return ToolResult.error("File not found: " + filePath + 
                    ". Tried searching in: project root, source roots, and by filename index.");
            }
            
            if (file.isDirectory()) {
                return ToolResult.error("Path is a directory, not a file: " + filePath + 
                    ". Use list_files tool to see directory contents.");
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
        // Normalize path
        filePath = filePath.replace('\\', '/');
        
        // Try as absolute path first
        VirtualFile file = VirtualFileManager.getInstance().findFileByUrl("file://" + filePath);
        if (file != null && file.exists()) {
            return file;
        }
        
        // Try relative to project base path
        VirtualFile baseDir = project.getBaseDir();
        if (baseDir != null) {
            // Remove leading slash for relative paths
            if (filePath.startsWith("/")) {
                filePath = filePath.substring(1);
            }
            
            file = baseDir.findFileByRelativePath(filePath);
            if (file != null && file.exists()) {
                return file;
            }
        }
        
        // Try as relative path from source roots
        for (VirtualFile sourceRoot : ProjectRootManager.getInstance(project).getContentSourceRoots()) {
            file = sourceRoot.findFileByRelativePath(filePath);
            if (file != null && file.exists()) {
                return file;
            }
        }
        
        // If still not found, try to find by filename using index
        String fileName = new File(filePath).getName();
        PsiFile[] files = FilenameIndex.getFilesByName(project, fileName, GlobalSearchScope.projectScope(project));
        
        if (files.length == 0) {
            return null;
        }
        
        // If multiple files with the same name exist, try to find the best match
        if (files.length > 1 && filePath.contains("/")) {
            for (PsiFile psiFile : files) {
                VirtualFile vFile = psiFile.getVirtualFile();
                if (vFile != null) {
                    String fullPath = vFile.getPath();
                    if (fullPath.endsWith(filePath) || fullPath.contains(filePath)) {
                        return vFile;
                    }
                }
            }
        }
        
        // Return first matching file
        return files[0].getVirtualFile();
    }
    
    private String getFileExtension(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot > 0 && lastDot < fileName.length() - 1) {
            return fileName.substring(lastDot + 1).toLowerCase();
        }
        return "";
    }
}
