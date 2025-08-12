package com.zps.zest.codehealth.testplan

import com.intellij.openapi.components.Service
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileListener
import com.intellij.openapi.vfs.VirtualFileSystem
import com.zps.zest.codehealth.testplan.models.TestPlanData

/**
 * Virtual file system for test plans
 */
@Service
class TestPlanVirtualFileSystem : VirtualFileSystem() {
    
    companion object {
        const val PROTOCOL = "testplan"
        
        fun getInstance(): TestPlanVirtualFileSystem {
            return com.intellij.openapi.application.ApplicationManager.getApplication()
                .getService(TestPlanVirtualFileSystem::class.java)
        }
        
        /**
         * Create a virtual file for a test plan
         */
        fun createTestPlanFile(methodFqn: String): TestPlanVirtualFile {
            return TestPlanVirtualFile(methodFqn)
        }
        
        /**
         * Create a virtual file for test plan overview
         */
        fun createTestPlanOverviewFile(): TestPlanOverviewVirtualFile {
            return TestPlanOverviewVirtualFile()
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
        throw UnsupportedOperationException("Cannot delete virtual test plan files")
    }
    
    override fun moveFile(requestor: Any?, file: VirtualFile, newParent: VirtualFile) {
        throw UnsupportedOperationException("Cannot move virtual test plan files")
    }
    
    override fun renameFile(requestor: Any?, file: VirtualFile, newName: String) {
        throw UnsupportedOperationException("Cannot rename virtual test plan files")
    }
    
    override fun createChildFile(requestor: Any?, parent: VirtualFile, fileName: String): VirtualFile {
        throw UnsupportedOperationException("Cannot create child files in virtual test plan filesystem")
    }
    
    override fun createChildDirectory(requestor: Any?, parent: VirtualFile, dirName: String): VirtualFile {
        throw UnsupportedOperationException("Cannot create directories in virtual test plan filesystem")
    }
    
    override fun copyFile(
        requestor: Any?,
        file: VirtualFile,
        newParent: VirtualFile,
        copyName: String
    ): VirtualFile {
        throw UnsupportedOperationException("Cannot copy virtual test plan files")
    }
    
    override fun isReadOnly(): Boolean = false // Test plans can be edited
}