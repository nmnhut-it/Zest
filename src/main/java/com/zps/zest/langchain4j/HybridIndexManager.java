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
import com.zps.zest.rag.CodeSignature;
import com.zps.zest.rag.SignatureExtractor;
import com.zps.zest.rag.ProjectInfoExtractor;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.*;
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
    
    // The three indices
    private NameIndex nameIndex;
    private SemanticIndex semanticIndex;
    private StructuralIndex structuralIndex;
    
    // Track indexed files
    private final Map<String, Long> indexedFiles = new ConcurrentHashMap<>();
    private volatile boolean isIndexing = false;
    
    // Statistics
    private final AtomicInteger totalFilesIndexed = new AtomicInteger(0);
    private final AtomicInteger totalSignaturesIndexed = new AtomicInteger(0);
    
    public HybridIndexManager(Project project) {
        this.project = project;
        this.signatureExtractor = new SignatureExtractor();
        this.embeddingGenerator = new CodeEmbeddingGenerator();
        this.projectInfoExtractor = new ProjectInfoExtractor(project);
        
        initializeIndices();
        LOG.info("Initialized HybridIndexManager for project: " + project.getName());
    }
    
    private void initializeIndices() {
        try {
            nameIndex = new NameIndex();
            semanticIndex = new SemanticIndex();
            structuralIndex = new StructuralIndex();
        } catch (IOException e) {
            LOG.error("Failed to initialize indices", e);
            throw new RuntimeException("Failed to initialize search indices", e);
        }
    }
    
    /**
     * Indexes the entire project.
     */
    public void indexProject(boolean forceReindex) {
        if (isIndexing) {
            LOG.info("Indexing already in progress");
            return;
        }
        
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Hybrid Code Indexing", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    isIndexing = true;
                    performIndexing(indicator, forceReindex);
                } catch (Exception e) {
                    LOG.error("Error during indexing", e);
                } finally {
                    isIndexing = false;
                }
            }
        });
    }
    
    /**
     * Performs the actual indexing.
     */
    private void performIndexing(ProgressIndicator indicator, boolean forceReindex) {
        indicator.setText("Preparing for indexing...");
        
        // Get all source files
        List<VirtualFile> sourceFiles = ReadAction.compute(() -> 
            projectInfoExtractor.findAllSourceFiles()
        );
        
        indicator.setText("Indexing " + sourceFiles.size() + " source files...");
        
        int total = sourceFiles.size();
        int current = 0;
        
        for (VirtualFile file : sourceFiles) {
            if (indicator.isCanceled()) {
                break;
            }
            
            current++;
            indicator.setText2("Processing " + file.getName() + " (" + current + "/" + total + ")");
            indicator.setFraction((double) current / total);
            
            // Check if file needs indexing
            if (!forceReindex && !needsReindex(file)) {
                LOG.debug("Skipping already indexed file: " + file.getName());
                continue;
            }
            
            indexFile(file);
        }
        
        // Commit changes
        try {
            nameIndex.commit();
        } catch (IOException e) {
            LOG.error("Failed to commit name index", e);
        }
        
        indicator.setText("Indexing complete");
        LOG.info("Completed indexing. Stats: " + getStatistics());
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
        Map<String, String> additionalFields = new HashMap<>();
        
        // Extract additional metadata
        try {
            com.google.gson.JsonObject metadata = com.google.gson.JsonParser
                .parseString(signature.getMetadata())
                .getAsJsonObject();
                
            if (metadata.has("javadoc")) {
                additionalFields.put("javadoc", metadata.get("javadoc").getAsString());
            }
            if (metadata.has("package")) {
                additionalFields.put("package_name", metadata.get("package").getAsString());
            }
        } catch (Exception e) {
            // Ignore metadata parsing errors
        }
        
        String type = extractSignatureType(signature);
        
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
        // Generate rich embedding content
        CodeEmbeddingGenerator.EmbeddingContent content = 
            embeddingGenerator.generateEmbedding(signature, psiElement);
        
        // Create metadata
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("signature_id", signature.getId());
        metadata.put("type", content.getElementType());
        metadata.put("file_path", signature.getFilePath());
        
        // Add context metadata
        if (content.getContext() != null) {
            CodeEmbeddingGenerator.ContextInfo ctx = content.getContext();
            if (ctx.getPackageName() != null) {
                metadata.put("package", ctx.getPackageName());
            }
            if (ctx.getContainingClass() != null) {
                metadata.put("class_name", ctx.getContainingClass());
            }
            if (ctx.getReturnType() != null) {
                metadata.put("return_type", ctx.getReturnType());
            }
        }
        
        // Add metrics
        if (content.getMetrics() != null) {
            CodeEmbeddingGenerator.CodeMetrics metrics = content.getMetrics();
            metadata.put("loc", metrics.getLinesOfCode());
            metadata.put("complexity", metrics.getCyclomaticComplexity());
        }
        
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
        String type = extractSignatureType(signature);
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
    }
    
    /**
     * Extracts structural information from a class.
     */
    private void extractClassStructure(PsiClass clazz, StructuralIndex.ElementStructure structure) {
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
     * Extracts signature type from metadata.
     */
    private String extractSignatureType(CodeSignature signature) {
        try {
            com.google.gson.JsonObject metadata = com.google.gson.JsonParser
                .parseString(signature.getMetadata())
                .getAsJsonObject();
            return metadata.has("type") ? metadata.get("type").getAsString() : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
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
     * Clears all indices.
     */
    public void clearIndices() {
        if (nameIndex != null) {
            try {
                nameIndex.close();
            } catch (IOException e) {
                LOG.error("Failed to close name index", e);
            }
        }
        
        if (semanticIndex != null) {
            semanticIndex.clear();
        }
        
        if (structuralIndex != null) {
            structuralIndex.clear();
        }
        
        indexedFiles.clear();
        totalFilesIndexed.set(0);
        totalSignaturesIndexed.set(0);
        
        // Re-initialize
        initializeIndices();
        
        LOG.info("Cleared all indices");
    }
    
    // Getters for the indices
    public NameIndex getNameIndex() { return nameIndex; }
    public SemanticIndex getSemanticIndex() { return semanticIndex; }
    public StructuralIndex getStructuralIndex() { return structuralIndex; }
}
