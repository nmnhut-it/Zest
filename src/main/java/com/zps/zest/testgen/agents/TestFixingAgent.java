package com.zps.zest.testgen.agents;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiManager;
import com.zps.zest.langchain4j.ZestLangChain4jService;
import com.zps.zest.langchain4j.util.LLMService;
import com.zps.zest.testgen.tools.TestFixingTools;
import com.zps.zest.testgen.util.PSIErrorCollector;
import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

/**
 * Agent for fixing Java compilation errors in test files.
 * Uses LangChain4j with prompt templates and simple tools.
 */
public class TestFixingAgent extends StreamingBaseAgent {
    private static final Logger LOG = Logger.getInstance(TestFixingAgent.class);
    
    private final TestFixingTools tools;
    private final TestFixingAssistant assistant;
    private final MessageWindowChatMemory chatMemory;
    
    public TestFixingAgent(@NotNull Project project,
                           @NotNull ZestLangChain4jService langChainService,
                           @NotNull LLMService llmService) {
        super(project, langChainService, llmService, "TestFixingAgent");
        
        this.tools = new TestFixingTools(project);
        this.chatMemory = MessageWindowChatMemory.withMaxMessages(20);
        
        // Build the agent with tools
        this.assistant = AgenticServices
                .agentBuilder(TestFixingAssistant.class)
                .chatModel(getChatModelWithStreaming())
                .maxSequentialToolsInvocations(10)
                .chatMemory(chatMemory)
                .tools(tools)
                .build();
    }
    
    /**
     * LangChain4j Assistant interface with prompt templates
     */
    public interface TestFixingAssistant {
        @SystemMessage("""
        You are a Java compilation error fixer. Your goal is to fix ONLY compilation errors while preserving all business logic and test behavior.
        
        CONSTRAINTS:
        - Make minimal granular changes only
        - Add missing imports using addImport() tool
        - Replace wrong method calls/types using replaceCode() tool  
        - NEVER change test logic, assertions, or test scenarios
        - NEVER restructure code or rename methods
        - ONLY fix what prevents compilation
        
        TOOLS AVAILABLE:
        - addImport(filePath, importStatement): Add import at top of file
        - replaceCode(filePath, oldCode, newCode): Replace unique code string
        
        IMPORTANT: For replaceCode(), provide exact unique code strings only. If code appears multiple times, the tool will reject it.
        
        Work step by step through each compilation error.
        """)
        
        @UserMessage("""
        Fix compilation errors in this generated test file:
        
        FILE: {{filePath}}
        
        COMPILATION ERRORS:
        {{compilationErrors}}
        
        TEST CONTEXT:
        {{testContext}}
        
        CURRENT FILE CONTENT:
        {{fileContent}}
        
        Use the available tools to fix these compilation errors step by step. Explain what each fix does and why it's needed.
        """)
        
        @dev.langchain4j.agentic.Agent
        String fixCompilationErrors(@V("filePath") String filePath,
                                   @V("compilationErrors") String compilationErrors,
                                   @V("testContext") String testContext,
                                   @V("fileContent") String fileContent);
    }
    
    /**
     * Main entry point for fixing test file compilation errors.
     * Works with in-memory content instead of physical files.
     * 
     * @param context the test generation context
     * @param fileName name of the test file (for context)
     * @param fileContent the current content of the test file
     * @return CompletableFuture with the fixed content
     */
    @NotNull
    public CompletableFuture<String> fixTestContent(@NotNull String fileName, @NotNull String fileContent) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                LOG.info("Starting in-memory test content fixing for: " + fileName);
                
                // Notify UI that we're starting
                sendToUI("🔧 Starting compilation error analysis for test content...\n");
                
                // 1. Analyze the in-memory content for compilation errors using PSI
                String compilationErrors = analyzeContentForErrors(fileName, fileContent);
                
                // 2. Check if there are any errors to fix
                if (compilationErrors.trim().isEmpty()) {
                    String result = "No compilation errors found in the test content.";
                    sendToUI("✅ " + result + "\n");
                    return fileContent; // Return original content unchanged
                }
                
                // 3. Send info to UI
                sendToUI("📋 Found compilation errors:\n" + compilationErrors + "\n");
                sendToUI("🤖 Assistant working on fixes...\n");
                sendToUI("-".repeat(50) + "\n");
                
                // 4. Fix using LangChain4j agent with template variables
                String result = assistant.fixCompilationErrors(
                    fileName,
                    compilationErrors,
                    "Test fixing context", // Simple context info since TestFixingAgent has minimal context needs
                    fileContent
                );
                
                sendToUI(result);
                sendToUI("\n" + "-".repeat(50) + "\n");
                sendToUI("✅ Test fixing completed\n");
                
                // TODO: For now, return original content. Later, extract fixed content from agent response
                return fileContent;
                
            } catch (Exception e) {
                LOG.error("Failed to fix test content", e);
                String errorMsg = "❌ Test fixing failed: " + e.getMessage();
                sendToUI(errorMsg + "\n");
                return fileContent; // Return original content on error
            }
        });
    }
    
    /**
     * Analyze in-memory Java content for compilation errors using PSI.
     */
    private String analyzeContentForErrors(@NotNull String fileName, @NotNull String fileContent) {
        try {
            // Create a temporary PSI file from the content for analysis
            return ReadAction.compute(() -> {
                com.intellij.psi.PsiFileFactory factory = com.intellij.psi.PsiFileFactory.getInstance(project);
                PsiFile psiFile = factory.createFileFromText(fileName, 
                    com.intellij.ide.highlighter.JavaFileType.INSTANCE, fileContent);
                
                if (psiFile instanceof PsiJavaFile) {
                    return PSIErrorCollector.collectCompilationErrors((PsiJavaFile) psiFile, project);
                }
                return "";
            });
        } catch (Exception e) {
            LOG.warn("Failed to analyze content for errors", e);
            return "Error analyzing content: " + e.getMessage();
        }
    }
    
    @NotNull
    public MessageWindowChatMemory getChatMemory() {
        return chatMemory;
    }
}