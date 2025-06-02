package com.zps.zest.rag;

import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;

import java.util.List;

/**
 * Interface for code analysis operations.
 * Allows for testing without IntelliJ PSI dependencies.
 */
public interface CodeAnalyzer {
    
    /**
     * Extracts code signatures from a PSI file.
     * 
     * @param psiFile The file to analyze
     * @return List of code signatures
     */
    List<CodeSignature> extractSignatures(PsiFile psiFile);
    
    /**
     * Extracts project information.
     * 
     * @return Project information
     */
    ProjectInfo extractProjectInfo();
    
    /**
     * Finds all source files in the project.
     * 
     * @return List of source files
     */
    List<VirtualFile> findAllSourceFiles();
}
