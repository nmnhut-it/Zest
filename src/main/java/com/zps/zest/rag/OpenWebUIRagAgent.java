package com.zps.zest.rag;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.search.GlobalSearchScope;
import com.zps.zest.ConfigurationManager;
import com.zps.zest.RagManagerProjectListener;
import com.zps.zest.rag.models.KnowledgeCollection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.VisibleForTesting;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Main RAG agent that manages code indexing and retrieval.
 * Designed with testability in mind using dependency injection.
 */
@Service(Service.Level.PROJECT)
public final class OpenWebUIRagAgent {
    private static final Logger LOG = Logger.getInstance(OpenWebUIRagAgent.class);
    private static final String KNOWLEDGE_NAME_PREFIX = "project-code-";
    
    private final Project project;
    private final ConfigurationManager config;
    private final CodeAnalyzer codeAnalyzer;
    private final KnowledgeApiClient apiClient;
    private final Map<String, List<CodeSignature>> signatureCache;
    private final Gson gson = new Gson();
    
    private volatile boolean isIndexing = false;
    private volatile boolean hasLocalIndex = false; // Track local index state

    // Production constructor
    public OpenWebUIRagAgent(Project project) {
        this(project, 
             ConfigurationManager.getInstance(project),
             new DefaultCodeAnalyzer(project),
             RagComponentFactory.createJSBridgeApiClient(project)); // Use JS bridge client
    }
    
    // Test-friendly constructor
    @VisibleForTesting
    OpenWebUIRagAgent(Project project,
                      ConfigurationManager config,
                      CodeAnalyzer codeAnalyzer,
                      @Nullable KnowledgeApiClient apiClient) {
        this.project = project;
        this.config = config;
        this.codeAnalyzer = codeAnalyzer;
        this.signatureCache = new ConcurrentHashMap<>();
        this.apiClient = apiClient;
    }

    public static OpenWebUIRagAgent getInstance(Project project) {
        return project.getService(OpenWebUIRagAgent.class);
    }

    /**
     * Builds a local index for the exploration agent tools (separate from OpenWebUI).
     * This index is used by the ImprovedToolCallingAutonomousAgent's RAG search tool.
     * @return CompletableFuture that completes with true if successful, false otherwise
     */
    public CompletableFuture<Boolean> buildLocalIndex() {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        
        if (isIndexing) {
            LOG.info("Local indexing already in progress");
            future.complete(false);
            return future;
        }

        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Building Local Code Index", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    isIndexing = true;
                    performLocalIndexing(indicator);
                    hasLocalIndex = true;
                    future.complete(true);
                } catch (Exception e) {
                    LOG.error("Error during local indexing", e);
                    hasLocalIndex = false;
                    future.complete(false);
                } finally {
                    isIndexing = false;
                }
            }
        });
        
        return future;
    }
    
    /**
     * Performs local indexing - only builds the signature cache, no OpenWebUI upload.
     */
    private void performLocalIndexing(ProgressIndicator indicator) {
        indicator.setText("Extracting project information...");
        
        // Clear existing cache
        signatureCache.clear();
        
        // Extract project info (we might use this for local search context)
        ProjectInfo projectInfo = ReadAction.compute(() -> codeAnalyzer.extractProjectInfo());
        
        indicator.setText("Building local code index...");
        
        // Index all source files locally
        List<VirtualFile> sourceFiles = ReadAction.compute(() -> codeAnalyzer.findAllSourceFiles());
        int total = sourceFiles.size();
        int current = 0;

        for (VirtualFile file : sourceFiles) {
            if (indicator.isCanceled()) break;
            
            current++;
            indicator.setText2("Processing " + file.getName() + " (" + current + "/" + total + ")");
            indicator.setFraction((double) current / total);

            try {
                // Extract signatures and cache them locally
                List<CodeSignature> signatures = ReadAction.compute(() -> {
                    PsiFile psiFile = PsiManager.getInstance(project).findFile(file);
                    if (psiFile != null) {
                        return codeAnalyzer.extractSignatures(psiFile);
                    }
                    return Collections.<CodeSignature>emptyList();
                });

                if (!signatures.isEmpty()) {
                    signatureCache.put(file.getPath(), signatures);
                }
            } catch (Exception e) {
                LOG.warn("Failed to index file locally: " + file.getPath(), e);
            }
        }

        indicator.setText("Local indexing complete");
        LOG.info("Local project indexing completed. Indexed " + current + " files.");
    }
    
    /**
     * Checks if a local index has been built for agent tools.
     */
    public boolean hasLocalIndex() {
        return hasLocalIndex && !signatureCache.isEmpty();
    }
    
    /**
     * Searches the local index (used by agent tools, not OpenWebUI).
     * This is what the ImprovedToolCallingAutonomousAgent's RAG tool uses.
     */
    public CompletableFuture<List<CodeMatch>> searchLocalIndex(String query) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (!hasLocalIndex()) {
                    LOG.warn("No local index available");
                    return Collections.emptyList();
                }

                // Search through local signature cache
                List<CodeMatch> matches = new ArrayList<>();
                
                for (Map.Entry<String, List<CodeSignature>> entry : signatureCache.entrySet()) {
                    for (CodeSignature sig : entry.getValue()) {
                        double relevance = calculateRelevance(sig, query);
                        if (relevance > 0.1) {
                            matches.add(new CodeMatch(sig, relevance));
                        }
                    }
                }

                // Sort by relevance
                matches.sort((a, b) -> Double.compare(b.getRelevance(), a.getRelevance()));
                
                // Limit results
                if (matches.size() > 20) {
                    matches = matches.subList(0, 20);
                }
                
                return matches;
            } catch (Exception e) {
                LOG.error("Error searching local index", e);
                return Collections.emptyList();
            }
        });
    }

    /**
     * Indexes the entire project.
     * @return CompletableFuture that completes with true if successful, false otherwise
     */
    public CompletableFuture<Boolean> indexProject(boolean forceRefresh) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        
        if (isIndexing) {
            LOG.info("Indexing already in progress");
            future.complete(false);
            return future;
        }

        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Indexing Project for RAG", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    isIndexing = true;
                    performIndexing(indicator, forceRefresh);
                    future.complete(true);
                } catch (Exception e) {
                    LOG.error("Error during indexing", e);
                    future.complete(false);
                } finally {
                    isIndexing = false;
                }
            }
        });
        
        return future;
    }
    
    /**
     * Updates a single file in the existing index.
     * This is used by the file change listener to keep the index up to date.
     */
    public void updateFileInIndex(String knowledgeId, VirtualFile file) throws IOException {
        LOG.info("Updating file in index: " + file.getPath());
        
        // First, try to find and remove the old version of this file from the knowledge base
        String oldFileName = file.getNameWithoutExtension() + "-signatures.md";
        
        try {
            // Get the knowledge collection to find the existing file
            KnowledgeCollection collection = apiClient.getKnowledgeCollection(knowledgeId);
            if (collection != null && collection.getFiles() != null) {
                // Find and remove the old file if it exists
                collection.getFiles().stream()
                    .filter(f -> f.getMeta().getName().equals(oldFileName))
                    .findFirst()
                    .ifPresent(f -> {
                        try {
                            LOG.info("Removing old version of file from knowledge base: " + oldFileName);
                            // Note: You might need to add a removeFileFromKnowledge method to the API client
                            // For now, we'll just re-upload which should replace the old version
                        } catch (Exception e) {
                            LOG.warn("Failed to remove old file version", e);
                        }
                    });
            }
        } catch (Exception e) {
            LOG.warn("Failed to check for existing file version", e);
        }
        
        // Re-index the file
        indexFile(knowledgeId, file);
        
        // Update local cache if present
        if (hasLocalIndex()) {
            List<CodeSignature> signatures = ReadAction.compute(() -> {
                PsiFile psiFile = PsiManager.getInstance(project).findFile(file);
                if (psiFile != null) {
                    return codeAnalyzer.extractSignatures(psiFile);
                }
                return Collections.<CodeSignature>emptyList();
            });
            
            if (!signatures.isEmpty()) {
                signatureCache.put(file.getPath(), signatures);
            } else {
                signatureCache.remove(file.getPath());
            }
        }
    }
    
    /**
     * Performs the actual indexing - extracted for testability.
     */
    @VisibleForTesting
    void performIndexing(ProgressIndicator indicator, boolean forceRefresh) throws IOException {
        indicator.setText("Preparing knowledge base...");
        
        // Get or create knowledge base
        String knowledgeId = config.getKnowledgeId();
        boolean needNewKnowledgeBase = false;
        
        if (knowledgeId == null || knowledgeId.isEmpty()) {
            needNewKnowledgeBase = true;
        } else if (!forceRefresh) {
            // Validate existing knowledge ID
            indicator.setText("Validating existing knowledge base...");
            try {
                if (!apiClient.knowledgeExists(knowledgeId)) {
                    LOG.warn("Existing knowledge base not found: " + knowledgeId);
                    // Clear the invalid ID from config
                    config.setKnowledgeId(null);
                    config.saveConfig();
                    needNewKnowledgeBase = true;
                } else {
                    LOG.info("Using existing knowledge base: " + knowledgeId);
                }
            } catch (IOException e) {
                LOG.warn("Failed to validate existing knowledge base: " + e.getMessage());
                // Clear the ID and try to create new one
                config.setKnowledgeId(null);
                config.saveConfig();
                needNewKnowledgeBase = true;
            }
        } else {
            // Force refresh requested - clear existing ID
            config.setKnowledgeId(null);
            config.saveConfig();
            needNewKnowledgeBase = true;
        }
        
        if (needNewKnowledgeBase) {
            indicator.setText("Creating new knowledge base...");
            
            // Generate a unique name to avoid conflicts
            String timestamp = String.valueOf(System.currentTimeMillis());
            String uniqueName = KNOWLEDGE_NAME_PREFIX + project.getName() + "-" + timestamp.substring(timestamp.length() - 6);
            
            try {
                knowledgeId = apiClient.createKnowledgeBase(
                    uniqueName,
                    "Code signatures and project info for " + project.getName()
                );
                config.setKnowledgeId(knowledgeId);
                config.saveConfig();
                LOG.info("Created new knowledge base: " + knowledgeId + " with name: " + uniqueName);
            } catch (IOException e) {
                if (e.getMessage().contains("422")) {
                    // Try with a different name
                    String alternativeName = KNOWLEDGE_NAME_PREFIX + project.getName() + "-" + UUID.randomUUID().toString().substring(0, 8);
                    LOG.warn("Got 422 error, trying with alternative name: " + alternativeName);
                    
                    knowledgeId = apiClient.createKnowledgeBase(
                        alternativeName,
                        "Code signatures and project info for " + project.getName()
                    );
                    config.setKnowledgeId(knowledgeId);
                    config.saveConfig();
                    LOG.info("Created new knowledge base with alternative name: " + knowledgeId);
                } else {
                    throw e;
                }
            }
        }

        // Now we have a valid knowledge ID, verify it one more time before proceeding
        // Add retries with delays to handle timing issues
        boolean verified = false;
        int retries = 3;
        
        for (int i = 0; i < retries; i++) {
            try {
                // Add a delay before verification to allow server to fully process
                if (i > 0) {
                    Thread.sleep(2000 * i); // Exponential backoff: 0s, 2s, 4s
                }
                
                if (apiClient.knowledgeExists(knowledgeId)) {
                    verified = true;
                    LOG.info("Knowledge base verified successfully on attempt " + (i + 1));
                    break;
                }
            } catch (Exception e) {
                LOG.warn("Knowledge base verification attempt " + (i + 1) + " failed: " + e.getMessage());
            }
        }
        
        if (!verified) {
            LOG.error("Failed to verify knowledge base after " + retries + " attempts");
            // Clear the invalid ID
            config.setKnowledgeId(null);
            config.saveConfig();
            throw new IOException("Knowledge base verification failed after multiple attempts");
        }

        indicator.setText("Extracting project information...");
        
        // Extract project info
        ProjectInfo projectInfo = ReadAction.compute(() -> codeAnalyzer.extractProjectInfo());
        
        // Create project overview document
        String projectOverview = createProjectOverviewDocument(projectInfo);
        uploadDocument(knowledgeId, "project-overview.md", projectOverview);

        indicator.setText("Indexing source files...");
        
        // Index all source files
        List<VirtualFile> sourceFiles = ReadAction.compute(() -> codeAnalyzer.findAllSourceFiles());
        int total = sourceFiles.size();
        int current = 0;

        for (VirtualFile file : sourceFiles) {
            if (indicator.isCanceled()) break;
            
            current++;
            indicator.setText2("Processing " + file.getName() + " (" + current + "/" + total + ")");
            indicator.setFraction((double) current / total);

            try {
                indexFile(knowledgeId, file);
            } catch (Exception e) {
                LOG.warn("Failed to index file: " + file.getPath(), e);
            }
        }

        indicator.setText("Indexing complete");
        LOG.info("Project indexing completed. Indexed " + current + " files.");
        
        // Set up file change listener to keep index updated
        ApplicationManager.getApplication().invokeLater(() -> {
            RagManagerProjectListener.onIndexingComplete(project);
        });
    }
    
    @VisibleForTesting
    void indexFile(String knowledgeId, VirtualFile file) throws IOException {
        List<CodeSignature> signatures = ReadAction.compute(() -> {
            PsiFile psiFile = PsiManager.getInstance(project).findFile(file);
            if (psiFile != null) {
                return codeAnalyzer.extractSignatures(psiFile);
            }
            return Collections.<CodeSignature>emptyList();
        });

        if (!signatures.isEmpty()) {
            signatureCache.put(file.getPath(), signatures);
            
            String content = createSignatureDocument(file, signatures);
            String fileName = file.getNameWithoutExtension() + "-signatures.md";
            uploadDocument(knowledgeId, fileName, content);
        }
    }

    /**
     * Queries the knowledge base for related code.
     */
    public CompletableFuture<List<CodeMatch>> findRelatedCode(String query) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String knowledgeId = config.getKnowledgeId();
                if (knowledgeId == null) {
                    LOG.warn("No knowledge base configured");
                    return Collections.emptyList();
                }

                // Query the knowledge base
                List<String> relevantDocs = queryKnowledgeBase(knowledgeId, query);
                
                // Extract code matches from relevant documents
                List<CodeMatch> matches = new ArrayList<>();
                for (String docId : relevantDocs) {
                    matches.addAll(extractCodeMatches(docId, query));
                }

                // Sort by relevance
                matches.sort((a, b) -> Double.compare(b.getRelevance(), a.getRelevance()));
                
                return matches;
            } catch (Exception e) {
                LOG.error("Error querying knowledge base", e);
                return Collections.emptyList();
            }
        });
    }

    /**
     * Gets the full code for a specific signature ID.
     */
    public String getFullCode(String signatureId) {
        if (signatureId == null || signatureId.isEmpty()) {
            return null;
        }
        
        return ReadAction.compute(() -> {
            try {
                if (signatureId.contains("#")) {
                    // Method signature
                    String[] parts = signatureId.split("#", 2);
                    if (parts.length != 2) {
                        return null;
                    }
                    
                    String className = parts[0];
                    String methodName = parts[1];
                    
                    PsiClass psiClass = JavaPsiFacade.getInstance(project)
                        .findClass(className, GlobalSearchScope.projectScope(project));
                    
                    if (psiClass != null) {
                        for (PsiMethod method : psiClass.getMethods()) {
                            if (methodName.equals(method.getName())) {
                                return method.getText();
                            }
                        }
                    }
                } else {
                    // Class signature
                    PsiClass psiClass = JavaPsiFacade.getInstance(project)
                        .findClass(signatureId, GlobalSearchScope.projectScope(project));
                    
                    if (psiClass != null) {
                        return psiClass.getText();
                    }
                }
            } catch (Exception e) {
                LOG.error("Error getting full code for: " + signatureId, e);
            }
            return null;
        });
    }

    /**
     * Checks if indexing is currently in progress.
     */
    public boolean isIndexing() {
        return isIndexing;
    }
    /**
     * 
     * @param knowledgeId The knowledge collection ID
     * @return The complete knowledge collection with all metadata
     */
    public KnowledgeCollection getKnowledgeCollection(String knowledgeId) {
        try {
            return apiClient.getKnowledgeCollection(knowledgeId);
        } catch (IOException e) {
            LOG.error("Error fetching knowledge collection: " + knowledgeId, e);
            return null;
        }
    }



    private void uploadDocument(String knowledgeId, String fileName, String content) throws IOException {
        String fileId = apiClient.uploadFile(fileName, content);
        
        // Add a small delay between upload and adding to knowledge
        try {
            Thread.sleep(1000); // 1 second delay
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        apiClient.addFileToKnowledge(knowledgeId, fileId);
        
        // Add another small delay after adding to knowledge
        try {
            Thread.sleep(500); // 500ms delay
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private List<String> queryKnowledgeBase(String knowledgeId, String query) throws IOException {
        // For MVP, we'll use our local cache
        List<String> docIds = new ArrayList<>();
        for (String path : signatureCache.keySet()) {
            docIds.add(path);
        }
        return docIds;
    }

    @VisibleForTesting
    List<CodeMatch> extractCodeMatches(String docId, String query) {
        List<CodeMatch> matches = new ArrayList<>();
        
        for (Map.Entry<String, List<CodeSignature>> entry : signatureCache.entrySet()) {
            for (CodeSignature sig : entry.getValue()) {
                double relevance = calculateRelevance(sig, query);
                if (relevance > 0.1) {
                    matches.add(new CodeMatch(sig, relevance));
                }
            }
        }
        
        return matches;
    }

    @VisibleForTesting
    double calculateRelevance(CodeSignature signature, String query) {
        String lowerQuery = query.toLowerCase();
        String lowerSig = signature.getSignature().toLowerCase();
        String lowerId = signature.getId().toLowerCase();
        
        // Simple relevance scoring
        double score = 0.0;
        
        if (lowerId.contains(lowerQuery)) score += 1.0;
        if (lowerSig.contains(lowerQuery)) score += 0.5;
        
        // Check individual words
        String[] queryWords = lowerQuery.split("\\s+");
        for (String word : queryWords) {
            if (lowerId.contains(word)) score += 0.3;
            if (lowerSig.contains(word)) score += 0.2;
        }
        
        return Math.min(score, 1.0);
    }

    @VisibleForTesting
    String createProjectOverviewDocument(ProjectInfo info) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Project Overview: ").append(project.getName()).append("\n\n");
        
        sb.append("## Technologies & Frameworks\n\n");
        sb.append("**Build System:** ").append(info.getBuildSystem()).append("\n\n");
        
        if (!info.getDependencies().isEmpty()) {
            sb.append("### Dependencies\n\n");
            for (String dep : info.getDependencies()) {
                sb.append("- ").append(dep).append("\n");
            }
            sb.append("\n");
        }
        
        if (!info.getLibraries().isEmpty()) {
            sb.append("### Libraries (from lib folder)\n\n");
            for (String lib : info.getLibraries()) {
                sb.append("- ").append(lib).append("\n");
            }
            sb.append("\n");
        }
        
        sb.append("## Project Structure\n\n");
        sb.append("- **Total Source Files:** ").append(info.getTotalSourceFiles()).append("\n");
        sb.append("- **Main Language:** ").append(info.getMainLanguage()).append("\n");
        
        return sb.toString();
    }

    @VisibleForTesting
    String createSignatureDocument(VirtualFile file, List<CodeSignature> signatures) {
        StringBuilder sb = new StringBuilder();
        
        sb.append("---\n");
        sb.append("file: ").append(file.getPath()).append("\n");
        sb.append("type: code-signatures\n");
        sb.append("---\n\n");
        
        sb.append("# Code Signatures: ").append(file.getName()).append("\n\n");
        
        // Group by type
        Map<String, List<CodeSignature>> grouped = new HashMap<>();
        for (CodeSignature sig : signatures) {
            String type = gson.fromJson(sig.getMetadata(), JsonObject.class).get("type").getAsString();
            grouped.computeIfAbsent(type, k -> new ArrayList<>()).add(sig);
        }
        
        // Classes
        if (grouped.containsKey("class")) {
            sb.append("## Classes\n\n");
            for (CodeSignature sig : grouped.get("class")) {
                JsonObject metadata = gson.fromJson(sig.getMetadata(), JsonObject.class);
                sb.append("### `").append(sig.getId()).append("`\n");
                
                // Add javadoc if present
                if (metadata.has("javadoc") && !metadata.get("javadoc").isJsonNull()) {
                    sb.append("\n").append(metadata.get("javadoc").getAsString()).append("\n\n");
                }
                
                sb.append("```java\n").append(sig.getSignature()).append("\n```\n\n");
            }
        }
        
        // Interfaces
        if (grouped.containsKey("interface")) {
            sb.append("## Interfaces\n\n");
            for (CodeSignature sig : grouped.get("interface")) {
                JsonObject metadata = gson.fromJson(sig.getMetadata(), JsonObject.class);
                sb.append("### `").append(sig.getId()).append("`\n");
                
                // Add javadoc if present
                if (metadata.has("javadoc") && !metadata.get("javadoc").isJsonNull()) {
                    sb.append("\n").append(metadata.get("javadoc").getAsString()).append("\n\n");
                }
                
                sb.append("```java\n").append(sig.getSignature()).append("\n```\n\n");
            }
        }
        
        // Enums
        if (grouped.containsKey("enum")) {
            sb.append("## Enums\n\n");
            for (CodeSignature sig : grouped.get("enum")) {
                JsonObject metadata = gson.fromJson(sig.getMetadata(), JsonObject.class);
                sb.append("### `").append(sig.getId()).append("`\n");
                
                // Add javadoc if present
                if (metadata.has("javadoc") && !metadata.get("javadoc").isJsonNull()) {
                    sb.append("\n").append(metadata.get("javadoc").getAsString()).append("\n\n");
                }
                
                sb.append("```java\n").append(sig.getSignature()).append("\n```\n\n");
            }
        }
        
        // Annotations
        if (grouped.containsKey("annotation")) {
            sb.append("## Annotations\n\n");
            for (CodeSignature sig : grouped.get("annotation")) {
                JsonObject metadata = gson.fromJson(sig.getMetadata(), JsonObject.class);
                sb.append("### `").append(sig.getId()).append("`\n");
                
                // Add javadoc if present
                if (metadata.has("javadoc") && !metadata.get("javadoc").isJsonNull()) {
                    sb.append("\n").append(metadata.get("javadoc").getAsString()).append("\n\n");
                }
                
                sb.append("```java\n").append(sig.getSignature()).append("\n```\n\n");
            }
        }
        
        // Methods
        if (grouped.containsKey("method")) {
            sb.append("## Methods\n\n");
            for (CodeSignature sig : grouped.get("method")) {
                JsonObject metadata = gson.fromJson(sig.getMetadata(), JsonObject.class);
                sb.append("- `").append(sig.getId()).append("`\n");
                
                // Add javadoc if present
                if (metadata.has("javadoc") && !metadata.get("javadoc").isJsonNull()) {
                    sb.append("  \n  ").append(metadata.get("javadoc").getAsString().replace("\n", "\n  ")).append("\n");
                }
                
                sb.append("  ```java\n  ").append(sig.getSignature()).append("\n  ```\n");
            }
            sb.append("\n");
        }
        
        // Fields
        if (grouped.containsKey("field")) {
            sb.append("## Fields\n\n");
            for (CodeSignature sig : grouped.get("field")) {
                JsonObject metadata = gson.fromJson(sig.getMetadata(), JsonObject.class);
                sb.append("- `").append(sig.getSignature()).append("`");
                
                // Add javadoc if present
                if (metadata.has("javadoc") && !metadata.get("javadoc").isJsonNull()) {
                    sb.append(" - ").append(metadata.get("javadoc").getAsString().replace("\n", " "));
                }
                sb.append("\n");
            }
        }
        
        return sb.toString();
    }

    /**
     * Gets the signature cache for testing.
     */
    @VisibleForTesting
    Map<String, List<CodeSignature>> getSignatureCache() {
        return new HashMap<>(signatureCache);
    }

    /**
     * Represents a code match from the knowledge base.
     */
    public static class CodeMatch {
        private final CodeSignature signature;
        private final double relevance;

        public CodeMatch(CodeSignature signature, double relevance) {
            this.signature = signature;
            this.relevance = relevance;
        }

        public CodeSignature getSignature() {
            return signature;
        }

        public double getRelevance() {
            return relevance;
        }
    }
}
