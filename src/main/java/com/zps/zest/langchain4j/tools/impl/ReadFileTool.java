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
            "Read the contents of a file with optional targeted reading. " +
            "Supports multiple path formats: " +
            "- Relative to project: 'src/main/java/User.java' " +
            "- Just filename: 'User.java' (searches project, returns list if multiple found) " +
            "- Absolute path: '/home/user/project/src/User.java' " +
            "Optional targeted reading: " +
            "- startBoundary: Text pattern to find in file (e.g., 'public void processData'). Reading centers around this line. " +
            "- linesBefore: Number of lines to include BEFORE the boundary (default 0). Shows context like annotations/comments. " +
            "- linesAfter: Number of lines to include AFTER the boundary (default: to end of file). Shows method body/block. " +
            "Examples: " +
            "- read_file({\"filePath\": \"User.java\"}) - read entire file " +
            "- read_file({\"filePath\": \"User.java\", \"startBoundary\": \"public void getName\", \"linesBefore\": 3, \"linesAfter\": 20}) - read method with context " +
            "Params: filePath (string, required), startBoundary (string, optional), linesBefore (number, optional), linesAfter (number, optional)");
    }
    
    @Override
    public JsonObject getParameterSchema() {
        JsonObject schema = new JsonObject();
        JsonObject properties = new JsonObject();

        JsonObject filePath = new JsonObject();
        filePath.addProperty("type", "string");
        filePath.addProperty("description", "Path to the file (absolute, relative to project root, relative to source roots, or just filename)");
        properties.add("filePath", filePath);

        JsonObject startBoundary = new JsonObject();
        startBoundary.addProperty("type", "string");
        startBoundary.addProperty("description", "Optional text pattern to find in file. Reading centers around the line containing this text.");
        properties.add("startBoundary", startBoundary);

        JsonObject linesBefore = new JsonObject();
        linesBefore.addProperty("type", "integer");
        linesBefore.addProperty("description", "Optional number of lines to include BEFORE the boundary line (default 0)");
        properties.add("linesBefore", linesBefore);

        JsonObject linesAfter = new JsonObject();
        linesAfter.addProperty("type", "integer");
        linesAfter.addProperty("description", "Optional number of lines to include AFTER the boundary line (default: to end of file)");
        properties.add("linesAfter", linesAfter);

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
        String startBoundary = getOptionalString(parameters, "startBoundary", null);
        int linesBefore = getOptionalInt(parameters, "linesBefore", 0);
        int linesAfter = getOptionalInt(parameters, "linesAfter", -1); // -1 means read to end

        try {
            // First check if it's just a filename and might have multiple matches
            String fileName = new File(filePath).getName();
            if (filePath.equals(fileName) && !filePath.contains("/")) {
                // User provided just a filename, check for multiple matches
                PsiFile[] files = FilenameIndex.getFilesByName(project, fileName, GlobalSearchScope.projectScope(project));

                if (files.length == 0) {
                    return ToolResult.error("No files found with name: " + fileName);
                }

                if (files.length > 1) {
                    // Multiple matches - show them all
                    StringBuilder allMatches = new StringBuilder();
                    allMatches.append("Multiple files found with name '").append(fileName).append("':\n\n");

                    for (int i = 0; i < files.length; i++) {
                        VirtualFile vFile = files[i].getVirtualFile();
                        if (vFile != null) {
                            allMatches.append(i + 1).append(". ").append(vFile.getPath()).append("\n");
                        }
                    }

                    allMatches.append("\nPlease use a more specific path to read the exact file you want.");
                    allMatches.append("\nExample: read_file({\"filePath\": \"").append(files[0].getVirtualFile().getPath()).append("\"})");

                    JsonObject metadata = createMetadata();
                    metadata.addProperty("multipleMatches", true);
                    metadata.addProperty("matchCount", files.length);

                    return ToolResult.error(allMatches.toString());
                }
            }

            // Normal file finding logic
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
            String[] lines = content.split("\n");

            // Create metadata
            JsonObject metadata = createMetadata();
            metadata.addProperty("filePath", file.getPath());
            metadata.addProperty("fileName", file.getName());
            metadata.addProperty("fileSize", file.getLength());
            metadata.addProperty("lastModified", file.getTimeStamp());
            metadata.addProperty("totalLines", lines.length);

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

            // Handle targeted reading if startBoundary provided
            if (startBoundary != null && !startBoundary.trim().isEmpty()) {
                // Find all lines containing the boundary
                java.util.List<Integer> matchingLines = new java.util.ArrayList<>();
                for (int i = 0; i < lines.length; i++) {
                    if (lines[i].contains(startBoundary)) {
                        matchingLines.add(i);
                    }
                }

                if (matchingLines.isEmpty()) {
                    return ToolResult.error("Start boundary not found: \"" + startBoundary + "\"");
                }

                if (matchingLines.size() > 1) {
                    StringBuilder error = new StringBuilder();
                    error.append("Start boundary \"").append(startBoundary).append("\" appears ").append(matchingLines.size()).append(" times in file:\n\n");
                    for (Integer lineNum : matchingLines) {
                        error.append("  Line ").append(lineNum + 1).append(": ").append(lines[lineNum].trim()).append("\n");
                    }
                    error.append("\nPlease use a more specific boundary text that appears only once.");
                    return ToolResult.error(error.toString());
                }

                int boundaryLine = matchingLines.get(0);
                int startLine = Math.max(0, boundaryLine - linesBefore);
                int endLine = linesAfter == -1 ? lines.length - 1 : Math.min(lines.length - 1, boundaryLine + linesAfter);

                metadata.addProperty("boundaryLine", boundaryLine + 1);
                metadata.addProperty("startLine", startLine + 1);
                metadata.addProperty("endLine", endLine + 1);
                metadata.addProperty("linesRead", endLine - startLine + 1);

                result.append("Total lines in file: ").append(lines.length).append("\n");
                result.append("Boundary found at line: ").append(boundaryLine + 1).append("\n");
                result.append("Reading lines ").append(startLine + 1).append("-").append(endLine + 1).append(" (").append(endLine - startLine + 1).append(" lines)\n");
                result.append("\n```").append(getFileExtension(file.getName())).append("\n");

                for (int i = startLine; i <= endLine; i++) {
                    result.append(String.format("%5d  %s\n", i + 1, lines[i]));
                }
                result.append("```");
            } else {
                // Read entire file (original behavior)
                result.append("Total lines: ").append(lines.length).append("\n");
                result.append("\n```").append(getFileExtension(file.getName())).append("\n");
                result.append(content);
                result.append("\n```");
            }

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
