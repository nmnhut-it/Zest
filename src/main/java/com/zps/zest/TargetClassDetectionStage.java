package com.zps.zest;

import com.intellij.openapi.application.ReadAction;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.util.PsiTreeUtil; /**
 * Stage for detecting the target class for test generation.
 */
public class TargetClassDetectionStage implements PipelineStage {
    @Override
    public void process(CodeContext context) throws PipelineExecutionException {
        if (context.getEditor() == null || context.getPsiFile() == null) {
            throw new PipelineExecutionException("No editor or file found");
        }
        ReadAction.run(()->{

            // First try to get the class directly from the cursor position
            PsiElement element = context.getPsiFile().findElementAt(
                    context.getEditor().getCaretModel().getOffset());
            PsiClass targetClass = PsiTreeUtil.getParentOfType(element, PsiClass.class);

            // If not found directly, try to get it from a method
            if (targetClass == null) {
                PsiMethod method = PsiTreeUtil.getParentOfType(element, PsiMethod.class);
                if (method != null) {
                    targetClass = method.getContainingClass();
                }
            }

            if (targetClass == null) {
                try {
                    throw new PipelineExecutionException("Please place cursor inside a class or method");
                } catch (PipelineExecutionException e) {
                    throw new RuntimeException(e);
                }
            }

            context.setTargetClass(targetClass);
            context.setClassName(targetClass.getName());

            if (context.getPsiFile() instanceof PsiJavaFile) {
                context.setPackageName(((PsiJavaFile) context.getPsiFile()).getPackageName());
            }
        });

    }
}
