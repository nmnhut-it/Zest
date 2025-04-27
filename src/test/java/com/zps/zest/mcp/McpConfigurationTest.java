package com.zps.zest.mcp;

import com.intellij.openapi.project.Project;
import com.zps.zest.ConfigurationManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for MCP configuration functionality
 */
public class McpConfigurationTest {
    
    /**
     * Simple implementation of ConfigurationManager for testing that doesn't rely on
     * actual project structure.
     */
    private static class TestConfigurationManager extends ConfigurationManager {
        private boolean mcpEnabled = false;
        private String mcpServerUri = "http://localhost:8080/mcp";
        
        public TestConfigurationManager() {
            super(null); // Null project as we won't use it
        }
        
        @Override
        public boolean isMcpEnabled() {
            return mcpEnabled;
        }
        
        @Override
        public void setMcpEnabled(boolean value) {
            this.mcpEnabled = value;
        }
        
        @Override
        public String getMcpServerUri() {
            return mcpServerUri;
        }
        
        @Override
        public void setMcpServerUri(String uri) {
            this.mcpServerUri = uri;
        }
    }
    
    private TestConfigurationManager configManager;
    
    @BeforeEach
    void setUp() {
        configManager = new TestConfigurationManager();
    }
    
    @Test
    void testMcpEnabledFlag() {
        // Default should be disabled
        assertFalse(configManager.isMcpEnabled());
        
        // Enable MCP
        configManager.setMcpEnabled(true);
        assertTrue(configManager.isMcpEnabled());
        
        // Disable MCP
        configManager.setMcpEnabled(false);
        assertFalse(configManager.isMcpEnabled());
    }
    
    @Test
    void testMcpServerUri() {
        // Check default value
        assertEquals("http://localhost:8080/mcp", configManager.getMcpServerUri());
        
        // Set a new URI
        configManager.setMcpServerUri("https://example.com/mcp");
        assertEquals("https://example.com/mcp", configManager.getMcpServerUri());
        
        // Set an empty URI should be allowed (though not recommended)
        configManager.setMcpServerUri("");
        assertEquals("", configManager.getMcpServerUri());
        
        // Set back to default
        configManager.setMcpServerUri("http://localhost:8080/mcp");
        assertEquals("http://localhost:8080/mcp", configManager.getMcpServerUri());
    }
    
    @Test
    void testPropertiesFileFormat(@TempDir Path tempDir) throws Exception {
        // Create a properties file with MCP settings
        File propsFile = tempDir.resolve("test-config.properties").toFile();
        Properties props = new Properties();
        props.setProperty("mcpEnabled", "true");
        props.setProperty("mcpServerUri", "https://test-server.com/mcp");
        
        try (FileOutputStream out = new FileOutputStream(propsFile)) {
            props.store(out, "Test MCP Properties");
        }
        
        // Verify the file was created with correct content
        assertTrue(propsFile.exists());
        
        // Load the properties to verify format
        Properties loadedProps = new Properties();
        try (java.io.FileInputStream in = new java.io.FileInputStream(propsFile)) {
            loadedProps.load(in);
        }
        
        assertEquals("true", loadedProps.getProperty("mcpEnabled"));
        assertEquals("https://test-server.com/mcp", loadedProps.getProperty("mcpServerUri"));
    }
}
