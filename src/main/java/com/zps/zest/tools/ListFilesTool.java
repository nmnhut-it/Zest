package com.zps.zest.tools;

import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;

import java.io.File;
import java.util.Collection;

public class ListFilesTool extends BaseAgentTool {
    private static final Logger LOG = Logger.getInstance(ListFilesTool.class);
    private final Project project;

    public ListFilesTool(Project project) {
        super("list_files", "Lists all files in a directory or with a specific extension");
        this.project = project;
    }

    @Override
    protected String doExecute(JsonObject params) throws Exception {
        String path = getStringParam(params, "path", null);
        String extension = getStringParam(params, "extension", null);

        if (path == null || path.isEmpty()) {
            path = null;
        }
        if (extension == null || extension.isEmpty()) {
            extension = null;
        }

        if (path == null && extension == null) {
            return "Error: Either 'path' or 'extension' parameter is required";
        }

        if (path != null && extension != null) {
            return "Error: Cannot specify both 'path' and 'extension' parameters at the same time";
        }

        if (path != null) {
            return listFilesInDirectory(path);
        } else {
            return listFilesByExtension(extension);
        }
    }

    @Override
    public JsonObject getExampleParams() {
        JsonObject params1 = new JsonObject();
        params1.addProperty("path", "src/main"); // Directory path
        JsonObject params2 = new JsonObject();
        params2.addProperty("extension", "java"); // File extension
        return params1; // You can return either example, or provide both in documentation
    }

    private String listFilesInDirectory(String path) {
        try {
            return ApplicationManager.getApplication().runReadAction((Computable<String>) () -> {
                StringBuilder result = new StringBuilder();
                VirtualFile dir = findFileByPath(path);
                if (dir == null || !dir.isDirectory()) {
                    return "Directory not found: " + path;
                }
                result.append("Files in directory '").append(path).append("':\n");
                listFilesRecursively(dir, result, 0, 3); // Max depth of 3
                return result.toString();
            });
        } catch (Exception e) {
            LOG.error("Error listing files", e);
            return "Error listing files: " + e.getMessage();
        }
    }

    private String listFilesByExtension(String extension) {
        try {
            return ApplicationManager.getApplication().runReadAction((Computable<String>) () -> {
                StringBuilder result = new StringBuilder();
                Collection<VirtualFile> files = FilenameIndex.getAllFilesByExt(
                        project, extension, GlobalSearchScope.projectScope(project));
                result.append("Files with extension '.").append(extension).append("':\n");
                int count = 0;
                for (VirtualFile file : files) {
                    result.append("- ").append(file.getPath()).append("\n");
                    count++;
                    if (count >= 50) {
                        result.append("... and ").append(files.size() - 50).append(" more files\n");
                        break;
                    }
                }
                result.append("\nTotal: ").append(files.size()).append(" files\n");
                return result.toString();
            });
        } catch (Exception e) {
            LOG.error("Error listing files", e);
            return "Error listing files: " + e.getMessage();
        }
    }

    private VirtualFile findFileByPath(String filePath) {
        // Check if it's an absolute path
        File file = new File(filePath);
        if (file.exists() && file.isAbsolute()) {
            return LocalFileSystem.getInstance().findFileByPath(filePath);
        }
        // Try as a relative path from project root
        String projectBasePath = project.getBasePath();
        if (projectBasePath != null) {
            String absolutePath = projectBasePath + "/" + filePath;
            VirtualFile vFile = LocalFileSystem.getInstance().findFileByPath(absolutePath);
            if (vFile != null && vFile.exists()) {
                return vFile;
            }
        }
        // Try as a relative path from source roots
        for (VirtualFile sourceRoot : ProjectRootManager.getInstance(project).getContentSourceRoots()) {
            String rootPath = sourceRoot.getPath();
            String absolutePath = rootPath + "/" + filePath;
            VirtualFile vFile = LocalFileSystem.getInstance().findFileByPath(absolutePath);
            if (vFile != null && vFile.exists()) {
                return vFile;
            }
        }
        return null;
    }

    private void listFilesRecursively(VirtualFile dir, StringBuilder result, int depth, int maxDepth) {
        if (depth > maxDepth) {
            return;
        }
        String indent = "  ".repeat(depth);
        for (VirtualFile child : dir.getChildren()) {
            result.append(indent).append("- ").append(child.getName());
            if (child.isDirectory()) {
                result.append("/");
            }
            result.append("\n");
            if (child.isDirectory()) {
                listFilesRecursively(child, result, depth + 1, maxDepth);
            }
        }
    }
}