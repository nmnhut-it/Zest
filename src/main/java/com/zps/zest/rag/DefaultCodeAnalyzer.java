package com.zps.zest.rag;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;

import java.util.List;

/**
 * Default implementation of CodeAnalyzer using IntelliJ PSI.
 */
public class DefaultCodeAnalyzer implements CodeAnalyzer {
    private final Project project;
    private final SignatureExtractor signatureExtractor;
    private final ProjectInfoExtractor projectInfoExtractor;
    
    public DefaultCodeAnalyzer(Project project) {
        this.project = project;
        this.signatureExtractor = new SignatureExtractor();
        this.projectInfoExtractor = new ProjectInfoExtractor(project);
    }
    
    @Override
    public List<CodeSignature> extractSignatures(PsiFile psiFile) {
        return signatureExtractor.extractFromFile(psiFile);
    }
    
    @Override
    public ProjectInfo extractProjectInfo() {
        return projectInfoExtractor.extractProjectInfo();
    }
    
    @Override
    public List<VirtualFile> findAllSourceFiles() {
        return projectInfoExtractor.findAllSourceFiles();
    }
}
