package com.zps.zest.completion.context

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project

/**
 * Lean context collector that forwards to the PSI-based implementation.
 * This class maintains API compatibility while using the more accurate PSI-based parsing.
 * 
 * @deprecated Use ZestLeanContextCollectorPSI directly for new code
 */
@Deprecated("Use ZestLeanContextCollectorPSI directly", ReplaceWith("ZestLeanContextCollectorPSI"))
class ZestLeanContextCollector(private val project: Project) {


    private val psiCollector = ZestLeanContextCollectorPSI(project)

    // Data class that mirrors the PSI collector's LeanContext
    data class LeanContext(
        val fileName: String,
        val language: String,
        val fullContent: String,
        val markedContent: String,
        val cursorOffset: Int,
        val cursorLine: Int,
        val contextType: CursorContextType,
        val isTruncated: Boolean = false,
        val preservedMethods: Set<String> = emptySet(),
        val preservedFields: Set<String> = emptySet()
    )

    // Enum that mirrors the PSI collector's CursorContextType
    enum class CursorContextType {
        // Java contexts
        METHOD_BODY,
        CLASS_DECLARATION,
        IMPORT_SECTION,
        VARIABLE_ASSIGNMENT,
        AFTER_OPENING_BRACE,
        FIELD_DECLARATION,
        ANNOTATION,
        
        // JavaScript contexts
        FUNCTION_BODY,
        FUNCTION_DECLARATION,
        OBJECT_LITERAL,
        ARROW_FUNCTION,
        MODULE_IMPORT,
        MODULE_EXPORT,
        
        UNKNOWN
    }

    /**
     * Collects full file context using PSI-based implementation.
     * 
     * @param editor The editor containing the file
     * @param offset The cursor offset
     * @return The collected context with cursor position marked
     */
    fun collectFullFileContext(editor: Editor, offset: Int): LeanContext {
        val psiContext = psiCollector.collectFullFileContext(editor, offset)
        return convertContext(psiContext)
    }

    /**
     * Collect context with async dependency analysis for better method preservation.
     * 
     * @param editor The editor containing the file
     * @param offset The cursor offset
     * @param onComplete Callback invoked with the enhanced context (called on EDT)
     */
    fun collectWithDependencyAnalysis(
        editor: Editor,
        offset: Int,
        onComplete: (LeanContext) -> Unit
    ) {
        psiCollector.collectWithDependencyAnalysis(editor, offset) { psiContext ->
            onComplete(convertContext(psiContext))
        }
    }

    /**
     * Convert PSI collector context to local context type
     */
    private fun convertContext(psiContext: ZestLeanContextCollectorPSI.LeanContext): LeanContext {
        return LeanContext(
            fileName = psiContext.fileName,
            language = psiContext.language,
            fullContent = psiContext.fullContent,
            markedContent = psiContext.markedContent,
            cursorOffset = psiContext.cursorOffset,
            cursorLine = psiContext.cursorLine,
            contextType = convertContextType(psiContext.contextType),
            isTruncated = psiContext.isTruncated,
            preservedMethods = psiContext.preservedMethods,
            preservedFields = psiContext.preservedFields
        )
    }

    /**
     * Convert PSI collector context type to local enum
     */
    private fun convertContextType(psiType: ZestLeanContextCollectorPSI.CursorContextType): CursorContextType {
        return when (psiType) {
            ZestLeanContextCollectorPSI.CursorContextType.METHOD_BODY -> CursorContextType.METHOD_BODY
            ZestLeanContextCollectorPSI.CursorContextType.CLASS_DECLARATION -> CursorContextType.CLASS_DECLARATION
            ZestLeanContextCollectorPSI.CursorContextType.IMPORT_SECTION -> CursorContextType.IMPORT_SECTION
            ZestLeanContextCollectorPSI.CursorContextType.VARIABLE_ASSIGNMENT -> CursorContextType.VARIABLE_ASSIGNMENT
            ZestLeanContextCollectorPSI.CursorContextType.AFTER_OPENING_BRACE -> CursorContextType.AFTER_OPENING_BRACE
            ZestLeanContextCollectorPSI.CursorContextType.FIELD_DECLARATION -> CursorContextType.FIELD_DECLARATION
            ZestLeanContextCollectorPSI.CursorContextType.ANNOTATION -> CursorContextType.ANNOTATION
            ZestLeanContextCollectorPSI.CursorContextType.FUNCTION_BODY -> CursorContextType.FUNCTION_BODY
            ZestLeanContextCollectorPSI.CursorContextType.FUNCTION_DECLARATION -> CursorContextType.FUNCTION_DECLARATION
            ZestLeanContextCollectorPSI.CursorContextType.OBJECT_LITERAL -> CursorContextType.OBJECT_LITERAL
            ZestLeanContextCollectorPSI.CursorContextType.ARROW_FUNCTION -> CursorContextType.ARROW_FUNCTION
            ZestLeanContextCollectorPSI.CursorContextType.MODULE_IMPORT -> CursorContextType.MODULE_IMPORT
            ZestLeanContextCollectorPSI.CursorContextType.MODULE_EXPORT -> CursorContextType.MODULE_EXPORT
            ZestLeanContextCollectorPSI.CursorContextType.UNKNOWN -> CursorContextType.UNKNOWN
        }
    }
}