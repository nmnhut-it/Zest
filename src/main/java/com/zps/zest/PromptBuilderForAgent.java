package com.zps.zest;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.zps.zest.tools.AgentTool;
import com.zps.zest.tools.RagSearchTool;
import com.zps.zest.tools.XmlRpcUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;

/**
 * Builds prompts for the LLM with context and tool documentation.
 */
public class PromptBuilderForAgent {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Logger LOG = Logger.getInstance(AgentToolRegistry.class);
    private static final long RAG_TIMEOUT_SECONDS = 3_000;

    private final AgentToolRegistry toolRegistry;
    private final ConfigurationManager configManager;

    public PromptBuilderForAgent(AgentToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
        this.configManager = ConfigurationManager.getInstance(toolRegistry.project);
    }
    
    /**
     * Builds a complete prompt with tools, context, history, and user request.
     * 
     * @param userRequest The user's request
     * @param conversationHistory The conversation history
     * @param codeContext The code context information
     * @return The complete prompt
     */
    public String buildPrompt(String userRequest, List<String> conversationHistory, Map<String, String> codeContext) {
        StringBuilder prompt = new StringBuilder();

        // Add system instructions
        addSystemInstructions(prompt);
        
        // Add tool documentation
        addToolDocumentation(prompt);
        
        // Add code context
        addCodeContext(prompt, codeContext);
        // Automatically add RAG knowledge if applicable and available
        String ragKnowledge = getRelevantKnowledgeForRequest(userRequest);
        if (ragKnowledge != null && !ragKnowledge.isEmpty()) {
            addRagKnowledge(prompt, ragKnowledge);
        }

        // Add conversation history
        addConversationHistory(prompt, conversationHistory);
        
        // Add user request
        prompt.append("<USER_REQUEST>\n").append(userRequest).append("\n</USER_REQUEST>\n");

        return prompt.toString();
    }
    
    /**
     * Adds system instructions to the prompt.
     */
    private void addSystemInstructions(StringBuilder prompt) {

        prompt.append("<s>\n");
        prompt.append("You are Zest, Zingplay's IDE assistant. You help programmers write better code with concise, practical solutions. You strictly follow instructions while being professional and highly intellectual.\n\n");

        prompt.append("# WORKFLOW\n");
        prompt.append("Do these steps one by one. PERFORM AT MOST ONE TOOL CALL IN A RESPONSE\n");
        prompt.append("1. CLARIFY: Ask questions to understand requirements\n");
        prompt.append("2. COLLECT: Use tools to gather context and code\n");
        prompt.append("3. ANALYZE: Identify improvements and solutions\n");
        prompt.append("4. IMPLEMENT: Apply changes with modification tools\n");
        prompt.append("5. VERIFY: Test changes and fix any issues\n\n");

        prompt.append("# APPROACH\n");
        prompt.append("- UNDERSTAND: Examine code thoroughly\n");
        prompt.append("- EXPLAIN: Provide clear, concise analysis\n");
        prompt.append("- IMPLEMENT: Make targeted improvements\n");
        prompt.append("- VERIFY: Ensure quality and functionality\n");
        // Add explicit character escaping guidance
        prompt.append("# IMPORTANT XML ESCAPING RULES\n\n");
        prompt.append("When using tools, you MUST properly escape special characters in XML content:\n");
        prompt.append("- Replace < with &lt;\n");
        prompt.append("- Replace > with &gt;\n");
        prompt.append("- Replace & with &amp;\n");
        prompt.append("- Replace \" with &quot;\n");
        prompt.append("- Replace ' with &apos;\n\n");
        prompt.append("Example: If parameter value contains code like 'if (x < 10)', it should be escaped as 'if (x &lt; 10)'\n\n");
        prompt.append("Forgetting to escape these characters will cause tool invocations to fail, so this is critical.\n\n");

        prompt.append("RESPONSE GUIDELINES:\n");
        prompt.append("- Keep responses concise and focused on the user's specific request\n");
        prompt.append("- Use code blocks with proper syntax highlighting when sharing code\n");
        prompt.append("- When tools return large outputs, summarize the key findings\n");
        prompt.append("- Provide step-by-step explanations for complex operations\n\n");

        prompt.append("Remember, your primary advantage is your ability to use tools to examine and modify code directly in the IDE.\n");
        prompt.append("</s>\n\n");
    }
    private void addToolDocumentation(StringBuilder prompt) {
        prompt.append("# AVAILABLE TOOLS\n\n");
        prompt.append("You have access to the following tools. Use these tools strategically to gather context and modify code. DO NOT USE MORE THAN ONE TOOL AT A TIME.\n\n");
        prompt.append("## Clarifying tools\n");
        addToolsByCategory(prompt, "follow_up_question");
        // Group tools by category
        prompt.append("## File Operations\n");
        addToolsByCategory(prompt, "read_file", "list_files", "create_file","replace_in_file");

        prompt.append("## Code Analysis\n");
        addToolsByCategory(prompt, "find_methods", "search_classes", "get_current_class_info", "analyze_code_problems");

        prompt.append("## Project Navigation\n");
        addToolsByCategory(prompt, "get_project_structure", "search_by_regex", "find_references");

        prompt.append("# HOW TO INVOKE TOOLS\n\n");
        prompt.append("Always use this format for invoking tools:\n");
        prompt.append("```\n");
        prompt.append("<TOOL>\n");
        prompt.append("  <methodName>tool_name</methodName>\n");
        prompt.append("  <params>\n");
        prompt.append("    <param>\n");
        prompt.append("      <name>param1</name>\n");
        prompt.append("      <value>value1</value>\n");
        prompt.append("    </param>\n");
        prompt.append("  </params>\n");
        prompt.append("</TOOL>\n");
        prompt.append("```\n\n");


        // In addToolDocumentation method in PromptBuilderForAgent.java

// Add specific instructions for the RAG tool
//        prompt.append("# SPECIAL INSTRUCTION FOR RAG TOOL\n\n");
//        prompt.append("You have access to a RAG (Retrieval Augmented Generation) tool that can search through the project's knowledge base. Use this tool when you need specific information about the user's codebase or project-specific details.\n\n");
//        prompt.append("When to use the RAG tool:\n");
//        prompt.append("- When asked about specific classes or implementations in the user's project\n");
//        prompt.append("- When you need details about project structure or architecture\n");
//        prompt.append("- When you're uncertain about project-specific conventions or patterns\n\n");
//
//        prompt.append("Example usage:\n");
//        prompt.append("<TOOL>\n");
//        prompt.append("  <methodName>rag_search</methodName>\n");
//        prompt.append("  <params>\n");
//        prompt.append("    <param>\n");
//        prompt.append("      <name>query</name>\n");
//        prompt.append("      <value>How does the ToolParser process tool invocations?</value>\n");
//        prompt.append("    </param>\n");
//        prompt.append("    <param>\n");
//        prompt.append("      <name>top_k</name>\n");
//        prompt.append("      <value>3</value>\n");
//        prompt.append("    </param>\n");
//        prompt.append("  </params>\n");
//        prompt.append("</TOOL>\n\n");

        prompt.append("IMPORTANT USAGE NOTES:\n");
        prompt.append("- ALWAYS wrap tool invocations between <TOOL> and </TOOL> tags\n");
        prompt.append("- ALWAYS properly escape characters in XML: use &lt; for <, &gt; for >, and &amp; for &\n");
        prompt.append("- ALWAYS properly escape characters in regex");
        prompt.append("- ONLY call ONE tool per message");
        prompt.append("- Use read_file BEFORE attempting to modify code to understand the context\n");
        prompt.append("- Use search_classes when you need to understand related classes\n");
        prompt.append("- Use analyze_code_problems to diagnose issues in problematic code\n\n");
    }

    // Helper method to add tools by category
    private void addToolsByCategory(StringBuilder prompt, String... toolNames) {
        for (String toolName : toolNames) {
            AgentTool tool = toolRegistry.getTool(toolName);
            if (tool != null) {
                prompt.append("- `").append(tool.getName()).append("`: ")
                        .append(tool.getDescription()).append("\n");

                // Add example in collapsed form
                prompt.append("  Example: ");
                addToolExample(prompt, tool);
                prompt.append("\n");
            }
        }
        prompt.append("\n");
    }
    /**
     * Gets relevant knowledge for the user's request using the RAG tool.
     * Returns null if RAG is disabled or if an error occurs.
     */
    private String getRelevantKnowledgeForRequest(String userRequest) {
        // Check if RAG is enabled
        if (!configManager.isRagEnabled()) {
            LOG.info("RAG is disabled, skipping knowledge retrieval");
            return null;
        }

        // Check if request appears to be code-related
        if (!isCodeRelatedQuery(userRequest)) {
            LOG.info("Request doesn't appear to be code-related, skipping RAG");
            return null;
        }

        // Get RAG tool
        RagSearchTool ragTool = (RagSearchTool) toolRegistry.getTool("rag_search");
        if (ragTool == null) {
            LOG.warn("RAG tool not found in registry");
            return null;
        }

        try {
            // Create parameters for RAG search
            JsonObject params = new JsonObject();
            params.addProperty("query", createRagQueryFromUserRequest(userRequest));
            params.addProperty("top_k", 3);

            // Execute RAG search with timeout
            CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
                try {
                    return ragTool.execute(params);
                } catch (Exception e) {
                    LOG.warn("Error executing RAG tool", e);
                    return null;
                }
            });

            String result = future.get(RAG_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            // Process and clean up the result if needed
            if (result != null && !result.isEmpty()) {
                return cleanRagResults(result);
            }
        } catch (TimeoutException e) {
            LOG.warn("RAG search timed out after " + RAG_TIMEOUT_SECONDS + " seconds");
            e.printStackTrace();
        } catch (InterruptedException | ExecutionException e) {
            LOG.warn("Error getting RAG results", e);
        }

        return null;
    }

    /**
     * Determines if a query is code-related using keyword matching.
     */
    private boolean isCodeRelatedQuery(String query) {
        // Simple keyword matching for now
        return  query.contains("code");
    }

    /**
     * Creates an optimized query for RAG based on the user's request.
     */
    private String createRagQueryFromUserRequest(String userRequest) {
        // For now, just use the user request directly
        // In the future, we could extract key terms or rephrase for better RAG results
        return userRequest;
    }

    /**
     * Cleans and formats RAG results for inclusion in the prompt.
     */
    private String cleanRagResults(String ragResults) {
        // Remove any tool output headers/footers
        if (ragResults.contains("### Knowledge Base Results for:")) {
            int startIdx = ragResults.indexOf("### Knowledge Base Results for:");
            int endIdx = ragResults.indexOf("*Note: This information was retrieved from your project's knowledge base.*");

            if (startIdx >= 0 && endIdx > startIdx) {
                // Get content between header and footer
                return ragResults.substring(startIdx + "### Knowledge Base Results for:".length(), endIdx).trim();
            }
        }

        return ragResults;
    }
    /**
     * Adds RAG knowledge to the prompt.
     */
    private void addRagKnowledge(StringBuilder prompt, String knowledge) {
        prompt.append("<KNOWLEDGE_BASE_CONTEXT>\n");
        prompt.append("The following information from the project's knowledge base may be relevant to the user's request:\n\n");
        prompt.append(knowledge);
        prompt.append("\n</KNOWLEDGE_BASE_CONTEXT>\n\n");
    }


    // Helper method to add a tool example
    private void addToolExample(StringBuilder prompt, AgentTool tool) {
        StringBuilder xmlBuilder = new StringBuilder();
        xmlBuilder.append("<TOOL>\n");
        xmlBuilder.append("  <methodName>").append(tool.getName()).append("</methodName>\n");
        xmlBuilder.append("  <params>\n");

        JsonObject params = tool.getExampleParams();
        for (String paramName : params.keySet()) {
            xmlBuilder.append("    <param>\n");
            xmlBuilder.append("      <name>").append(paramName).append("</name>\n");
            xmlBuilder.append("      <value>").append(params.get(paramName).getAsString()).append("</value>\n");
            xmlBuilder.append("    </param>\n");
        }

        xmlBuilder.append("  </params>\n");
        xmlBuilder.append("</TOOL>");

        prompt.append(xmlBuilder);
    }
    /**
     * Adds code context to the prompt.
     */
    private void addCodeContext(StringBuilder prompt, Map<String, String> codeContext) {
        if (codeContext == null || codeContext.isEmpty()) {
            return;
        }
        
        prompt.append("The context is hereby provided.")
        .append("<strong>THIS CODE_CONTEXT MAY NOT BE RELEVANT TO USER'S REQUEST</strong>\n").append("\n\n<CODE_CONTEXT>\n");
        for (Map.Entry<String, String> entry : codeContext.entrySet()) {
            if (!entry.getKey().equals("currentFileContent")) {
                prompt.append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
            }
        }

        // Include the file content separately to avoid cluttering the context
//        if (codeContext.containsKey("currentFileContent")) {
//            String content = codeContext.get("currentFileContent");
//            if (content.length() > 1000) {
//                // If file is large, include a truncated version
//                prompt.append("currentFileContent (truncated): \n```\n");
//                prompt.append(content.substring(0, 1000)).append("\n... [truncated, use READ_FILE tool for full content]\n```\n");
//            } else {
//                prompt.append("currentFileContent: \n```\n").append(content).append("\n```\n");
//            }
//        }
        prompt.append("</CODE_CONTEXT>\n\n");
    }
    
    /**
     * Adds conversation history to the prompt.
     */
    private void addConversationHistory(StringBuilder prompt, List<String> conversationHistory) {
        if (conversationHistory == null || conversationHistory.isEmpty()) {
            return;
        }
        
        prompt.append("<CONVERSATION_HISTORY>\n");
        // Limit history to last 10 messages
        int startIdx = Math.max(0, conversationHistory.size() - 10);
        for (int i = startIdx; i < conversationHistory.size(); i++) {
            prompt.append(conversationHistory.get(i)).append("\n\n");
        }
        prompt.append("</CONVERSATION_HISTORY>\n\n");
    }
}