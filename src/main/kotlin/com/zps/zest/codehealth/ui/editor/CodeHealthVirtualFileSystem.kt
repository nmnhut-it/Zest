package com.zps.zest.codehealth.ui.editor

import com.intellij.openapi.components.Service
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileListener
import com.intellij.openapi.vfs.VirtualFileSystem
import com.zps.zest.codehealth.CodeHealthAnalyzer

/**
 * Virtual file system for Code Health issues and test plans
 */
@Service
class CodeHealthVirtualFileSystem : VirtualFileSystem() {
    
    companion object {
        const val PROTOCOL = "codehealth"
        
        fun getInstance(): CodeHealthVirtualFileSystem {
            return com.intellij.openapi.application.ApplicationManager.getApplication()
                .getService(CodeHealthVirtualFileSystem::class.java)
        }
        
        /**
         * Create a virtual file for a Code Health issue
         */
        fun createIssueFile(
            issue: CodeHealthAnalyzer.HealthIssue,
            methodFqn: String
        ): CodeHealthIssueVirtualFile {
            return CodeHealthIssueVirtualFile(issue, methodFqn)
        }
        
        /**
         * Create a virtual file for Code Health overview
         */
        fun createOverviewFile(): CodeHealthOverviewVirtualFile {
            return CodeHealthOverviewVirtualFile()
        }
    }
    
    override fun getProtocol(): String = PROTOCOL
    
    override fun findFileByPath(path: String): VirtualFile? {
        // Virtual files are created on demand, not stored
        return null
    }
    
    override fun refresh(asynchronous: Boolean) {
        // No-op for virtual files
    }
    
    override fun refreshAndFindFileByPath(path: String): VirtualFile? {
        return findFileByPath(path)
    }
    
    override fun addVirtualFileListener(listener: VirtualFileListener) {
        // No-op for virtual files
    }
    
    override fun removeVirtualFileListener(listener: VirtualFileListener) {
        // No-op for virtual files  
    }
    
    override fun deleteFile(requestor: Any?, file: VirtualFile) {
        throw UnsupportedOperationException("Cannot delete virtual Code Health files")
    }
    
    override fun moveFile(requestor: Any?, file: VirtualFile, newParent: VirtualFile) {
        throw UnsupportedOperationException("Cannot move virtual Code Health files")
    }
    
    override fun renameFile(requestor: Any?, file: VirtualFile, newName: String) {
        throw UnsupportedOperationException("Cannot rename virtual Code Health files")
    }
    
    override fun createChildFile(requestor: Any?, parent: VirtualFile, fileName: String): VirtualFile {
        throw UnsupportedOperationException("Cannot create child files in virtual Code Health filesystem")
    }
    
    override fun createChildDirectory(requestor: Any?, parent: VirtualFile, dirName: String): VirtualFile {
        throw UnsupportedOperationException("Cannot create directories in virtual Code Health filesystem")
    }
    
    override fun copyFile(
        requestor: Any?,
        file: VirtualFile,
        newParent: VirtualFile,
        copyName: String
    ): VirtualFile {
        throw UnsupportedOperationException("Cannot copy virtual Code Health files")
    }
    
    override fun isReadOnly(): Boolean = true
}