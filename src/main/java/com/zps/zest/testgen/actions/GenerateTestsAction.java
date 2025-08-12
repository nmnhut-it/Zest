package com.zps.zest.testgen.actions;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.SelectionModel;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import com.zps.zest.testgen.TestGenerationService;
import com.zps.zest.testgen.model.TestGenerationRequest;
import com.zps.zest.testgen.ui.TestGenerationVirtualFile;
import com.zps.zest.testgen.ui.TestGenerationVirtualFileSystem;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Action to generate tests for selected code
 */
public class GenerateTestsAction extends AnAction {
    
    public GenerateTestsAction() {
        super("Generate Tests", "Generate unit and integration tests using AI", null);
    }
    
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;
        
        PsiFile psiFile = e.getData(CommonDataKeys.PSI_FILE);
        if (psiFile == null) return;
        
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        if (editor == null) return;
        
        SelectionModel selectionModel = editor.getSelectionModel();
        int selectionStart = selectionModel.getSelectionStart();
        int selectionEnd = selectionModel.getSelectionEnd();
        
        // If no selection, select the entire file
        if (selectionStart == selectionEnd) {
            selectionStart = 0;
            selectionEnd = editor.getDocument().getTextLength();
        }
        
        // Generate session ID
        String sessionId = UUID.randomUUID().toString();
        
        // Create virtual file for the session
        TestGenerationVirtualFile virtualFile = TestGenerationVirtualFileSystem.INSTANCE
            .createSessionFile(psiFile, selectionStart, selectionEnd, sessionId);
        
        // Open the test generation editor
        FileEditorManager.getInstance(project).openFile(virtualFile, true);
    }
    
    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        PsiFile psiFile = e.getData(CommonDataKeys.PSI_FILE);
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        
        // Enable action only if we have a project, PSI file, and editor
        boolean enabled = project != null && psiFile != null && editor != null;
        
        // Further restrict to Java/Kotlin files for now
        if (enabled && psiFile.getName() != null) {
            String fileName = psiFile.getName().toLowerCase();
            enabled = fileName.endsWith(".java") || fileName.endsWith(".kt") || 
                     fileName.endsWith(".js") || fileName.endsWith(".ts");
        }
        
        e.getPresentation().setEnabled(enabled);
        e.getPresentation().setVisible(enabled);
    }
}