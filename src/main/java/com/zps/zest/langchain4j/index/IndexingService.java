package com.zps.zest.langchain4j.index;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.zps.zest.langchain4j.DocumentProcessor;
import com.zps.zest.langchain4j.EmbeddingService;
import com.zps.zest.langchain4j.VectorStore;
import com.zps.zest.langchain4j.util.CodeSearchUtils;
import com.zps.zest.rag.CodeSignature;
import dev.langchain4j.data.segment.TextSegment;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Centralized service for indexing operations across different index types.
 * Eliminates duplication between RagService and HybridIndexManager indexing logic.
 */
public class IndexingService {
    private static final Logger LOG = Logger.getInstance(IndexingService.class);
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(4);
    
    private final EmbeddingService embeddingService;
    private final DocumentProcessor documentProcessor;
    
    public IndexingService(EmbeddingService embeddingService, DocumentProcessor documentProcessor) {
        this.embeddingService = embeddingService;
        this.documentProcessor = documentProcessor;
    }
    
    /**
     * Index a file with its code signatures into a vector store.
     */
    public CompletableFuture<IndexResult> indexFileInVectorStore(
            VirtualFile file, 
            List<CodeSignature> codeSignatures,
            VectorStore vectorStore,
            PsiFile psiFile) {
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                List<TextSegment> segments = processFile(file, codeSignatures, psiFile);
                
                if (segments.isEmpty()) {
                    return new IndexResult(file.getPath(), 0, IndexStatus.SKIPPED, "No segments to index");
                }
                
                List<VectorStore.EmbeddingEntry> entries = createEmbeddingEntries(file, segments);
                vectorStore.storeBatch(entries);
                
                LOG.info("Indexed " + segments.size() + " segments from: " + file.getName());
                return new IndexResult(file.getPath(), segments.size(), IndexStatus.SUCCESS);
                
            } catch (Exception e) {
                LOG.error("Failed to index file: " + file.getPath(), e);
                return new IndexResult(file.getPath(), 0, IndexStatus.FAILED, e.getMessage());
            }
        }, EXECUTOR);
    }
    
    /**
     * Index a single code signature.
     */
    public CompletableFuture<IndexResult> indexSignature(
            CodeSignature signature,
            VectorStore vectorStore,
            SemanticIndex semanticIndex,
            NameIndex nameIndex,
            StructuralIndex structuralIndex) {
        
        return CompletableFuture.supplyAsync(() -> {
            if (!CodeSearchUtils.isValidForIndexing(signature)) {
                return new IndexResult(signature.getId(), 0, IndexStatus.SKIPPED, "Invalid signature");
            }
            
            try {
                // Index in vector store if provided
                if (vectorStore != null) {
                    indexSignatureInVectorStore(signature, vectorStore);
                }
                
                // Index in semantic index if provided
                if (semanticIndex != null) {
                    indexSignatureInSemanticIndex(signature, semanticIndex);
                }
                
                // Index in name index if provided
                if (nameIndex != null) {
                    indexSignatureInNameIndex(signature, nameIndex);
                }
                
                // Note: Structural index requires PSI element, handled separately
                
                return new IndexResult(signature.getId(), 1, IndexStatus.SUCCESS);
                
            } catch (Exception e) {
                LOG.error("Failed to index signature: " + signature.getId(), e);
                return new IndexResult(signature.getId(), 0, IndexStatus.FAILED, e.getMessage());
            }
        }, EXECUTOR);
    }
    
    /**
     * Process a file to extract text segments.
     */
    private List<TextSegment> processFile(VirtualFile file, List<CodeSignature> codeSignatures, PsiFile psiFile) {
        if (codeSignatures != null && !codeSignatures.isEmpty() && psiFile != null) {
            return documentProcessor.processPsiFile(psiFile, codeSignatures);
        } else {
            return documentProcessor.processFile(file);
        }
    }
    
    /**
     * Create embedding entries for vector store.
     */
    private List<VectorStore.EmbeddingEntry> createEmbeddingEntries(VirtualFile file, List<TextSegment> segments) {
        List<VectorStore.EmbeddingEntry> entries = new ArrayList<>();
        
        for (int i = 0; i < segments.size(); i++) {
            TextSegment segment = segments.get(i);
            String id = file.getPath() + "#segment-" + i;
            
            float[] embedding = embeddingService.embed(segment.text());
            
            Map<String, Object> metadata = createSegmentMetadata(file, i, segment);
            
            entries.add(new VectorStore.EmbeddingEntry(id, embedding, segment, metadata));
        }
        
        return entries;
    }
    
    /**
     * Create metadata for a segment.
     */
    private Map<String, Object> createSegmentMetadata(VirtualFile file, int index, TextSegment segment) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("file_path", CodeSearchUtils.normalizeFilePath(file.getPath()));
        metadata.put("file_name", file.getName());
        metadata.put("segment_index", index);
        metadata.put("type", "code");
        
        // Add segment metadata if available
        if (segment.metadata() != null) {
            segment.metadata().toMap().forEach(metadata::put);
        }
        
        return metadata;
    }
    
    /**
     * Index signature in vector store.
     */
    private void indexSignatureInVectorStore(CodeSignature signature, VectorStore vectorStore) {
        String content = CodeSearchUtils.buildSignatureContent(signature);
        
        Map<String, String> segmentMetadata = new HashMap<>();
        segmentMetadata.put("type", "signature");
        segmentMetadata.put("signature_id", signature.getId());
        segmentMetadata.put("file_path", signature.getFilePath());
        
        TextSegment segment = documentProcessor.createSegment(content, segmentMetadata);
        float[] embedding = embeddingService.embed(content);
        
        Map<String, Object> metadata = CodeSearchUtils.createVectorMetadata(signature, Map.of(
            "type", "signature",
            "signature_type", CodeSearchUtils.extractSignatureType(signature)
        ));
        
        vectorStore.store(signature.getId(), embedding, segment, metadata);
    }
    
    /**
     * Index signature in semantic index.
     */
    private void indexSignatureInSemanticIndex(CodeSignature signature, SemanticIndex semanticIndex) {
        String content = CodeSearchUtils.buildSignatureContent(signature);
        Map<String, Object> metadata = CodeSearchUtils.createVectorMetadata(signature, null);
        semanticIndex.indexElement(signature.getId(), content, metadata);
    }
    
    /**
     * Index signature in name index.
     */
    private void indexSignatureInNameIndex(CodeSignature signature, NameIndex nameIndex) throws Exception {
        Map<String, String> additionalFields = CodeSearchUtils.extractSignatureMetadata(signature);
        String type = CodeSearchUtils.extractSignatureType(signature);
        
        nameIndex.indexElement(
            signature.getId(),
            signature.getSignature(),
            type,
            CodeSearchUtils.normalizeFilePath(signature.getFilePath()),
            additionalFields
        );
    }
    
    /**
     * Result of an indexing operation.
     */
    public static class IndexResult {
        private final String id;
        private final int itemsIndexed;
        private final IndexStatus status;
        private final String message;
        
        public IndexResult(String id, int itemsIndexed, IndexStatus status) {
            this(id, itemsIndexed, status, null);
        }
        
        public IndexResult(String id, int itemsIndexed, IndexStatus status, String message) {
            this.id = id;
            this.itemsIndexed = itemsIndexed;
            this.status = status;
            this.message = message;
        }
        
        public String getId() { return id; }
        public int getItemsIndexed() { return itemsIndexed; }
        public IndexStatus getStatus() { return status; }
        public String getMessage() { return message; }
        public boolean isSuccess() { return status == IndexStatus.SUCCESS; }
    }
    
    /**
     * Status of indexing operation.
     */
    public enum IndexStatus {
        SUCCESS,
        FAILED,
        SKIPPED
    }
}
