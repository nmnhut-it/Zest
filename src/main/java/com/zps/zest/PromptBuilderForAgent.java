package com.zps.zest;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.zps.zest.tools.AgentTool;
import com.zps.zest.tools.XmlRpcUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds prompts for the LLM with context and tool documentation.
 */
public class PromptBuilderForAgent {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    
    private final AgentToolRegistry toolRegistry;
    
    public PromptBuilderForAgent(AgentToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
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
        prompt.append("You are Zingplay Game Studio Assistant (Zest), a helpful AI coding assistant integrated into IntelliJ IDEA. ");
        prompt.append("You help programmers write, understand, and improve code. ");
        prompt.append("Be concise, precise, and helpful. Remember you are part of an IDE, so focus on code improvements, ");
        prompt.append("explanations, and practical solutions.\n\n");


        prompt.append("# HOW TO HELP USERS\n\n");
        prompt.append("1. First, clarify user's requirement. Ask follow up questions if needed\n");
        prompt.append("2. Then, use appropriate tools to understand the context (read files, examine structure)\n");
        prompt.append("3. After that, analyze the code and identify potential improvements\n");
        prompt.append("4. Next, implement changes using the modification tools\n");
        prompt.append("5. Finally, use tools to check for errors and try to fix with modification tools.\n\n");

        prompt.append("When helping users with code, always follow this workflow:\n");
        prompt.append("1. UNDERSTAND: Use tools to examine relevant code\n");
        prompt.append("2. EXPLAIN: Provide clear analysis based on what you found\n");
        prompt.append("3. IMPLEMENT: Suggest or make changes with appropriate tools\n");
        prompt.append("4. VERIFY: Verify if the output is good enough and suggest fixes if needed\n\n");

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
        prompt.append("You have access to the following tools. Use these tools strategically to gather context and modify code.\n\n");

        // Group tools by category
        prompt.append("## File Operations\n");
        addToolsByCategory(prompt, "read_file", "list_files", "create_file");

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
        prompt.append("# SPECIAL INSTRUCTION FOR RAG TOOL\n\n");
        prompt.append("You have access to a RAG (Retrieval Augmented Generation) tool that can search through the project's knowledge base. Use this tool when you need specific information about the user's codebase or project-specific details.\n\n");
        prompt.append("When to use the RAG tool:\n");
        prompt.append("- When asked about specific classes or implementations in the user's project\n");
        prompt.append("- When you need details about project structure or architecture\n");
        prompt.append("- When you're uncertain about project-specific conventions or patterns\n\n");

        prompt.append("Example usage:\n");
        prompt.append("<TOOL>\n");
        prompt.append("  <methodName>rag_search</methodName>\n");
        prompt.append("  <params>\n");
        prompt.append("    <param>\n");
        prompt.append("      <name>query</name>\n");
        prompt.append("      <value>How does the ToolParser process tool invocations?</value>\n");
        prompt.append("    </param>\n");
        prompt.append("    <param>\n");
        prompt.append("      <name>top_k</name>\n");
        prompt.append("      <value>3</value>\n");
        prompt.append("    </param>\n");
        prompt.append("  </params>\n");
        prompt.append("</TOOL>\n\n");

        prompt.append("IMPORTANT USAGE NOTES:\n");
        prompt.append("- ALWAYS wrap tool invocations between <TOOL> and </TOOL> tags\n");
        prompt.append("- ALWAYS properly escape characters in XML: use &lt; for <, &gt; for >, and &amp; for &\n");
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