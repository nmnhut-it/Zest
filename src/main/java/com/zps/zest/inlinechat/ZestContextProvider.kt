package com.zps.zest.inlinechat

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.zps.zest.ClassAnalyzer
import com.zps.zest.CodeContext
import com.zps.zest.ConfigurationManager

/**
 * Class to integrate with Zest's existing context gathering capabilities.
 * Enhanced to collect more comprehensive context similar to TodoPromptDrafter.
 */
class ZestContextProvider {
    companion object {
        /**
         * Create a CodeContext with information gathered from the current selection
         */
        fun createCodeContext(project: Project, editor: Editor, command: String): CodeContext {
            // Use ReadAction to ensure thread safety when accessing PSI and editor models
            return ReadAction.compute<CodeContext, Throwable> {
                val codeContext = CodeContext()
                codeContext.setProject(project)
                codeContext.setEditor(editor)
                
                val psiFile = PsiManager.getInstance(project).findFile(editor.virtualFile)
                val selectionModel = editor.selectionModel
                
                // Get the selected text and context
                val (selectedText, contextString) = if (selectionModel.hasSelection()) {
                    val text = selectionModel.selectedText ?: ""
                    val context = if (psiFile != null) {
                        ClassAnalyzer.collectSelectionContext(
                            psiFile,
                            selectionModel.selectionStart,
                            selectionModel.selectionEnd
                        )
                    } else {
                        ""
                    }
                    Pair(text, context)
                } else {
                    // If no selection, try to get the current method or class text
                    val offset = editor.caretModel.offset
                    val element = psiFile?.findElementAt(offset)
                    val text = gatherContextFromElement(element, editor)
                    val context = if (psiFile != null && element != null) {
                        // Get broader context around the cursor
                        val contextRange = getExpandedContextRange(element, editor)
                        ClassAnalyzer.collectSelectionContext(
                            psiFile,
                            contextRange.startOffset,
                            contextRange.endOffset
                        )
                    } else {
                        ""
                    }
                    Pair(text, context)
                }
                
                // Collect related class implementations if available
                val relatedClassImpls = if (psiFile != null && selectionModel.hasSelection()) {
                    ClassAnalyzer.collectRelatedClassImplementations(
                        psiFile,
                        selectionModel.selectionStart,
                        selectionModel.selectionEnd
                    )
                } else if (psiFile != null) {
                    // For cursor position, use a small range around the cursor
                    val offset = editor.caretModel.offset
                    ClassAnalyzer.collectRelatedClassImplementations(
                        psiFile,
                        maxOf(0, offset - 10),
                        minOf(editor.document.textLength, offset + 10)
                    )
                } else {
                    emptyMap()
                }
                
                // Set the prompt with the selected text, context, and command
                val prompt = buildEnhancedPrompt(selectedText, contextString, relatedClassImpls, command)
                codeContext.setPrompt(prompt)
                
                // Set config
                codeContext.setConfig(ConfigurationManager.getInstance(project))
                
                codeContext
            }
        }
        
        /**
         * Get an expanded context range around an element for better context gathering
         */
        private fun getExpandedContextRange(element: PsiElement?, editor: Editor): TextRange {
            if (element == null) {
                val offset = editor.caretModel.offset
                return TextRange(offset, offset)
            }
            
            // Try to find the containing method or class
            val method = PsiTreeUtil.getParentOfType(element, com.intellij.psi.PsiMethod::class.java)
            if (method != null) {
                return method.textRange
            }
            
            val clazz = PsiTreeUtil.getParentOfType(element, com.intellij.psi.PsiClass::class.java)
            if (clazz != null) {
                return clazz.textRange
            }
            
            // Default to element's range
            return element.textRange
        }
        
        /**
         * Gather context from a PsiElement
         */
        private fun gatherContextFromElement(element: PsiElement?, editor: Editor): String {
            if (element == null) return ""
            
            // Try to find method first
            val method = PsiTreeUtil.getParentOfType(element, com.intellij.psi.PsiMethod::class.java)
            if (method != null) {
                return method.text
            }
            
            // Try to find class
            val clazz = PsiTreeUtil.getParentOfType(element, com.intellij.psi.PsiClass::class.java)
            if (clazz != null) {
                return clazz.text
            }
            
            // If neither method nor class is found, return a small context around the cursor
            val document = editor.document
            val offset = editor.caretModel.offset
            val lineNum = document.getLineNumber(offset)
            
            // Include a few lines before and after for context
            val contextStart = document.getLineStartOffset(Math.max(0, lineNum - 3))
            val contextEnd = document.getLineEndOffset(Math.min(document.lineCount - 1, lineNum + 3))
            
            return document.getText(TextRange(contextStart, contextEnd))
        }
        
        /**
         * Build an enhanced prompt for the LLM using the selected text, context, and command
         */
        private fun buildEnhancedPrompt(
            selectedText: String,
            codeContext: String,
            relatedClassImpls: Map<String, String>,
            command: String
        ): String {
            val promptBuilder = StringBuilder()
            
            // Add main instruction
            promptBuilder.append("""
                You are a helpful programming assistant for Java. I'll provide you with code and a request. 
                Please respond with concise, helpful information based on the request.
                
            """.trimIndent())
            
            // Add code context if available
            if (codeContext.isNotBlank()) {
                promptBuilder.append("\nCODE CONTEXT:\n")
                promptBuilder.append(codeContext)
                promptBuilder.append("\n")
            }
            
            // Add related class implementations if available
            if (relatedClassImpls.isNotEmpty()) {
                promptBuilder.append("\nRELATED CLASS IMPLEMENTATIONS:\n")
                for ((className, implementation) in relatedClassImpls) {
                    promptBuilder.append("// Class: $className\n")
                    promptBuilder.append("```java\n")
                    promptBuilder.append(implementation)
                    promptBuilder.append("\n```\n\n")
                }
            }
            
            // Add the selected code
            promptBuilder.append("\nSELECTED CODE:\n```java\n")
            promptBuilder.append(selectedText)
            promptBuilder.append("\n```\n\n")
            
            // Add the user's request
            promptBuilder.append("REQUEST: $command\n\n")
            
            // Add specific instructions based on common commands
            when {
                command.contains("test", ignoreCase = true) -> {
                    promptBuilder.append("""
                        Please generate comprehensive unit tests for the selected code.
                        Include:
                        1. Test cases for normal operation
                        2. Edge cases and boundary conditions
                        3. Error scenarios
                        4. Use appropriate assertions and test frameworks (JUnit, Mockito if needed)
                    """.trimIndent())
                }
                command.contains("explain", ignoreCase = true) -> {
                    promptBuilder.append("""
                        Please provide a clear explanation of:
                        1. What this code does
                        2. How it works
                        3. Key concepts or patterns used
                        4. Potential issues or improvements
                    """.trimIndent())
                }
                command.contains("refactor", ignoreCase = true) -> {
                    promptBuilder.append("""
                        Please suggest refactoring improvements:
                        1. Code readability and clarity
                        2. Design patterns that could be applied
                        3. Performance optimizations
                        4. Error handling improvements
                        Provide the refactored code with explanations for each change.
                    """.trimIndent())
                }
                command.contains("document", ignoreCase = true) -> {
                    promptBuilder.append("""
                        Please add comprehensive documentation:
                        1. JavaDoc comments for classes and methods
                        2. Inline comments for complex logic
                        3. Parameter descriptions
                        4. Return value explanations
                        5. Exception documentation
                    """.trimIndent())
                }
                else -> {
                    promptBuilder.append("""
                        Please provide a clear, well-structured response. If you're suggesting code changes, 
                        explain the reasoning behind them and provide the complete modified code.
                    """.trimIndent())
                }
            }
            
            return promptBuilder.toString()
        }
        
        /**
         * Get detailed context for more comprehensive analysis
         */
        fun getDetailedContext(project: Project, editor: Editor): String {
            return ReadAction.compute<String, Throwable> {
                val psiFile = PsiManager.getInstance(project).findFile(editor.virtualFile) ?: return@compute ""
                
                if (editor.selectionModel.hasSelection()) {
                    val start = editor.selectionModel.selectionStart
                    val end = editor.selectionModel.selectionEnd
                    
                    // Use existing ClassAnalyzer to get detailed context
                    ClassAnalyzer.collectSelectionContext(psiFile, start, end)
                } else {
                    val offset = editor.caretModel.offset
                    val element = psiFile.findElementAt(offset)
                    
                    // Try to find containing class
                    val containingClass = PsiTreeUtil.getParentOfType(element, com.intellij.psi.PsiClass::class.java)
                    if (containingClass != null) {
                        // Use existing ClassAnalyzer to get detailed context
                        ClassAnalyzer.collectClassContext(containingClass)
                    } else {
                        ""
                    }
                }
            }
        }
        
        /**
         * Check if the selected text contains TODOs
         */
        fun containsTodos(selectedText: String): Boolean {
            val todoPattern = Regex("//\\s*(TODO|FIXME|HACK|XXX)\\s*:?\\s*(.*?)\\s*$", RegexOption.MULTILINE)
            return todoPattern.containsMatchIn(selectedText)
        }
        
        /**
         * Extract TODO items from the selected text
         */
        fun extractTodos(selectedText: String): List<Pair<String, String>> {
            val todoPattern = Regex("//\\s*(TODO|FIXME|HACK|XXX)\\s*:?\\s*(.*?)\\s*$", RegexOption.MULTILINE)
            return todoPattern.findAll(selectedText).map { match ->
                val type = match.groupValues[1]
                val description = match.groupValues[2].trim()
                Pair(type, description)
            }.toList()
        }
    }
}