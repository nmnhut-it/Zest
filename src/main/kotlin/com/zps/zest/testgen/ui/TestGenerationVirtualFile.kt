package com.zps.zest.testgen.ui

import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypes
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileListener
import com.intellij.openapi.vfs.VirtualFileSystem
import com.zps.zest.testgen.model.TestGenerationRequest
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlin.streams.toList

/**
 * Virtual file for test generation sessions.
 * Each file has a unique session ID to allow concurrent test generation.
 */
class TestGenerationVirtualFile(
    private val name: String,
    val request: TestGenerationRequest? = null
) : VirtualFile() {

    val sessionId: String = UUID.randomUUID().toString()

    constructor(request: TestGenerationRequest) : this(
        "TestGen_${request.targetFile.name}_${UUID.randomUUID().toString().take(8)}.testgen",
        request
    )

    init {
        TestGenerationFileSystem.INSTANCE.registerFile(this)
    }

    override fun getName(): String = name

    override fun getFileSystem(): VirtualFileSystem = TestGenerationFileSystem.INSTANCE

    override fun getPath(): String = "${TestGenerationFileSystem.PROTOCOL}://$sessionId/$name"
    
    override fun isWritable(): Boolean = false
    
    override fun isDirectory(): Boolean = false
    
    override fun isValid(): Boolean = true
    
    override fun getParent(): VirtualFile? = null
    
    override fun getChildren(): Array<VirtualFile> = emptyArray()
    
    override fun getFileType(): FileType = TestGenerationFileType.INSTANCE
    
    override fun getOutputStream(requestor: Any?, newModificationStamp: Long, newTimeStamp: Long): OutputStream {
        throw UnsupportedOperationException("TestGeneration virtual files are read-only")
    }
    
    override fun contentsToByteArray(): ByteArray {
        val content = buildString {
            append("Test Generation Session\n")
            append("=======================\n\n")
            
            request?.let { req ->
                append("Target File: ${req.targetFile.name}\n")
                append("Target Methods: ${req.targetMethods.stream().map { v->v.name }.toList() ?: "All methods"}\n")
                if (req.getTargetMethods().isNotEmpty()) {
                    append("Methods: ${req.getTargetMethods().size} selected\n")
                }
                append("Test Type: ${req.testType.description}\n")
                if (req.hasSelection()) {
                    append("Has Selection: Yes\n")
                }
            } ?: append("No request data available\n")
            
            append("\nSession ID: $sessionId\n")
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
        return sessionId == other.sessionId
    }
    
    override fun hashCode(): Int {
        return sessionId.hashCode()
    }
    
    override fun toString(): String = "TestGenerationVirtualFile($name)"
}

/**
 * Custom file type for test generation sessions.
 */
class TestGenerationFileType : FileType {
    companion object {
        val INSTANCE = TestGenerationFileType()
    }
    
    override fun getName(): String = "TestGeneration"
    
    override fun getDescription(): String = "Test Generation Session"
    
    override fun getDefaultExtension(): String = "testgen"
    
    override fun getIcon(): javax.swing.Icon? = null
    
    override fun isBinary(): Boolean = false
    
    override fun isReadOnly(): Boolean = true
}