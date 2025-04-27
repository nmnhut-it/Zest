package com.zps.zest.mcp;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import com.zps.zest.AgentToolRegistry;
import com.zps.zest.tools.AgentTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for McpToolAdapter that focus on functionality that can be tested
 * without requiring external dependencies like project instances or network connections.
 */
public class McpToolAdapterTest {

    // A simple test implementation of AgentTool for testing
    private static class TestAgentTool implements AgentTool {
        private final String name;
        private final String description;
        private final JsonObject exampleParams;
        
        public TestAgentTool(String name, String description, JsonObject exampleParams) {
            this.name = name;
            this.description = description;
            this.exampleParams = exampleParams;
        }
        
        @Override
        public String getName() {
            return name;
        }
        
        @Override
        public String getDescription() {
            return description;
        }
        
        @Override
        public String execute(JsonObject params) {
            return "Executed " + name + " with params: " + params;
        }
        
        @Override
        public JsonObject getExampleParams() {
            return exampleParams;
        }
    }
    
    // A minimal implementation of AgentToolRegistry for testing
    private static class TestToolRegistry extends AgentToolRegistry {

        public TestToolRegistry() {
            super(null); // Pass null project, won't be used in tests
            this.removeAll();
        }
        

    }
    
    private McpToolAdapter adapter;
    private TestToolRegistry registry;
    
    @BeforeEach
    void setUp() {
        registry = new TestToolRegistry();
        adapter = new McpToolAdapter(registry);
        
        // Register a test tool
        JsonObject params = new JsonObject();
        params.add("fileName", new JsonPrimitive("example.txt"));
        params.add("lineNumber", new JsonPrimitive(42));
        params.add("isRecursive", new JsonPrimitive(true));
        
        registry.register(new TestAgentTool(
                "testTool",
                "A test tool for unit testing",
                params
        ));
    }
    
    @Test
    void testConvertToolsToMcpDefinitions() {
        // Convert tools to MCP definitions
        List<Tool> tools = adapter.convertToolsToMcpDefinitions();
        
        // Verify the result
        assertEquals(1, tools.size(), "Should have converted one tool");
        Tool tool = tools.get(0);
        
        // Check basic properties
        assertEquals("testTool", tool.name(), "Tool name should match");
        assertEquals("A test tool for unit testing", tool.description(), "Tool description should match");
        
        // Check that schema is not null
        assertNotNull(tool.inputSchema(), "Schema should not be null");
    }
    
    @Test
    public void testExecuteTool() {
        // Create a simple map of parameters
        Map<String, Object> params = new HashMap<>();
        params.put("fileName", "test.txt");
        params.put("lineNumber", 10);
        
        // Create a CallToolRequest
        CallToolRequest request = new CallToolRequest("testTool", params);
        
        // Execute the tool
        CallToolResult result = adapter.executeTool(request);
        
        // Verify the result
        assertNotNull(result);
        assertFalse(result.isError(), "Should not have an error");
        
        // The content should be a list with one text item
        assertEquals(1, result.content().size(), "Should have one content item");
        
        // Verify the content is a TextContent
        assertTrue(result.content().get(0) instanceof TextContent, "Content should be TextContent");
        TextContent textContent = (TextContent) result.content().get(0);
        String content = textContent.text();
        assertTrue(content.contains("Executed testTool"), "Result should contain execution confirmation");
        assertTrue(content.contains("test.txt"), "Result should contain the parameter value");
    }
    
    @Test
    void testExecuteToolWithUnknownTool() {
        // Create a request for a non-existent tool
        CallToolRequest request = new CallToolRequest("unknownTool", new HashMap<>());
        
        // Execute the tool
        CallToolResult result = adapter.executeTool(request);
        
        // Verify the result
        assertNotNull(result);
        assertTrue(result.isError(), "Should have an error");
        
        // Verify the error message
        assertTrue(result.content().get(0) instanceof TextContent, "Content should be TextContent");
        TextContent textContent = (TextContent) result.content().get(0);
        String errorContent = textContent.text();
        assertTrue(errorContent.contains("Unknown tool"), "Error message should mention unknown tool");
    }
}