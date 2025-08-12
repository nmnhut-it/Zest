package com.zps.zest.browser.editor

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileListener
import com.intellij.openapi.vfs.VirtualFileSystem
import com.intellij.testFramework.LightVirtualFile
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Virtual file system for Zest Chat editor tabs.
 * Creates virtual files that represent chat sessions for opening in editor tabs.
 */
class ZestChatVirtualFileSystem : VirtualFileSystem() {
    
    companion object {
        private const val PROTOCOL = "zest-chat"
        private val instance = ZestChatVirtualFileSystem()
        
        fun getInstance(): ZestChatVirtualFileSystem = instance
        
        /**
         * Creates a virtual file for a chat session
         */
        fun createChatFile(sessionId: String = "main"): VirtualFile {
            return ZestChatVirtualFile(sessionId)
        }
    }
    
    override fun getProtocol(): String = PROTOCOL
    
    override fun findFileByPath(path: String): VirtualFile? {
        return if (path.startsWith("$PROTOCOL://")) {
            val sessionId = path.removePrefix("$PROTOCOL://").removeSuffix(".zest-chat")
            ZestChatVirtualFile(sessionId)
        } else null
    }
    
    override fun refresh(asynchronous: Boolean) {
        // No-op for virtual file system
    }
    
    override fun refreshAndFindFileByPath(path: String): VirtualFile? {
        return findFileByPath(path)
    }
    
    override fun addVirtualFileListener(listener: VirtualFileListener) {
        // No-op for virtual file system
    }
    
    override fun removeVirtualFileListener(listener: VirtualFileListener) {
        // No-op for virtual file system
    }
    
    override fun deleteFile(requestor: Any?, file: VirtualFile) {
        // No-op for virtual file system - files cannot be deleted
    }

    override fun moveFile(
        p0: Any?,
        p1: VirtualFile,
        p2: VirtualFile
    ) {
        throw UnsupportedOperationException("Move not supported for chat virtual files")
    }

    override fun renameFile(p0: Any?, p1: VirtualFile, p2: String) {
        throw UnsupportedOperationException("Rename not supported for chat virtual files")
    }

    override fun createChildFile(
        p0: Any?,
        p1: VirtualFile,
        p2: String
    ): VirtualFile {
        throw UnsupportedOperationException("Create child file not supported for chat virtual files")
    }

    override fun createChildDirectory(
        p0: Any?,
        p1: VirtualFile,
        p2: String
    ): VirtualFile {
        throw UnsupportedOperationException("Create child directory not supported for chat virtual files")
    }

    override fun copyFile(
        p0: Any?,
        p1: VirtualFile,
        p2: VirtualFile,
        p3: String
    ): VirtualFile {
        throw UnsupportedOperationException("Copy not supported for chat virtual files")
    }

    override fun isReadOnly(): Boolean {
        return true // Chat virtual files are read-only
    }

    /**
     * Virtual file representing a Zest Chat session
     */
    class ZestChatVirtualFile(
        private val sessionId: String
    ) : LightVirtualFile() {
        
        override fun getName(): String = "ZPS Chat - $sessionId.zest-chat"
        
        override fun getPath(): String = "$PROTOCOL://$sessionId.zest-chat"
        
        override fun getFileSystem(): VirtualFileSystem = getInstance()
        
        override fun isWritable(): Boolean = false
        
        override fun isDirectory(): Boolean = false
        
        override fun isValid(): Boolean = true
        
        override fun getParent(): VirtualFile? = null
        
        override fun getChildren(): Array<VirtualFile> = emptyArray()
        
        override fun getOutputStream(requestor: Any?, newModificationStamp: Long, newTimeStamp: Long): OutputStream {
            throw IOException("ZestChatVirtualFile is not writable")
        }
        
        override fun contentsToByteArray(): ByteArray = ByteArray(0)
        
        override fun getInputStream(): InputStream {
            return ByteArray(0).inputStream()
        }
        
        override fun getLength(): Long = 0L
        
        override fun getTimeStamp(): Long = System.currentTimeMillis()
        
        override fun getModificationStamp(): Long = 0L
        
        override fun refresh(asynchronous: Boolean, recursive: Boolean, postRunnable: Runnable?) {
            postRunnable?.run()
        }
        
        /**
         * Gets the session ID for this chat file
         */
        fun getSessionId(): String = sessionId
        
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ZestChatVirtualFile) return false
            return sessionId == other.sessionId
        }
        
        override fun hashCode(): Int = sessionId.hashCode()
    }
}