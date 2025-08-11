package com.zps.zest.testing;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import com.zps.zest.testing.ui.TestWritingVirtualFile;
import com.zps.zest.testing.ui.TestWritingVirtualFileSystem;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Action that opens the modern Test Writing Editor for AI-powered test generation.
 * This streamlined version provides an immersive editor-based experience with real-time streaming.
 */
public class AgentBasedTestWritingAction extends AnAction {
    private static final Logger LOG = Logger.getInstance(AgentBasedTestWritingAction.class);

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            LOG.warn("No project available for test writing action");
            return;
        }

        // Get the target class from the current context
        String targetClass = extractTargetClass(e);
        if (targetClass == null) {
            LOG.warn("Could not determine target class for test writing");
            return;
        }

        // Create and open the test writing editor
        openTestWritingEditor(project, targetClass);
    }

    /**
     * Extracts the target class name from the action event context.
     */
    private String extractTargetClass(@NotNull AnActionEvent e) {
        PsiFile psiFile = e.getData(CommonDataKeys.PSI_FILE);
        if (psiFile == null) {
            return null;
        }

        // Try to get the class from the current caret position
        PsiElement element = e.getData(CommonDataKeys.PSI_ELEMENT);
        if (element != null) {
            PsiClass psiClass = PsiTreeUtil.getParentOfType(element, PsiClass.class);
            if (psiClass != null) {
                return psiClass.getQualifiedName();
            }
        }

        // Fallback: try to find the first public class in the file
        PsiClass[] classes = PsiTreeUtil.getChildrenOfType(psiFile, PsiClass.class);
        if (classes != null && classes.length > 0) {
            for (PsiClass psiClass : classes) {
                if (psiClass.hasModifierProperty("public")) {
                    return psiClass.getQualifiedName();
                }
            }
            // If no public class, return the first class
            return classes[0].getQualifiedName();
        }

        // Final fallback: use the file name
        String fileName = psiFile.getName();
        if (fileName.endsWith(".java")) {
            return fileName.substring(0, fileName.length() - 5);
        }

        return fileName;
    }

    /**
     * Opens the test writing editor for the specified target class.
     */
    private void openTestWritingEditor(Project project, String targetClass) {
        try {
            // Create a unique session ID for this test writing session
            String sessionId = UUID.randomUUID().toString().substring(0, 8);
            
            // Create the virtual file for the test writing session
            TestWritingVirtualFile virtualFile = TestWritingVirtualFileSystem.Companion.createTestWritingFile(
                    targetClass,
                    sessionId
            );

            // Open the editor
            FileEditorManager.getInstance(project).openFile(virtualFile, true);
            
            LOG.info("Opened test writing editor for class: " + targetClass + " (session: " + sessionId + ")");

        } catch (Exception ex) {
            LOG.error("Failed to open test writing editor for class: " + targetClass, ex);
        }
    }
}

