package com.zps.zest.testgen.actions;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.zps.zest.testgen.model.TestGenerationRequest;
import com.zps.zest.testgen.ui.MethodSelectionDialog;
import com.zps.zest.testgen.ui.TestGenerationVirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Action to trigger test generation using the improved LangChain4j agents.
 * Clean workflow with method selection and streaming UI.
 */
public class GenerateTestAction extends AnAction {
    private static final Logger LOG = Logger.getInstance(GenerateTestAction.class);
    
    public GenerateTestAction() {
        super("Generate Tests (AI)", "Generate tests using AI agents", null);
    }
    
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;
        
        PsiFile psiFile = e.getData(CommonDataKeys.PSI_FILE);
        if (psiFile == null) {
            Messages.showErrorDialog(project, "No file selected", "Error");
            return;
        }
        
        // Check if it's a Java or Kotlin file
        if (!isTestableFile(psiFile)) {
            Messages.showErrorDialog(
                project,
                "Test generation is only supported for Java and Kotlin files",
                "Unsupported File Type"
            );
            return;
        }
        
        // Get editor for selection info
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        
        // Handle based on selection
        if (editor != null && editor.getSelectionModel().hasSelection()) {
            // User has selected code
            handleSelectedCode(project, psiFile, editor);
        } else {
            // No selection - show method selection dialog
            showMethodSelectionDialog(project, psiFile);
        }
    }
    
    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        // Use BGT (Background Thread) for accessing PSI elements
        return ActionUpdateThread.BGT;
    }
    
    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        PsiFile psiFile = e.getData(CommonDataKeys.PSI_FILE);
        
        boolean enabled = project != null && psiFile != null && isTestableFile(psiFile);
        e.getPresentation().setEnabled(enabled);
        
        // Update text based on context
        if (enabled) {
            Editor editor = e.getData(CommonDataKeys.EDITOR);
            if (editor != null && editor.getSelectionModel().hasSelection()) {
                e.getPresentation().setText("Generate Tests for Selection (AI)");
            } else {
                e.getPresentation().setText("Generate Tests (AI)");
            }
        }
    }
    
    private boolean isTestableFile(@NotNull PsiFile file) {
        return file instanceof PsiJavaFile || 
               file.getName().endsWith(".kt") ||
               file.getName().endsWith(".java");
    }
    
    private void handleSelectedCode(@NotNull Project project,
                                   @NotNull PsiFile psiFile,
                                   @NotNull Editor editor) {
        // Get selected text
        String selectedText = editor.getSelectionModel().getSelectedText();
        if (selectedText == null || selectedText.trim().isEmpty()) {
            Messages.showWarningDialog(project, "No code selected", "Warning");
            return;
        }
        
        // Get selected range
        int startOffset = editor.getSelectionModel().getSelectionStart();
        int endOffset = editor.getSelectionModel().getSelectionEnd();
        
        // Find methods in selection
        List<PsiMethod> selectedMethods = findMethodsInRange(psiFile, startOffset, endOffset);
        
        if (selectedMethods.isEmpty()) {
            // No complete methods in selection - treat as code snippet
            createTestGenerationRequest(project, psiFile, selectedText, (PsiMethod) null);
        } else if (selectedMethods.size() == 1) {
            // Single method selected
            createTestGenerationRequest(project, psiFile, selectedText, selectedMethods.get(0));
        } else {
            // Multiple methods - let user choose
            showMethodSelectionDialog(project, psiFile, selectedMethods);
        }
    }
    
    private List<PsiMethod> findMethodsInRange(@NotNull PsiFile file,
                                              int startOffset,
                                              int endOffset) {
        List<PsiMethod> methods = new ArrayList<>();
        
        PsiElement startElement = file.findElementAt(startOffset);
        PsiElement endElement = file.findElementAt(endOffset);
        
        if (startElement != null && endElement != null) {
            PsiElement commonParent = PsiTreeUtil.findCommonParent(startElement, endElement);
            if (commonParent != null) {
                commonParent.accept(new PsiRecursiveElementVisitor() {
                    @Override
                    public void visitElement(@NotNull PsiElement element) {
                        if (element instanceof PsiMethod) {
                            PsiMethod method = (PsiMethod) element;
                            TextRange methodRange = method.getTextRange();
                            // Check if method is fully within selection
                            if (methodRange.getStartOffset() >= startOffset &&
                                methodRange.getEndOffset() <= endOffset) {
                                methods.add(method);
                            }
                        }
                        super.visitElement(element);
                    }
                });
            }
        }
        
        return methods;
    }
    
    private void showMethodSelectionDialog(@NotNull Project project,
                                          @NotNull PsiFile psiFile) {
        // Find all methods in file
        List<PsiMethod> allMethods = findAllMethods(psiFile);
        
        if (allMethods.isEmpty()) {
            Messages.showWarningDialog(
                project,
                "No methods found in this file",
                "No Methods"
            );
            return;
        }
        
        showMethodSelectionDialog(project, psiFile, allMethods);
    }
    
    private void showMethodSelectionDialog(@NotNull Project project,
                                          @NotNull PsiFile psiFile,
                                          @NotNull List<PsiMethod> methods) {
        // Use the existing MethodSelectionDialog with its signature
        MethodSelectionDialog dialog = new MethodSelectionDialog(project, psiFile, 
                                                                 methods.isEmpty() ? null : methods.get(0));
        if (dialog.showAndGet()) {
            List<PsiMethod> selectedMethods = dialog.getSelectedMethods();
            if (!selectedMethods.isEmpty()) {
                // Create request for selected methods
                createTestGenerationRequest(project, psiFile, null, selectedMethods);
            }
        }
    }
    
    private List<PsiMethod> findAllMethods(@NotNull PsiFile file) {
        List<PsiMethod> methods = new ArrayList<>();
        
        file.accept(new PsiRecursiveElementVisitor() {
            @Override
            public void visitElement(@NotNull PsiElement element) {
                if (element instanceof PsiMethod) {
                    PsiMethod method = (PsiMethod) element;
                    // Skip constructors and private methods if desired
                    if (!method.isConstructor()) {
                        methods.add(method);
                    }
                }
                super.visitElement(element);
            }
        });
        
        return methods;
    }
    
    public void createTestGenerationRequest(@NotNull Project project,
                                            @NotNull PsiFile psiFile,
                                            @Nullable String selectedCode,
                                            @Nullable PsiMethod targetMethod) {
        // Create single method list if we have one
        List<PsiMethod> methods = targetMethod != null ? 
            List.of(targetMethod) : new ArrayList<>();
        
        createTestGenerationRequest(project, psiFile, selectedCode, methods);
    }
    
    private void createTestGenerationRequest(@NotNull Project project,
                                            @NotNull PsiFile psiFile,
                                            @Nullable String selectedCode,
                                            @NotNull List<PsiMethod> targetMethods) {
        try {
            // Create test generation request
            TestGenerationRequest.TestType testType = TestGenerationRequest.TestType.UNIT_TESTS;
            
            TestGenerationRequest request = new TestGenerationRequest(
                psiFile,
                    targetMethods,
                selectedCode,
                testType,
                null // Additional context
            );
            
            // Create virtual file for the editor
            TestGenerationVirtualFile virtualFile = new TestGenerationVirtualFile(
                psiFile.getName() + " - Test Generation",
                request
            );
            
            // Open in editor
            FileEditorManager.getInstance(project).openFile(virtualFile, true);
            
            LOG.info("Test generation request created for " + 
                    (targetMethods.isEmpty() ? "selected code" : targetMethods.size() + " method(s)"));
            
        } catch (Exception ex) {
            LOG.error("Failed to create test generation request", ex);
            Messages.showErrorDialog(
                project,
                "Failed to start test generation: " + ex.getMessage(),
                "Error"
            );
        }
    }
}