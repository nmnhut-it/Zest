package com.zps.zest.testgen.ui

import com.intellij.openapi.vfs.*
import com.intellij.psi.PsiFile
import java.util.concurrent.ConcurrentHashMap

/**
 * Virtual file system for test generation sessions
 */
class TestGenerationVirtualFileSystem : VirtualFileSystem() {
    
    private val listeners = mutableListOf<VirtualFileListener>()
    
    companion object {
        @JvmField
        val INSTANCE = TestGenerationVirtualFileSystem()
        private const val PROTOCOL = "testgen"
    }
    
    private val files = ConcurrentHashMap<String, TestGenerationVirtualFile>()
    
    override fun getProtocol(): String = PROTOCOL
    
    override fun findFileByPath(path: String): VirtualFile? {
        return files[path]
    }
    
    override fun refresh(asynchronous: Boolean) {
        // No-op for virtual file system
    }
    
    override fun refreshAndFindFileByPath(path: String): VirtualFile? {
        return findFileByPath(path)
    }
    
    override fun addVirtualFileListener(listener: VirtualFileListener) {
        listeners.add(listener)
    }
    
    override fun removeVirtualFileListener(listener: VirtualFileListener) {
        listeners.remove(listener)
    }
    
    /**
     * Create a test generation session file
     */
    fun createSessionFile(targetFile: PsiFile, selectionStart: Int, selectionEnd: Int, sessionId: String): TestGenerationVirtualFile {
        val virtualFile = TestGenerationVirtualFile(targetFile, selectionStart, selectionEnd, sessionId)
        files[virtualFile.path] = virtualFile
        
        // Notify VFS listeners
        VirtualFileManager.getInstance().notifyPropertyChanged(
            virtualFile,
            VirtualFile.PROP_NAME,
            null,
            virtualFile.name
        )
        
        return virtualFile
    }
    
    /**
     * Remove a session file
     */
    fun removeSessionFile(path: String) {
        val file = files.remove(path)
        if (file != null) {
            // Notify listeners about file deletion
            val event = VirtualFileEvent(null, file, file.parent, file.modificationStamp, System.currentTimeMillis())
            listeners.forEach { it.fileDeleted(event) }
        }
    }
    
    override fun deleteFile(requestor: Any?, vFile: VirtualFile) {
        if (vFile is TestGenerationVirtualFile) {
            files.remove(vFile.path)
            val event = VirtualFileEvent(requestor, vFile, vFile.parent, vFile.modificationStamp, System.currentTimeMillis())
            listeners.forEach { it.fileDeleted(event) }
        }
    }
    
    override fun moveFile(requestor: Any?, vFile: VirtualFile, newParent: VirtualFile) {
        throw UnsupportedOperationException("Move not supported in test generation VFS")
    }
    
    override fun renameFile(requestor: Any?, vFile: VirtualFile, newName: String) {
        throw UnsupportedOperationException("Rename not supported in test generation VFS")
    }
    
    override fun createChildFile(requestor: Any?, vDir: VirtualFile, fileName: String): VirtualFile {
        throw UnsupportedOperationException("Create child file not supported in test generation VFS")
    }
    
    override fun createChildDirectory(requestor: Any?, vDir: VirtualFile, dirName: String): VirtualFile {
        throw UnsupportedOperationException("Create child directory not supported in test generation VFS")
    }
    
    override fun copyFile(requestor: Any?, virtualFile: VirtualFile, newParent: VirtualFile, copyName: String): VirtualFile {
        throw UnsupportedOperationException("Copy not supported in test generation VFS")
    }
    
    override fun isReadOnly(): Boolean = false
    
    /**
     * Get all active session files
     */
    fun getActiveSessionFiles(): Collection<TestGenerationVirtualFile> {
        return files.values
    }
    
    /**
     * Cleanup old session files
     */
    fun cleanup() {
        // Remove files older than 24 hours
        val cutoffTime = System.currentTimeMillis() - 24 * 60 * 60 * 1000
        val toRemove = files.entries.filter { it.value.timeStamp < cutoffTime }
        
        for ((path, file) in toRemove) {
            files.remove(path)
            // Notify listeners about file deletion
            val event = VirtualFileEvent(null, file, file.parent, file.modificationStamp, System.currentTimeMillis())
            listeners.forEach { it.fileDeleted(event) }
        }
    }
}