package com.zps.zest.langchain4j.tools.impl;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.zps.zest.ConfigurationManager;
import com.zps.zest.langchain4j.LocalEmbeddingService;
import com.zps.zest.langchain4j.index.DocumentationIndex;
import com.zps.zest.langchain4j.tools.ThreadSafeCodeExplorationTool;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * Tool for searching documentation using LangChain4j semantic search.
 * Searches through markdown files and code samples in the configured docs folder.
 */
public class SearchDocsTool extends ThreadSafeCodeExplorationTool {
    private static final Logger LOG = Logger.getInstance(SearchDocsTool.class);
    
    private final DocumentationIndex docIndex;
    private final String docsPath;
    private final AtomicBoolean isIndexed = new AtomicBoolean(false);
    private final AtomicBoolean isIndexing = new AtomicBoolean(false);
    private final ExecutorService indexingExecutor = Executors.newSingleThreadExecutor();
    
    public SearchDocsTool(@NotNull Project project) {
        super(project, "search_docs",
            "Search documentation files using natural language queries. " +
            "Searches through markdown files and code samples in the docs folder. " +
            "The tool automatically indexes documentation on first use. " +
            "Examples: " +
            "- search_docs({\"query\": \"how to configure RAG system\"}) " +
            "- search_docs({\"query\": \"authentication setup guide\", \"maxResults\": 5}) " +
            "- search_docs({\"query\": \"inline chat feature\", \"includeCodeBlocks\": true}) " +
            "Params: query (string, required), maxResults (int, optional, default 5), " +
            "includeCodeBlocks (boolean, optional, default false)");
        
        // Initialize with local embedding service
        LocalEmbeddingService embeddingService = new LocalEmbeddingService();
        this.docIndex = new DocumentationIndex(embeddingService);
        
        // Get docs path from configuration
        ConfigurationManager config = ConfigurationManager.getInstance(project);
        this.docsPath = config.getDocsPath();
    }
    
    @Override
    public JsonObject getParameterSchema() {
        JsonObject schema = new JsonObject();
        JsonObject properties = new JsonObject();
        
        JsonObject query = new JsonObject();
        query.addProperty("type", "string");
        query.addProperty("description", "Natural language search query for documentation");
        properties.add("query", query);
        
        JsonObject maxResults = new JsonObject();
        maxResults.addProperty("type", "integer");
        maxResults.addProperty("description", "Maximum number of results (default: 5, max: 20)");
        maxResults.addProperty("default", 5);
        maxResults.addProperty("minimum", 1);
        maxResults.addProperty("maximum", 20);
        properties.add("maxResults", maxResults);
        
        JsonObject includeCodeBlocks = new JsonObject();
        includeCodeBlocks.addProperty("type", "boolean");
        includeCodeBlocks.addProperty("description", "Include code blocks in search (default: false)");
        includeCodeBlocks.addProperty("default", false);
        properties.add("includeCodeBlocks", includeCodeBlocks);
        
        schema.add("properties", properties);
        JsonArray required = new JsonArray();
        required.add("query");
        schema.add("required", required);
        
        return schema;
    }
    
    @Override
    protected boolean requiresReadAction() {
        return false; // We handle read actions internally
    }
    
    @Override
    protected ToolResult doExecuteInReadAction(JsonObject parameters) {
        // Ensure index is built
        if (!isIndexed.get()) {
            if (!isIndexing.get()) {
                // Start async indexing
                CompletableFuture<Void> indexFuture = CompletableFuture.runAsync(
                    this::indexDocumentation, 
                    indexingExecutor
                );
                
                // Wait for indexing to complete (with timeout)
                try {
                    indexFuture.get(30, java.util.concurrent.TimeUnit.SECONDS);
                } catch (Exception e) {
                    LOG.error("Documentation indexing failed or timed out", e);
                    return ToolResult.error("Failed to index documentation: " + e.getMessage());
                }
            } else {
                // Already indexing, wait a bit
                int waitCount = 0;
                while (isIndexing.get() && waitCount < 60) { // Max 30 seconds
                    try {
                        Thread.sleep(500);
                        waitCount++;
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return ToolResult.error("Interrupted while waiting for indexing");
                    }
                }
                
                if (!isIndexed.get()) {
                    return ToolResult.error("Documentation indexing is still in progress");
                }
            }
        }
        
        String query = getRequiredString(parameters, "query");
        int maxResults = getOptionalInt(parameters, "maxResults", 5);
        boolean includeCodeBlocks = getOptionalBoolean(parameters, "includeCodeBlocks", false);
        
        // Validate parameters
        if (maxResults < 1 || maxResults > 20) {
            return ToolResult.error("maxResults must be between 1 and 20");
        }
        
        try {
            // Search documentation
            List<DocumentationIndex.DocumentSearchResult> results = 
                docIndex.search(query, maxResults);
            
            // Filter out code blocks if not requested
            if (!includeCodeBlocks) {
                results = results.stream()
                    .filter(r -> !"code_block".equals(r.getMetadata().get("type")))
                    .toList();
            }
            
            // Format results
            StringBuilder content = new StringBuilder();
            JsonObject metadata = createMetadata();
            metadata.addProperty("query", query);
            metadata.addProperty("resultCount", results.size());
            metadata.addProperty("docsPath", docsPath);
            
            if (results.isEmpty()) {
                content.append("No documentation found for query: \"").append(query).append("\"\n\n");
                content.append("Try:\n");
                content.append("- Using different keywords or phrases\n");
                content.append("- Searching for specific feature names or concepts\n");
                content.append("- Including technical terms or acronyms\n");
                
                // Suggest checking if docs exist
                Path docsDir = Paths.get(project.getBasePath(), docsPath);
                if (!Files.exists(docsDir)) {
                    content.append("\n⚠️ Note: Documentation directory '").append(docsPath)
                           .append("' does not exist in the project.");
                }
            } else {
                content.append("Found ").append(results.size())
                       .append(" documentation results for: \"").append(query).append("\"\n\n");
                
                for (int i = 0; i < results.size(); i++) {
                    DocumentationIndex.DocumentSearchResult result = results.get(i);
                    
                    content.append("## Result ").append(i + 1).append(": ")
                           .append(result.getBreadcrumb()).append("\n\n");
                    
                    // File info
                    String filePath = (String) result.getMetadata().get("file_path");
                    if (filePath != null) {
                        // Make path relative to project
                        String relativePath = makeRelativePath(filePath);
                        content.append("**File**: `").append(relativePath).append("`\n");
                    }
                    
                    // Type info
                    String type = (String) result.getMetadata().get("type");
                    if ("code_block".equals(type)) {
                        String language = (String) result.getMetadata().get("language");
                        content.append("**Type**: Code block (").append(language).append(")\n");
                    }
                    
                    // Score
                    content.append("**Relevance**: ").append(String.format("%.1f%%", result.getScore() * 100)).append("\n\n");
                    
                    // Highlight
                    content.append("**Preview**: ").append(result.getHighlight()).append("\n\n");
                    
                    // Full content (truncated if too long)
                    content.append("**Content**:\n");
                    String fullContent = result.getContent();
                    if (fullContent.length() > 1000) {
                        content.append(fullContent.substring(0, 1000)).append("\n... (truncated)");
                    } else {
                        content.append(fullContent);
                    }
                    
                    content.append("\n\n---\n\n");
                }
                
                // Add search tips
                content.append("💡 **Search Tips**:\n");
                content.append("- Use natural language queries like \"how to configure X\"\n");
                content.append("- Search for specific features or concepts\n");
                content.append("- Set `includeCodeBlocks: true` to search code examples\n");
            }
            
            // Add index statistics
            JsonObject stats = new JsonObject();
            docIndex.getStatistics().forEach((key, value) -> 
                stats.addProperty(key, value.toString())
            );
            metadata.add("indexStats", stats);
            
            return ToolResult.success(content.toString(), metadata);
            
        } catch (Exception e) {
            LOG.error("Documentation search failed", e);
            return ToolResult.error("Documentation search failed: " + e.getMessage());
        }
    }
    
    /**
     * Indexes all documentation files in the configured docs directory.
     */
    private void indexDocumentation() {
        if (!isIndexing.compareAndSet(false, true)) {
            return; // Already indexing
        }
        
        try {
            LOG.info("Starting documentation indexing for path: " + docsPath);
            
            Path docsDir = Paths.get(project.getBasePath(), docsPath);
            if (!Files.exists(docsDir)) {
                LOG.warn("Documentation directory not found: " + docsDir);
                return;
            }
            
            // Clear existing index
            docIndex.clear();
            
            // Count files for progress tracking
            int totalFiles = 0;
            int indexedFiles = 0;
            
            // Find and index all markdown files
            try (Stream<Path> paths = Files.walk(docsDir)) {
                List<Path> markdownFiles = paths
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase();
                        return name.endsWith(".md") || name.endsWith(".markdown");
                    })
                    .toList();
                
                totalFiles = markdownFiles.size();
                LOG.info("Found " + totalFiles + " markdown files to index");
                
                for (Path path : markdownFiles) {
                    try {
                        // Convert to VirtualFile
                        String relativePath = docsDir.relativize(path).toString().replace('\\', '/');
                        String fullRelativePath = docsPath + "/" + relativePath;
                        
                        ReadAction.run(() -> {
                            VirtualFile vFile = project.getBaseDir().findFileByRelativePath(fullRelativePath);
                            if (vFile != null && vFile.exists()) {
                                boolean indexed = docIndex.indexDocFile(vFile);
                                if (indexed) {
                                    LOG.debug("Indexed: " + relativePath);
                                }
                            } else {
                                LOG.warn("Could not find VirtualFile for: " + fullRelativePath);
                            }
                        });
                        
                        indexedFiles++;
                        
                    } catch (Exception e) {
                        LOG.error("Failed to index file: " + path, e);
                    }
                }
            }
            
            isIndexed.set(true);
            LOG.info("Documentation indexing complete. Indexed " + indexedFiles + "/" + totalFiles + " files");
            
            // Log statistics
            docIndex.getStatistics().forEach((key, value) -> 
                LOG.info("Documentation index " + key + ": " + value)
            );
            
        } catch (Exception e) {
            LOG.error("Failed to index documentation", e);
        } finally {
            isIndexing.set(false);
        }
    }
    
    /**
     * Makes a file path relative to the project base path.
     */
    private String makeRelativePath(String absolutePath) {
        try {
            Path projectPath = Paths.get(project.getBasePath());
            Path filePath = Paths.get(absolutePath);
            if (filePath.startsWith(projectPath)) {
                return projectPath.relativize(filePath).toString().replace('\\', '/');
            }
        } catch (Exception e) {
            // Ignore and return original path
        }
        return absolutePath;
    }
    
    /**
     * Helper to get optional boolean parameter.
     */
    private boolean getOptionalBoolean(JsonObject parameters, String name, boolean defaultValue) {
        if (parameters.has(name) && !parameters.get(name).isJsonNull()) {
            return parameters.get(name).getAsBoolean();
        }
        return defaultValue;
    }
    
    /**
     * Shuts down the indexing executor service.
     * Should be called when the tool is no longer needed.
     */
    public void shutdown() {
        indexingExecutor.shutdown();
        try {
            if (!indexingExecutor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                indexingExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            indexingExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
