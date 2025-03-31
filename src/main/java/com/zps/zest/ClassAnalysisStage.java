package com.zps.zest;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Computable;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;

/**
 * Stage for analyzing the class and gathering context information.
 * Uses the centralized ClassAnalyzer utility for code analysis.
 */
public class ClassAnalysisStage implements PipelineStage {
    /**
     * Shows a notification.
     */
    private void showNotification(Project project, String message) {
        ApplicationManager.getApplication().invokeLater(() -> {
            Messages.showInfoMessage(project, message, "Class Analysis Stage");
        });
    }
    @Override
    public void process(TestGenerationContext context) throws PipelineExecutionException {
        PsiFile psiFile = context.getPsiFile();
        PsiClass targetClass = context.getTargetClass();
        StringBuilder importBuilder = new StringBuilder();
        if (DumbService.isDumb(context.getProject())){
            showNotification(context.getProject(),   "Indexing is in progress, please try later");
            return;
        }
        ReadAction.run(() -> {
            // Collect imports from the current file
            if (psiFile instanceof PsiJavaFile) {
                for (PsiImportStatement importStatement : ((PsiJavaFile) psiFile).getImportList().getImportStatements()) {
                    importBuilder.append(ClassAnalyzer.getTextOfPsiElement(importStatement)).append("\n");
                }
            }

            context.setImports(importBuilder.toString());

            // Collect class-level information using the ClassAnalyzer utility
            String classContext = ClassAnalyzer.collectClassContext(targetClass);
            context.setClassContext(classContext);

            // Detect JUnit version
            boolean isJUnit5 = ApplicationManager.getApplication().runReadAction((Computable<Boolean>) () ->
                    JavaPsiFacade.getInstance(context.getProject()).findClass(
                            "org.junit.jupiter.api.Test", GlobalSearchScope.allScope(context.getProject())) != null);

            context.setJunitVersion(isJUnit5 ? "JUnit 5" : "JUnit 4");

            // Detect Mockito presence
            boolean hasMockito = ApplicationManager.getApplication().runReadAction((Computable<Boolean>) () ->
                    JavaPsiFacade.getInstance(context.getProject()).findClass(
                            "org.mockito.Mockito", GlobalSearchScope.allScope(context.getProject())) != null);

            context.setMockitoPresent(hasMockito);
        });
    }
}