package com.zps.zest.rag;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import com.zps.zest.ConfigurationManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for the complete RAG system using JUnit Jupiter.
 * Tests the full flow from indexing to search.
 */
public class RagSystemIntegrationTest extends LightJavaCodeInsightFixtureTestCase {
    
    private RagAgent ragAgent;
    private MockKnowledgeApiClient mockApiClient;
    
    @Override
    protected void setUp() throws Exception {
        super.setUp();
        
        Project project = getProject();
        ConfigurationManager config = ConfigurationManager.getInstance(project);
        
        // Set up mock API client
        mockApiClient = new MockKnowledgeApiClient();
        
        // Create RAG agent with mocks
        ragAgent = RagComponentFactory.createRagAgent(
            project,
            config,
            new DefaultCodeAnalyzer(project),
            mockApiClient
        );
    }
    
    @Test
    public void testFullIndexingAndSearchFlow() throws Exception {
        // Create a test file
        myFixture.configureByText("AuthService.java",
            "package com.example;\n" +
            "\n" +
            "public class AuthService {\n" +
            "    private TokenManager tokenManager;\n" +
            "    \n" +
            "    public String authenticate(String username, String password) {\n" +
            "        // Authentication logic\n" +
            "        return tokenManager.generateToken(username);\n" +
            "    }\n" +
            "    \n" +
            "    public boolean validateToken(String token) {\n" +
            "        return tokenManager.isValid(token);\n" +
            "    }\n" +
            "}"
        );
        
        // Index the project
        ApplicationManager.getApplication().invokeAndWait(() -> {
            try {
                ragAgent.performIndexing(new TestUtils.MockProgressIndicator(), false);
            } catch (Exception e) {
                fail("Indexing failed: " + e.getMessage());
            }
        });
        
        // Verify indexing created knowledge base and uploaded files
        assertEquals(1, mockApiClient.getKnowledgeBaseCount());
        assertTrue(mockApiClient.getFileCount() > 0);
        
        // Search for authentication code
        List<RagAgent.CodeMatch> matches = ragAgent.findRelatedCode("authenticate")
            .get(10, TimeUnit.SECONDS);
        
        // Verify search results
        assertFalse(matches.isEmpty());
        
        // Find the AuthService class match
        RagAgent.CodeMatch authMatch = matches.stream()
            .filter(m -> m.getSignature().getId().contains("AuthService"))
            .findFirst()
            .orElse(null);
        
        assertNotNull(authMatch, "Should find AuthService in results");
        assertTrue(authMatch.getRelevance() > 0.5, "Should have high relevance");
        
        // Get full code for the method
        String fullCode = ragAgent.getFullCode("com.example.AuthService#authenticate");
        assertNotNull(fullCode);
        assertTrue(fullCode.contains("authenticate"));
        assertTrue(fullCode.contains("username"));
        assertTrue(fullCode.contains("password"));
    }
    
    @Test
    public void testSearchWithMultipleFiles() throws Exception {
        // Create multiple related files
        myFixture.configureByText("UserService.java",
            "package com.example;\n" +
            "public class UserService {\n" +
            "    public User findByUsername(String username) { return null; }\n" +
            "}"
        );
        
        myFixture.addFileToProject("UserRepository.java",
            "package com.example;\n" +
            "public interface UserRepository {\n" +
            "    User findByUsername(String username);\n" +
            "}"
        );
        
        // Index
        ApplicationManager.getApplication().invokeAndWait(() -> {
            try {
                ragAgent.performIndexing(new TestUtils.MockProgressIndicator(), false);
            } catch (Exception e) {
                fail("Indexing failed: " + e.getMessage());
            }
        });
        
        // Search for "user" related code
        List<RagAgent.CodeMatch> matches = ragAgent.findRelatedCode("user")
            .get(10, TimeUnit.SECONDS);
        
        // Should find both UserService and UserRepository
        assertTrue(matches.size() >= 2);
        
        boolean foundService = matches.stream()
            .anyMatch(m -> m.getSignature().getId().contains("UserService"));
        boolean foundRepository = matches.stream()
            .anyMatch(m -> m.getSignature().getId().contains("UserRepository"));
        
        assertTrue(foundService, "Should find UserService");
        assertTrue(foundRepository, "Should find UserRepository");
    }
    
    @Test
    public void testProjectInfoExtraction() throws Exception {
        // Create a simple pom.xml
        myFixture.addFileToProject("pom.xml",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<project>\n" +
            "    <dependencies>\n" +
            "        <dependency>\n" +
            "            <groupId>org.springframework</groupId>\n" +
            "            <artifactId>spring-core</artifactId>\n" +
            "            <version>5.3.0</version>\n" +
            "        </dependency>\n" +
            "    </dependencies>\n" +
            "</project>"
        );
        
        // Extract project info
        DefaultCodeAnalyzer analyzer = new DefaultCodeAnalyzer(getProject());
        ProjectInfo info = analyzer.extractProjectInfo();
        
        // Verify extraction
        assertEquals("Maven", info.getBuildSystem());
        assertTrue(info.getDependencies().contains("org.springframework:spring-core:5.3.0"));
    }
    
    @Test
    public void testKotlinFileIndexing() throws Exception {
        // Create a Kotlin file
        myFixture.configureByText("DataService.kt",
            "package com.example\n" +
            "\n" +
            "data class User(val id: String, val name: String)\n" +
            "\n" +
            "class DataService {\n" +
            "    suspend fun fetchUser(id: String): User? {\n" +
            "        return null\n" +
            "    }\n" +
            "}"
        );
        
        // Index
        ApplicationManager.getApplication().invokeAndWait(() -> {
            try {
                ragAgent.performIndexing(new TestUtils.MockProgressIndicator(), false);
            } catch (Exception e) {
                fail("Indexing failed: " + e.getMessage());
            }
        });
        
        // Search for Kotlin code
        List<RagAgent.CodeMatch> matches = ragAgent.findRelatedCode("fetchUser")
            .get(10, TimeUnit.SECONDS);
        
        assertTrue(matches.size() > 0, "Should find Kotlin code");
    }
    
    @Test
    public void testEmptyProjectHandling() throws Exception {
        // Index empty project
        ApplicationManager.getApplication().invokeAndWait(() -> {
            try {
                ragAgent.performIndexing(new TestUtils.MockProgressIndicator(), false);
            } catch (Exception e) {
                fail("Indexing failed: " + e.getMessage());
            }
        });
        
        // Should still create knowledge base with project overview
        assertEquals(1, mockApiClient.getKnowledgeBaseCount());
        assertTrue(mockApiClient.getFileCount() >= 1); // At least project overview
    }
}
