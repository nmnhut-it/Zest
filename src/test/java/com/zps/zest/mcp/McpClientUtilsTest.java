package com.zps.zest.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.ClientCapabilities;
import io.modelcontextprotocol.spec.McpSchema.Implementation;
import io.modelcontextprotocol.spec.McpSchema.InitializeRequest;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCRequest;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCResponse;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCNotification;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for MCP client utility functions that don't require external dependencies.
 */
public class McpClientUtilsTest {
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @Test
    void testDeserializeJsonRpcRequest() throws IOException {
        // Create a sample JSON-RPC request
        String jsonRequest = "{"
                + "\"jsonrpc\": \"2.0\","
                + "\"method\": \"initialize\","
                + "\"id\": 123,"
                + "\"params\": {\"protocolVersion\": \"2024-11-05\"}"
                + "}";
        
        // Deserialize using the MCP Schema utility
        JSONRPCMessage message = McpSchema.deserializeJsonRpcMessage(objectMapper, jsonRequest);
        
        // Verify the result
        assertTrue(message instanceof JSONRPCRequest);
        JSONRPCRequest request = (JSONRPCRequest) message;
        
        assertEquals("2.0", request.jsonrpc());
        assertEquals("initialize", request.method());
        assertEquals(123, ((Number) request.id()).intValue());
        assertNotNull(request.params());
    }
    
    @Test
    void testDeserializeJsonRpcResponse() throws IOException {
        // Create a sample JSON-RPC response
        String jsonResponse = "{"
                + "\"jsonrpc\": \"2.0\","
                + "\"id\": 123,"
                + "\"result\": {\"status\": \"success\"}"
                + "}";
        
        // Deserialize using the MCP Schema utility
        JSONRPCMessage message = McpSchema.deserializeJsonRpcMessage(objectMapper, jsonResponse);
        
        // Verify the result
        assertTrue(message instanceof JSONRPCResponse);
        JSONRPCResponse response = (JSONRPCResponse) message;
        
        assertEquals("2.0", response.jsonrpc());
        assertEquals(123, ((Number) response.id()).intValue());
        assertNotNull(response.result());
        assertNull(response.error());
    }
    
    @Test
    void testDeserializeJsonRpcResponseWithError() throws IOException {
        // Create a sample JSON-RPC error response
        String jsonErrorResponse = "{"
                + "\"jsonrpc\": \"2.0\","
                + "\"id\": 123,"
                + "\"error\": {\"code\": -32601, \"message\": \"Method not found\"}"
                + "}";
        
        // Deserialize using the MCP Schema utility
        JSONRPCMessage message = McpSchema.deserializeJsonRpcMessage(objectMapper, jsonErrorResponse);
        
        // Verify the result
        assertTrue(message instanceof JSONRPCResponse);
        JSONRPCResponse response = (JSONRPCResponse) message;
        
        assertEquals("2.0", response.jsonrpc());
        assertEquals(123, ((Number) response.id()).intValue());
        assertNull(response.result());
        assertNotNull(response.error());
        assertEquals(-32601, response.error().code());
        assertEquals("Method not found", response.error().message());
    }
    
    @Test
    void testDeserializeJsonRpcNotification() throws IOException {
        // Create a sample JSON-RPC notification
        String jsonNotification = "{"
                + "\"jsonrpc\": \"2.0\","
                + "\"method\": \"notifications/tools/list_changed\","
                + "\"params\": {\"updated\": true}"
                + "}";
        
        // Deserialize using the MCP Schema utility
        JSONRPCMessage message = McpSchema.deserializeJsonRpcMessage(objectMapper, jsonNotification);
        
        // Verify the result
        assertTrue(message instanceof JSONRPCNotification);
        JSONRPCNotification notification = (JSONRPCNotification) message;
        
        assertEquals("2.0", notification.jsonrpc());
        assertEquals("notifications/tools/list_changed", notification.method());
        assertNotNull(notification.params());
    }
    
    @Test
    void testInitializeRequestSerialization() throws IOException {
        // Create a client capabilities object
        ClientCapabilities capabilities = ClientCapabilities.builder()
                .sampling()
                .build();
        
        // Create an implementation info object
        Implementation implementation = new Implementation("TestClient", "1.0.0");
        
        // Create an initialize request
        InitializeRequest initRequest = new InitializeRequest(
                McpSchema.LATEST_PROTOCOL_VERSION,
                capabilities,
                implementation
        );
        
        // Create a JSON-RPC request
        JSONRPCRequest rpcRequest = new JSONRPCRequest(
                McpSchema.JSONRPC_VERSION,
                McpSchema.METHOD_INITIALIZE,
                "test_id",
                initRequest
        );
        
        // Serialize to JSON
        String json = objectMapper.writeValueAsString(rpcRequest);
        
        // Verify the JSON
        assertTrue(json.contains("\"jsonrpc\":\"2.0\""));
        assertTrue(json.contains("\"method\":\"initialize\""));
        assertTrue(json.contains("\"id\":\"test_id\""));
        assertTrue(json.contains("\"protocolVersion\":\"" + McpSchema.LATEST_PROTOCOL_VERSION + "\""));
        assertTrue(json.contains("\"sampling\":{}"));
        assertTrue(json.contains("\"name\":\"TestClient\""));
        assertTrue(json.contains("\"version\":\"1.0.0\""));
        
        // Deserialize back to verify round-trip
        JSONRPCMessage deserialized = McpSchema.deserializeJsonRpcMessage(objectMapper, json);
        assertTrue(deserialized instanceof JSONRPCRequest);
    }
    
    @Test
    void testToolUtilities() {
        // Test the JsonSchema and Tool constructor
        String schemaJson = "{"
                + "\"type\": \"object\","
                + "\"properties\": {"
                + "  \"fileName\": {\"type\": \"string\"},"
                + "  \"lineNumber\": {\"type\": \"number\"}"
                + "},"
                + "\"required\": [\"fileName\"]"
                + "}";
        
        McpSchema.Tool tool = new McpSchema.Tool(
                "testTool",
                "A test tool",
                schemaJson
        );
        
        assertEquals("testTool", tool.name());
        assertEquals("A test tool", tool.description());
        
        // Test CallToolRequest with a string argument
        Map<String, Object> args = new HashMap<>();
        args.put("fileName", "test.txt");
        args.put("lineNumber", 42);
        
        McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(
                "testTool",
                args
        );
        
        assertEquals("testTool", request.name());
        assertEquals("test.txt", request.arguments().get("fileName"));
        assertEquals(42, request.arguments().get("lineNumber"));
        
        // Test the string-based constructor as well
        String argsJson = "{\"fileName\":\"test.txt\",\"lineNumber\":42}";
        McpSchema.CallToolRequest request2 = new McpSchema.CallToolRequest("testTool", argsJson);
        
        assertEquals("testTool", request2.name());
        assertEquals("test.txt", request2.arguments().get("fileName"));
        assertEquals(42, ((Number)request2.arguments().get("lineNumber")).intValue());
    }
}
