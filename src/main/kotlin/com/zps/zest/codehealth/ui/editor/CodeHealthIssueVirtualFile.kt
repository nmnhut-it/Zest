package com.zps.zest.codehealth.ui.editor

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileSystem
import com.zps.zest.codehealth.CodeHealthAnalyzer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Virtual file representing a Code Health issue for editor display
 */
class CodeHealthIssueVirtualFile(
    private val issue: CodeHealthAnalyzer.HealthIssue,
    private val methodFqn: String
) : VirtualFile() {
    
    private val fileName = "CodeHealthIssue_${extractMethodName(methodFqn)}.zest"
    private val path = "codehealth://issues/$fileName"
    
    override fun getName(): String = fileName
    
    override fun getPath(): String = path
    
    override fun getFileSystem(): VirtualFileSystem = CodeHealthVirtualFileSystem.getInstance()
    
    override fun isWritable(): Boolean = false
    
    override fun isDirectory(): Boolean = false
    
    override fun isValid(): Boolean = true
    
    override fun getParent(): VirtualFile? = null
    
    override fun getChildren(): Array<VirtualFile>? = null
    
    override fun getOutputStream(requestor: Any?, newModificationStamp: Long, newTimeStamp: Long): OutputStream {
        throw UnsupportedOperationException("Code Health issues are read-only")
    }
    
    override fun contentsToByteArray(): ByteArray {
        return generateMarkdownContent().toByteArray(Charsets.UTF_8)
    }
    
    override fun getTimeStamp(): Long = System.currentTimeMillis()
    
    override fun getLength(): Long = contentsToByteArray().size.toLong()
    
    override fun refresh(asynchronous: Boolean, recursive: Boolean, postRunnable: Runnable?) {
        postRunnable?.run()
    }
    
    override fun getInputStream(): InputStream {
        return ByteArrayInputStream(contentsToByteArray())
    }
    
    /**
     * Get the health issue data
     */
    fun getHealthIssue(): CodeHealthAnalyzer.HealthIssue = issue
    
    /**
     * Get the method FQN
     */
    fun getMethodFqn(): String = methodFqn
    
    private fun generateMarkdownContent(): String {
        return buildString {
            appendLine("# Code Health Issue: ${issue.title}")
            appendLine()
            appendLine("**Method:** `$methodFqn`")
            appendLine("**Severity:** ${issue.severity}/5")
            appendLine("**Category:** ${issue.issueCategory}")
            appendLine("**Verified:** ${if (issue.verified) "✅ Yes" else "❌ No"}")
            appendLine("**False Positive:** ${if (issue.falsePositive) "⚠️ Yes" else "✅ No"}")
            appendLine()
            appendLine("## Description")
            appendLine(issue.description)
            appendLine()
            appendLine("## Impact")
            appendLine(issue.impact)
            appendLine()
            appendLine("## Suggested Fix")
            appendLine(issue.suggestedFix)
        }
    }
    
    private fun extractMethodName(fqn: String): String {
        return if (fqn.contains(":")) {
            // JS/TS file with line numbers
            val colonIndex = fqn.lastIndexOf(":")
            val filePath = fqn.substring(0, colonIndex)
            val fileName = filePath.substringAfterLast("/").substringAfterLast("\\")
            val lineInfo = fqn.substring(colonIndex)
            fileName.substringBeforeLast(".") + lineInfo
        } else {
            // Java method FQN
            fqn.substringAfterLast(".")
        }
    }
}