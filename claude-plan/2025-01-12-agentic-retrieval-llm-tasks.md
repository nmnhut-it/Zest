# Zest Agentic Retrieval & LLM Task Management System
**Date**: January 12, 2025  
**Goal**: Implement intelligent codebase retrieval and autonomous LLM task management as integrated tools

## Executive Summary

This plan implements two complementary systems following Zest's tool architecture pattern (JavalinProxyServer):

1. **Agentic Retrieval System**: SQLite-vec + LSP-based semantic search for project-wide code understanding
2. **LLM Task Management System**: ReAct-based autonomous task execution with self-reflection

Both systems will be exposed as HTTP tools, enabling integration with Code Health Review, Chat features, and external systems.

## Current Architecture Analysis

### Zest Tool Pattern (from JavalinProxyServer)
```java
@Service(Service.Level.PROJECT)
public class JavalinProxyServer {
    private final Project project;
    private final AgentProxyConfiguration config;
    private Javalin app;
    
    // HTTP endpoints with OpenAPI docs
    // Manager coordination via ProjectProxyManager
    // Startup integration via ProxyStartupActivity
}
```

### Integration Points
- **ProjectProxyManager**: Coordinates multiple proxy servers per project
- **LLMService**: OpenAI-compatible HTTP client with connection pooling
- **CodeExplorationToolRegistry**: Tool registration and discovery
- **StartupActivity**: Automatic service initialization

## Part 1: Agentic Retrieval System

### Architecture Overview
```java
@Service(Service.Level.PROJECT)
public class RetrievalProxyServer {
    private final Project project;
    private final RetrievalConfiguration config;
    private final VectorStore vectorStore;
    private final LSPContextCollector lspCollector;
    private final EmbeddingService embeddingService;
    private Javalin app;
}
```

### Technology Stack
- **Vector Store**: Browser-based via JCEF + Vectra.js (local file storage, in-memory)
- **Code Analysis**: LSP via LSP4IJ (semantic understanding)
- **Embeddings**: Extend LLMService for `/v1/embeddings` endpoint
- **Storage**: Local index files + IndexedDB (~50MB per 100k LOC)
- **Runtime**: JCEF browser + Node.js embeddings service

### Core Components

#### 1. BrowserVectorStore (JCEF + Vectra.js Integration)
```java
public class BrowserVectorStore {
    private final JCEFBrowserManager browserManager;
    private final String indexPath;
    private final CompletableFuture<Void> initializationFuture;
    
    public void initialize() {
        // Load vector search HTML page via JCEF
        // Initialize Vectra.js index in browser context
        // Set up JavaScriptBridge for vector operations
    }
    
    public CompletableFuture<List<SimilarityResult>> search(float[] embedding, int limit) {
        return browserManager.callBrowserFunction("vectorSearch", Map.of(
            "embedding", embedding,
            "limit", limit,
            "threshold", 0.7
        ));
    }
    
    public CompletableFuture<Void> upsertEmbedding(String id, float[] embedding, String content, Map<String, Object> metadata) {
        return browserManager.callBrowserFunction("upsertVector", Map.of(
            "id", id,
            "embedding", embedding,
            "content", content,
            "metadata", metadata
        ));
    }
    
    public CompletableFuture<Void> buildIndex(List<CodeChunk> chunks) {
        // Batch indexing for initial codebase scan
        return browserManager.callBrowserFunction("buildIndex", chunks);
    }
}
```

#### Browser Vector Search HTML (`vector-search.html`)
```html
<!DOCTYPE html>
<html>
<head>
    <script src="https://unpkg.com/vectra@latest/dist/vectra.min.js"></script>
    <script>
        class ZestVectorStore {
            constructor() {
                this.index = null;
                this.isInitialized = false;
            }
            
            async initialize(indexPath) {
                const { LocalIndex } = vectra;
                this.index = new LocalIndex(indexPath);
                
                if (!await this.index.isIndexCreated()) {
                    await this.index.createIndex();
                }
                
                this.isInitialized = true;
                return { success: true };
            }
            
            async upsertVector(params) {
                const { id, embedding, content, metadata } = params;
                await this.index.upsertItem({
                    id: id,
                    vector: embedding,
                    metadata: {
                        content: content,
                        ...metadata
                    }
                });
                return { success: true };
            }
            
            async vectorSearch(params) {
                const { embedding, limit, threshold } = params;
                const results = await this.index.queryItems(embedding, limit);
                
                return results
                    .filter(r => r.score >= threshold)
                    .map(r => ({
                        id: r.item.id,
                        score: r.score,
                        content: r.item.metadata.content,
                        metadata: r.item.metadata
                    }));
            }
            
            async buildIndex(chunks) {
                const batch = chunks.map(chunk => ({
                    id: chunk.id,
                    vector: chunk.embedding,
                    metadata: {
                        content: chunk.content,
                        filePath: chunk.filePath,
                        startLine: chunk.startLine,
                        endLine: chunk.endLine,
                        type: chunk.type
                    }
                }));
                
                await this.index.insertItems(batch);
                return { success: true, indexed: batch.length };
            }
            
            async getStats() {
                const itemCount = await this.index.getItemCount();
                return {
                    itemCount: itemCount,
                    isInitialized: this.isInitialized,
                    indexPath: this.index.indexPath
                };
            }
        }
        
        // Global instance for IntelliJ bridge
        window.zestVectorStore = new ZestVectorStore();
        
        // Bridge functions for IntelliJ
        window.initializeVectorStore = (indexPath) => {
            return window.zestVectorStore.initialize(indexPath);
        };
        
        window.upsertVector = (params) => {
            return window.zestVectorStore.upsertVector(params);
        };
        
        window.vectorSearch = (params) => {
            return window.zestVectorStore.vectorSearch(params);
        };
        
        window.buildIndex = (chunks) => {
            return window.zestVectorStore.buildIndex(chunks);
        };
        
        window.getVectorStats = () => {
            return window.zestVectorStore.getStats();
        };
    </script>
</head>
<body>
    <div id="status">Vector store ready</div>
</body>
</html>
```

#### 2. EmbeddingService (LLMService Extension)
```java
public class EmbeddingService {
    private final LLMService llmService;
    private final Cache<String, float[]> embeddingCache;
    
    public CompletableFuture<float[]> generateEmbedding(String text) {
        // Call `/v1/embeddings` endpoint via LLMService
        // Content-based caching with SHA-256 hashing
        // Batch processing for efficiency
    }
    
    public CompletableFuture<List<float[]>> generateBatchEmbeddings(List<String> texts) {
        // Parallel embedding generation with rate limiting
    }
}
```

#### 3. LSPContextCollector (Semantic Analysis)
```java
public class LSPContextCollector {
    private final LanguageServerManager lspManager;
    
    public List<CodeSymbol> analyzeFile(VirtualFile file) {
        // Extract symbols, definitions, references via LSP
        // Cross-file dependency tracking
        // Semantic relationship mapping
    }
    
    public List<CodeChunk> chunkCode(String content, String language) {
        // Method-level chunking with context preservation
        // Class definitions, imports, and usage patterns
    }
}
```

#### 4. Context Providers System
```java
public interface ContextProvider {
    String getName(); // @codebase, @search, @tree, @git
    CompletableFuture<List<ContextItem>> search(String query, int limit);
}

@Component
public class CodebaseProvider implements ContextProvider {
    public CompletableFuture<List<ContextItem>> search(String query, int limit) {
        // 1. Generate query embedding
        // 2. Vector similarity search
        // 3. Rank results by relevance
        // 4. Return code chunks with metadata
    }
}

@Component  
public class SearchProvider implements ContextProvider {
    public CompletableFuture<List<ContextItem>> search(String query, int limit) {
        // 1. LSP symbol search
        // 2. Vector semantic search
        // 3. Hybrid ranking (symbols + similarity)
        // 4. Cross-file relationship analysis
    }
}
```

### HTTP API Endpoints

#### Configuration
```java
public class RetrievalConfiguration {
    private String vectorDbPath = ".zest/vectors.db";
    private int embeddingDimensions = 1536;
    private boolean useQuantization = true;
    private boolean backgroundIndexing = true;
    private int updateIntervalMinutes = 5;
    private List<String> excludePatterns = Arrays.asList("*.test.*", "node_modules/");
    private List<String> enabledProviders = Arrays.asList("@codebase", "@search", "@tree", "@git");
}
```

#### REST Endpoints
```
POST /retrieval/search
{
  "query": "authentication middleware",
  "providers": ["@codebase", "@search"],
  "limit": 10
}

POST /retrieval/context
{
  "query": "fix performance in UserService",
  "maxTokens": 8000,
  "includeReferences": true
}

GET /retrieval/index/status
{
  "totalFiles": 1247,
  "indexedFiles": 1200,
  "lastUpdate": "2025-01-12T10:30:00Z",
  "isIndexing": false
}

POST /retrieval/index/rebuild
{
  "force": true,
  "includePatterns": ["src/**/*.java", "src/**/*.kt"]
}
```

## Part 2: LLM Task Management System

### Architecture Overview
```java
@Service(Service.Level.PROJECT)
public class TaskManagementProxyServer {
    private final Project project;
    private final TaskConfiguration config;
    private final ReActEngine reactEngine;
    private final TaskReflector reflector;
    private Javalin app;
}
```

### Core Components

#### 1. LLMTask (Individual Task Unit)
```java
public class LLMTask {
    private String id;
    private String title;
    private String description;
    private TaskType type;
    private TaskStatus status;
    private List<String> dependencies;
    private Map<String, Object> context;
    private List<ReActStep> executionSteps;
    private TaskResult result;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    
    public enum TaskType {
        CODE_ANALYSIS,      // Analyze code quality/patterns
        REFACTORING,        // Improve code structure
        OPTIMIZATION,       // Performance improvements  
        DEBUGGING,          // Find and fix issues
        DOCUMENTATION,      // Generate docs/comments
        TESTING,           // Create/improve tests
        RESEARCH,          // Investigate technologies
        INTEGRATION        // Connect systems/APIs
    }
    
    public enum TaskStatus {
        PENDING,           // Waiting to start
        IN_PROGRESS,       // Currently executing
        BLOCKED,           // Waiting for dependency
        COMPLETED,         // Successfully finished
        FAILED,            // Execution failed
        CANCELLED          // Manually stopped
    }
}
```

#### 2. ReActEngine (Thought→Action→Observation)
```java
public class ReActEngine {
    private final LLMService llmService;
    private final ActionExecutor actionExecutor;
    private final ContextRetriever contextRetriever;
    
    public CompletableFuture<TaskResult> executeTask(LLMTask task) {
        List<ReActStep> steps = new ArrayList<>();
        
        while (!isTaskComplete(task)) {
            // Reasoning Phase
            ReActStep step = new ReActStep();
            step.thought = generateThought(task, steps);
            
            // Action Phase  
            step.action = planAction(step.thought, task.getContext());
            
            // Observation Phase
            step.observation = executeAction(step.action);
            
            // Reflection Phase
            evaluateProgress(task, step);
            
            steps.add(step);
            
            // Task decomposition if needed
            if (isComplexityThresholdReached(step)) {
                List<LLMTask> subtasks = decomposeTask(task, steps);
                return executeSubtasks(subtasks);
            }
        }
        
        return CompletableFuture.completedFuture(buildResult(task, steps));
    }
    
    private String generateThought(LLMTask task, List<ReActStep> previousSteps) {
        String prompt = buildReasoningPrompt(task, previousSteps);
        return llmService.query(prompt, "reasoning-model");
    }
    
    private TaskAction planAction(String thought, Map<String, Object> context) {
        // Parse thought to determine next action
        // Access available tools and context providers
        // Plan specific action with parameters
    }
    
    private String executeAction(TaskAction action) {
        switch (action.getType()) {
            case RETRIEVAL_SEARCH:
                return contextRetriever.search(action.getQuery(), action.getProviders());
            case CODE_ANALYSIS:
                return analyzeCode(action.getTargetFiles());
            case FILE_MODIFICATION:
                return modifyFiles(action.getChanges());
            case EXTERNAL_API:
                return callExternalAPI(action.getEndpoint(), action.getParams());
            default:
                return "Unknown action type";
        }
    }
}
```

#### 3. TaskReflector (Self-Assessment)
```java
public class TaskReflector {
    private final LLMService llmService;
    private final TaskMemory memory;
    
    public ReflectionResult reflectOnTask(LLMTask task, List<ReActStep> steps) {
        // Self-criticism and quality assessment
        String reflectionPrompt = buildReflectionPrompt(task, steps);
        String reflection = llmService.query(reflectionPrompt, "reflection-model");
        
        return ReflectionResult.builder()
            .taskId(task.getId())
            .qualityScore(extractQualityScore(reflection))
            .improvements(extractImprovements(reflection))
            .lessonsLearned(extractLessons(reflection))
            .futureRecommendations(extractRecommendations(reflection))
            .build();
    }
    
    public ComplexityAnalysis analyzeComplexity(LLMTask task) {
        // Determine if task should be decomposed
        // Identify blocking dependencies
        // Estimate execution time and resource requirements
    }
    
    public List<LLMTask> decomposeTask(LLMTask complexTask) {
        // Break down complex task into manageable subtasks
        // Establish dependency relationships
        // Preserve context and requirements
    }
}
```

#### 4. TaskList (Collection Management)
```java
public class TaskList {
    private String id;
    private String name;
    private List<LLMTask> tasks;
    private TaskListStatus status;
    private Map<String, Set<String>> dependencies;
    
    public void addTask(LLMTask task) {
        tasks.add(task);
        updateDependencyGraph(task);
    }
    
    public List<LLMTask> getReadyTasks() {
        // Return tasks with no pending dependencies
        return tasks.stream()
            .filter(task -> task.getStatus() == TaskStatus.PENDING)
            .filter(this::allDependenciesCompleted)
            .collect(Collectors.toList());
    }
    
    public CompletableFuture<Void> executeAll() {
        return CompletableFuture.runAsync(() -> {
            while (hasIncompleteTasks()) {
                List<LLMTask> readyTasks = getReadyTasks();
                List<CompletableFuture<TaskResult>> futures = readyTasks.stream()
                    .map(task -> reactEngine.executeTask(task))
                    .collect(Collectors.toList());
                
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
                updateTaskStatuses(futures);
            }
        });
    }
}
```

### HTTP API Endpoints

#### Configuration
```java
public class TaskConfiguration {
    private boolean autoExecute = true;
    private int maxConcurrentTasks = 3;
    private int maxReActSteps = 50;
    private boolean enableReflection = true;
    private boolean enableDecomposition = true;
    private String reasoningModel = "gpt-4";
    private String actionModel = "gpt-3.5-turbo";
    private String reflectionModel = "gpt-4";
}
```

#### REST Endpoints
```
POST /tasks/create
{
  "title": "Optimize database queries in UserService",
  "type": "OPTIMIZATION", 
  "context": {
    "targetFiles": ["UserService.java"],
    "priority": "high"
  },
  "autoExecute": true
}

POST /tasks/execute/{taskId}
{
  "maxSteps": 20,
  "enableReflection": true
}

GET /tasks/{taskId}/status
{
  "id": "task-123",
  "status": "IN_PROGRESS",
  "currentStep": 5,
  "totalSteps": 12,
  "progress": 0.42
}

GET /tasks/{taskId}/steps
[
  {
    "stepNumber": 1,
    "thought": "I need to analyze the current query patterns",
    "action": "retrieval_search",
    "observation": "Found 3 inefficient queries in getUsersByRole method",
    "timestamp": "2025-01-12T10:15:00Z"
  }
]

POST /tasks/{taskId}/reflect
{
  "includeRecommendations": true
}

POST /tasks/lists
{
  "name": "Code Health Improvements",
  "tasks": ["task-123", "task-124", "task-125"]
}
```

## Integration Strategy

### 1. Project Manager Extension
```java
public class EnhancedProjectProxyManager extends ProjectProxyManager {
    private RetrievalProxyServer retrievalServer;
    private TaskManagementProxyServer taskServer;
    
    @Override
    public void startAllServers() {
        super.startAllServers();
        
        // Start retrieval server on port + 100
        retrievalServer = new RetrievalProxyServer(project, basePort + 100, retrievalConfig);
        retrievalServer.start();
        
        // Start task management server on port + 200  
        taskServer = new TaskManagementProxyServer(project, basePort + 200, taskConfig);
        taskServer.start();
    }
}
```

### 2. Startup Activity
```java
public class AgenticSystemStartupActivity implements StartupActivity {
    @Override
    public void runActivity(@NotNull Project project) {
        // Initialize retrieval system
        RetrievalProxyServer retrievalServer = project.getService(RetrievalProxyServer.class);
        retrievalServer.startBackgroundIndexing();
        
        // Initialize task management
        TaskManagementProxyServer taskServer = project.getService(TaskManagementProxyServer.class);
        taskServer.loadPersistedTasks();
    }
}
```

### 3. Code Health Integration
```java
public class EnhancedCodeHealthAnalyzer extends CodeHealthAnalyzer {
    private final ContextRetriever contextRetriever;
    private final TaskManagementProxyServer taskServer;
    
    @Override
    public List<MethodHealthResult> analyzeMethod(ModifiedMethod method) {
        // Get enhanced context from retrieval system
        String context = contextRetriever.getContext(
            method.getFqn(), 
            Arrays.asList("@codebase", "@git")
        );
        
        // Standard analysis with rich context
        List<MethodHealthResult> results = super.analyzeMethodWithContext(method, context);
        
        // Create improvement tasks automatically
        for (MethodHealthResult result : results) {
            if (result.getHealthScore() < 70) {
                LLMTask improvementTask = createImprovementTask(result);
                taskServer.submitTask(improvementTask);
            }
        }
        
        return results;
    }
}
```

### 4. Chat System Enhancement
```java
public class EnhancedChatResponseService extends ChatResponseService {
    private final ContextRetriever contextRetriever;
    
    @Override
    public String generateResponse(String userQuery, ChatContext context) {
        // Retrieve project-specific context
        String projectContext = contextRetriever.buildContext(
            userQuery, 
            Arrays.asList("@codebase", "@search", "@tree")
        );
        
        // Enhanced prompt with semantic context
        String enhancedPrompt = combineUserQueryWithProjectContext(userQuery, projectContext);
        return llmService.query(enhancedPrompt, context.getModel());
    }
}
```

## Implementation Phases

### Phase 1: Retrieval Foundation (3-4 weeks)
1. **Week 1**: SQLite-vec integration, VectorStore implementation
2. **Week 2**: EmbeddingService extension, LSP4IJ integration  
3. **Week 3**: Basic context providers (@codebase, @search)
4. **Week 4**: HTTP API, configuration, testing

### Phase 2: Task Management Core (3-4 weeks)
1. **Week 1**: LLMTask model, TaskList management
2. **Week 2**: ReActEngine implementation, action execution
3. **Week 3**: TaskReflector, complexity analysis, decomposition
4. **Week 4**: HTTP API, persistence, testing

### Phase 3: Integration & Enhancement (2-3 weeks)
1. **Week 1**: Code Health integration, automatic task creation
2. **Week 2**: Chat system enhancement, project-aware responses
3. **Week 3**: Performance optimization, monitoring, documentation

### Phase 4: Advanced Features (2-3 weeks)
1. **Week 1**: Task dependency management, workflow automation
2. **Week 2**: Memory system, pattern learning, adaptive improvement
3. **Week 3**: Monitoring dashboard, metrics, user configuration

## Expected Benefits

### Code Health Review
- **95% accuracy improvement** through project-wide semantic context
- **Autonomous improvement workflows** with self-directed AI tasks
- **Cross-file impact analysis** with dependency understanding
- **Historical pattern learning** from successful optimizations

### Chat & Development Experience
- **Project-aware conversations** with deep codebase understanding
- **Contextual code suggestions** matching existing patterns
- **Self-improving development environment** through task automation
- **Reduced cognitive load** via intelligent context assembly

### System Architecture  
- **Tool-based modularity** following Zest patterns
- **HTTP API integration** with external systems
- **Local-first privacy** with optional cloud enhancement
- **Scalable performance** with background processing

## Resource Requirements

### Storage & Performance
- **Vector Index**: ~50-100MB per 100k LOC (Vectra local files)
- **Task History**: ~25MB per project (JSON + IndexedDB)
- **Memory Usage**: ~80MB additional (JCEF browser + in-memory index)
- **Query Performance**: <50ms retrieval (in-memory), background task execution

### Dependencies
- **Vectra.js**: Browser-based vector database via CDN
- **JCEF**: Browser runtime (already in Zest)
- **LSP4IJ**: Language server integration (or built-in for Ultimate)
- **Javalin**: HTTP server framework (already in use)
- **Existing Services**: LLMService, JCEFBrowserManager, CodeExplorationToolRegistry

### Browser-First Architecture Benefits
- **Leverage Existing JCEF**: Reuse Zest's mature browser infrastructure
- **No Native Dependencies**: Pure JavaScript vector operations
- **Easy Updates**: Vectra.js updates via CDN without plugin rebuilds
- **Cross-Platform**: Consistent behavior across all OS (Windows, macOS, Linux)
- **Offline Capable**: Local file storage with no external dependencies
- **Future WebGPU**: Ready for GPU acceleration when browsers support it

This architecture creates a **self-improving AI development environment** where Zest not only provides intelligent context and analysis but actively works to enhance itself and the codebase through autonomous task execution and continuous learning, all while maintaining the familiar tool-based architecture that makes Zest extensible and maintainable.