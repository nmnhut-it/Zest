package com.zps.zest.tools;

import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;

import java.io.File;

public class ReadFileTool extends BaseAgentTool {
    private static final Logger LOG = Logger.getInstance(ReadFileTool.class);
    private final Project project;

    public ReadFileTool(Project project) {
        super("read_file", "Reads the content of a file");
        this.project = project;
    }

    @Override
    protected String doExecute(JsonObject params) throws Exception {
        String filePath = getStringParam(params, "path", "");
        if (filePath.isEmpty()) {
            return "Error: File path is required";
        }
        return "```java\n"+readFile(filePath)+"\n```\n";
    }

    @Override
    public JsonObject getExampleParams() {
        JsonObject params = new JsonObject();
        params.addProperty("path", "path/to/file.java");
        return params;
    }

    private String readFile(String filePath) {
        try {
            return ApplicationManager.getApplication().runReadAction((Computable<String>) () -> {
                // First try to find by exact path (supporting both absolute and relative paths)
                VirtualFile fileByPath = findFileByPath(filePath);
                if (fileByPath != null) {
                    PsiFile psiFile = PsiManager.getInstance(project).findFile(fileByPath);
                    if (psiFile != null) {
                        return psiFile.getText();
                    }
                }
                // If not found by path, try to find by filename
                String fileName = new File(filePath).getName();
                PsiFile[] files = FilenameIndex.getFilesByName(project, fileName, GlobalSearchScope.projectScope(project));
                if (files.length == 0) {
                    return "File not found: " + filePath;
                }
                // If multiple files with the same name exist, try to find the best match
                if (files.length > 1 && filePath.contains("/")) {
                    for (PsiFile file : files) {
                        String fullPath = file.getVirtualFile().getPath();
                        if (fullPath.endsWith(filePath) || fullPath.contains(filePath)) {
                            return file.getText();
                        }
                    }
                }
                // Return first matching file
                return files[0].getText();
            });
        } catch (Exception e) {
            LOG.error("Error reading file: " + filePath, e);
            return "Error reading file: " + e.getMessage();
        }
    }

    private VirtualFile findFileByPath(String filePath) {
        // Check if it's an absolute path
        File file = new File(filePath);
        if (file.isAbsolute()) {
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
}