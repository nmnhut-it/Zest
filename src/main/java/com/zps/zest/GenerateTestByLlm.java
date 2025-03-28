package com.zps.zest;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;

/**
 * Main action class that orchestrates the test generation pipeline.
 */
public class GenerateTestByLlm extends AnAction {
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        // Create the pipeline context to maintain state
        TestGenerationContext context = new TestGenerationContext();
        context.setEvent(e);
        context.setProject(e.getProject());
        context.setEditor(e.getData(CommonDataKeys.EDITOR));
        context.setPsiFile(e.getData(CommonDataKeys.PSI_FILE));

        // Execute the pipeline in a background task with progress indicators
        BackgroundPipelineExecutor.executeInBackground(context);
    }
}

