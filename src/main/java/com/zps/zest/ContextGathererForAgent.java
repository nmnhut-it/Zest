package com.zps.zest;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.vfs.VirtualFile;

import java.util.HashMap;
import java.util.Map;

/**
 * Gathers context information from the current editor and project.
 */
public class ContextGathererForAgent {
    
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
        
        // Get project roots
        String sourceRoot = findSourceRoot(project);
        String testRoot = findTestRoot(project);
        
        if (sourceRoot != null) {
            context.put("sourceRoot", sourceRoot);
        }
        
        if (testRoot != null) {
            context.put("testRoot", testRoot);
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
     * Finds the source root directory for the project.
     */
    private static String findSourceRoot(Project project) {
        try {
            VirtualFile[] sourceRoots = ProjectRootManager.getInstance(project).getContentSourceRoots();
            for (VirtualFile root : sourceRoots) {
                if (root.getPath().contains("/src/main/java")) {
                    return root.getPath();
                }
            }
            
            // Fall back to any source root
            if (sourceRoots.length > 0) {
                return sourceRoots[0].getPath();
            }
        } catch (Exception e) {
            // Ignore exceptions
        }
        
        return null;
    }
    
    /**
     * Finds the test root directory for the project.
     */
    private static String findTestRoot(Project project) {
        try {
            VirtualFile baseDir = project.getBaseDir();
            if (baseDir == null) {
                return null;
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
            for (String root : commonTestRoots) {
                VirtualFile testRoot = baseDir.findFileByRelativePath(root);
                if (testRoot != null && testRoot.isDirectory()) {
                    return testRoot.getPath();
                }
            }
        } catch (Exception e) {
            // Ignore exceptions
        }
        
        return null;
    }
}