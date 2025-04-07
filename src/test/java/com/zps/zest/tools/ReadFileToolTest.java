package com.zps.zest.tools;

import com.google.gson.JsonObject;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReadFileToolTest {

    @Mock
    private Project project;

    @Mock
    private Application application;

    @Mock
    private LocalFileSystem localFileSystem;
    
    @Mock
    private ProjectRootManager projectRootManager;
    
    @Mock
    private PsiManager psiManager;
    
    @Mock
    private VirtualFile virtualFile;
    
    @Mock
    private PsiFile psiFile;
    
    private ReadFileTool readFileTool;
    
    private static final String TEST_FILE_PATH = "src/test/file.java";
    private static final String TEST_FILE_CONTENT = "package com.test;\n\npublic class Test { }";

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        
        // Setup Application Manager
        mockStatic(ApplicationManager.class);
        when(ApplicationManager.getApplication()).thenReturn(application);

        // Setup for running tasks in read action
        when(application.runReadAction((Computable<Object>) any())).thenAnswer(invocation -> {
            return TEST_FILE_CONTENT;
        });
        
        // Setup LocalFileSystem
        mockStatic(LocalFileSystem.class);
        when(LocalFileSystem.getInstance()).thenReturn(localFileSystem);
        
        // Setup project base path
        when(project.getBasePath()).thenReturn("/test/project");
        
        // Setup ProjectRootManager
        mockStatic(ProjectRootManager.class);
        when(ProjectRootManager.getInstance(project)).thenReturn(projectRootManager);
        VirtualFile[] sourceRoots = new VirtualFile[]{virtualFile};
        when(projectRootManager.getContentSourceRoots()).thenReturn(sourceRoots);
        
        // Setup VirtualFile
        when(virtualFile.getPath()).thenReturn("/test/project/src");
        when(virtualFile.exists()).thenReturn(true);
        
        // Setup file paths
        when(localFileSystem.findFileByPath("/test/project/src/test/file.java")).thenReturn(virtualFile);
        when(localFileSystem.findFileByPath("/test/project/" + TEST_FILE_PATH)).thenReturn(virtualFile);
        
        // Setup PsiManager
        mockStatic(PsiManager.class);
        when(PsiManager.getInstance(project)).thenReturn(psiManager);
        when(psiManager.findFile(virtualFile)).thenReturn(psiFile);
        
        // Setup PsiFile
        when(psiFile.getText()).thenReturn(TEST_FILE_CONTENT);
        
        // Setup FilenameIndex
        mockStatic(FilenameIndex.class);
        when(FilenameIndex.getFilesByName(eq(project), eq("file.java"), any(GlobalSearchScope.class)))
                .thenReturn(new PsiFile[]{psiFile});
        
        readFileTool = new ReadFileTool(project);
    }

    @Test
    void testDoExecuteWithValidPath() throws Exception {
        JsonObject params = new JsonObject();
        params.addProperty("path", TEST_FILE_PATH);
        
        String result = readFileTool.doExecute(params);
        
        assertEquals(TEST_FILE_CONTENT, result);
    }
    
    @Test
    void testDoExecuteWithEmptyPath() throws Exception {
        JsonObject params = new JsonObject();
        params.addProperty("path", "");
        
        String result = readFileTool.doExecute(params);
        
        assertEquals("Error: File path is required", result);
    }
    
    @Test
    void testDoExecuteWithNonExistentFile() throws Exception {
        JsonObject params = new JsonObject();
        params.addProperty("path", "nonexistent.java");
        
        // Setup to simulate file not found
        when(application.runReadAction((Computable<Object>) any())).thenAnswer(invocation -> {
            return "File not found: nonexistent.java";
        });
        
        String result = readFileTool.doExecute(params);
        
        assertEquals("File not found: nonexistent.java", result);
    }
    
    @Test
    void testGetExampleParams() {
        JsonObject params = readFileTool.getExampleParams();
        
        assertNotNull(params);
        assertTrue(params.has("path"));
        assertEquals("path/to/file.java", params.get("path").getAsString());
    }
}