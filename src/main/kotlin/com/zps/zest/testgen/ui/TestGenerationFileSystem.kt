package com.zps.zest.testgen.ui

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileListener
import com.intellij.openapi.vfs.VirtualFileSystem

class TestGenerationFileSystem private constructor() : VirtualFileSystem() {

    companion object {
        val INSTANCE = TestGenerationFileSystem()
        const val PROTOCOL = "testgen"
    }

    private val files = mutableMapOf<String, TestGenerationVirtualFile>()

    override fun getProtocol(): String = PROTOCOL

    override fun findFileByPath(path: String): VirtualFile? {
        return synchronized(files) {
            files[path]
        }
    }

    override fun refresh(asynchronous: Boolean) {
        // No-op for virtual file system
    }

    override fun refreshAndFindFileByPath(path: String): VirtualFile? {
        return findFileByPath(path)
    }

    override fun addVirtualFileListener(listener: VirtualFileListener) {
        // No-op - we don't track listeners
    }

    override fun removeVirtualFileListener(listener: VirtualFileListener) {
        // No-op
    }

    override fun deleteFile(requestor: Any?, vFile: VirtualFile) {
        if (vFile is TestGenerationVirtualFile) {
            synchronized(files) {
                files.remove(vFile.path)
            }
        }
    }

    override fun moveFile(requestor: Any?, vFile: VirtualFile, newParent: VirtualFile) {
        throw UnsupportedOperationException("Cannot move test generation files")
    }

    override fun renameFile(requestor: Any?, vFile: VirtualFile, newName: String) {
        throw UnsupportedOperationException("Cannot rename test generation files")
    }

    override fun createChildFile(requestor: Any?, vDir: VirtualFile, fileName: String): VirtualFile {
        throw UnsupportedOperationException("Cannot create child files in test generation file system")
    }

    override fun createChildDirectory(requestor: Any?, vDir: VirtualFile, dirName: String): VirtualFile {
        throw UnsupportedOperationException("Cannot create directories in test generation file system")
    }

    override fun copyFile(requestor: Any?, virtualFile: VirtualFile, newParent: VirtualFile, copyName: String): VirtualFile {
        throw UnsupportedOperationException("Cannot copy test generation files")
    }

    override fun isReadOnly(): Boolean = true

    fun registerFile(file: TestGenerationVirtualFile) {
        synchronized(files) {
            files[file.path] = file
        }
    }

    fun unregisterFile(file: TestGenerationVirtualFile) {
        synchronized(files) {
            files.remove(file.path)
        }
    }
}