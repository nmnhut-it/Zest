package com.zps.zest.inlinechat

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.zps.zest.ClassAnalyzer
import com.zps.zest.CodeContext
import com.zps.zest.ConfigurationManager

/**
 * Class to integrate with Zest's existing context gathering capabilities
 */
class ZestContextProvider {
    companion object {
        /**
         * Create a CodeContext with information gathered from the current selection
         */
        fun createCodeContext(project: Project, editor: Editor, command: String): CodeContext {
            val codeContext = CodeContext(project)
            val selectedText = if (editor.selectionModel.hasSelection()) {
                editor.selectionModel.selectedText ?: ""
            } else {
                // If no selection, try to get the current method or class text
                val psiFile = PsiManager.getInstance(project).findFile(editor.virtualFile)
                val offset = editor.caretModel.offset
                val element = psiFile?.findElementAt(offset)
                
                gatherContextFromElement(element, editor)
            }
            
            // Set the prompt with the selected text and command
            val prompt = buildPrompt(selectedText, command)
            codeContext.setPrompt(prompt)
            
            // Set config
            codeContext.setConfig(ConfigurationManager())
            
            return codeContext
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
            val lineStart = document.getLineStartOffset(lineNum)
            val lineEnd = document.getLineEndOffset(lineNum)
            
            // Include a few lines before and after for context
            val contextStart = document.getLineStartOffset(Math.max(0, lineNum - 3))
            val contextEnd = document.getLineEndOffset(Math.min(document.lineCount - 1, lineNum + 3))
            
            return document.getText(com.intellij.openapi.util.TextRange(contextStart, contextEnd))
        }
        
        /**
         * Build a prompt for the LLM using the selected text and command
         */
        private fun buildPrompt(selectedText: String, command: String): String {
            return """
                You are a helpful programming assistant for Java. I'll provide you with code and a request. 
                Please respond with concise, helpful information based on the request.
                
                CODE CONTEXT:
                ```java
                $selectedText
                ```
                
                REQUEST: $command
                
                Please provide a clear, well-structured response. If you're suggesting code changes, 
                explain the reasoning behind them and provide the complete modified code.
            """.trimIndent()
        }
        
        /**
         * Get detailed context for more comprehensive analysis
         */
        fun getDetailedContext(project: Project, editor: Editor): String {
            val psiFile = PsiManager.getInstance(project).findFile(editor.virtualFile) ?: return ""
            
            if (editor.selectionModel.hasSelection()) {
                val start = editor.selectionModel.selectionStart
                val end = editor.selectionModel.selectionEnd
                
                // Use existing ClassAnalyzer to get detailed context
                return ClassAnalyzer.collectSelectionContext(psiFile, start, end)
            } else {
                val offset = editor.caretModel.offset
                val element = psiFile.findElementAt(offset)
                
                // Try to find containing class
                val containingClass = PsiTreeUtil.getParentOfType(element, com.intellij.psi.PsiClass::class.java)
                if (containingClass != null) {
                    // Use existing ClassAnalyzer to get detailed context
                    return ClassAnalyzer.collectClassContext(containingClass)
                }
            }
            
            return ""
        }
    }
}