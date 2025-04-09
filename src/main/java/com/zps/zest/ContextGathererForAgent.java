package com.zps.zest;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Gathers context information from the current editor and project.
 */
public class ContextGathererForAgent {

    private static final int MAX_FILES_PER_ROOT = 50; // Limit number of files to avoid context overload

    /**
     * Gathers context information from the current editor.
     *
     * @param project The current project
     * @param editor The current editor (can be null)
     * @return A map of context information
     */
    public static Map<String, String> gatherCodeContext(Project project, Editor editor) {
        Map<String, String> context = new HashMap<>();

        if (project == null) {
            context.put("hasProject", "false");
            return context;
        }

        context.put("hasProject", "true");
        context.put("projectName", project.getName());

        // Get project roots with file paths
        Map<String, List<String>> sourceRootInfo = findSourceRootWithFiles(project);
        Map<String, List<String>> testRootInfo = findTestRootWithFiles(project);

        if (sourceRootInfo != null && !sourceRootInfo.isEmpty()) {
            for (Map.Entry<String, List<String>> entry : sourceRootInfo.entrySet()) {
                context.put("sourceRoot", entry.getKey());

                // Add file paths in source root (limited to prevent context overflow)
                StringBuilder sourceFiles = new StringBuilder();
                List<String> files = entry.getValue();
                int fileCount = Math.min(files.size(), MAX_FILES_PER_ROOT);

                for (int i = 0; i < fileCount; i++) {
                    sourceFiles.append("- ").append(files.get(i)).append("\n");
                }

                if (files.size() > MAX_FILES_PER_ROOT) {
                    sourceFiles.append("- ... and ").append(files.size() - MAX_FILES_PER_ROOT).append(" more files\n");
                }

                context.put("sourceRootFiles", sourceFiles.toString());
                break; // Just use the first source root for now
            }
        }

        if (testRootInfo != null && !testRootInfo.isEmpty()) {
            for (Map.Entry<String, List<String>> entry : testRootInfo.entrySet()) {
                context.put("testRoot", entry.getKey());

                // Add file paths in test root (limited to prevent context overflow)
                StringBuilder testFiles = new StringBuilder();
                List<String> files = entry.getValue();
                int fileCount = Math.min(files.size(), MAX_FILES_PER_ROOT);

                for (int i = 0; i < fileCount; i++) {
                    testFiles.append("- ").append(files.get(i)).append("\n");
                }

                if (files.size() > MAX_FILES_PER_ROOT) {
                    testFiles.append("- ... and ").append(files.size() - MAX_FILES_PER_ROOT).append(" more files\n");
                }

                context.put("testRootFiles", testFiles.toString());
                break; // Just use the first test root for now
            }
        }

        if (editor == null) {
            context.put("hasEditor", "false");
            return context;
        }

        context.put("hasEditor", "true");

        // Get current file information
        VirtualFile currentFile = FileDocumentManager.getInstance().getFile(editor.getDocument());
        if (currentFile != null) {
            context.put("currentFilePath", currentFile.getPath());
            context.put("currentFileName", currentFile.getName());
            context.put("currentFileExtension", currentFile.getExtension());
            context.put("currentFileContent", editor.getDocument().getText());

            // Add information about files in the same directory
            VirtualFile parentDir = currentFile.getParent();
            if (parentDir != null && parentDir.exists()) {
                context.put("currentFileDirectory", parentDir.getPath());

                StringBuilder siblingFiles = new StringBuilder();
                VirtualFile[] children = parentDir.getChildren();
                int siblingCount = 0;

                for (VirtualFile child : children) {
                    if (!child.isDirectory() && siblingCount < MAX_FILES_PER_ROOT) {
                        siblingFiles.append("- ").append(child.getName()).append("\n");
                        siblingCount++;
                    }
                }

                context.put("siblingFiles", siblingFiles.toString());
            }
        }

        // Add selection information if there is a selection
        if (editor.getSelectionModel().hasSelection()) {
            context.put("hasSelection", "true");
            context.put("selectedText", editor.getSelectionModel().getSelectedText());
            context.put("selectionStart", String.valueOf(editor.getSelectionModel().getSelectionStart()));
            context.put("selectionEnd", String.valueOf(editor.getSelectionModel().getSelectionEnd()));
        } else {
            context.put("hasSelection", "false");

            // Add cursor position
            int offset = editor.getCaretModel().getOffset();
            int lineNumber = editor.getDocument().getLineNumber(offset);
            int column = offset - editor.getDocument().getLineStartOffset(lineNumber);

            context.put("cursorOffset", String.valueOf(offset));
            context.put("cursorLineNumber", String.valueOf(lineNumber + 1));
            context.put("cursorColumn", String.valueOf(column + 1));
        }

        return context;
    }

    /**
     * Finds the source root directory for the project and lists files in it.
     */
    private static Map<String, List<String>> findSourceRootWithFiles(Project project) {
        return ApplicationManager.getApplication().runReadAction((Computable<Map<String, List<String>>>) () -> {
            Map<String, List<String>> sourceRoots = new HashMap<>();

            try {
                VirtualFile[] roots = ProjectRootManager.getInstance(project).getContentSourceRoots();
                for (VirtualFile root : roots) {
                    if (root.getPath().contains("/src/main/java")) {
                        List<String> files = collectFiles(root, new ArrayList<>(), 0, 3); // Max depth of 3
                        sourceRoots.put(root.getPath(), files);
                        break; // Just use the first matching source root
                    }
                }

                // Fall back to any source root
                if (sourceRoots.isEmpty() && roots.length > 0) {
                    List<String> files = collectFiles(roots[0], new ArrayList<>(), 0, 3); // Max depth of 3
                    sourceRoots.put(roots[0].getPath(), files);
                }
            } catch (Exception e) {
                // Ignore exceptions
            }

            return sourceRoots;
        });
    }

    /**
     * Finds the test root directory for the project and lists files in it.
     */
    private static Map<String, List<String>> findTestRootWithFiles(Project project) {
        return ApplicationManager.getApplication().runReadAction((Computable<Map<String, List<String>>>) () -> {
            Map<String, List<String>> testRoots = new HashMap<>();

            try {
                VirtualFile baseDir = project.getBaseDir();
                if (baseDir == null) {
                    return testRoots;
                }

                // Common test roots to check
                String[] commonTestRoots = {
                        "src/test/java",
                        "src/test/kotlin",
                        "src/test",
                        "test",
                        "tests"
                };

                // Check if common test roots exist
                for (String rootPath : commonTestRoots) {
                    VirtualFile testRoot = baseDir.findFileByRelativePath(rootPath);
                    if (testRoot != null && testRoot.isDirectory()) {
                        List<String> files = collectFiles(testRoot, new ArrayList<>(), 0, 3); // Max depth of 3
                        testRoots.put(testRoot.getPath(), files);
                        break; // Just use the first matching test root
                    }
                }
            } catch (Exception e) {
                // Ignore exceptions
            }

            return testRoots;
        });
    }

    /**
     * Recursively collects files in a directory up to a specified depth.
     */
    private static List<String> collectFiles(VirtualFile dir, List<String> files, int depth, int maxDepth) {
        if (depth > maxDepth || files.size() >= MAX_FILES_PER_ROOT) {
            return files;
        }

        for (VirtualFile child : dir.getChildren()) {
            if (files.size() >= MAX_FILES_PER_ROOT) {
                break;
            }

            if (child.isDirectory()) {
                collectFiles(child, files, depth + 1, maxDepth);
            } else {
                files.add(child.getPath());
            }
        }

        return files;
    }
}