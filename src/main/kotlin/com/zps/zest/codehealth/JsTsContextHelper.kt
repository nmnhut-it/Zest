package com.zps.zest.codehealth

import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.zps.zest.completion.context.ZestLeanContextCollectorPSI

/**
 * Helper class for handling JavaScript and TypeScript context in Code Health
 */
class JsTsContextHelper(private val project: Project) {
    
    private val leanCollector = ZestLeanContextCollectorPSI(project)
    
    enum class FrameworkType {
        COCOS2DX,
        REACT,
        NODE_JS,
        VANILLA_JS,
        TYPESCRIPT_GENERIC
    }
    
    /**
     * Detect the framework type from file content
     */
    fun detectFramework(fileName: String, codeSnippet: String): FrameworkType {
        return when {
            // Cocos2d-x patterns
            codeSnippet.contains("cc.") || 
            codeSnippet.contains("extends cc.") ||
            codeSnippet.contains("cocos2d") -> FrameworkType.COCOS2DX
            
            // React patterns  
            codeSnippet.contains("React") || 
            codeSnippet.contains("useState") ||
            codeSnippet.contains("useEffect") ||
            codeSnippet.contains("Component") -> FrameworkType.REACT
                
            // Node.js patterns
            codeSnippet.contains("require(") || 
            codeSnippet.contains("module.exports") ||
            codeSnippet.contains("process.") -> FrameworkType.NODE_JS
                
            // TypeScript specific
            fileName.endsWith(".ts") && !fileName.endsWith(".d.ts") -> FrameworkType.TYPESCRIPT_GENERIC
                
            else -> FrameworkType.VANILLA_JS
        }
    }
    
    /**
     * Extract region context for analysis
     */
    fun extractRegionContext(
        document: Document, 
        centerLine: Int,
        contextLines: Int = 20
    ): RegionContext {
        val lineCount = document.lineCount
        val startLine = (centerLine - contextLines).coerceAtLeast(0)
        val endLine = (centerLine + contextLines).coerceAtMost(lineCount - 1)
        
        // Extract the text for the region
        val startOffset = document.getLineStartOffset(startLine)
        val endOffset = document.getLineEndOffset(endLine)
        val regionText = document.text.substring(startOffset, endOffset)
        
        // Detect framework
        val framework = detectFramework(document.text, regionText)
        
        // Add context markers
        val markedText = addContextMarkers(document, startLine, endLine, centerLine)
        
        return RegionContext(
            text = regionText,
            markedText = markedText,
            startLine = startLine,
            endLine = endLine,
            centerLine = centerLine,
            framework = framework,
            lineCount = endLine - startLine + 1
        )
    }
    
    /**
     * Add context markers to show what changed
     */
    private fun addContextMarkers(
        document: Document,
        startLine: Int,
        endLine: Int,
        centerLine: Int
    ): String {
        val lines = mutableListOf<String>()
        
        // Add truncation marker if needed
        if (startLine > 0) {
            lines.add("/* ... earlier code omitted (partial view) ... */")
        }
        
        for (lineNum in startLine..endLine) {
            val lineStartOffset = document.getLineStartOffset(lineNum)
            val lineEndOffset = document.getLineEndOffset(lineNum)
            val lineText = document.text.substring(lineStartOffset, lineEndOffset)
            
            if (lineNum == centerLine) {
                lines.add(">>> $lineText  // <<< MODIFIED LINE")
            } else {
                lines.add(lineText)
            }
        }
        
        // Add truncation marker if needed
        if (endLine < document.lineCount - 1) {
            lines.add("/* ... later code omitted (partial view) ... */")
        }
        
        return lines.joinToString("\n")
    }
    
    /**
     * Build analysis prompt for JS/TS region
     */
    fun buildRegionAnalysisPrompt(
        fileName: String,
        region: ModifiedRegion,
        context: RegionContext
    ): String {
        val frameworkHint = when (context.framework) {
            FrameworkType.COCOS2DX -> "This appears to be Cocos2d-x JavaScript code."
            FrameworkType.REACT -> "This appears to be React code."
            FrameworkType.NODE_JS -> "This appears to be Node.js code."
            FrameworkType.TYPESCRIPT_GENERIC -> "This is TypeScript code."
            else -> "This is JavaScript code."
        }
        
        return """
            Analyze this code fragment for potential issues.
            
            Context:
            - File: $fileName
            - Language: ${region.language.uppercase()}
            - Lines shown: ${region.startLine + 1} to ${region.endLine + 1} (partial file view)
            - Modified ${region.modificationCount} times
            - $frameworkHint
            
            IMPORTANT: This is only a PARTIAL view of the file (±20 lines around cursor).
            - DON'T flag missing imports or undefined variables that might be defined elsewhere
            - DON'T assume architectural issues you can't fully see
            - DO focus on issues clearly visible in this code fragment
            - BE CONSERVATIVE - only flag issues you're confident about
            
            Code fragment:
            ```${region.language}
            ${context.markedText}
            ```
            
            Analyze for:
            - Obvious syntax errors or bugs
            - Clear logic errors
            - Performance issues visible in this fragment
            - Security concerns (eval, innerHTML, etc.)
            - Code quality issues you can see
            
            Return ONLY valid JSON:
            {
                "summary": "Brief assessment of this code fragment",
                "healthScore": 85,
                "issues": [
                    {
                        "category": "Category Name",
                        "severity": 3,
                        "title": "Brief issue title",
                        "description": "What's wrong in this fragment",
                        "impact": "What could happen",
                        "suggestedFix": "How to fix it",
                        "confidence": 0.9
                    }
                ]
            }
        """.trimIndent()
    }
    
    /**
     * Context data for a code region
     */
    data class RegionContext(
        val text: String,
        val markedText: String,
        val startLine: Int,
        val endLine: Int,
        val centerLine: Int,
        val framework: FrameworkType,
        val lineCount: Int
    )
}
