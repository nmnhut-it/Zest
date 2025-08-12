package com.zps.zest.codehealth.ui.editor

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileSystem
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Virtual file representing Code Health overview/dashboard
 */
class CodeHealthOverviewVirtualFile : VirtualFile() {
    
    private val fileName = "CodeHealthOverview.zest"
    private val path = "codehealth://overview/$fileName"
    
    override fun getName(): String = fileName
    
    override fun getPath(): String = path
    
    override fun getFileSystem(): VirtualFileSystem = CodeHealthVirtualFileSystem.getInstance()
    
    override fun isWritable(): Boolean = false
    
    override fun isDirectory(): Boolean = false
    
    override fun isValid(): Boolean = true
    
    override fun getParent(): VirtualFile? = null
    
    override fun getChildren(): Array<VirtualFile>? = null
    
    override fun getOutputStream(requestor: Any?, newModificationStamp: Long, newTimeStamp: Long): OutputStream {
        throw UnsupportedOperationException("Code Health overview is read-only")
    }
    
    override fun contentsToByteArray(): ByteArray {
        return generateOverviewContent().toByteArray(Charsets.UTF_8)
    }
    
    override fun getTimeStamp(): Long = System.currentTimeMillis()
    
    override fun getLength(): Long = contentsToByteArray().size.toLong()
    
    override fun refresh(asynchronous: Boolean, recursive: Boolean, postRunnable: Runnable?) {
        postRunnable?.run()
    }
    
    override fun getInputStream(): InputStream {
        return ByteArrayInputStream(contentsToByteArray())
    }
    
    private fun generateOverviewContent(): String {
        return buildString {
            appendLine("# Code Health Overview")
            appendLine()
            appendLine("**Generated:** ${java.time.LocalDateTime.now()}")
            appendLine()
            appendLine("## Summary")
            appendLine("This is the project-wide Code Health dashboard.")
            appendLine("Detailed metrics and trends will be displayed in the custom editor.")
        }
    }
}