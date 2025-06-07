package com.zps.zest.rag;

import com.intellij.openapi.project.Project;
import com.zps.zest.ConfigurationManager;
import org.jetbrains.annotations.NotNull;

/**
 * Factory for creating RAG components with proper dependency injection.
 * This makes the system more testable and configurable.
 */
public class RagComponentFactory {
    
    /**
     * Creates a RagAgent with default production components.
     */
    public static OpenWebUIRagAgent createRagAgent(@NotNull Project project) {
        ConfigurationManager config = ConfigurationManager.getInstance(project);
        CodeAnalyzer analyzer = new DefaultCodeAnalyzer(project);
        
        // Use JS bridge client to avoid CORS issues
        KnowledgeApiClient apiClient = createJSBridgeApiClient(project);
        
        return new OpenWebUIRagAgent(project, config, analyzer, apiClient);
    }
    
    /**
     * Creates a RagAgent with custom components for testing.
     */
    public static OpenWebUIRagAgent createRagAgent(@NotNull Project project,
                                                   @NotNull ConfigurationManager config,
                                                   @NotNull CodeAnalyzer analyzer,
                                                   @NotNull KnowledgeApiClient apiClient) {
        return new OpenWebUIRagAgent(project, config, analyzer, apiClient);
    }
    
    /**
     * Creates the appropriate API client based on configuration.
     * 
     * Note: Currently uses direct HTTP calls which may face CORS issues
     * when the OpenWebUI server doesn't allow cross-origin requests from
     * the IDE's embedded browser. The JSBridgeApiClient provides a foundation
     * for future implementation that would make API calls through the browser
     * to avoid CORS restrictions.
     */
    public static KnowledgeApiClient createApiClient(@NotNull ConfigurationManager config) {
        String apiUrl = config.getApiUrl();
        String authToken = config.getAuthToken();
        
        if (apiUrl == null || apiUrl.isEmpty() || authToken == null || authToken.isEmpty()) {
            throw new IllegalStateException("API URL and auth token must be configured");
        }
        
        return new OpenWebUIKnowledgeClient(apiUrl, authToken);
    }
    
    /**
     * Creates a JS bridge API client for the project.
     * This should be used when making API calls through the browser to avoid CORS.
     */
    public static KnowledgeApiClient createJSBridgeApiClient(@NotNull Project project) {
        return new JSBridgeKnowledgeClient(project);
    }

    /**
     * Creates a code analyzer for the project.
     */
    public static CodeAnalyzer createCodeAnalyzer(@NotNull Project project) {
        return new DefaultCodeAnalyzer(project);
    }
}
