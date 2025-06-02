package com.zps.zest.rag;

import com.intellij.openapi.project.Project;
import com.zps.zest.ConfigurationManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Factory for creating RAG components with proper dependency injection.
 * This makes the system more testable and configurable.
 */
public class RagComponentFactory {
    
    /**
     * Creates a RagAgent with default production components.
     */
    public static RagAgent createRagAgent(@NotNull Project project) {
        ConfigurationManager config = ConfigurationManager.getInstance(project);
        CodeAnalyzer analyzer = new DefaultCodeAnalyzer(project);
        KnowledgeApiClient apiClient = createApiClient(config);
        
        return new RagAgent(project, config, analyzer, apiClient);
    }
    
    /**
     * Creates a RagAgent with custom components for testing.
     */
    public static RagAgent createRagAgent(@NotNull Project project,
                                          @NotNull ConfigurationManager config,
                                          @NotNull CodeAnalyzer analyzer,
                                          @NotNull KnowledgeApiClient apiClient) {
        return new RagAgent(project, config, analyzer, apiClient);
    }
    
    /**
     * Creates the appropriate API client based on configuration.
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
     * Creates a mock API client for testing.
     */
    public static MockKnowledgeApiClient createMockApiClient() {
        return new MockKnowledgeApiClient();
    }
    
    /**
     * Creates a code analyzer for the project.
     */
    public static CodeAnalyzer createCodeAnalyzer(@NotNull Project project) {
        return new DefaultCodeAnalyzer(project);
    }
}
