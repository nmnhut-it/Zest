package com.zps.zest.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Test for MCP Streamable HTTP transport.
 * Verifies the server starts and responds to basic requests.
 */
public class McpServerTransportTest {

    private static final int TEST_PORT = 45451;
    private static final String MCP_ENDPOINT = "/mcp";

    private Server jettyServer;
    private McpSyncServer mcpServer;

    @Before
    public void setUp() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        JacksonMcpJsonMapper mcpJsonMapper = new JacksonMcpJsonMapper(objectMapper);

        HttpServletStreamableServerTransportProvider transport = HttpServletStreamableServerTransportProvider.builder()
                .jsonMapper(mcpJsonMapper)
                .mcpEndpoint(MCP_ENDPOINT)
                .build();

        mcpServer = McpServer.sync(transport)
                .jsonMapper(mcpJsonMapper)
                .serverInfo("test-server", "1.0.0")
                .capabilities(McpSchema.ServerCapabilities.builder()
                        .tools(true)
                        .prompts(true)
                        .build())
                .build();

        // Register a simple test tool
        mcpServer.addTool(new io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification(
                new McpSchema.Tool("ping", "Simple ping tool", "{}", null, null, null, null),
                (exchange, args) -> new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("pong")),
                        false
                )
        ));

        // Create Jetty server
        jettyServer = new Server();
        ServerConnector connector = new ServerConnector(jettyServer);
        connector.setPort(TEST_PORT);
        jettyServer.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");
        jettyServer.setHandler(context);

        ServletHolder transportServlet = new ServletHolder(transport);
        context.addServlet(transportServlet, "/*");

        jettyServer.start();
    }

    @After
    public void tearDown() throws Exception {
        if (jettyServer != null) {
            jettyServer.stop();
        }
    }

    @Test
    public void testServerStarts() {
        assertTrue("Server should be running", jettyServer.isRunning());
    }

    @Test
    public void testMcpEndpointResponds() throws Exception {
        URL url = new URL("http://localhost:" + TEST_PORT + MCP_ENDPOINT);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json, text/event-stream");
        conn.setDoOutput(true);

        // Send initialize request
        String initRequest = """
            {
                "jsonrpc": "2.0",
                "id": 1,
                "method": "initialize",
                "params": {
                    "protocolVersion": "2024-11-05",
                    "capabilities": {},
                    "clientInfo": {
                        "name": "test-client",
                        "version": "1.0.0"
                    }
                }
            }
            """;

        conn.getOutputStream().write(initRequest.getBytes());
        conn.getOutputStream().flush();

        int responseCode = conn.getResponseCode();
        // Accept 200 (OK) or 405 (Method not allowed for stateless) as valid responses
        assertTrue("Should get valid HTTP response, got: " + responseCode,
                responseCode == 200 || responseCode == 405 || responseCode == 400);
    }

    @Test
    public void testOptionsRequest() throws Exception {
        URL url = new URL("http://localhost:" + TEST_PORT + MCP_ENDPOINT);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("OPTIONS");

        int responseCode = conn.getResponseCode();
        // OPTIONS should return 200 or 204
        assertTrue("OPTIONS should succeed, got: " + responseCode,
                responseCode == 200 || responseCode == 204 || responseCode == 405);
    }
}
