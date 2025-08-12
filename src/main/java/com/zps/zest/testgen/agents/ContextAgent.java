package com.zps.zest.testgen.agents;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.zps.zest.ClassAnalyzer;
import com.zps.zest.langchain4j.ZestLangChain4jService;
import com.zps.zest.langchain4j.util.LLMService;
import com.zps.zest.testgen.model.*;

import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.CompletableFuture;

public class ContextAgent extends BaseAgent {
    
    public ContextAgent(@NotNull Project project,
                       @NotNull ZestLangChain4jService langChainService,
                       @NotNull LLMService llmService) {
        super(project, langChainService, llmService, "ContextAgent");
    }
    
    /**
     * Gather comprehensive context for test generation
     */
    @NotNull
    public CompletableFuture<TestContext> gatherContext(@NotNull TestGenerationRequest request, @NotNull TestPlan testPlan) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                LOG.info("[ContextAgent] Gathering context for: " + request.getTargetFile().getName());
                
                String task = "Gather comprehensive context for test generation. " +
                             "Target class: " + testPlan.getTargetClass() + ". " +
                             "Target method: " + testPlan.getTargetMethod() + ". " +
                             "Dependencies: " + String.join(", ", testPlan.getDependencies()) + ".";
                
                String initialContext = buildInitialContext(request, testPlan);
                
                // Execute ReAct workflow to gather context
                String contextResult = executeReActTask(task, initialContext).join();
                
                // Build the final TestContext
                return buildTestContext(request, testPlan, contextResult);
                
            } catch (Exception e) {
                LOG.error("[ContextAgent] Failed to gather context", e);
                throw new RuntimeException("Context gathering failed: " + e.getMessage());
            }
        });
    }
    
    private String buildInitialContext(@NotNull TestGenerationRequest request, @NotNull TestPlan testPlan) {
        StringBuilder context = new StringBuilder();
        
        // Add basic file information
        context.append("Target File: ").append(request.getTargetFile().getName()).append("\n");
        context.append("Target Class: ").append(testPlan.getTargetClass()).append("\n");
        context.append("Target Method: ").append(testPlan.getTargetMethod()).append("\n");
        context.append("Test Type: ").append(testPlan.getRecommendedTestType()).append("\n");
        context.append("Dependencies: ").append(String.join(", ", testPlan.getDependencies())).append("\n\n");
        
        // Add test scenarios summary
        context.append("Test Scenarios:\n");
        for (TestPlan.TestScenario scenario : testPlan.getTestScenarios()) {
            context.append("- ").append(scenario.getName()).append(" (").append(scenario.getType()).append(")\n");
        }
        context.append("\n");
        
        return context.toString();
    }
    
    @NotNull
    @Override
    protected AgentAction determineAction(@NotNull String reasoning, @NotNull String observation) {
        String lowerReasoning = reasoning.toLowerCase();
        
        if (lowerReasoning.contains("search") || lowerReasoning.contains("find") || lowerReasoning.contains("look for")) {
            return new AgentAction(AgentAction.ActionType.SEARCH, "Search for related code and patterns", reasoning);
        } else if (lowerReasoning.contains("analyze") || lowerReasoning.contains("examine") || lowerReasoning.contains("inspect")) {
            return new AgentAction(AgentAction.ActionType.ANALYZE, "Analyze code dependencies and structure", reasoning);
        } else if (lowerReasoning.contains("gather") || lowerReasoning.contains("collect") || lowerReasoning.contains("retrieve")) {
            return new AgentAction(AgentAction.ActionType.GENERATE, "Gather context information", reasoning);
        } else if (lowerReasoning.contains("complete") || lowerReasoning.contains("done") || lowerReasoning.contains("finished")) {
            return new AgentAction(AgentAction.ActionType.COMPLETE, "Context gathering completed", reasoning);
        } else {
            return new AgentAction(AgentAction.ActionType.SEARCH, "Search for additional context", reasoning);
        }
    }
    
    @NotNull
    @Override
    protected String executeAction(@NotNull AgentAction action) {
        switch (action.getType()) {
            case SEARCH:
                return performRAGSearch(action.getParameters());
            case ANALYZE:
                return analyzeCodeStructure(action.getParameters());
            case GENERATE:
                return gatherAdditionalContext(action.getParameters());
            case COMPLETE:
                return action.getParameters();
            default:
                return "Unknown action: " + action.getType();
        }
    }
    
    private String performRAGSearch(@NotNull String parameters) {
        try {
            // Extract key terms from parameters for search
            String searchQuery = extractSearchTerms(parameters);
            
            // Use RAG to find related context
            ZestLangChain4jService.RetrievalResult result = langChainService
                .retrieveContext(searchQuery, 8, 0.6).join();
            
            if (result.isSuccess() && !result.getItems().isEmpty()) {
                StringBuilder context = new StringBuilder();
                context.append("Found ").append(result.getItems().size()).append(" relevant code pieces:\n\n");
                
                for (ZestLangChain4jService.ContextItem item : result.getItems()) {
                    context.append("File: ").append(item.getFilePath()).append("\n");
                    if (item.getLineNumber() != null) {
                        context.append("Line: ").append(item.getLineNumber()).append("\n");
                    }
                    context.append("Content:\n").append(item.getContent()).append("\n");
                    context.append("Relevance Score: ").append(String.format("%.2f", item.getScore())).append("\n\n");
                }
                
                return context.toString();
            } else {
                return "No relevant context found for: " + searchQuery;
            }
            
        } catch (Exception e) {
            LOG.warn("[ContextAgent] RAG search failed", e);
            return "RAG search failed: " + e.getMessage();
        }
    }
    
    private String analyzeCodeStructure(@NotNull String parameters) {
        try {
            StringBuilder analysis = new StringBuilder();
            analysis.append("Code Structure Analysis:\n\n");
            
            // Search for existing test files
            List<VirtualFile> testFiles = findExistingTestFiles();
            if (!testFiles.isEmpty()) {
                analysis.append("Existing Test Files Found:\n");
                for (VirtualFile testFile : testFiles) {
                    analysis.append("- ").append(testFile.getName()).append(" (").append(testFile.getPath()).append(")\n");
                }
                analysis.append("\n");
            }
            
            // Analyze test patterns in existing files
            String testPatterns = analyzeExistingTestPatterns(testFiles);
            analysis.append(testPatterns);
            
            // Detect testing framework
            String frameworkInfo = detectTestingFramework();
            analysis.append("Testing Framework: ").append(frameworkInfo).append("\n\n");
            
            return analysis.toString();
            
        } catch (Exception e) {
            LOG.warn("[ContextAgent] Code structure analysis failed", e);
            return "Code structure analysis failed: " + e.getMessage();
        }
    }
    
    private String gatherAdditionalContext(@NotNull String parameters) {
        StringBuilder context = new StringBuilder();
        
        try {
            // Gather project-specific context
            context.append("Project Context:\n");
            context.append("Project Name: ").append(project.getName()).append("\n");
            context.append("Base Path: ").append(project.getBasePath()).append("\n");
            
            // Check for common configuration files
            String configInfo = analyzeProjectConfiguration();
            context.append(configInfo);
            
            // Analyze dependencies from build files
            String dependencyInfo = analyzeDependencies();
            context.append(dependencyInfo);
            
        } catch (Exception e) {
            LOG.warn("[ContextAgent] Additional context gathering failed", e);
            context.append("Additional context gathering failed: ").append(e.getMessage()).append("\n");
        }
        
        return context.toString();
    }
    
    private String extractSearchTerms(@NotNull String parameters) {
        // Use LLM to extract key search terms
        String prompt = "Extract key search terms for finding related code from the following context:\n\n" +
                       parameters + "\n\n" +
                       "Extract 3-5 key terms that would help find relevant code, classes, methods, or patterns. " +
                       "Return only the search terms separated by spaces:";
        
        String response = queryLLM(prompt, 200);
        return response.isEmpty() ? "test methods patterns" : response;
    }
    
    private List<VirtualFile> findExistingTestFiles() {
        try {
            Collection<VirtualFile> testFiles = FilenameIndex.getAllFilesByExt(
                project, "java", GlobalSearchScope.projectScope(project)
            );
            
            return testFiles.stream()
                .filter(file -> file.getName().contains("Test") || 
                               file.getPath().contains("test") ||
                               file.getPath().contains("/test/"))
                .limit(10) // Limit to avoid too much processing
                .toList();
                
        } catch (Exception e) {
            LOG.warn("[ContextAgent] Failed to find existing test files", e);
            return new ArrayList<>();
        }
    }
    
    private String analyzeExistingTestPatterns(@NotNull List<VirtualFile> testFiles) {
        if (testFiles.isEmpty()) {
            return "No existing test patterns found.\n\n";
        }
        
        StringBuilder patterns = new StringBuilder();
        patterns.append("Existing Test Patterns:\n");
        
        try {
            PsiManager psiManager = PsiManager.getInstance(project);
            
            for (VirtualFile testFile : testFiles.subList(0, Math.min(3, testFiles.size()))) {
                PsiFile psiFile = psiManager.findFile(testFile);
                if (psiFile != null) {
                    // Get a sample of the test file structure
                    String structure = ClassAnalyzer.getTextOfPsiElement(psiFile);
                    if (structure.length() > 500) {
                        structure = structure.substring(0, 500) + "...";
                    }
                    patterns.append("From ").append(testFile.getName()).append(":\n");
                    patterns.append(structure).append("\n\n");
                }
            }
            
        } catch (Exception e) {
            LOG.warn("[ContextAgent] Failed to analyze test patterns", e);
            patterns.append("Failed to analyze test patterns: ").append(e.getMessage()).append("\n");
        }
        
        return patterns.toString();
    }
    
    private String detectTestingFramework() {
        try {
            // Search for common testing framework indicators
            Collection<VirtualFile> javaFiles = FilenameIndex.getAllFilesByExt(
                project, "java", GlobalSearchScope.projectScope(project)
            );
            
            for (VirtualFile file : javaFiles) {
                PsiFile psiFile = PsiManager.getInstance(project).findFile(file);
                if (psiFile != null) {
                    String content = ClassAnalyzer.getTextOfPsiElement(psiFile);
                    
                    if (content.contains("@Test") && content.contains("org.junit.jupiter")) {
                        return "JUnit 5";
                    } else if (content.contains("@Test") && content.contains("org.junit.")) {
                        return "JUnit 4";
                    } else if (content.contains("@Test") && content.contains("testng")) {
                        return "TestNG";
                    }
                }
            }
            
            return "Unknown (will use JUnit 5 as default)";
            
        } catch (Exception e) {
            LOG.warn("[ContextAgent] Framework detection failed", e);
            return "Detection failed (will use JUnit 5 as default)";
        }
    }
    
    private String analyzeProjectConfiguration() {
        StringBuilder config = new StringBuilder();
        
        try {
            // Check for common build files
            VirtualFile baseDir = project.getBaseDir();
            if (baseDir != null) {
                VirtualFile pomXml = baseDir.findChild("pom.xml");
                VirtualFile buildGradle = baseDir.findChild("build.gradle");
                VirtualFile buildGradleKts = baseDir.findChild("build.gradle.kts");
                
                if (pomXml != null) {
                    config.append("Build System: Maven\n");
                } else if (buildGradle != null || buildGradleKts != null) {
                    config.append("Build System: Gradle\n");
                } else {
                    config.append("Build System: Unknown\n");
                }
            }
            
        } catch (Exception e) {
            LOG.warn("[ContextAgent] Configuration analysis failed", e);
            config.append("Configuration analysis failed\n");
        }
        
        return config.toString();
    }
    
    private String analyzeDependencies() {
        // This could be expanded to actually parse build files
        // For now, return basic information
        return "Dependencies: Will be determined from build configuration\n\n";
    }
    
    private TestContext buildTestContext(@NotNull TestGenerationRequest request, @NotNull TestPlan testPlan, @NotNull String contextResult) {
        try {
            // Extract context items from RAG results
            List<ZestLangChain4jService.ContextItem> codeContext = extractCodeContext(contextResult);
            
            // Extract related files
            List<String> relatedFiles = extractRelatedFiles(contextResult);
            
            // Extract dependencies
            Map<String, String> dependencies = extractDependencies(contextResult, testPlan.getDependencies());
            
            // Extract test patterns
            List<String> testPatterns = extractTestPatterns(contextResult);
            
            // Extract framework info
            String frameworkInfo = extractFrameworkInfo(contextResult);
            
            // Build additional metadata
            Map<String, Object> metadata = buildMetadata(request, testPlan, contextResult);
            
            return new TestContext(codeContext, relatedFiles, dependencies, testPatterns, frameworkInfo, metadata);
            
        } catch (Exception e) {
            LOG.error("[ContextAgent] Failed to build test context", e);
            // Return minimal context
            return new TestContext(
                new ArrayList<>(),
                List.of(request.getTargetFile().getName()),
                Map.of(),
                new ArrayList<>(),
                "JUnit 5",
                Map.of("error", e.getMessage())
            );
        }
    }
    
    private List<ZestLangChain4jService.ContextItem> extractCodeContext(@NotNull String contextResult) {
        // In a real implementation, this would parse the context result
        // For now, return empty list - the actual context items come from RAG calls
        return new ArrayList<>();
    }
    
    private List<String> extractRelatedFiles(@NotNull String contextResult) {
        List<String> files = new ArrayList<>();
        String[] lines = contextResult.split("\n");
        
        for (String line : lines) {
            if (line.contains("File:") && line.contains(".java")) {
                String fileName = line.substring(line.indexOf("File:") + 5).trim();
                if (!files.contains(fileName)) {
                    files.add(fileName);
                }
            }
        }
        
        return files;
    }
    
    private Map<String, String> extractDependencies(@NotNull String contextResult, @NotNull List<String> plannedDependencies) {
        Map<String, String> dependencies = new HashMap<>();
        
        // Add planned dependencies
        for (String dep : plannedDependencies) {
            dependencies.put(dep, "planned");
        }
        
        // Extract additional dependencies from context
        if (contextResult.contains("import ")) {
            String[] lines = contextResult.split("\n");
            for (String line : lines) {
                if (line.trim().startsWith("import ") && !line.contains("java.")) {
                    String importStmt = line.trim().substring(7);
                    if (importStmt.endsWith(";")) {
                        importStmt = importStmt.substring(0, importStmt.length() - 1);
                    }
                    dependencies.put(importStmt, "discovered");
                }
            }
        }
        
        return dependencies;
    }
    
    private List<String> extractTestPatterns(@NotNull String contextResult) {
        List<String> patterns = new ArrayList<>();
        
        if (contextResult.contains("@Test")) {
            patterns.add("JUnit test methods");
        }
        if (contextResult.contains("@Before") || contextResult.contains("@BeforeEach")) {
            patterns.add("Setup methods");
        }
        if (contextResult.contains("@After") || contextResult.contains("@AfterEach")) {
            patterns.add("Cleanup methods");
        }
        if (contextResult.contains("@Mock") || contextResult.contains("mock(")) {
            patterns.add("Mocking patterns");
        }
        if (contextResult.contains("assertEquals") || contextResult.contains("assertThat")) {
            patterns.add("Assertion patterns");
        }
        
        return patterns;
    }
    
    private String extractFrameworkInfo(@NotNull String contextResult) {
        if (contextResult.contains("JUnit 5") || contextResult.contains("jupiter")) {
            return "JUnit 5";
        } else if (contextResult.contains("JUnit 4") || contextResult.contains("org.junit.Test")) {
            return "JUnit 4";
        } else if (contextResult.contains("TestNG")) {
            return "TestNG";
        } else {
            return "JUnit 5"; // Default
        }
    }
    
    private Map<String, Object> buildMetadata(@NotNull TestGenerationRequest request, @NotNull TestPlan testPlan, @NotNull String contextResult) {
        Map<String, Object> metadata = new HashMap<>();
        
        metadata.put("targetFile", request.getTargetFile().getName());
        metadata.put("hasSelection", request.hasSelection());
        metadata.put("userDescription", request.getUserDescription());
        metadata.put("scenarioCount", testPlan.getScenarioCount());
        metadata.put("contextLength", contextResult.length());
        metadata.put("gatheringTimestamp", System.currentTimeMillis());
        
        return metadata;
    }
    
    @NotNull
    @Override
    protected String getAgentDescription() {
        return "a context gathering agent that uses RAG and code analysis to collect comprehensive information for test generation";
    }
    
    @NotNull
    @Override
    protected List<AgentAction.ActionType> getAvailableActions() {
        return Arrays.asList(
            AgentAction.ActionType.SEARCH,
            AgentAction.ActionType.ANALYZE,
            AgentAction.ActionType.GENERATE,
            AgentAction.ActionType.COMPLETE
        );
    }
}