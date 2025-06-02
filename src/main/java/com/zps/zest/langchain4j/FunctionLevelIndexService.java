package com.zps.zest.langchain4j;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.zps.zest.rag.CodeSignature;
import com.zps.zest.rag.ProjectInfo;
import com.zps.zest.rag.SignatureExtractor;
import com.zps.zest.rag.ProjectInfoExtractor;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Service that indexes project code at function level for semantic search.
 * Uses SignatureExtractor to extract functions and stores them in InMemoryVectorStore.
 */
@Service(Service.Level.PROJECT)
public final class FunctionLevelIndexService {
    private static final Logger LOG = Logger.getInstance(FunctionLevelIndexService.class);
    
    private final Project project;
    private final RagService ragService;
    private final SignatureExtractor signatureExtractor;
    private final ProjectInfoExtractor projectInfoExtractor;
    
    // Track indexed files to avoid re-indexing
    private final Map<String, Long> indexedFiles = new ConcurrentHashMap<>();
    private volatile boolean isIndexing = false;
    
    // Statistics
    private final AtomicInteger totalFunctionsIndexed = new AtomicInteger(0);
    private final AtomicInteger totalClassesIndexed = new AtomicInteger(0);
    private final AtomicInteger totalFilesIndexed = new AtomicInteger(0);
    
    public FunctionLevelIndexService(Project project) {
        this.project = project;
        this.ragService = project.getService(RagService.class);
        this.signatureExtractor = new SignatureExtractor();
        this.projectInfoExtractor = new ProjectInfoExtractor(project);
        
        LOG.info("Initialized FunctionLevelIndexService for project: " + project.getName());
    }
    
    /**
     * Indexes the entire project at function level.
     * 
     * @param forceReindex If true, re-indexes all files even if already indexed
     */
    public void indexProject(boolean forceReindex) {
        if (isIndexing) {
            LOG.info("Indexing already in progress");
            return;
        }
        
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Function-Level Indexing", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    isIndexing = true;
                    performIndexing(indicator, forceReindex);
                } catch (Exception e) {
                    LOG.error("Error during function-level indexing", e);
                } finally {
                    isIndexing = false;
                }
            }
        });
    }
    
    /**
     * Indexes a single file at function level.
     * 
     * @param file The file to index
     * @return Number of signatures indexed
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
                    indexFunctionSignature(signature, file);
                    indexedCount++;
                    
                    // Update statistics
                    String type = extractSignatureType(signature);
                    if ("method".equals(type)) {
                        totalFunctionsIndexed.incrementAndGet();
                    } else if ("class".equals(type) || "interface".equals(type) || 
                               "enum".equals(type) || "annotation".equals(type)) {
                        totalClassesIndexed.incrementAndGet();
                    }
                }
                
                // Mark file as indexed
                indexedFiles.put(file.getPath(), file.getModificationStamp());
                totalFilesIndexed.incrementAndGet();
                
                LOG.info("Indexed " + indexedCount + " signatures from: " + file.getName());
                return indexedCount;
                
            } catch (Exception e) {
                LOG.error("Failed to index file: " + file.getPath(), e);
                return 0;
            }
        });
    }
    
    /**
     * Checks if a file needs re-indexing.
     */
    public boolean needsReindex(VirtualFile file) {
        Long lastIndexed = indexedFiles.get(file.getPath());
        return lastIndexed == null || lastIndexed < file.getModificationStamp();
    }
    
    /**
     * Gets indexing statistics.
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("total_files_indexed", totalFilesIndexed.get());
        stats.put("total_functions_indexed", totalFunctionsIndexed.get());
        stats.put("total_classes_indexed", totalClassesIndexed.get());
        stats.put("is_indexing", isIndexing);
        
        // Get vector store statistics
        Map<String, Object> vectorStats = ragService.getStatistics();
        stats.put("vector_store", vectorStats);
        
        return stats;
    }
    
    /**
     * Clears the index and resets statistics.
     */
    public void clearIndex() {
        ragService.clearIndex();
        indexedFiles.clear();
        totalFunctionsIndexed.set(0);
        totalClassesIndexed.set(0);
        totalFilesIndexed.set(0);
        LOG.info("Cleared function-level index");
    }
    
    private void performIndexing(ProgressIndicator indicator, boolean forceReindex) {
        indicator.setText("Extracting project structure...");
        
        // First, index project overview
        ProjectInfo projectInfo = ReadAction.compute(() -> projectInfoExtractor.extractProjectInfo());
        indexProjectOverview(projectInfo);
        
        // Get all source files
        List<VirtualFile> sourceFiles = ReadAction.compute(() -> projectInfoExtractor.findAllSourceFiles());
        
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
        
        indicator.setText("Function-level indexing complete");
        LOG.info("Completed function-level indexing. Stats: " + getStatistics());
    }
    
    private void indexProjectOverview(ProjectInfo projectInfo) {
        try {
            // Create a searchable project overview
            StringBuilder content = new StringBuilder();
            content.append("Project: ").append(project.getName()).append("\n");
            content.append("Build System: ").append(projectInfo.getBuildSystem()).append("\n");
            content.append("Main Language: ").append(projectInfo.getMainLanguage()).append("\n");
            content.append("Total Source Files: ").append(projectInfo.getTotalSourceFiles()).append("\n\n");
            
            if (!projectInfo.getDependencies().isEmpty()) {
                content.append("Dependencies:\n");
                projectInfo.getDependencies().forEach(dep -> 
                    content.append("- ").append(dep).append("\n"));
            }
            
            // Index as a special document
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("type", "project_overview");
            metadata.put("project_name", project.getName());
            metadata.put("build_system", projectInfo.getBuildSystem());
            
            ragService.indexCodeSignature(new CodeSignature(
                "project.overview",
                "Project Overview",
                new JsonObject().toString(),
                project.getBasePath()
            ));
            
        } catch (Exception e) {
            LOG.error("Failed to index project overview", e);
        }
    }
    
    private void indexFunctionSignature(CodeSignature signature, VirtualFile file) {
        try {
            // Build rich content for embedding
            StringBuilder content = new StringBuilder();
            
            // Add signature
            content.append(signature.getSignature()).append("\n\n");
            
            // Add context from metadata
            JsonObject metadata = JsonParser.parseString(signature.getMetadata()).getAsJsonObject();
            
            // Add type information
            String type = metadata.has("type") ? metadata.get("type").getAsString() : "unknown";
            content.append("Type: ").append(type).append("\n");
            
            // Add qualified name
            if (metadata.has("qualifiedName")) {
                content.append("Qualified Name: ").append(metadata.get("qualifiedName").getAsString()).append("\n");
            } else if (metadata.has("class") && metadata.has("name")) {
                content.append("Location: ").append(metadata.get("class").getAsString())
                       .append("#").append(metadata.get("name").getAsString()).append("\n");
            }
            
            // Add package
            if (metadata.has("package")) {
                content.append("Package: ").append(metadata.get("package").getAsString()).append("\n");
            }
            
            // Add javadoc if present
            if (metadata.has("javadoc") && !metadata.get("javadoc").isJsonNull()) {
                String javadoc = metadata.get("javadoc").getAsString();
                if (!javadoc.isEmpty()) {
                    content.append("\n").append(javadoc).append("\n");
                }
            }
            
            // Add return type for methods
            if ("method".equals(type) && metadata.has("returnType")) {
                content.append("Returns: ").append(metadata.get("returnType").getAsString()).append("\n");
            }
            
            // Store in vector store with rich metadata
            Map<String, Object> storeMetadata = new HashMap<>();
            storeMetadata.put("type", type);
            storeMetadata.put("signature_id", signature.getId());
            storeMetadata.put("file_path", file.getPath());
            storeMetadata.put("file_name", file.getName());
            storeMetadata.put("project_name", project.getName());
            
            // Add searchable attributes
            if (metadata.has("qualifiedName")) {
                storeMetadata.put("qualified_name", metadata.get("qualifiedName").getAsString());
            }
            if (metadata.has("package")) {
                storeMetadata.put("package", metadata.get("package").getAsString());
            }
            if (metadata.has("class")) {
                storeMetadata.put("class_name", metadata.get("class").getAsString());
            }
            if (metadata.has("name")) {
                storeMetadata.put("element_name", metadata.get("name").getAsString());
            }
            
            // Add flags
            if (metadata.has("isStatic")) {
                storeMetadata.put("is_static", metadata.get("isStatic").getAsBoolean());
            }
            if (metadata.has("isAbstract")) {
                storeMetadata.put("is_abstract", metadata.get("isAbstract").getAsBoolean());
            }
            if (metadata.has("isInterface")) {
                storeMetadata.put("is_interface", metadata.get("isInterface").getAsBoolean());
            }
            
            // Index the signature
            ragService.indexCodeSignature(new CodeSignature(
                signature.getId(),
                content.toString(),
                signature.getMetadata(),
                signature.getFilePath()
            ));
            
        } catch (Exception e) {
            LOG.error("Failed to index signature: " + signature.getId(), e);
        }
    }
    
    private String extractSignatureType(CodeSignature signature) {
        try {
            JsonObject metadata = JsonParser.parseString(signature.getMetadata()).getAsJsonObject();
            return metadata.has("type") ? metadata.get("type").getAsString() : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }
    
    private boolean isSourceFile(VirtualFile file) {
        if (file == null || file.isDirectory()) {
            return false;
        }
        String name = file.getName();
        return name.endsWith(".java") || name.endsWith(".kt") || name.endsWith(".kts");
    }
}
