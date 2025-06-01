package com.zps.zest.rag;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
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
import com.intellij.psi.PsiMethod;
import com.intellij.psi.search.GlobalSearchScope;
import com.zps.zest.ConfigurationManager;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Main RAG agent that manages code indexing and retrieval.
 */
@Service(Service.Level.PROJECT)
public final class RagAgent {
    private static final Logger LOG = Logger.getInstance(RagAgent.class);
    private static final String KNOWLEDGE_NAME_PREFIX = "project-code-";
    
    private final Project project;
    private final ConfigurationManager config;
    private final SignatureExtractor signatureExtractor;
    private final ProjectInfoExtractor projectInfoExtractor;
    private final Gson gson = new Gson();
    
    // Cache for signatures to avoid re-extraction
    private final Map<String, List<CodeSignature>> signatureCache = new ConcurrentHashMap<>();
    private volatile boolean isIndexing = false;

    public RagAgent(Project project) {
        this.project = project;
        this.config = ConfigurationManager.getInstance(project);
        this.signatureExtractor = new SignatureExtractor();
        this.projectInfoExtractor = new ProjectInfoExtractor(project);
    }

    public static RagAgent getInstance(Project project) {
        return project.getService(RagAgent.class);
    }

    /**
     * Indexes the entire project.
     */
    public void indexProject(boolean forceRefresh) {
        if (isIndexing) {
            LOG.info("Indexing already in progress");
            return;
        }

        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Indexing Project for RAG", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    isIndexing = true;
                    performIndexing(indicator, forceRefresh);
                } catch (Exception e) {
                    LOG.error("Error during indexing", e);
                } finally {
                    isIndexing = false;
                }
            }
        });
    }

    private void performIndexing(ProgressIndicator indicator, boolean forceRefresh) throws IOException {
        indicator.setText("Preparing knowledge base...");
        
        // Get or create knowledge base
        String knowledgeId = config.getKnowledgeId();
        if (knowledgeId == null || forceRefresh) {
            knowledgeId = createOrResetKnowledgeBase();
            config.setKnowledgeId(knowledgeId);
            config.saveConfig();
        }

        indicator.setText("Extracting project information...");
        
        // Extract project info
        ProjectInfo projectInfo = ReadAction.compute(() -> projectInfoExtractor.extractProjectInfo());
        
        // Create project overview document
        String projectOverview = createProjectOverviewDocument(projectInfo);
        uploadDocument(knowledgeId, "project-overview.md", projectOverview);

        indicator.setText("Indexing source files...");
        
        // Index all source files
        List<VirtualFile> sourceFiles = ReadAction.compute(() -> projectInfoExtractor.findAllSourceFiles());
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
    }

    private void indexFile(String knowledgeId, VirtualFile file) throws IOException {
        List<CodeSignature> signatures = ReadAction.compute(() -> {
            var psiFile = com.intellij.psi.PsiManager.getInstance(project).findFile(file);
            if (psiFile != null) {
                return signatureExtractor.extractFromFile(psiFile);
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
        return ReadAction.compute(() -> {
            try {
                if (signatureId.contains("#")) {
                    // Method signature
                    String[] parts = signatureId.split("#");
                    String className = parts[0];
                    String methodName = parts[1];
                    
                    PsiClass psiClass = JavaPsiFacade.getInstance(project)
                        .findClass(className, GlobalSearchScope.projectScope(project));
                    
                    if (psiClass != null) {
                        for (PsiMethod method : psiClass.getMethods()) {
                            if (method.getName().equals(methodName)) {
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

    private String createOrResetKnowledgeBase() throws IOException {
        String apiUrl = config.getApiUrl().replace("/chat/completions", "") + "/v1/knowledge/create";
        URL url = new URL(apiUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + config.getAuthToken());
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);

        JsonObject body = new JsonObject();
        body.addProperty("name", KNOWLEDGE_NAME_PREFIX + project.getName());
        body.addProperty("description", "Code signatures and project info for " + project.getName());
        
        try (var writer = new java.io.OutputStreamWriter(conn.getOutputStream())) {
            writer.write(body.toString());
        }

        if (conn.getResponseCode() == 200) {
            try (var reader = new java.io.InputStreamReader(conn.getInputStream())) {
                JsonObject response = gson.fromJson(reader, JsonObject.class);
                return response.get("id").getAsString();
            }
        } else {
            throw new IOException("Failed to create knowledge base: " + conn.getResponseCode());
        }
    }

    private void uploadDocument(String knowledgeId, String fileName, String content) throws IOException {
        // First, create the file
        String fileId = uploadFile(fileName, content);
        
        // Then add it to the knowledge base
        addFileToKnowledge(knowledgeId, fileId);
    }

    private String uploadFile(String fileName, String content) throws IOException {
        String apiUrl = config.getApiUrl().replace("/chat/completions", "") + "/v1/files/";
        URL url = new URL(apiUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + config.getAuthToken());
        conn.setDoOutput(true);

        String boundary = "Boundary-" + System.currentTimeMillis();
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

        try (var os = conn.getOutputStream()) {
            // Write file part
            os.write(("--" + boundary + "\r\n").getBytes());
            os.write(("Content-Disposition: form-data; name=\"file\"; filename=\"" + fileName + "\"\r\n").getBytes());
            os.write(("Content-Type: text/markdown\r\n\r\n").getBytes());
            os.write(content.getBytes(StandardCharsets.UTF_8));
            os.write(("\r\n--" + boundary + "--\r\n").getBytes());
        }

        if (conn.getResponseCode() == 200) {
            try (var reader = new java.io.InputStreamReader(conn.getInputStream())) {
                JsonObject response = gson.fromJson(reader, JsonObject.class);
                return response.get("id").getAsString();
            }
        } else {
            throw new IOException("Failed to upload file: " + conn.getResponseCode());
        }
    }

    private void addFileToKnowledge(String knowledgeId, String fileId) throws IOException {
        String apiUrl = config.getApiUrl().replace("/chat/completions", "") + "/v1/knowledge/" + knowledgeId + "/file/add";
        URL url = new URL(apiUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + config.getAuthToken());
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);

        JsonObject body = new JsonObject();
        body.addProperty("file_id", fileId);
        
        try (var writer = new java.io.OutputStreamWriter(conn.getOutputStream())) {
            writer.write(body.toString());
        }

        if (conn.getResponseCode() != 200) {
            throw new IOException("Failed to add file to knowledge: " + conn.getResponseCode());
        }
    }

    private List<String> queryKnowledgeBase(String knowledgeId, String query) throws IOException {
        // For MVP, we'll use a simple approach:
        // The knowledge base is already integrated with OpenWebUI's chat completions
        // So we don't need a separate query endpoint
        
        // Instead, we'll search through our cached signatures
        List<String> docIds = new ArrayList<>();
        
        // This is a placeholder - in production, you'd query the actual knowledge base
        // For now, we'll use our local cache
        for (String path : signatureCache.keySet()) {
            docIds.add(path);
        }
        
        return docIds;
    }

    private List<CodeMatch> extractCodeMatches(String docId, String query) {
        // Extract matches from cached signatures
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

    private double calculateRelevance(CodeSignature signature, String query) {
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

    private String createProjectOverviewDocument(ProjectInfo info) {
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

    private String createSignatureDocument(VirtualFile file, List<CodeSignature> signatures) {
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
                sb.append("### `").append(sig.getId()).append("`\n");
                sb.append("```java\n").append(sig.getSignature()).append("\n```\n\n");
            }
        }
        
        // Methods
        if (grouped.containsKey("method")) {
            sb.append("## Methods\n\n");
            for (CodeSignature sig : grouped.get("method")) {
                sb.append("- `").append(sig.getId()).append("`\n");
                sb.append("  ```java\n  ").append(sig.getSignature()).append("\n  ```\n");
            }
            sb.append("\n");
        }
        
        // Fields
        if (grouped.containsKey("field")) {
            sb.append("## Fields\n\n");
            for (CodeSignature sig : grouped.get("field")) {
                sb.append("- `").append(sig.getSignature()).append("`\n");
            }
        }
        
        return sb.toString();
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
