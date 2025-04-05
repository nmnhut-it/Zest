package com.zps.zest.tools;

import com.google.gson.JsonObject;
import com.intellij.ide.actions.searcheverywhere.SearchAdapter;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiUtilBase;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SearchByRegexTool extends BaseAgentTool {
    private static final Logger LOG = Logger.getInstance(SearchByRegexTool.class);
    private final Project project;

    public SearchByRegexTool(Project project) {
        super("search_by_regex", "Searches for text in files using a regular expression");
        this.project = project;
    }

    @Override
    protected String doExecute(JsonObject params) throws Exception {
        String regex = getStringParam(params, "regex", null);
        String scope = getStringParam(params, "scope", "project");

        if (regex == null || regex.isEmpty()) {
            return "Error: Regex pattern is required";
        }

        return searchByRegex(regex, scope);
    }

    @Override
    public JsonObject getExampleParams() {
        JsonObject params = new JsonObject();
        params.addProperty("regex", ".*example.*");
        params.addProperty("scope", "project"); // Can be "project", "current_file", or a directory path
        return params;
    }

    private String searchByRegex(String regex, String scope) {
        try {
            return ApplicationManager.getApplication().runReadAction((Computable<String>) () -> {
                StringBuilder result = new StringBuilder();
                Pattern pattern = Pattern.compile(regex);
                switch (scope.toLowerCase()) {
                    case "project":
                        result.append("Search results in project for regex '").append(regex).append("':\n");
                        searchInProject(pattern, result);
                        break;
                    case "current_file":
                        result.append("Search results in current file for regex '").append(regex).append("':\n");
                        searchInCurrentFile(pattern, result);
                        break;
                    default:
                        result.append("Search results in directory '").append(scope).append("' for regex '").append(regex).append("':\n");
                        searchInDirectory(pattern, result, scope);
                        break;
                }
                return result.toString();
            });
        } catch (Exception e) {
            LOG.error("Error searching by regex", e);
            return "Error searching by regex: " + e.getMessage();
        }
    }

    private void searchInProject(Pattern pattern, StringBuilder result) {

        Collection<VirtualFile> files = FilenameIndex.getAllFilesByExt(
                project, "*", GlobalSearchScope.projectScope(project));
        searchFiles(pattern, result, files);
    }

    private void searchInCurrentFile(Pattern pattern, StringBuilder result) {
        Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
        if (editor == null) {
            result.append("No active editor\n");
            return;
        }
        PsiFile psiFile = PsiUtilBase.getPsiFileInEditor(editor, project);
        if (psiFile == null) {
            result.append("No file in active editor\n");
            return;
        }
        searchFile(pattern, result, psiFile);
    }

    private void searchInDirectory(Pattern pattern, StringBuilder result, String directoryPath) {
        VirtualFile dir = findFileByPath(directoryPath);
        if (dir == null || !dir.isDirectory()) {
            result.append("Directory not found: ").append(directoryPath).append("\n");
            return;
        }
        List<VirtualFile> files = getFilesInDirectoryRecursively(dir);
        searchFiles(pattern, result, files);
    }

    private void searchFiles(Pattern pattern, StringBuilder result, Collection<VirtualFile> files) {
        for (VirtualFile file : files) {
            PsiFile psiFile = PsiManager.getInstance(project).findFile(file);
            if (psiFile == null) {
                continue;
            }
            searchFile(pattern, result, psiFile);
        }
    }

    private void searchFile(Pattern pattern, StringBuilder result, PsiFile psiFile) {
        String content = psiFile.getText();
        Matcher matcher = pattern.matcher(content);
        if (matcher.find()) {
            int matchCount = 0;
            result.append("File: ").append(psiFile.getVirtualFile().getPath()).append("\n");
            while (matcher.find()) {
                int start = matcher.start();
                int end = matcher.end();
                String matchedText = matcher.group();
                result.append("  Match found at line ").append(getLineNumber(psiFile, start))
                        .append(": ").append(matchedText).append("\n");
                matchCount++;
            }
            result.append("  Total matches: ").append(matchCount).append("\n");
        }
    }

    private int getLineNumber(PsiFile file, int offset) {
        String text = file.getText();
        int line = 1;
        for (int i = 0; i < offset; i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private List<VirtualFile> getFilesInDirectoryRecursively(VirtualFile dir) {
        List<VirtualFile> files = new ArrayList<>();
        getFilesRecursively(dir, files);
        return files;
    }

    private void getFilesRecursively(VirtualFile dir, List<VirtualFile> files) {
        for (VirtualFile child : dir.getChildren()) {
            if (child.isDirectory()) {
                getFilesRecursively(child, files);
            } else {
                files.add(child);
            }
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
}