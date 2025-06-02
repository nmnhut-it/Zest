package com.zps.zest.rag;

import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.zps.zest.ConfigurationManager;
import junit.framework.TestCase;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for RagAgent using JUnit 4.
 */
public class RagAgentTest extends TestCase {
    
    @Mock
    private Project mockProject;
    
    @Mock
    private ConfigurationManager mockConfig;
    
    @Mock
    private CodeAnalyzer mockCodeAnalyzer;
    
    @Mock
    private KnowledgeApiClient mockApiClient;
    
    @Mock
    private ProgressIndicator mockProgressIndicator;
    
    @Mock
    private VirtualFile mockFile;
    
    @Mock
    private PsiFile mockPsiFile;
    
    private RagAgent ragAgent;
    
    @Override
    protected void setUp() throws Exception {
        super.setUp();
        MockitoAnnotations.openMocks(this);
        
        when(mockProject.getName()).thenReturn("TestProject");
        when(mockConfig.getApiUrl()).thenReturn("http://localhost:8080/api");
        when(mockConfig.getAuthToken()).thenReturn("test-token");
        
        ragAgent = new RagAgent(mockProject, mockConfig, mockCodeAnalyzer, mockApiClient);
    }
    
    public void testPerformIndexing_CreatesKnowledgeBase() throws Exception {
        // Given
        when(mockConfig.getKnowledgeId()).thenReturn(null);
        when(mockApiClient.createKnowledgeBase(anyString(), anyString())).thenReturn("kb-123");
        
        ProjectInfo projectInfo = new ProjectInfo();
        projectInfo.setBuildSystem("Gradle");
        when(mockCodeAnalyzer.extractProjectInfo()).thenReturn(projectInfo);
        when(mockCodeAnalyzer.findAllSourceFiles()).thenReturn(Arrays.asList());
        
        // When
        ragAgent.performIndexing(mockProgressIndicator, false);
        
        // Then
        verify(mockApiClient).createKnowledgeBase(
            eq("project-code-TestProject"), 
            contains("Code signatures and project info")
        );
        verify(mockConfig).setKnowledgeId("kb-123");
        verify(mockConfig).saveConfig();
    }
    
    public void testIndexFile_ExtractsAndUploadsSignatures() throws Exception {
        // Given
        String knowledgeId = "kb-123";
        when(mockFile.getPath()).thenReturn("/src/TestClass.java");
        when(mockFile.getName()).thenReturn("TestClass.java");
        when(mockFile.getNameWithoutExtension()).thenReturn("TestClass");
        
        CodeSignature signature = TestUtils.createTestSignature("com.test.TestClass", "class");
        
        when(mockCodeAnalyzer.extractSignatures(any())).thenReturn(Arrays.asList(signature));
        when(mockApiClient.uploadFile(anyString(), anyString())).thenReturn("file-123");
        
        // When
        ragAgent.indexFile(knowledgeId, mockFile);
        
        // Then
        verify(mockApiClient).uploadFile(eq("TestClass-signatures.md"), anyString());
        verify(mockApiClient).addFileToKnowledge(eq(knowledgeId), eq("file-123"));
        
        // Verify signature was cached
        assertEquals(1, ragAgent.getSignatureCache().size());
    }
    
    public void testFindRelatedCode_ReturnsMatchingSignatures() throws Exception {
        // Given
        when(mockConfig.getKnowledgeId()).thenReturn("kb-123");
        
        // Add some signatures to cache
        CodeSignature sig1 = new CodeSignature(
            "com.test.AuthService",
            "public class AuthService",
            "{\"type\":\"class\"}",
            "/src/AuthService.java"
        );
        CodeSignature sig2 = new CodeSignature(
            "com.test.UserService",
            "public class UserService",
            "{\"type\":\"class\"}",
            "/src/UserService.java"
        );
        
        ragAgent.getSignatureCache().put("/src/AuthService.java", Arrays.asList(sig1));
        ragAgent.getSignatureCache().put("/src/UserService.java", Arrays.asList(sig2));
        
        // When
        CompletableFuture<List<RagAgent.CodeMatch>> future = ragAgent.findRelatedCode("auth");
        List<RagAgent.CodeMatch> matches = future.get();
        
        // Then
        assertEquals(1, matches.size());
        assertEquals("com.test.AuthService", matches.get(0).getSignature().getId());
        assertTrue(matches.get(0).getRelevance() > 0);
    }
    
    public void testCalculateRelevance_ScoresCorrectly() {
        // Given
        CodeSignature signature = new CodeSignature(
            "com.test.AuthenticationService#login",
            "public void login(String username, String password)",
            "{\"type\":\"method\"}",
            "/src/AuthenticationService.java"
        );
        
        // When & Then
        assertEquals(1.0, ragAgent.calculateRelevance(signature, "AuthenticationService"), 0.01);
        assertEquals(1.0, ragAgent.calculateRelevance(signature, "login"), 0.01);
        assertTrue(ragAgent.calculateRelevance(signature, "auth") > 0.5);
        assertTrue(ragAgent.calculateRelevance(signature, "password") > 0);
        assertEquals(0.0, ragAgent.calculateRelevance(signature, "unrelated"), 0.01);
    }
    
    public void testCreateProjectOverviewDocument() {
        // Given
        ProjectInfo info = new ProjectInfo();
        info.setBuildSystem("Maven");
        info.setMainLanguage("Java");
        info.setTotalSourceFiles(42);
        info.addDependency("org.springframework:spring-core:5.3.0");
        info.addLibrary("commons-lang3.jar");
        
        // When
        String document = ragAgent.createProjectOverviewDocument(info);
        
        // Then
        assertTrue(document.contains("Project Overview: TestProject"));
        assertTrue(document.contains("Build System:** Maven"));
        assertTrue(document.contains("org.springframework:spring-core:5.3.0"));
        assertTrue(document.contains("commons-lang3.jar"));
        assertTrue(document.contains("Total Source Files:** 42"));
    }
    
    public void testErrorHandling_ContinuesOnFileFailure() throws Exception {
        // Given
        when(mockConfig.getKnowledgeId()).thenReturn("kb-123");
        
        VirtualFile file1 = mock(VirtualFile.class);
        VirtualFile file2 = mock(VirtualFile.class);
        when(file1.getName()).thenReturn("File1.java");
        when(file2.getName()).thenReturn("File2.java");
        
        when(mockCodeAnalyzer.extractProjectInfo()).thenReturn(new ProjectInfo());
        when(mockCodeAnalyzer.findAllSourceFiles()).thenReturn(Arrays.asList(file1, file2));
        
        // Make first file fail
        when(mockCodeAnalyzer.extractSignatures(any())).thenThrow(new RuntimeException("PSI error"));
        
        // When
        ragAgent.performIndexing(mockProgressIndicator, false);
        
        // Then - should complete without throwing
        verify(mockProgressIndicator, never()).cancel();
    }
    
    public void testGetFullCode_HandlesNullInput() {
        // When
        String result = ragAgent.getFullCode(null);
        
        // Then
        assertNull(result);
    }
    
    public void testGetFullCode_HandlesEmptyInput() {
        // When
        String result = ragAgent.getFullCode("");
        
        // Then
        assertNull(result);
    }
    
    public void testExtractCodeMatches_FiltersLowRelevance() {
        // Given
        CodeSignature sig1 = new CodeSignature(
            "com.test.Service",
            "public class Service",
            "{\"type\":\"class\"}",
            "/src/Service.java"
        );
        CodeSignature sig2 = new CodeSignature(
            "com.test.Helper",
            "public class Helper",
            "{\"type\":\"class\"}",
            "/src/Helper.java"
        );
        
        ragAgent.getSignatureCache().put("/src/Service.java", Arrays.asList(sig1));
        ragAgent.getSignatureCache().put("/src/Helper.java", Arrays.asList(sig2));
        
        // When
        List<RagAgent.CodeMatch> matches = ragAgent.extractCodeMatches("dummy", "unrelated");
        
        // Then
        assertTrue(matches.isEmpty()); // All should be filtered out due to low relevance
    }
}
