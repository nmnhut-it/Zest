package com.zps.zest.testgen.util;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiErrorElement;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiRecursiveElementVisitor;
import org.jetbrains.annotations.NotNull;

/**
 * Utility class for collecting PSI compilation errors from Java files.
 * Extracted from PSITestMergerAgent for reuse across different agents.
 */
public class PSIErrorCollector {
    
    /**
     * Collect compilation errors from a PSI Java file.
     * This method should be called from within a ReadAction for thread safety.
     * 
     * @param file the PsiJavaFile to analyze
     * @param project the IntelliJ project
     * @return formatted string containing all compilation errors with line numbers
     */
    @NotNull
    public static String collectCompilationErrors(@NotNull PsiJavaFile file, @NotNull Project project) {
        StringBuilder errors = new StringBuilder();
        Document doc = PsiDocumentManager.getInstance(project).getDocument(file);
        
        if (doc != null) {
            file.accept(new PsiRecursiveElementVisitor() {
                @Override
                public void visitErrorElement(PsiErrorElement element) {
                    super.visitErrorElement(element);
                    int line = doc.getLineNumber(element.getTextOffset()) + 1;
                    errors.append("Line ").append(line).append(": ")
                          .append(element.getErrorDescription()).append("\n");
                }
            });
        }
        
        return errors.toString();
    }
}