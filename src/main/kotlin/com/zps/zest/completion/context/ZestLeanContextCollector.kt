package com.zps.zest.completion.context

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project

/**
 * Lean context collector that captures full file content with cursor position
 * for reasoning-based completions
 */
class ZestLeanContextCollector(private val project: Project) {
    
    data class LeanContext(
        val fileName: String,
        val language: String,
        val fullContent: String,
        val markedContent: String, // Content with [CURSOR] marker
        val cursorOffset: Int,
        val cursorLine: Int,
        val contextType: CursorContextType
    )
    
    enum class CursorContextType {
        METHOD_BODY,
        CLASS_DECLARATION, 
        IMPORT_SECTION,
        VARIABLE_ASSIGNMENT,
        AFTER_OPENING_BRACE,
        UNKNOWN
    }
    
    fun collectFullFileContext(editor: Editor, offset: Int): LeanContext {
        val document = editor.document
        val text = document.text
        val virtualFile = FileDocumentManager.getInstance().getFile(document)
        
        // Insert cursor marker
        val markedContent = text.substring(0, offset) + "[CURSOR]" + text.substring(offset)
        
        // Calculate cursor line
        val cursorLine = document.getLineNumber(offset)
        
        // Detect context type
        val contextType = detectCursorContext(text, offset)
        
        return LeanContext(
            fileName = virtualFile?.name ?: "unknown",
            language = virtualFile?.fileType?.name ?: "text",
            fullContent = text,
            markedContent = markedContent,
            cursorOffset = offset,
            cursorLine = cursorLine,
            contextType = contextType
        )
    }
    
    private fun detectCursorContext(text: String, offset: Int): CursorContextType {
        val beforeCursor = text.substring(0, offset)
        val lines = beforeCursor.lines()
        val currentLine = lines.lastOrNull() ?: ""
        val previousLines = lines.takeLast(10) // Look at more context
        
        return when {
            // Check for import section
            currentLine.trim().startsWith("import") || 
            previousLines.any { it.trim().startsWith("import") } &&
            !hasSignificantCodeAfterImports(beforeCursor) -> 
                CursorContextType.IMPORT_SECTION
            
            // Check for method body (inside braces after method signature)
            isInsideMethodBody(beforeCursor) -> 
                CursorContextType.METHOD_BODY
            
            // Check for class level (not inside any method)
            isAtClassLevel(beforeCursor) -> 
                CursorContextType.CLASS_DECLARATION
            
            // Check for variable assignment
            currentLine.contains("=") && !currentLine.contains("==") && 
            !currentLine.trim().startsWith("//") -> 
                CursorContextType.VARIABLE_ASSIGNMENT
            
            // Check for after opening brace
            currentLine.trim().endsWith("{") || 
            previousLines.lastOrNull()?.trim()?.endsWith("{") == true -> 
                CursorContextType.AFTER_OPENING_BRACE
            
            else -> CursorContextType.UNKNOWN
        }
    }
    
    private fun isInsideMethodBody(beforeCursor: String): Boolean {
        var braceCount = 0
        var inMethod = false
        
        val lines = beforeCursor.lines()
        
        for (line in lines) {
            val trimmed = line.trim()
            
            // Look for method signature
            if (trimmed.matches(Regex(".*\\b(public|private|protected|static|final)\\s+.*\\s+\\w+\\s*\\(.*\\).*\\{?"))) {
                inMethod = true
            }
            
            // Count braces
            braceCount += trimmed.count { it == '{' }
            braceCount -= trimmed.count { it == '}' }
            
            // If we exit all braces, we're not in a method anymore
            if (braceCount == 0 && inMethod) {
                inMethod = false
            }
        }
        
        return inMethod && braceCount > 0
    }
    
    private fun isAtClassLevel(beforeCursor: String): Boolean {
        var braceCount = 0
        var inClass = false
        
        val lines = beforeCursor.lines()
        
        for (line in lines) {
            val trimmed = line.trim()
            
            // Look for class declaration
            if (trimmed.matches(Regex(".*\\b(class|interface|enum)\\s+\\w+.*\\{?"))) {
                inClass = true
            }
            
            // Count braces
            braceCount += trimmed.count { it == '{' }
            braceCount -= trimmed.count { it == '}' }
        }
        
        // At class level if we're inside class but not deeply nested
        return inClass && braceCount == 1
    }
    
    private fun hasSignificantCodeAfterImports(beforeCursor: String): Boolean {
        val lines = beforeCursor.lines()
        
        // Find last import line
        var lastImportIndex = -1
        for (i in lines.indices) {
            if (lines[i].trim().startsWith("import")) {
                lastImportIndex = i
            }
        }
        
        if (lastImportIndex == -1) return true
        
        // Check if there's significant code after imports
        val afterImports = lines.drop(lastImportIndex + 1)
        return afterImports.any { line ->
            val trimmed = line.trim()
            trimmed.isNotEmpty() && 
            !trimmed.startsWith("//") && 
            !trimmed.startsWith("/*") && 
            !trimmed.startsWith("*") &&
            !trimmed.startsWith("package")
        }
    }
}
