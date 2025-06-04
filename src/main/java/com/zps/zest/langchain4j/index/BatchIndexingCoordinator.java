package com.zps.zest.langchain4j.index;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.openapi.application.ReadAction;
import com.zps.zest.rag.CodeSignature;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.Collectors;

/**
 * Coordinates batch indexing operations across multiple files and indices.
 * Provides progress tracking and error handling for large-scale indexing.
 */
public class BatchIndexingCoordinator {
    private static final Logger LOG = Logger.getInstance(BatchIndexingCoordinator.class);
    private static final int BATCH_SIZE = 50;
    private static final int THREAD_POOL_SIZE = 4;
    
    private final Project project;
    private final ExecutorService executor;
    private final Map<String, Long> indexedFiles = new ConcurrentHashMap<>();
    
    // Statistics
    private final AtomicInteger totalFilesProcessed = new AtomicInteger(0);
    private final AtomicInteger totalSignaturesIndexed = new AtomicInteger(0);
    private final AtomicInteger failedFiles = new AtomicInteger(0);
    
    public BatchIndexingCoordinator(Project project) {
        this.project = project;
        this.executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
    }
    
    /**
     * Index multiple files in batches.
     */
    public BatchIndexingResult indexFiles(
            List<VirtualFile> files,
            IndexingStrategy strategy,
            ProgressIndicator indicator) {
        
        if (indicator != null) {
            indicator.setText("Preparing for batch indexing...");
        }
        
        long startTime = System.currentTimeMillis();
        List<CompletableFuture<BatchFileIndexResult>> futures = new ArrayList<>();
        
        // Process files in batches
        for (int i = 0; i < files.size(); i += BATCH_SIZE) {
            int end = Math.min(i + BATCH_SIZE, files.size());
            List<VirtualFile> batch = files.subList(i, end);
            
            futures.add(processBatch(batch, strategy, indicator, i, files.size()));
        }
        
        // Wait for all batches to complete
        CompletableFuture<Void> allOf = CompletableFuture.allOf(
            futures.toArray(new CompletableFuture[0])
        );
        
        try {
            allOf.get();
        } catch (Exception e) {
            LOG.error("Batch indexing failed", e);
        }
        
        // Collect results
        List<FileIndexResult> fileResults = futures.stream()
            .map(f -> {
                try {
                    return f.get();
                } catch (Exception e) {
                    return null;
                }
            })
            .filter(Objects::nonNull)
            .flatMap(batchResult -> batchResult.getResults().stream())
            .collect(Collectors.toList());
        
        long duration = System.currentTimeMillis() - startTime;
        
        return new BatchIndexingResult(
            totalFilesProcessed.get(),
            totalSignaturesIndexed.get(),
            failedFiles.get(),
            duration,
            fileResults
        );
    }
    
    /**
     * Process a batch of files.
     */
    private CompletableFuture<BatchFileIndexResult> processBatch(
            List<VirtualFile> batch,
            IndexingStrategy strategy,
            ProgressIndicator indicator,
            int batchStart,
            int totalFiles) {
        
        return CompletableFuture.supplyAsync(() -> {
            List<FileIndexResult> results = new ArrayList<>();
            
            for (int i = 0; i < batch.size(); i++) {
                VirtualFile file = batch.get(i);
                
                if (indicator != null && indicator.isCanceled()) {
                    break;
                }
                
                if (indicator != null) {
                    int current = batchStart + i + 1;
                    indicator.setText2("Processing " + file.getName() + " (" + current + "/" + totalFiles + ")");
                    indicator.setFraction((double) current / totalFiles);
                }
                
                // Check if file needs indexing
                if (!strategy.shouldIndex(file, getLastIndexTime(file))) {
                    LOG.debug("Skipping already indexed file: " + file.getName());
                    continue;
                }
                
                FileIndexResult result = indexSingleFile(file, strategy);
                results.add(result);
                
                // Update statistics
                totalFilesProcessed.incrementAndGet();
                if (result.isSuccess()) {
                    totalSignaturesIndexed.addAndGet(result.getSignaturesIndexed());
                    indexedFiles.put(file.getPath(), file.getModificationStamp());
                } else {
                    failedFiles.incrementAndGet();
                }
            }
            
            return new BatchFileIndexResult(results);
        }, executor);
    }
    
    /**
     * Index a single file using the provided strategy.
     */
    private FileIndexResult indexSingleFile(VirtualFile file, IndexingStrategy strategy) {
        try {
            PsiFile psiFile = ReadAction.compute(() -> 
                PsiManager.getInstance(project).findFile(file)
            );
            
            if (psiFile == null) {
                return new FileIndexResult(file, 0, false, "PSI file not found");
            }
            
            List<CodeSignature> signatures = ReadAction.compute(() -> 
                strategy.extractSignatures(psiFile)
            );
            
            if (signatures.isEmpty()) {
                return new FileIndexResult(file, 0, true, "No signatures found");
            }
            
            int indexed = strategy.indexSignatures(signatures, psiFile, file);
            
            return new FileIndexResult(file, indexed, true, null);
            
        } catch (Exception e) {
            LOG.error("Failed to index file: " + file.getPath(), e);
            return new FileIndexResult(file, 0, false, e.getMessage());
        }
    }
    
    /**
     * Get last index time for a file.
     */
    private Long getLastIndexTime(VirtualFile file) {
        return indexedFiles.get(file.getPath());
    }
    
    /**
     * Clear indexing history.
     */
    public void clearHistory() {
        indexedFiles.clear();
        totalFilesProcessed.set(0);
        totalSignaturesIndexed.set(0);
        failedFiles.set(0);
    }
    
    /**
     * Shutdown the coordinator.
     */
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * Strategy interface for customizing indexing behavior.
     */
    public interface IndexingStrategy {
        /**
         * Determine if a file should be indexed.
         */
        boolean shouldIndex(VirtualFile file, Long lastIndexTime);
        
        /**
         * Extract signatures from a file.
         */
        List<CodeSignature> extractSignatures(PsiFile file);
        
        /**
         * Index the extracted signatures.
         * @return number of signatures successfully indexed
         */
        int indexSignatures(List<CodeSignature> signatures, PsiFile psiFile, VirtualFile file);
    }
    
    /**
     * Result of batch indexing operation.
     */
    public static class BatchIndexingResult {
        private final int filesProcessed;
        private final int signaturesIndexed;
        private final int failedFiles;
        private final long durationMs;
        private final List<FileIndexResult> fileResults;
        
        public BatchIndexingResult(int filesProcessed, int signaturesIndexed, 
                                 int failedFiles, long durationMs,
                                 List<FileIndexResult> fileResults) {
            this.filesProcessed = filesProcessed;
            this.signaturesIndexed = signaturesIndexed;
            this.failedFiles = failedFiles;
            this.durationMs = durationMs;
            this.fileResults = fileResults;
        }
        
        public int getFilesProcessed() { return filesProcessed; }
        public int getSignaturesIndexed() { return signaturesIndexed; }
        public int getFailedFiles() { return failedFiles; }
        public long getDurationMs() { return durationMs; }
        public List<FileIndexResult> getFileResults() { return fileResults; }
        
        @Override
        public String toString() {
            return String.format("BatchIndexingResult{files=%d, signatures=%d, failed=%d, duration=%dms}",
                filesProcessed, signaturesIndexed, failedFiles, durationMs);
        }
    }
    
    /**
     * Result of indexing a single file.
     */
    public static class FileIndexResult {
        private final VirtualFile file;
        private final int signaturesIndexed;
        private final boolean success;
        private final String errorMessage;
        
        public FileIndexResult(VirtualFile file, int signaturesIndexed, boolean success, String errorMessage) {
            this.file = file;
            this.signaturesIndexed = signaturesIndexed;
            this.success = success;
            this.errorMessage = errorMessage;
        }
        
        public VirtualFile getFile() { return file; }
        public int getSignaturesIndexed() { return signaturesIndexed; }
        public boolean isSuccess() { return success; }
        public String getErrorMessage() { return errorMessage; }
    }
    
    /**
     * Container for batch file results.
     */
    private static class BatchFileIndexResult {
        private final List<FileIndexResult> results;
        
        public BatchFileIndexResult(List<FileIndexResult> results) {
            this.results = results;
        }
        
        public List<FileIndexResult> getResults() {
            return results;
        }
    }
}
