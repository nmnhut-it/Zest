package com.zps.zest.testgen.ui

import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypes
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileSystem
import com.intellij.psi.PsiFile
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/**
 * Virtual file for test generation sessions
 */
class TestGenerationVirtualFile(
    val targetFile: PsiFile,
    val selectionStart: Int,
    val selectionEnd: Int,
    private val sessionId: String
) : VirtualFile() {
    
    private val fileName = "TestGeneration_${targetFile.name}_${sessionId.take(8)}.testgen"
    
    override fun getName(): String = fileName
    
    override fun getFileSystem(): VirtualFileSystem = TestGenerationVirtualFileSystem.INSTANCE
    
    override fun getPath(): String = "testgen://$fileName"
    
    override fun isWritable(): Boolean = false
    
    override fun isDirectory(): Boolean = false
    
    override fun isValid(): Boolean = true
    
    override fun getParent(): VirtualFile? = null
    
    override fun getChildren(): Array<VirtualFile> = emptyArray()
    
    override fun getFileType(): FileType = FileTypes.UNKNOWN
    
    override fun getOutputStream(requestor: Any?, newModificationStamp: Long, newTimeStamp: Long): OutputStream {
        throw UnsupportedOperationException("TestGeneration virtual files are read-only")
    }
    
    override fun contentsToByteArray(): ByteArray {
        val content = buildString {
            append("Test Generation Session\n")
            append("=======================\n\n")
            append("Target File: ${targetFile.name}\n")
            append("Selection: $selectionStart-$selectionEnd\n")
            append("Session ID: $sessionId\n")
        }
        return content.toByteArray(StandardCharsets.UTF_8)
    }
    
    override fun getTimeStamp(): Long = System.currentTimeMillis()
    
    override fun getLength(): Long = contentsToByteArray().size.toLong()
    
    override fun refresh(asynchronous: Boolean, recursive: Boolean, postRunnable: Runnable?) {
        postRunnable?.run()
    }
    
    override fun getInputStream(): InputStream {
        return contentsToByteArray().inputStream()
    }
    
    override fun getModificationStamp(): Long = timeStamp
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TestGenerationVirtualFile) return false
        return sessionId == other.sessionId && targetFile == other.targetFile
    }
    
    override fun hashCode(): Int {
        return sessionId.hashCode() * 31 + targetFile.hashCode()
    }
    
    override fun toString(): String = "TestGenerationVirtualFile($fileName)"
}