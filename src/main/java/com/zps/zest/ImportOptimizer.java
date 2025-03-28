package com.zps.zest;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.SearchScope;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.util.PsiUtilCore;
import com.intellij.util.Query;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class ImportOptimizer {
    private static final Logger LOG = Logger.getInstance(ImportOptimizer.class);

    /**
     * Optimizes imports in a file after indexing completes.
     * This method will wait for IntelliJ indexing to complete before processing.
     * 
     * @param project The project
     * @param file The file to optimize imports for
     */
    public static void optimizeImportsWhenIndexingComplete(Project project, VirtualFile file) {
        if (project == null || file == null || !file.exists()) {
            LOG.warn("Cannot optimize imports: invalid project or file");
            return;
        }

        // Check if we're in dumb mode (indexing)
        if (DumbService.isDumb(project)) {
            LOG.info("Indexing in progress, scheduling import optimization for later");
            
            // Queue the action to run after indexing is complete
            DumbService.getInstance(project).runWhenSmart(() -> {
                if (!project.isDisposed() && file.isValid()) {
                    LOG.info("Indexing complete, optimizing imports now");
                    performImportOptimization(project, file);
                }
            });
        } else {
            // Indexing already complete, optimize immediately
            LOG.info("No indexing in progress, optimizing imports immediately");
            performImportOptimization(project, file);
        }
    }

    /**
     * Performs the actual import optimization.
     * This involves removing unused imports and organizing the remaining ones.
     * 
     * @param project The project
     * @param file The virtual file to optimize
     */
    private static void performImportOptimization(Project project, VirtualFile file) {
        // Get the PSI file from the virtual file
        final AtomicReference<PsiFile> psiFileRef = new AtomicReference<>();
        
        ReadAction.run(() -> {
            if (!project.isDisposed() && file.isValid()) {
                psiFileRef.set(PsiManager.getInstance(project).findFile(file));
            }
        });
        
        PsiFile psiFile = psiFileRef.get();
        if (!(psiFile instanceof PsiJavaFile)) {
            LOG.warn("Cannot optimize imports: not a Java file");
            return;
        }

        PsiJavaFile javaFile = (PsiJavaFile) psiFile;
        
        // Run the optimization in a write command
        WriteCommandAction.runWriteCommandAction(project, "Optimize Imports", null, () -> {
            if (project.isDisposed() || !file.isValid()) {
                return;
            }
            
            try {
                // Remove non-existing imports first
                removeNonExistingImports(project, javaFile);
                
                // Then use the standard import optimizer which removes unused imports
                JavaCodeStyleManager styleManager = JavaCodeStyleManager.getInstance(project);
                styleManager.optimizeImports(javaFile);
                styleManager.shortenClassReferences(javaFile);
                
                // Save the document
                Document document = FileDocumentManager.getInstance().getDocument(file);
                if (document != null) {
                    FileDocumentManager.getInstance().saveDocument(document);
                }
                
                LOG.info("Import optimization completed for file: " + file.getName());
            } catch (Exception e) {
                LOG.error("Error optimizing imports: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Removes imports that refer to non-existent classes.
     * 
     * @param project The project
     * @param javaFile The Java file to process
     */
    private static void removeNonExistingImports(Project project, PsiJavaFile javaFile) {
        PsiImportList importList = javaFile.getImportList();
        if (importList == null) return;
        
        // Process normal imports
        List<PsiElement> importsToRemove = new ArrayList<>();
        for (PsiImportStatement importStatement : importList.getImportStatements()) {
            String qualifiedName = importStatement.getQualifiedName();
            if (qualifiedName == null) continue;
            
            // Skip wildcard imports for this check
            if (qualifiedName.endsWith(".*")) continue;
            
            // Check if the class exists in the project's classpath
            PsiClass importedClass = JavaPsiFacade.getInstance(project)
                    .findClass(qualifiedName, GlobalSearchScope.allScope(project));
            
            // If the class doesn't exist, mark it for removal
            if (importedClass == null) {
                importsToRemove.add(importStatement);
                LOG.info("Marking non-existent import for removal: " + qualifiedName);
            } else {
                // Class exists, but check if it's actually used
                if (!isImportUsed(importedClass, javaFile)) {
                    importsToRemove.add(importStatement);
                    LOG.info("Marking unused import for removal: " + qualifiedName);
                }
            }
        }
        
        // Process static imports
        for (PsiImportStaticStatement staticImport : importList.getImportStaticStatements()) {
            String referenceName = staticImport.getReferenceName();
            // For static wildcard imports, we need special handling
            if (referenceName == null || "*".equals(referenceName)) continue;
            
            @Nullable PsiClass qualifiedName = staticImport.resolveTargetClass();
            if (qualifiedName == null) continue;
            
            // Check if the static import can be resolved
            PsiElement resolved = staticImport.resolve();
            if (resolved == null) {
                LOG.info("Marking unresolvable static import for removal: " + qualifiedName);
                importsToRemove.add(staticImport);
            }
        }
        
        // Remove all non-existent and unused imports
        for (PsiElement importToRemove : importsToRemove) {
            importToRemove.delete();
        }
    }

    /**
     * Checks if an imported class is actually used in the file.
     * 
     * @param importedClass The imported class to check
     * @param file The file to search in
     * @return true if the import is used, false otherwise
     */
    private static boolean isImportUsed(@NotNull PsiClass importedClass, @NotNull PsiJavaFile file) {
        // Create a scope limited to just this file to improve performance
        SearchScope scope = GlobalSearchScope.fileScope(file);
        
        // Search for references to the class
        Query<PsiReference> references = ReferencesSearch.search(importedClass, scope);
        
        // The import is used if there's at least one reference
        return references.findFirst() != null;
    }
}