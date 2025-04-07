package com.zps.zest.tools;

import com.google.gson.JsonObject;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.deft.Obj;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.File;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class CreateFileToolTest {

    @Mock
    private Project project;
    
    @Mock
    private LocalFileSystem localFileSystem;
    
    @Mock
    private VirtualFile parentVirtualFile;
    
    @Mock
    private VirtualFile newVirtualFile;
    
    @Mock
    private VirtualFile existingVirtualFile;
    
    @Mock
    private FileEditorManager fileEditorManager;

    private Application application;

    private CreateFileTool createFileTool;
    
    private static final String TEST_FILE_PATH = "src/test/NewFile.java";
    private static final String TEST_FILE_CONTENT = "package com.test;\n\npublic class NewFile { }";

    @BeforeEach
    void setUp() throws IOException {
        MockitoAnnotations.openMocks(this);
        
        // Setup ApplicationManager
        mockStatic(ApplicationManager.class);
        when(ApplicationManager.getApplication()).thenReturn(application);
        doNothing().when(application).invokeLater(any(Runnable.class));
        
        // Setup WriteCommandAction
        mockStatic(WriteCommandAction.class);
        when(WriteCommandAction.runWriteCommandAction((Project) eq(project), (Computable<Object>) any())).thenAnswer(invocation -> {
            return "File created successfully: " + TEST_FILE_PATH;
        });
        
        // Setup LocalFileSystem
        mockStatic(LocalFileSystem.class);
        when(LocalFileSystem.getInstance()).thenReturn(localFileSystem);
        
        // Setup project base path
        when(project.getBasePath()).thenReturn("/test/project");
        
        // Setup parent directory
        File parentDir = new File("/test/project/src/test");
        when(localFileSystem.refreshAndFindFileByPath(parentDir.getAbsolutePath())).thenReturn(parentVirtualFile);
        
        // Setup new file creation
        when(parentVirtualFile.createChildData(any(), eq("NewFile.java"))).thenReturn(newVirtualFile);
        doNothing().when(newVirtualFile).setBinaryContent(any(byte[].class));
        
        // Setup existing file
        when(parentVirtualFile.findChild("ExistingFile.java")).thenReturn(existingVirtualFile);
        doNothing().when(existingVirtualFile).setBinaryContent(any(byte[].class));
        
        // Setup FileEditorManager
        mockStatic(FileEditorManager.class);
        when(FileEditorManager.getInstance(project)).thenReturn(fileEditorManager);
        
        createFileTool = new CreateFileTool(project);
    }

    @Test
    void testDoExecuteCreatesNewFile() throws Exception {
        JsonObject params = new JsonObject();
        params.addProperty("filePath", TEST_FILE_PATH);
        params.addProperty("content", TEST_FILE_CONTENT);
        
        String result = createFileTool.doExecute(params);
        
        assertTrue(result.contains("File created successfully"));
    }
    
    @Test
    void testDoExecuteWithMissingPath() throws Exception {
        JsonObject params = new JsonObject();
        params.addProperty("content", TEST_FILE_CONTENT);
        
        String result = createFileTool.doExecute(params);
        
        assertEquals("Error: File path is required", result);
    }
    
    @Test
    void testDoExecuteWithMissingContent() throws Exception {
        JsonObject params = new JsonObject();
        params.addProperty("filePath", TEST_FILE_PATH);
        
        String result = createFileTool.doExecute(params);
        
        assertEquals("Error: File content is required", result);
    }
    
    @Test
    void testDoExecuteUpdatesExistingFile() throws Exception {
        // Setup WriteCommandAction to return "updated" message
        when(WriteCommandAction.runWriteCommandAction(eq(project), (Computable< Object>)any())).thenAnswer(invocation -> {
            return "File updated successfully: src/test/ExistingFile.java";
        });
        
        JsonObject params = new JsonObject();
        params.addProperty("filePath", "src/test/ExistingFile.java");
        params.addProperty("content", "Updated content");
        
        String result = createFileTool.doExecute(params);
        
        assertTrue(result.contains("File updated successfully"));
    }
    
    @Test
    void testGetExampleParams() {
        JsonObject params = createFileTool.getExampleParams();
        
        assertNotNull(params);
        assertTrue(params.has("filePath"));
        assertTrue(params.has("content"));
        assertEquals("path/to/file.java", params.get("filePath").getAsString());
        assertEquals("file content here", params.get("content").getAsString());
    }
}