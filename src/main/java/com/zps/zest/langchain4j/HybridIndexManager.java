package com.zps.zest.langchain4j;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.zps.zest.langchain4j.index.NameIndex;
import com.zps.zest.langchain4j.index.SemanticIndex;
import com.zps.zest.langchain4j.index.StructuralIndex;
import com.zps.zest.langchain4j.index.BatchIndexingCoordinator;
import com.zps.zest.langchain4j.index.DiskBasedNameIndex;
import com.zps.zest.langchain4j.index.DiskBasedSemanticIndex;
import com.zps.zest.langchain4j.index.DiskBasedStructuralIndex;
import com.zps.zest.rag.CodeSignature;
import com.zps.zest.rag.SignatureExtractor;
import com.zps.zest.rag.ProjectInfoExtractor;
import com.zps.zest.langchain4j.util.CodeSearchUtils;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages the three indices (Name, Semantic, Structural) for hybrid code search.
 * Coordinates indexing and provides unified access to all indices.
 */
@Service(Service.Level.PROJECT)
public final class HybridIndexManager {
    private static final Logger LOG = Logger.getInstance(HybridIndexManager.class);
    
    private final Project project;
    private final SignatureExtractor signatureExtractor;
    private final CodeEmbeddingGenerator embeddingGenerator;
    private final ProjectInfoExtractor projectInfoExtractor;
    private final BatchIndexingCoordinator batchCoordinator;
    
    // The three indices
    private NameIndex nameIndex;
    private SemanticIndex semanticIndex;
    private StructuralIndex structuralIndex;
    
    // Track indexed files
    private final Map<String, Long> indexedFiles = new ConcurrentHashMap<>();
    private volatile boolean isIndexing = false;
    private volatile CompletableFuture<Boolean> currentIndexingFuture = null;
    
    // Statistics
    private final AtomicInteger totalFilesIndexed = new AtomicInteger(0);
    private final AtomicInteger totalSignaturesIndexed = new AtomicInteger(0);
    
    // Periodic persistence timer
    private Timer persistenceTimer;
    
    public HybridIndexManager(Project project) {
        this.project = project;
        this.signatureExtractor = new SignatureExtractor();
        this.embeddingGenerator = new CodeEmbeddingGenerator();
        this.projectInfoExtractor = new ProjectInfoExtractor(project);
        this.batchCoordinator = new BatchIndexingCoordinator(project);
        
        initializeIndices();
        
        // Start periodic persistence if using disk storage
        if (project.getService(HybridIndexSettings.class).isUseDiskStorage() &&
            project.getService(HybridIndexSettings.class).isAutoPersist()) {
            startPeriodicPersistence();
        }
        
        LOG.info("Initialized HybridIndexManager for project: " + project.getName());
    }
    
    private void initializeIndices() {
        try {
            // Use disk-based implementations if configured
            boolean useDiskStorage = project.getService(HybridIndexSettings.class).isUseDiskStorage();
            
            if (useDiskStorage) {
                LOG.info("Initializing disk-based indices for better memory efficiency");
                try {
                    nameIndex = new DiskBasedNameIndex(project);
                } catch (Exception e) {
                    LOG.error("Failed to initialize disk-based name index, falling back to in-memory: " + e.getMessage());
                    nameIndex = new NameIndex();
                }
                
                try {
                    semanticIndex = new DiskBasedSemanticIndex(project);
                } catch (Exception e) {
                    LOG.error("Failed to initialize disk-based semantic index, falling back to in-memory: " + e.getMessage());
                    semanticIndex = new SemanticIndex(project);
                }
                
                try {
                    structuralIndex = new DiskBasedStructuralIndex(project);
                } catch (Exception e) {
                    LOG.error("Failed to initialize disk-based structural index, falling back to in-memory: " + e.getMessage());
                    structuralIndex = new StructuralIndex();
                }
            } else {
                LOG.info("Initializing in-memory indices");
                nameIndex = new NameIndex();
                semanticIndex = new SemanticIndex(project);
                structuralIndex = new StructuralIndex();
            }
            
            // Ensure all indices are initialized
            if (nameIndex == null) {
                LOG.warn("Name index is null, creating fallback in-memory index");
                nameIndex = new NameIndex();
            }
            if (semanticIndex == null) {
                LOG.warn("Semantic index is null, creating fallback in-memory index");
                semanticIndex = new SemanticIndex(project);
            }
            if (structuralIndex == null) {
                LOG.warn("Structural index is null, creating fallback in-memory index");
                structuralIndex = new StructuralIndex();
            }
            
        } catch (Exception e) {
            LOG.error("Critical error during index initialization, using fallback in-memory indices: " + e.getMessage(), e);
            // Fallback to in-memory indices to ensure plugin doesn't fail to start
            nameIndex = new NameIndex();
            semanticIndex = new SemanticIndex(project);
            structuralIndex = new StructuralIndex();
        }
    }
    
    /**
     * Indexes the entire project.
     */
    public void indexProject(boolean forceReindex) {
        if (isIndexing && currentIndexingFuture != null && !currentIndexingFuture.isDone()) {
            LOG.info("Indexing already in progress");
            return;
        }
        
        // Create a future for tracking
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        currentIndexingFuture = future;
        
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Hybrid Code Indexing", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    isIndexing = true;
                    performBatchIndexing(indicator, forceReindex);
                    future.complete(true);
                } catch (Exception e) {
                    LOG.error("Error during indexing", e);
                    future.completeExceptionally(e);
                } finally {
                    isIndexing = false;
                    currentIndexingFuture = null;
                }
            }
        });
    }
    
    /**
     * Indexes the entire project and returns a CompletableFuture.
     * @return CompletableFuture that completes when indexing is done
     */
    public CompletableFuture<Boolean> indexProjectAsync(boolean forceReindex) {
        // If already indexing, return the existing future
        if (isIndexing && currentIndexingFuture != null && !currentIndexingFuture.isDone()) {
            LOG.info("Indexing already in progress - returning existing future");
            return currentIndexingFuture;
        }
        
        // Create new future for this indexing operation
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        currentIndexingFuture = future;
        
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Hybrid Code Indexing", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    isIndexing = true;
                    performBatchIndexing(indicator, forceReindex);
                    future.complete(true);
                } catch (Exception e) {
                    LOG.error("Error during indexing", e);
                    future.completeExceptionally(e);
                } finally {
                    isIndexing = false;
                    currentIndexingFuture = null;
                }
            }
        });
        
        return future;
    }
    
    /**
     * Performs batch indexing using the coordinator.
     */
    private void performBatchIndexing(ProgressIndicator indicator, boolean forceReindex) {
        // Get all source files
        List<VirtualFile> sourceFiles = ReadAction.compute(() -> 
            projectInfoExtractor.findAllSourceFiles()
        );
        
        // Create indexing strategy
        BatchIndexingCoordinator.IndexingStrategy strategy = new HybridIndexingStrategy(forceReindex);
        
        // Perform batch indexing
        BatchIndexingCoordinator.BatchIndexingResult result = 
            batchCoordinator.indexFiles(sourceFiles, strategy, indicator);
        
        // Update statistics
        totalFilesIndexed.addAndGet(result.getFilesProcessed());
        totalSignaturesIndexed.addAndGet(result.getSignaturesIndexed());
        
        // Commit changes
        try {
            nameIndex.commit();
        } catch (IOException e) {
            LOG.error("Failed to commit name index", e);
        }
        
        LOG.info("Batch indexing complete: " + result);
    }
    
    /**
     * Indexes a single file.
     */
    public int indexFile(VirtualFile file) {
        if (!isSourceFile(file)) {
            return 0;
        }
        
        return ReadAction.compute(() -> {
            try {
                PsiFile psiFile = PsiManager.getInstance(project).findFile(file);
                if (psiFile == null) {
                    return 0;
                }
                
                List<CodeSignature> signatures = signatureExtractor.extractFromFile(psiFile);
                if (signatures.isEmpty()) {
                    return 0;
                }
                
                int indexedCount = 0;
                
                for (CodeSignature signature : signatures) {
                    try {
                        // Skip invalid signatures
                        if (!CodeSearchUtils.isValidForIndexing(signature)) {
                            LOG.warn("Skipping invalid signature in file: " + file.getName());
                            continue;
                        }
                        
                        // Find corresponding PSI element
                        PsiElement psiElement = findPsiElement(signature.getId(), psiFile);
                        
                        // 1. Index in NameIndex
                        indexInNameIndex(signature, file);
                        
                        // 2. Generate embedding and index in SemanticIndex
                        if (psiElement != null) {
                            indexInSemanticIndex(signature, psiElement);
                        }
                        
                        // 3. Extract and index structural information
                        if (psiElement != null) {
                            indexInStructuralIndex(signature, psiElement);
                        }
                        
                        indexedCount++;
                        
                    } catch (Exception e) {
                        LOG.error("Failed to index signature: " + signature.getId(), e);
                    }
                }
                
                // Mark file as indexed
                indexedFiles.put(file.getPath(), file.getModificationStamp());
                totalFilesIndexed.incrementAndGet();
                totalSignaturesIndexed.addAndGet(indexedCount);
                
                LOG.info("Indexed " + indexedCount + " signatures from: " + file.getName());
                return indexedCount;
                
            } catch (Exception e) {
                LOG.error("Failed to index file: " + file.getPath(), e);
                return 0;
            }
        });
    }
    
    /**
     * Indexes in the name-based index.
     */
    private void indexInNameIndex(CodeSignature signature, VirtualFile file) throws IOException {
        Map<String, String> additionalFields = CodeSearchUtils.extractSignatureMetadata(signature);
        String type = CodeSearchUtils.extractSignatureType(signature);
        
        nameIndex.indexElement(
            signature.getId(),
            signature.getSignature(),
            type,
            file.getPath(),
            additionalFields
        );
    }
    
    /**
     * Indexes in the semantic index with embeddings.
     */
    private void indexInSemanticIndex(CodeSignature signature, PsiElement psiElement) {
        // Generate rich embedding content - this handles read actions internally
        CodeEmbeddingGenerator.EmbeddingContent content = 
            embeddingGenerator.generateEmbedding(signature, psiElement);
        
        // Create metadata using utility
        Map<String, Object> additionalMetadata = new HashMap<>();
        additionalMetadata.put("type", content.getElementType());
        
        // Add context metadata
        if (content.getContext() != null) {
            CodeEmbeddingGenerator.ContextInfo ctx = content.getContext();
            if (ctx.getPackageName() != null) {
                additionalMetadata.put("package", ctx.getPackageName());
            }
            if (ctx.getContainingClass() != null) {
                additionalMetadata.put("class_name", ctx.getContainingClass());
            }
            if (ctx.getReturnType() != null) {
                additionalMetadata.put("return_type", ctx.getReturnType());
            }
        }
        
        // Add metrics
        if (content.getMetrics() != null) {
            CodeEmbeddingGenerator.CodeMetrics metrics = content.getMetrics();
            additionalMetadata.put("loc", metrics.getLinesOfCode());
            additionalMetadata.put("complexity", metrics.getCyclomaticComplexity());
        }
        
        Map<String, Object> metadata = CodeSearchUtils.createVectorMetadata(signature, additionalMetadata);
        
        // Index in semantic index
        semanticIndex.indexElement(
            signature.getId(),
            content.getCombinedText(),
            metadata
        );
    }
    
    /**
     * Indexes structural relationships.
     */
    private void indexInStructuralIndex(CodeSignature signature, PsiElement element) {
        String type = CodeSearchUtils.extractSignatureType(signature);
        StructuralIndex.ElementStructure structure = 
            new StructuralIndex.ElementStructure(signature.getId(), type);
        
        if (element instanceof PsiMethod) {
            extractMethodStructure((PsiMethod) element, structure);
        } else if (element instanceof PsiClass) {
            extractClassStructure((PsiClass) element, structure);
        } else if (element instanceof PsiField) {
            extractFieldStructure((PsiField) element, structure);
        }
        
        structuralIndex.indexElement(signature.getId(), structure);
    }
    
    /**
     * Extracts structural information from a method.
     */
    private void extractMethodStructure(PsiMethod method, StructuralIndex.ElementStructure structure) {
        ReadAction.run(() -> {
            // Super methods (overrides)
            PsiMethod[] superMethods = method.findSuperMethods();
            for (PsiMethod superMethod : superMethods) {
                PsiClass superClass = superMethod.getContainingClass();
                if (superClass != null) {
                    structure.getOverrides().add(superClass.getQualifiedName() + "#" + superMethod.getName());
                }
            }
            
            // Called methods
            method.accept(new JavaRecursiveElementVisitor() {
                @Override
                public void visitMethodCallExpression(PsiMethodCallExpression expression) {
                    PsiMethod called = expression.resolveMethod();
                    if (called != null && called.getContainingClass() != null) {
                        String calledId = called.getContainingClass().getQualifiedName() + "#" + called.getName();
                        structure.getCalls().add(calledId);
                    }
                    super.visitMethodCallExpression(expression);
                }
                
                @Override
                public void visitReferenceExpression(PsiReferenceExpression expression) {
                    PsiElement resolved = expression.resolve();
                    if (resolved instanceof PsiField) {
                        PsiField field = (PsiField) resolved;
                        PsiClass containingClass = field.getContainingClass();
                        if (containingClass != null) {
                            String fieldId = containingClass.getQualifiedName() + "." + field.getName();
                            structure.getAccessesFields().add(fieldId);
                        }
                    }
                    super.visitReferenceExpression(expression);
                }
            });
        });
    }
    
    /**
     * Extracts structural information from a class.
     */
    private void extractClassStructure(PsiClass clazz, StructuralIndex.ElementStructure structure) {
        ReadAction.run(() -> {
            // Superclass
            PsiClass superClass = clazz.getSuperClass();
            if (superClass != null && !superClass.getQualifiedName().equals("java.lang.Object")) {
                structure.setSuperClass(superClass.getQualifiedName());
            }
            
            // Interfaces
            PsiClass[] interfaces = clazz.getInterfaces();
            for (PsiClass iface : interfaces) {
                if (iface.getQualifiedName() != null) {
                    structure.getImplements().add(iface.getQualifiedName());
                }
            }
        });
    }
    
    /**
     * Extracts structural information from a field.
     */
    private void extractFieldStructure(PsiField field, StructuralIndex.ElementStructure structure) {
        // Fields don't have much structural info by themselves
        // The accesses to this field are tracked when indexing methods
    }
    
    /**
     * Finds PSI element for a signature ID.
     */
    private PsiElement findPsiElement(String signatureId, PsiFile file) {
        // Add null check for signatureId
        if (signatureId == null || signatureId.isEmpty()) {
            LOG.warn("Cannot find PSI element for null or empty signature ID");
            return null;
        }
        
        // This is a simplified version - in practice, you'd need more robust matching
        if (signatureId.contains("#")) {
            // Method
            String[] parts = signatureId.split("#");
            if (parts.length == 2) {
                String className = parts[0];
                String methodName = parts[1];
                
                // Find class
                PsiClass[] classes = ((PsiJavaFile) file).getClasses();
                for (PsiClass clazz : classes) {
                    if (className.equals(clazz.getQualifiedName())) {
                        // Find method
                        for (PsiMethod method : clazz.getMethods()) {
                            if (methodName.equals(method.getName())) {
                                return method;
                            }
                        }
                    }
                }
            }
        } else if (signatureId.contains(".") && file instanceof PsiJavaFile) {
            // Could be class or field
            String className = signatureId;
            String fieldName = null;
            
            // Check if it's a field (heuristic: last part starts with lowercase)
            int lastDot = signatureId.lastIndexOf(".");
            if (lastDot > 0) {
                String lastPart = signatureId.substring(lastDot + 1);
                if (!lastPart.isEmpty() && Character.isLowerCase(lastPart.charAt(0))) {
                    // It's likely a field
                    className = signatureId.substring(0, lastDot);
                    fieldName = lastPart;
                }
            }
            
            // Find class
            PsiClass[] classes = ((PsiJavaFile) file).getClasses();
            for (PsiClass clazz : classes) {
                if (className.equals(clazz.getQualifiedName())) {
                    if (fieldName != null) {
                        // Find field
                        PsiField field = clazz.findFieldByName(fieldName, false);
                        if (field != null) return field;
                    } else {
                        // It's the class itself
                        return clazz;
                    }
                }
            }
        }
        
        return null;
    }
    
    /**
     * Checks if a file needs re-indexing.
     */
    private boolean needsReindex(VirtualFile file) {
        Long lastIndexed = indexedFiles.get(file.getPath());
        return lastIndexed == null || lastIndexed < file.getModificationStamp();
    }
    
    /**
     * Checks if a file is a source file.
     */
    private boolean isSourceFile(VirtualFile file) {
        if (file == null || file.isDirectory()) {
            return false;
        }
        String name = file.getName();
        return name.endsWith(".java") || name.endsWith(".kt") || name.endsWith(".kts");
    }
    
    /**
     * Gets statistics about the indices.
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("total_files_indexed", totalFilesIndexed.get());
        stats.put("total_signatures_indexed", totalSignaturesIndexed.get());
        stats.put("is_indexing", isIndexing);
        
        // Add index-specific statistics
        stats.put("name_index", Map.of("ready", nameIndex != null));
        stats.put("semantic_index", semanticIndex != null ? semanticIndex.getStatistics() : Map.of("ready", false));
        stats.put("structural_index", structuralIndex != null ? structuralIndex.getStatistics() : Map.of("ready", false));
        
        return stats;
    }
    
    /**
     * Checks if the project has been indexed.
     * @return true if the project has indexed files and signatures, false otherwise
     */
    public boolean hasIndex() {
        return totalFilesIndexed.get() > 0 && totalSignaturesIndexed.get() > 0 && !indexedFiles.isEmpty();
    }
    
    /**
     * Checks if indexing is currently in progress.
     * @return true if indexing is running, false otherwise
     */
    public boolean isIndexing() {
        return isIndexing;
    }
    
    /**
     * Gets the current indexing future if indexing is in progress.
     * @return the current indexing CompletableFuture, or null if not indexing
     */
    public CompletableFuture<Boolean> getCurrentIndexingFuture() {
        return currentIndexingFuture;
    }
    
    /**
     * Gets detailed indexing status.
     * @return a map with indexing status details
     */
    public Map<String, Object> getIndexingStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("isIndexing", isIndexing);
        status.put("hasIndex", hasIndex());
        status.put("totalFilesIndexed", totalFilesIndexed.get());
        status.put("totalSignaturesIndexed", totalSignaturesIndexed.get());
        
        if (isIndexing && currentIndexingFuture != null) {
            status.put("inProgress", true);
            status.put("isDone", currentIndexingFuture.isDone());
            status.put("isCompletedExceptionally", currentIndexingFuture.isCompletedExceptionally());
        } else {
            status.put("inProgress", false);
        }
        
        return status;
    }
    
    /**
     * Clears all indices with error handling.
     */
    public void clearIndices() {
        // Close name index
        if (nameIndex != null) {
            try {
                if (nameIndex instanceof DiskBasedNameIndex) {
                    ((DiskBasedNameIndex) nameIndex).close();
                } else {
                    nameIndex.close();
                }
            } catch (Exception e) {
                LOG.error("Failed to close name index: " + e.getMessage());
            }
        }
        
        // Close semantic index
        if (semanticIndex != null) {
            try {
                if (semanticIndex instanceof DiskBasedSemanticIndex) {
                    ((DiskBasedSemanticIndex) semanticIndex).close();
                } else {
                    semanticIndex.clear();
                }
            } catch (Exception e) {
                LOG.error("Failed to close semantic index: " + e.getMessage());
            }
        }
        
        // Close structural index
        if (structuralIndex != null) {
            try {
                if (structuralIndex instanceof DiskBasedStructuralIndex) {
                    ((DiskBasedStructuralIndex) structuralIndex).close();
                } else {
                    structuralIndex.clear();
                }
            } catch (Exception e) {
                LOG.error("Failed to close structural index: " + e.getMessage());
            }
        }
        
        // Clear tracking data
        indexedFiles.clear();
        totalFilesIndexed.set(0);
        totalSignaturesIndexed.set(0);
        
        // Re-initialize with error handling
        try {
            initializeIndices();
            LOG.info("Cleared and re-initialized all indices");
        } catch (Exception e) {
            LOG.error("Failed to re-initialize indices after clearing: " + e.getMessage(), e);
            // Ensure we have fallback indices
            if (nameIndex == null) nameIndex = new NameIndex();
            if (semanticIndex == null) semanticIndex = new SemanticIndex(project);
            if (structuralIndex == null) structuralIndex = new StructuralIndex();
        }
    }
    
    // Getters for the indices
    public NameIndex getNameIndex() { return nameIndex; }
    public SemanticIndex getSemanticIndex() { return semanticIndex; }
    public StructuralIndex getStructuralIndex() { return structuralIndex; }
    
    /**
     * Starts periodic persistence of disk-based indices.
     */
    private void startPeriodicPersistence() {
        HybridIndexSettings settings = project.getService(HybridIndexSettings.class);
        int intervalMinutes = settings.getAutoPersistIntervalMinutes();
        
        persistenceTimer = new Timer("HybridIndex-Persistence", true);
        persistenceTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                persistIndices();
            }
        }, intervalMinutes * 60 * 1000L, intervalMinutes * 60 * 1000L);
        
        LOG.info("Started periodic persistence every " + intervalMinutes + " minutes");
    }
    
    /**
     * Persists all disk-based indices with error handling.
     */
    private void persistIndices() {
        try {
            if (nameIndex instanceof DiskBasedNameIndex) {
                try {
                    ((DiskBasedNameIndex) nameIndex).commit();
                } catch (Exception e) {
                    LOG.error("Failed to commit name index: " + e.getMessage());
                }
            }
            
            if (semanticIndex instanceof DiskBasedSemanticIndex) {
                try {
                    ((DiskBasedSemanticIndex) semanticIndex).saveToDisk();
                } catch (Exception e) {
                    LOG.error("Failed to save semantic index: " + e.getMessage());
                }
            }
            
            if (structuralIndex instanceof DiskBasedStructuralIndex) {
                try {
                    ((DiskBasedStructuralIndex) structuralIndex).saveToDisk();
                } catch (Exception e) {
                    LOG.error("Failed to save structural index: " + e.getMessage());
                }
            }
            
            LOG.debug("Persisted indices to disk");
        } catch (Exception e) {
            LOG.error("General error during index persistence: " + e.getMessage(), e);
        }
    }
    
    /**
     * Disposes resources when the project is closed.
     */
    public void dispose() {
        if (persistenceTimer != null) {
            try {
                persistenceTimer.cancel();
                persistenceTimer = null;
            } catch (Exception e) {
                LOG.error("Failed to cancel persistence timer: " + e.getMessage());
            }
        }
        
        // Persist and close indices with error handling
        try {
            clearIndices();
        } catch (Exception e) {
            LOG.error("Failed to clear indices during disposal: " + e.getMessage());
        }
        
        LOG.info("Disposed HybridIndexManager for project: " + project.getName());
    }
    
    /**
     * Indexing strategy for hybrid indexing across all three indices.
     */
    private class HybridIndexingStrategy implements BatchIndexingCoordinator.IndexingStrategy {
        private final boolean forceReindex;
        
        public HybridIndexingStrategy(boolean forceReindex) {
            this.forceReindex = forceReindex;
        }
        
        @Override
        public boolean shouldIndex(VirtualFile file, Long lastIndexTime) {
            if (!isSourceFile(file)) {
                return false;
            }
            if (forceReindex) {
                return true;
            }
            return lastIndexTime == null || lastIndexTime < file.getModificationStamp();
        }
        
        @Override
        public List<CodeSignature> extractSignatures(PsiFile file) {
            return signatureExtractor.extractFromFile(file);
        }
        
        @Override
        public int indexSignatures(List<CodeSignature> signatures, PsiFile psiFile, VirtualFile file) {
            int indexedCount = 0;
            
            for (CodeSignature signature : signatures) {
                try {
                    // Skip invalid signatures
                    if (!CodeSearchUtils.isValidForIndexing(signature)) {
                        LOG.warn("Skipping invalid signature in file: " + file.getName());
                        continue;
                    }
                    
                    // Find corresponding PSI element - needs ReadAction
                    PsiElement psiElement = ReadAction.compute(() -> 
                        findPsiElement(signature.getId(), psiFile)
                    );
                    
                    // 1. Index in NameIndex
                    indexInNameIndex(signature, file);
                    
                    // 2. Generate embedding and index in SemanticIndex
                    if (psiElement != null) {
                        indexInSemanticIndex(signature, psiElement);
                    }
                    
                    // 3. Extract and index structural information
                    if (psiElement != null) {
                        indexInStructuralIndex(signature, psiElement);
                    }
                    
                    indexedCount++;
                    
                } catch (Exception e) {
                    LOG.error("Failed to index signature: " + signature.getId(), e);
                }
            }
            
            // Mark file as indexed
            indexedFiles.put(file.getPath(), file.getModificationStamp());
            
            return indexedCount;
        }
    }
}
