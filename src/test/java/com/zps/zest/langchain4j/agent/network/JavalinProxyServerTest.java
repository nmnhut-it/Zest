package com.zps.zest.langchain4j.agent.network;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.project.Project;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

/**
 * Tests for JavalinProxyServer
 */
public class JavalinProxyServerTest extends BasePlatformTestCase {
    
    private JavalinProxyServer server;
    private final int testPort = 8765;
    private final Gson gson = new Gson();
    private HttpClient httpClient;
    
    @Before
    public void setUp() throws Exception {
        super.setUp();
        Project project = getProject();
        AgentProxyConfiguration config = AgentProxyConfiguration.getQuickAugmentation();
        
        server = new JavalinProxyServer(project, testPort, config);
        server.start();
        
        httpClient = HttpClient.newHttpClient();
        
        // Give the server time to start
        Thread.sleep(1000);
    }
    
    @After
    public void tearDown() throws Exception {
        if (server != null) {
            server.stop();
        }
        super.tearDown();
    }
    
    @Test
    public void testHealthEndpoint() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + testPort + "/health"))
            .GET()
            .build();
            
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        assertEquals(200, response.statusCode());
        
        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        assertEquals("ok", json.get("status").getAsString());
        assertEquals("zest-proxy", json.get("service").getAsString());
        assertEquals(testPort, json.get("port").getAsInt());
    }
    
    @Test
    public void testOpenApiEndpoint() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + testPort + "/zest/openapi.json"))
            .GET()
            .build();
            
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        assertEquals(200, response.statusCode());
        
        JsonObject spec = JsonParser.parseString(response.body()).getAsJsonObject();
        assertEquals("3.1.0", spec.get("openapi").getAsString());
        assertEquals("Zest Code Explorer", spec.getAsJsonObject("info").get("title").getAsString());
        
        // Check that paths exist
        assertTrue(spec.has("paths"));
        JsonObject paths = spec.getAsJsonObject("paths");
        
        // Check orchestration endpoints
        assertTrue(paths.has("/explore_code"));
        assertTrue(paths.has("/list_tools"));
    }
    
    @Test
    public void testListToolsEndpoint() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + testPort + "/zest/list_tools"))
            .POST(HttpRequest.BodyPublishers.ofString("{}"))
            .header("Content-Type", "application/json")
            .build();
            
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        assertEquals(200, response.statusCode());
        
        Map<String, Object> result = gson.fromJson(response.body(), Map.class);
        assertTrue(result.containsKey("tools"));
        
        // Tools should be a list
        assertTrue(result.get("tools") instanceof java.util.List);
    }
    
    @Test
    public void testGetConfigEndpoint() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + testPort + "/zest/get_config"))
            .POST(HttpRequest.BodyPublishers.ofString("{}"))
            .header("Content-Type", "application/json")
            .build();
            
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        assertEquals(200, response.statusCode());
        
        Map<String, Object> config = gson.fromJson(response.body(), Map.class);
        assertTrue(config.containsKey("maxToolCalls"));
        assertEquals(5.0, config.get("maxToolCalls")); // Quick config has 5 max tool calls
    }
    
    @Test
    public void testExploreCodeValidation() throws Exception {
        // Test missing query field
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + testPort + "/zest/explore_code"))
            .POST(HttpRequest.BodyPublishers.ofString("{}"))
            .header("Content-Type", "application/json")
            .build();
            
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        assertEquals(400, response.statusCode());
        
        Map<String, Object> error = gson.fromJson(response.body(), Map.class);
        assertTrue(error.containsKey("message"));
        assertTrue(error.get("message").toString().contains("query"));
    }
}
