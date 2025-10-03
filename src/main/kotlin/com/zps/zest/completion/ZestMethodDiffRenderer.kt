package com.zps.zest.completion

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.zps.zest.completion.diff.MethodRewriteDiffDialogV2
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Simple replacement for deleted ZestMethodContextCollector.MethodContext
 */
data class MethodContext(
    val methodName: String,
    val methodStartOffset: Int,
    val methodEndOffset: Int,
    val methodContent: String,
    val language: String,
    val fileName: String = "",
    val isCocos2dx: Boolean = false,
    val relatedClasses: Map<String, String> = emptyMap(), // Changed from List to Map
    val methodSignature: String = methodName,
    val containingClass: String? = null,
    val cocosFrameworkVersion: String = "",
    val cocosContextType: String = "",
    val classContext: String = "",
    val surroundingMethods: List<SurroundingMethod> = emptyList(),
    val cocosCompletionHints: List<String> = emptyList()
)

// Enums and data classes from deleted collectors
enum class MethodPosition { BEFORE, AFTER, INSIDE }
enum class Cocos2dxContextType { SCENE, LAYER, SPRITE, ACTION, UNKNOWN }

data class SurroundingMethod(
    val position: MethodPosition,
    val signature: String
)

/**
 * New simplified method diff renderer with cleaner UX:
 * 1. Select method (highlight it)
 * 2. Hide original method and show processing indicator
 * 3. Replace with prominent diff display as main content
 * 4. User reviews diff in-place where method was
 * 5. Clean transitions between states
 */
class ZestMethodDiffRenderer {
    private val logger = Logger.getInstance(ZestMethodDiffRenderer::class.java)
    
    companion object {
        private const val DEBUG = true
        
        private fun debugLog(message: String) {
            if (DEBUG) {
                println("[ZestMethodDiff] $message")
            }
        }
    }
    
    // State management
    private val isActive = AtomicBoolean(false)
    
    /**
     * Start the method rewrite flow by directly showing the IntelliJ diff dialog
     */
    fun startMethodRewrite(
        editor: Editor,
        methodContext: MethodContext,
        originalContent: String,
        rewrittenContent: String,
         onAccept: () -> Unit,
        onReject: () -> Unit
    ) {
        ApplicationManager.getApplication().assertIsDispatchThread()
        
        if (!isActive.compareAndSet(false, true)) {
            logger.warn("Method diff already active, hiding previous")
            hide()
        }
        
        try {
            debugLog("Starting method rewrite for ${methodContext.methodName}")
            
            if (editor.isDisposed) {
                logger.warn("Editor is disposed, cannot start method rewrite")
                return
            }
            
            // Get file type for syntax highlighting
            val virtualFile = editor.virtualFile
            val fileType = if (virtualFile != null) {
                virtualFile.fileType
            } else {
                // Fallback: try to determine from language name or extension
                val extension = when (methodContext.language.lowercase()) {
                    "java" -> "java"
                    "kotlin" -> "kt"
                    "javascript", "js" -> "js"
                    "typescript", "ts" -> "ts"
                    "python" -> "py"
                    "go" -> "go"
                    "rust" -> "rs"
                    "cpp", "c++" -> "cpp"
                    "c" -> "c"
                    "csharp", "c#" -> "cs"
                    "ruby" -> "rb"
                    "php" -> "php"
                    "swift" -> "swift"
                    "objectivec", "objc" -> "m"
                    else -> methodContext.language
                }
                com.intellij.openapi.fileTypes.FileTypeManager.getInstance()
                    .getFileTypeByExtension(extension)
            }
            
            // Directly show the IntelliJ diff dialog
            com.zps.zest.completion.diff.MethodRewriteDiffDialogV2.show(
                project = editor.project ?: return,
                editor = editor,
                methodStartOffset = methodContext.methodStartOffset,
                methodEndOffset = methodContext.methodEndOffset,
                originalContent = originalContent,
                modifiedContent = rewrittenContent,
                fileType = fileType,
                methodName = methodContext.methodName,
                onAccept = {
                    onAccept()
                    hide()
                },
                onReject = {
                    onReject()
                    hide()
                }
            )
            
        } catch (e: Exception) {
            logger.error("Failed to start method rewrite", e)
            hide()
        }
    }
    
    
    /**
     * Hide and cleanup
     */
    fun hide() {
        if (!isActive.compareAndSet(true, false)) {
            return // Already hidden
        }
        
        debugLog("Method diff hidden and cleaned up")
    }
    
    /**
     * Check if active
     */
    fun isActive(): Boolean = isActive.get()
    
    
}
