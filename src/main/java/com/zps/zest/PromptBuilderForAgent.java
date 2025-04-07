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
//        addCodeContext(prompt, codeContext);
        
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
        prompt.append("3. IMPLEMENT: Suggest or make changes with appropriate tools\n\n");
        prompt.append("4. VERIFY: Verify if the output is good enough and suggest fixes if needed. Use tools to aid you.\n\n");

        prompt.append("Remember, your primary advantage is your ability to use tools to examine and modify code directly in the IDE.\n");
        prompt.append("</s>\n\n");
    }
    
    /**
     * Adds tool documentation to the prompt.
     */
    private void addToolDocumentation(StringBuilder prompt) {
        prompt.append("# AVAILABLE TOOLS\n\n");
        prompt.append("You have access to the following tools. Always use these tools to gather context before providing solutions.\n\n");
        
        Set<String> toolNames = toolRegistry.getToolNames();
        for (String toolName : toolNames) {
            AgentTool tool = toolRegistry.getTool(toolName);
            if (tool != null) {
                prompt.append("- `").append(tool.getName()).append("`: ")
                      .append(tool.getDescription()).append("\n")
                        .append("Example:\n");

                StringBuilder xmlBuilder = new StringBuilder();
                xmlBuilder.append("<TOOL>\n");
                xmlBuilder.append("   <methodName>").append(tool.getName()).append("</methodName>\n");
                xmlBuilder.append("   <params>\n");

// Assuming getExampleParams() returns a JsonObject
                JsonObject params = tool.getExampleParams();
                for (String paramName : params.keySet()) {
                    xmlBuilder.append("     <param>\n");
                    xmlBuilder.append("       <name>").append(paramName).append("</name>\n");
                    xmlBuilder.append("       <value>").append(params.get(paramName).getAsString()).append("</value>\n");
                    xmlBuilder.append("     </param>\n");
                }

                xmlBuilder.append("   </params>\n");
                xmlBuilder.append("</TOOL>\n");

                prompt.append(xmlBuilder).append("\n");
            }
        }
        prompt.append("# HOW TO INVOKE TOOLS\n\n");
        prompt.append("Always use the this format for invoking tools:\n");
        prompt.append("```\n");
        prompt.append("<TOOL>\n");
        prompt.append("  <methodName>tool_name</methodName>\n");
        prompt.append("  <params>\n");
        prompt.append("    <param>\n");
        prompt.append("      <name>param1</name>\n");
        prompt.append("      <value>value1</value>\n");
        prompt.append("    </param>\n");
        prompt.append("    <param>\n");
        prompt.append("      <name>param2</name>\n");
        prompt.append("      <value>value2</value>\n");
        prompt.append("    </param>\n");
        prompt.append("  </params>\n");
        prompt.append("</TOOL>\n\n");
        prompt.append("```\n\n");
        prompt.append("MAKE SURE YOU WRAP IT BETWEEN THE TAGS <TOOL> AND </TOOL>. MAKE SURE CHARACTERS ARE APPROPRIATELY ESCAPED\n\n");
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
        if (codeContext.containsKey("currentFileContent")) {
            String content = codeContext.get("currentFileContent");
            if (content.length() > 1000) {
                // If file is large, include a truncated version
                prompt.append("currentFileContent (truncated): \n```\n");
                prompt.append(content.substring(0, 1000)).append("\n... [truncated, use READ_FILE tool for full content]\n```\n");
            } else {
                prompt.append("currentFileContent: \n```\n").append(content).append("\n```\n");
            }
        }
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