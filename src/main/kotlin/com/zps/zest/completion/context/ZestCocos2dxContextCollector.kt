package com.zps.zest.completion.context

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project

/**
 * Specialized context collector for Cocos2d-x JavaScript projects.
 * Provides enhanced context understanding for Cocos2d-x specific patterns,
 * nodes, scenes, and framework-specific completions.
 */
class ZestCocos2dxContextCollector(private val project: Project) {

    companion object {
        private const val MAX_CONTEXT_LENGTH = 2000
        private const val FUNCTION_BODY_PLACEHOLDER = " { /* function body hidden */ }"

        // Cocos2d-x specific patterns
        private val COCOS_NODE_TYPES = listOf(
            "cc.Node", "cc.Scene", "cc.Layer", "cc.Sprite", "cc.Label",
            "cc.Menu", "cc.MenuItem", "cc.Action", "cc.Animation"
        )

        private val COCOS_LIFECYCLE_METHODS = listOf(
            "ctor", "onEnter", "onExit", "onEnterTransitionDidFinish",
            "onExitTransitionDidStart", "update", "init"
        )
        
        // Syntax preference constants
        private val SYNTAX_GUIDANCE = """
            IMPORTANT COCOS2D-X-JS SYNTAX PREFERENCES:
            - Use OLD VERSION cocos2d-x-js syntax patterns
            - PREFER cc.Node() over cc.Node.create() - direct constructor calls preferred
            - PREFER cc.Sprite() over cc.Sprite.create() - direct constructor calls preferred  
            - PREFER cc.Scene() over cc.Scene.create() - direct constructor calls preferred
            - Use .extend() pattern for class inheritance: var MyClass = cc.Layer.extend({...})
            - Use object literal methods: methodName: function() {...}
            - Include lifecycle methods: ctor, onEnter, onExit, init, update
        """.trimIndent()
    }

    enum class Cocos2dxContextType {
        // Scene contexts
        SCENE_DEFINITION,
        SCENE_LIFECYCLE_METHOD,
        SCENE_INIT_METHOD,

        // Node contexts
        NODE_CREATION,
        NODE_PROPERTY_SETTING,
        NODE_CHILD_MANAGEMENT,

        // Event handling
        EVENT_LISTENER_SETUP,
        TOUCH_EVENT_HANDLER,
        KEYBOARD_EVENT_HANDLER,

        // Action and animation
        ACTION_CREATION,
        ACTION_SEQUENCE,
        ANIMATION_SETUP,

        // Resource management
        RESOURCE_LOADING,
        TEXTURE_MANAGEMENT,

        // Game logic
        GAME_UPDATE_LOOP,
        COLLISION_DETECTION,

        // General JavaScript contexts
        FUNCTION_BODY,
        OBJECT_LITERAL,
        MODULE_EXPORT,

        UNKNOWN
    }

    data class Cocos2dxContext(
        val fileName: String,
        val language: String,
        val fullContent: String,
        val markedContent: String,
        val cursorOffset: Int,
        val cursorLine: Int,
        val contextType: Cocos2dxContextType,
        val cocosFrameworkVersion: String,
        val nearbyNodes: List<String>,
        val currentSceneContext: String?,
        val syntaxPreferences: CocosSyntaxPreferences,
        val isTruncated: Boolean = false
    )

    data class CocosSyntaxPreferences(
        val preferDirectConstructor: Boolean = true, // Prefer cc.Node() over cc.Node.create()
        val useOldVersionSyntax: Boolean = true,     // Use cocos2d-x-js old version syntax
        val preferredPatterns: List<String> = listOf(
            "cc.Node() instead of cc.Node.create()",
            "cc.Sprite() instead of cc.Sprite.create()",
            "cc.Layer() instead of cc.Layer.create()",
            "cc.Scene() instead of cc.Scene.create()",
            "Use .extend() pattern for class inheritance",
            "Prefer object literal methods over separate function definitions"
        )
    )

    fun collectCocos2dxContext(editor: Editor, offset: Int): ZestLeanContextCollector.LeanContext {
        val document = editor.document
        val text = document.text
        val virtualFile = FileDocumentManager.getInstance().getFile(document)
        val fileName = virtualFile?.name ?: "unknown"

        // Collect Cocos2d-x specific context
        val cocosContext = analyzeCocos2dxContext(text, offset)

        // Insert cursor marker
        val markedContent = text.substring(0, offset) + "[CURSOR]" + text.substring(offset)

        // Calculate cursor line
        val cursorLine = document.getLineNumber(offset)

        // Apply truncation if needed
        val (finalContent, finalMarkedContent, isTruncated) = if (markedContent.length > MAX_CONTEXT_LENGTH) {
            truncateCocos2dxContent(text, markedContent)
        } else {
            Triple(text, markedContent, false)
        }

        // Prepend Cocos2d-x specific syntax guidance to the marked content
        val contentWithGuidance = SYNTAX_GUIDANCE + "\n\n" + finalMarkedContent
        val originalWithGuidance = SYNTAX_GUIDANCE + "\n\n" + finalContent

        // Convert to standard LeanContext format
        val standardContextType = mapCocos2dxToStandardContext(cocosContext.contextType)

        return ZestLeanContextCollector.LeanContext(
            fileName = fileName,
            language = "cocos2d-x-js",
            fullContent = originalWithGuidance,
            markedContent = contentWithGuidance,
            cursorOffset = offset + SYNTAX_GUIDANCE.length + 2, // Adjust offset for prepended content
            cursorLine = cursorLine + SYNTAX_GUIDANCE.lines().size + 1, // Adjust line number
            contextType = standardContextType,
            isTruncated = isTruncated
        )
    }

    private fun analyzeCocos2dxContext(text: String, offset: Int): Cocos2dxContext {
        val beforeCursor = text.substring(0, offset)
        val afterCursor = text.substring(offset)
        val lines = beforeCursor.lines()
        val currentLine = lines.lastOrNull() ?: ""
        val contextType = detectCocos2dxContextType(beforeCursor, currentLine)
        val frameworkVersion = detectCocos2dxVersion(text)

        return Cocos2dxContext(
            fileName = "unknown",
            language = "cocos2d-x-js",
            fullContent = text,
            markedContent = text,
            cursorOffset = offset,
            cursorLine = lines.size - 1,
            contextType = contextType,
            cocosFrameworkVersion = frameworkVersion,
            nearbyNodes = findNearbyNodeReferences(beforeCursor, afterCursor),
            currentSceneContext = findCurrentSceneContext(beforeCursor),
            syntaxPreferences = createSyntaxPreferences(frameworkVersion),
            isTruncated = false
        )
    }

    private fun detectCocos2dxContextType(beforeCursor: String, currentLine: String): Cocos2dxContextType {
        val trimmedLine = currentLine.trim()
        val previousLines = beforeCursor.lines().takeLast(5)

        return when {
            // Scene contexts
            isInSceneDefinition(previousLines) -> Cocos2dxContextType.SCENE_DEFINITION
            isInLifecycleMethod(previousLines, trimmedLine) -> Cocos2dxContextType.SCENE_LIFECYCLE_METHOD
            trimmedLine.contains("init:") || trimmedLine.contains("init ") -> Cocos2dxContextType.SCENE_INIT_METHOD

            // Node creation and management
            isNodeCreation(trimmedLine) -> Cocos2dxContextType.NODE_CREATION
            isNodePropertySetting(trimmedLine) -> Cocos2dxContextType.NODE_PROPERTY_SETTING
            isChildManagement(trimmedLine) -> Cocos2dxContextType.NODE_CHILD_MANAGEMENT

            // Event handling
            isEventListenerSetup(trimmedLine, previousLines) -> Cocos2dxContextType.EVENT_LISTENER_SETUP
            isTouchEventHandler(previousLines) -> Cocos2dxContextType.TOUCH_EVENT_HANDLER
            isKeyboardEventHandler(previousLines) -> Cocos2dxContextType.KEYBOARD_EVENT_HANDLER

            // Actions and animations
            isActionCreation(trimmedLine) -> Cocos2dxContextType.ACTION_CREATION
            isActionSequence(trimmedLine, previousLines) -> Cocos2dxContextType.ACTION_SEQUENCE
            isAnimationSetup(trimmedLine) -> Cocos2dxContextType.ANIMATION_SETUP

            // Resource management
            isResourceLoading(trimmedLine) -> Cocos2dxContextType.RESOURCE_LOADING
            isTextureManagement(trimmedLine) -> Cocos2dxContextType.TEXTURE_MANAGEMENT

            // Game logic
            isInUpdateLoop(previousLines) -> Cocos2dxContextType.GAME_UPDATE_LOOP
            isCollisionDetection(trimmedLine) -> Cocos2dxContextType.COLLISION_DETECTION

            // General contexts
            isInFunctionBody(beforeCursor) -> Cocos2dxContextType.FUNCTION_BODY
            isInObjectLiteral(beforeCursor) -> Cocos2dxContextType.OBJECT_LITERAL
            trimmedLine.startsWith("module.exports") || trimmedLine.startsWith("export") ->
                Cocos2dxContextType.MODULE_EXPORT

            else -> Cocos2dxContextType.UNKNOWN
        }
    }

    // Cocos2d-x specific detection methods
    private fun isInSceneDefinition(previousLines: List<String>): Boolean {
        return previousLines.any { line ->
            line.contains("cc.Scene.extend") ||
                    line.contains("extend(cc.Scene") ||
                    line.contains("new cc.Scene")
        }
    }

    private fun isInLifecycleMethod(previousLines: List<String>, currentLine: String): Boolean {
        val inLifecycleMethod = COCOS_LIFECYCLE_METHODS.any { method ->
            currentLine.contains("$method:") || currentLine.contains("$method ")
        }
        val inLifecycleBlock = previousLines.any { line ->
            COCOS_LIFECYCLE_METHODS.any { method -> line.contains("$method:") }
        }
        return inLifecycleMethod || inLifecycleBlock
    }

    private fun isNodeCreation(line: String): Boolean {
        return COCOS_NODE_TYPES.any { nodeType ->
            // Prefer direct constructor: cc.Node() over cc.Node.create()
            line.contains("new $nodeType") || 
            line.contains("$nodeType(") ||  // Direct constructor (preferred)
            line.contains("$nodeType.create")  // Legacy .create() method (less preferred)
        }
    }

    private fun isNodePropertySetting(line: String): Boolean {
        val nodeProperties = listOf("setPosition", "setScale", "setRotation", "setVisible", "setOpacity", "setAnchorPoint")
        return nodeProperties.any { line.contains(it) } || line.matches(Regex(".*\\.(x|y|scale|rotation|visible|opacity)\\s*=.*"))
    }

    private fun isChildManagement(line: String): Boolean {
        return line.contains("addChild") || line.contains("removeChild") ||
                line.contains("removeFromParent") || line.contains("getChildByTag")
    }

    private fun isEventListenerSetup(line: String, previousLines: List<String>): Boolean {
        return line.contains("cc.EventListener") || line.contains("addEventListener") ||
                previousLines.any { it.contains("cc.EventListener") }
    }

    private fun isTouchEventHandler(previousLines: List<String>): Boolean {
        return previousLines.any { line ->
            line.contains("onTouchBegan") || line.contains("onTouchMoved") ||
                    line.contains("onTouchEnded") || line.contains("TouchOneByOne")
        }
    }

    private fun isKeyboardEventHandler(previousLines: List<String>): Boolean {
        return previousLines.any { line ->
            line.contains("onKeyPressed") || line.contains("onKeyReleased") ||
                    line.contains("Keyboard")
        }
    }

    private fun isActionCreation(line: String): Boolean {
        return line.contains("cc.MoveTo") || line.contains("cc.MoveBy") ||
                line.contains("cc.ScaleTo") || line.contains("cc.RotateTo") ||
                line.contains("cc.FadeIn") || line.contains("cc.FadeOut") ||
                line.contains("cc.Action")
    }

    private fun isActionSequence(line: String, previousLines: List<String>): Boolean {
        return line.contains("cc.Sequence") || line.contains("cc.Spawn") ||
                line.contains("runAction") || previousLines.any { it.contains("cc.Sequence") }
    }

    private fun isAnimationSetup(line: String): Boolean {
        return line.contains("cc.Animation") || line.contains("cc.Animate") ||
                line.contains("SpriteFrame") || line.contains("addSpriteFrame")
    }

    private fun isResourceLoading(line: String): Boolean {
        return line.contains("cc.loader") || line.contains("cc.textureCache") ||
                line.contains("preload") || line.contains("load(")
    }

    private fun isTextureManagement(line: String): Boolean {
        return line.contains("cc.Texture2D") || line.contains("getTexture") ||
                line.contains("setTexture") || line.contains("textureCache")
    }

    private fun isInUpdateLoop(previousLines: List<String>): Boolean {
        return previousLines.any { line ->
            line.contains("update:") || line.contains("scheduleUpdate") ||
                    line.contains("schedule(")
        }
    }

    private fun isCollisionDetection(line: String): Boolean {
        return line.contains("getBoundingBox") || line.contains("intersects") ||
                line.contains("containsPoint") || line.contains("collision")
    }

    private fun isInFunctionBody(beforeCursor: String): Boolean {
        var braceCount = 0
        var inFunction = false

        beforeCursor.lines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.contains("function") || trimmed.matches(Regex(".*:\\s*function.*"))) {
                inFunction = true
            }
            braceCount += trimmed.count { it == '{' }
            braceCount -= trimmed.count { it == '}' }
            if (braceCount == 0) inFunction = false
        }

        return inFunction && braceCount > 0
    }

    private fun isInObjectLiteral(beforeCursor: String): Boolean {
        var braceCount = 0
        var inObject = false

        beforeCursor.lines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.contains("={") || trimmed.matches(Regex(".*:\\s*\\{.*"))) {
                inObject = true
            }
            braceCount += trimmed.count { it == '{' }
            braceCount -= trimmed.count { it == '}' }
            if (braceCount == 0) inObject = false
        }

        return inObject && braceCount > 0
    }

    private fun detectCocos2dxVersion(text: String): String {
        return when {
            text.contains("cc.Class") -> "3.x"
            text.contains("cc.Node.extend") -> "2.x"
            text.contains("cocos2d-js") -> "3.x"
            else -> "unknown"
        }
    }

    private fun findNearbyNodeReferences(beforeCursor: String, afterCursor: String): List<String> {
        val nodePattern = Regex("""(this\.\w+|var\s+(\w+)|let\s+(\w+)|const\s+(\w+))\s*=\s*new\s+(cc\.\w+)""")
        val context = beforeCursor.takeLast(500) + afterCursor.take(500)

        return nodePattern.findAll(context).map { match ->
            val variableName = match.groupValues[2].ifEmpty { match.groupValues[3] }.ifEmpty { match.groupValues[4] }
            val nodeType = match.groupValues[5]
            "$variableName ($nodeType)"
        }.toList()
    }

    private fun findCurrentSceneContext(beforeCursor: String): String? {
        val scenePattern = Regex("""(var|let|const)\s+(\w+Scene)\s*=\s*cc\.Scene\.extend""")
        return scenePattern.find(beforeCursor)?.groupValues?.get(2)
    }

    private fun createSyntaxPreferences(frameworkVersion: String): CocosSyntaxPreferences {
        return when (frameworkVersion) {
            "2.x" -> CocosSyntaxPreferences(
                preferDirectConstructor = true,
                useOldVersionSyntax = true,
                preferredPatterns = listOf(
                    "Use cc.Node() instead of cc.Node.create() - direct constructor preferred",
                    "Use cc.Sprite() instead of cc.Sprite.create() - direct constructor preferred", 
                    "Use cc.Layer() instead of cc.Layer.create() - direct constructor preferred",
                    "Use cc.Scene() instead of cc.Scene.create() - direct constructor preferred",
                    "Use cc.Menu() instead of cc.Menu.create() - direct constructor preferred",
                    "Use cc.Label() instead of cc.Label.create() - direct constructor preferred",
                    "Use .extend() pattern for class inheritance: var MyLayer = cc.Layer.extend({...})",
                    "Prefer object literal methods in extend: methodName: function() {...}",
                    "Use old version cocos2d-x-js syntax patterns",
                    "Lifecycle methods: ctor, onEnter, onExit, init, update",
                    "Event handling with cc.EventListener patterns"
                )
            )
            "3.x" -> CocosSyntaxPreferences(
                preferDirectConstructor = true,
                useOldVersionSyntax = true,
                preferredPatterns = listOf(
                    "Use cc.Node() instead of cc.Node.create() - direct constructor preferred",
                    "Use cc.Sprite() instead of cc.Sprite.create() - direct constructor preferred",
                    "Use cc.Layer() instead of cc.Layer.create() - direct constructor preferred", 
                    "Use cc.Scene() instead of cc.Scene.create() - direct constructor preferred",
                    "Use cc.Class for class definitions when available",
                    "Prefer direct constructor calls over .create() methods",
                    "Use old version cocos2d-x-js syntax patterns",
                    "Modern event system with cc.EventListener",
                    "Use cc.loader for resource management"
                )
            )
            else -> CocosSyntaxPreferences(
                preferDirectConstructor = true,
                useOldVersionSyntax = true,
                preferredPatterns = listOf(
                    "Default: Use cc.Node() instead of cc.Node.create()",
                    "Prefer direct constructor calls over .create() methods",
                    "Use old version cocos2d-x-js syntax patterns",
                    "Follow cocos2d-x-js conventions"
                )
            )
        }
    }

    /**
     * Extracts syntax guidance and completion hints based on Cocos2d-x context and preferences.
     * This information can be used by the completion system to suggest appropriate patterns.
     */
    fun getCompletionHints(context: Cocos2dxContext): List<String> {
        val hints = mutableListOf<String>()
        
        // Add general syntax preferences
        hints.addAll(context.syntaxPreferences.preferredPatterns)
        
        // Add context-specific hints
        when (context.contextType) {
            Cocos2dxContextType.NODE_CREATION -> {
                hints.add("SYNTAX: Use cc.Sprite() instead of cc.Sprite.create()")  
                hints.add("SYNTAX: Use cc.Node() instead of cc.Node.create()")
                hints.add("SYNTAX: Direct constructor calls are preferred over .create() methods")
            }
            Cocos2dxContextType.SCENE_DEFINITION -> {
                hints.add("SYNTAX: Use var MyScene = cc.Scene.extend({...}) pattern")
                hints.add("SYNTAX: Include ctor, onEnter, onExit lifecycle methods")
            }
            Cocos2dxContextType.SCENE_LIFECYCLE_METHOD -> {
                hints.add("SYNTAX: Use old cocos2d-x-js lifecycle method patterns")
                hints.add("SYNTAX: Call this._super() in lifecycle methods when appropriate")
            }
            Cocos2dxContextType.ACTION_CREATION -> {
                hints.add("SYNTAX: Use cc.MoveTo() instead of cc.MoveTo.create()")
                hints.add("SYNTAX: Use cc.ScaleTo() instead of cc.ScaleTo.create()")
            }
            else -> {
                hints.add("SYNTAX: Follow cocos2d-x-js old version patterns")
                hints.add("SYNTAX: Prefer direct constructors over .create() methods")
            }
        }
        
        // Add framework version specific hints
        if (context.cocosFrameworkVersion == "2.x") {
            hints.add("FRAMEWORK: Using Cocos2d-x JS v2.x patterns")
            hints.add("FRAMEWORK: Use .extend() for inheritance")
        } else if (context.cocosFrameworkVersion == "3.x") {
            hints.add("FRAMEWORK: Using Cocos2d-x JS v3.x patterns") 
            hints.add("FRAMEWORK: cc.Class available for class definitions")
        }
        
        return hints.distinct()
    }

    private fun truncateCocos2dxContent(originalContent: String, markedContent: String): Triple<String, String, Boolean> {
        // Process the marked content first to preserve cursor position
        val collapsedMarkedContent = collapseFunctionBodiesPreservingCursor(markedContent)
        
        // Generate original content by removing cursor marker from the result
        val collapsedOriginalContent = collapsedMarkedContent.replace("[CURSOR]", "")

        return if (collapsedMarkedContent.length > MAX_CONTEXT_LENGTH) {
            // Find cursor position to ensure it's preserved
            val cursorIndex = collapsedMarkedContent.indexOf("[CURSOR]")
            
            if (cursorIndex != -1) {
                // Cursor found - use smart truncation that preserves cursor
                val truncated = smartTruncateAroundCursor(collapsedMarkedContent, cursorIndex, MAX_CONTEXT_LENGTH)
                val originalTruncated = truncated.replace("[CURSOR]", "")
                Triple(originalTruncated, truncated, true)
            } else {
                // No cursor found - use simple truncation (fallback)
                val truncated = collapsedMarkedContent.take(MAX_CONTEXT_LENGTH) + "\n/* ... content truncated ... */"
                val originalTruncated = collapsedOriginalContent.take(MAX_CONTEXT_LENGTH) + "\n/* ... content truncated ... */"
                Triple(originalTruncated, truncated, true)
            }
        } else {
            Triple(collapsedOriginalContent, collapsedMarkedContent, collapsedOriginalContent != originalContent)
        }
    }

    /**
     * Smart truncation that ensures cursor position is preserved within the content.
     * Keeps context around the cursor position while staying within the length limit.
     * Always preserves Cocos2d-x syntax guidance at the beginning.
     */
    private fun smartTruncateAroundCursor(content: String, cursorIndex: Int, maxLength: Int): String {
        val truncationMarker = "\n/* ... content truncated ... */"
        val syntaxGuidanceLength = SYNTAX_GUIDANCE.length + 2 // +2 for the newlines
        
        // Always preserve syntax guidance + some minimum content
        val minContentAfterGuidance = 500
        val availableLength = maxLength - truncationMarker.length * 2 - syntaxGuidanceLength
        
        if (availableLength <= minContentAfterGuidance) {
            // If we can't fit much after the guidance, just do simple truncation
            return content.take(maxLength) + truncationMarker
        }
        
        // Extract the syntax guidance part (should be at the beginning)
        val syntaxGuidanceEnd = content.indexOf("\n\n") + 2
        val guidance = if (syntaxGuidanceEnd > 2) content.substring(0, syntaxGuidanceEnd) else ""
        val actualContent = if (syntaxGuidanceEnd > 2) content.substring(syntaxGuidanceEnd) else content
        val adjustedCursorIndex = cursorIndex - syntaxGuidanceEnd
        
        if (adjustedCursorIndex < 0 || actualContent.isEmpty()) {
            // Cursor is in guidance section or no actual content
            return content.take(maxLength) + truncationMarker
        }
        
        // Calculate how much content to keep before and after cursor in the actual content
        val halfLength = availableLength / 2
        val beforeCursor = maxOf(0, adjustedCursorIndex - halfLength)
        val afterCursor = minOf(actualContent.length, adjustedCursorIndex + halfLength)
        
        // Adjust to line boundaries to avoid cutting words/lines awkwardly
        val beforeLineStart = actualContent.lastIndexOf('\n', beforeCursor).let { 
            if (it == -1) 0 else it + 1 
        }
        val afterLineEnd = actualContent.indexOf('\n', afterCursor).let { 
            if (it == -1) actualContent.length else it 
        }
        
        val beforeTruncated = beforeLineStart > 0
        val afterTruncated = afterLineEnd < actualContent.length
        
        return buildString {
            // Always include syntax guidance
            append(guidance)
            
            if (beforeTruncated) {
                append("/* ... content truncated ... */\n")
            }
            append(actualContent.substring(beforeLineStart, afterLineEnd))
            if (afterTruncated) {
                append("\n/* ... content truncated ... */")
            }
        }
    }

    /**
     * Cursor-preserving function body collapsing for Cocos2d-x projects.
     * Ensures the [CURSOR] marker is never lost during truncation.
     */
    private fun collapseFunctionBodiesPreservingCursor(content: String): String {
        val lines = content.lines().toMutableList()
        val result = mutableListOf<String>()
        
        // Find cursor position
        val cursorLine = findCursorLine(lines)
        
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val functionMatch = findCocos2dxFunctionStart(line, i, lines)

            if (functionMatch != null) {
                // Check if this function contains the cursor
                val functionEnd = findMatchingCloseBrace(lines, functionMatch.braceLineIndex)
                val containsCursor = cursorLine in i..functionEnd
                
                // Add the function signature line(s)
                result.addAll(functionMatch.signatureLines)

                // Find the opening brace and handle the body
                val braceLineIndex = functionMatch.braceLineIndex
                if (braceLineIndex != -1) {
                    if (containsCursor || functionMatch.isImportant) {
                        // Preserve the entire function body since it contains the cursor or is important
                        for (bodyLineIndex in braceLineIndex until functionEnd + 1) {
                            if (bodyLineIndex < lines.size) {
                                result.add(lines[bodyLineIndex])
                            }
                        }
                    } else {
                        // Collapse the function body since it doesn't contain the cursor
                        val collapsedBody = collapseBodyFromBrace(lines, braceLineIndex)
                        result.add(collapsedBody)
                    }
                    
                    // Skip to after the function body
                    i = functionEnd + 1
                } else {
                    // No opening brace found, just add the line and continue
                    i++
                }
            } else {
                result.add(line)
                i++
            }
        }

        return result.joinToString("\n")
    }
    
    /**
     * Finds the line number containing the [CURSOR] marker
     */
    private fun findCursorLine(lines: List<String>): Int {
        return lines.indexOfFirst { it.contains("[CURSOR]") }
    }

    private fun collapseFunctionBodies(content: String): String {
        val lines = content.lines().toMutableList()
        val result = mutableListOf<String>()
        var i = 0

        while (i < lines.size) {
            val line = lines[i]
            val functionMatch = findCocos2dxFunctionStart(line, i, lines)

            if (functionMatch != null) {
                // Add the function signature line(s)
                result.addAll(functionMatch.signatureLines)

                // Find the opening brace and collapse the body
                val braceLineIndex = functionMatch.braceLineIndex
                if (braceLineIndex != -1) {
                    val collapsedBody = collapseBodyFromBrace(lines, braceLineIndex)
                    result.add(collapsedBody)

                    // Skip to after the function body
                    i = findMatchingCloseBrace(lines, braceLineIndex) + 1
                } else {
                    // No opening brace found, just add the line and continue
                    i++
                }
            } else {
                result.add(line)
                i++
            }
        }

        return result.joinToString("\n")
    }

    private data class FunctionMatch(
        val signatureLines: List<String>,
        val braceLineIndex: Int,
        val isImportant: Boolean = false
    )

    private fun findCocos2dxFunctionStart(line: String, lineIndex: Int, lines: List<String>): FunctionMatch? {
        val trimmed = line.trim()

        // Cocos2d-x specific patterns with importance marking
        val cocosPatterns = listOf(
            // Lifecycle methods (important to preserve signature)
            Regex("""^\s*(${COCOS_LIFECYCLE_METHODS.joinToString("|")})\s*:\s*function\s*\([^)]*\)\s*\{?\s*$""") to true,
            // Scene extend pattern
            Regex("""^\s*var\s+\w+\s*=\s*cc\.Scene\.extend\s*\(\s*\{?\s*$""") to true,
            // Node extend pattern
            Regex("""^\s*var\s+\w+\s*=\s*cc\.Node\.extend\s*\(\s*\{?\s*$""") to true,
            // Class create method
            Regex("""^\s*create\s*:\s*function\s*\([^)]*\)\s*\{?\s*$""") to true,
            // Event handlers (important)
            Regex("""^\s*(onTouch\w+|onKey\w+|onEnter|onExit)\s*:\s*function\s*\([^)]*\)\s*\{?\s*$""") to true,
            // Regular methods in objects
            Regex("""^\s*\w+\s*:\s*function\s*\([^)]*\)\s*\{?\s*$""") to false,
            // Standard function declarations
            Regex("""^\s*function\s+\w*\s*\([^)]*\)\s*\{?\s*$""") to false,
            // Arrow functions
            Regex("""^\s*(?:const|let|var)\s+\w+\s*=\s*\([^)]*\)\s*=>\s*\{?\s*$""") to false
        )

        for ((pattern, isImportant) in cocosPatterns) {
            if (pattern.matches(trimmed)) {
                val braceIndex = when {
                    line.contains("{") -> lineIndex
                    lineIndex + 1 < lines.size && lines[lineIndex + 1].trim().startsWith("{") -> lineIndex + 1
                    else -> -1
                }

                return FunctionMatch(listOf(line), braceIndex, isImportant)
            }
        }

        return null
    }

    private fun collapseBodyFromBrace(lines: List<String>, braceLineIndex: Int): String {
        val braceLine = lines[braceLineIndex]
        val beforeBrace = braceLine.substringBefore("{")
        val afterBrace = braceLine.substringAfter("{")

        // For Cocos2d-x, preserve the structure but collapse body
        return if (afterBrace.trim().isNotEmpty() && !afterBrace.trim().startsWith("}")) {
            beforeBrace + "{" + FUNCTION_BODY_PLACEHOLDER + " }"
        } else {
            beforeBrace + FUNCTION_BODY_PLACEHOLDER
        }
    }

    private fun findMatchingCloseBrace(lines: List<String>, openBraceLineIndex: Int): Int {
        var braceCount = 0
        var foundOpenBrace = false
        var inString = false
        var inComment = false
        var i = openBraceLineIndex

        while (i < lines.size) {
            val line = lines[i]
            var j = 0

            while (j < line.length) {
                val char = line[j]
                val nextChar = if (j + 1 < line.length) line[j + 1] else null

                // Handle comments and strings
                when {
                    !inString && !inComment && char == '/' && nextChar == '/' -> {
                        // Single line comment - skip rest of line
                        break
                    }
                    !inString && !inComment && char == '/' && nextChar == '*' -> {
                        inComment = true
                        j++ // Skip next char
                    }
                    inComment && char == '*' && nextChar == '/' -> {
                        inComment = false
                        j++ // Skip next char
                    }
                    !inComment && char == '"' && (j == 0 || line[j-1] != '\\') -> {
                        inString = !inString
                    }
                    !inString && !inComment -> {
                        when (char) {
                            '{' -> {
                                braceCount++
                                foundOpenBrace = true
                            }
                            '}' -> {
                                if (foundOpenBrace) {
                                    braceCount--
                                    if (braceCount == 0) {
                                        return i
                                    }
                                }
                            }
                        }
                    }
                }
                j++
            }

            // Reset comment state at end of line for single-line comments
            if (inComment && !line.contains("*/")) {
                // This was actually a single line comment, reset
                inComment = false
            }

            i++
        }

        // If no matching brace found, return the last line
        return lines.size - 1
    }

    private fun mapCocos2dxToStandardContext(cocosType: Cocos2dxContextType): ZestLeanContextCollector.CursorContextType {
        return when (cocosType) {
            Cocos2dxContextType.FUNCTION_BODY -> ZestLeanContextCollector.CursorContextType.FUNCTION_BODY
            Cocos2dxContextType.OBJECT_LITERAL -> ZestLeanContextCollector.CursorContextType.OBJECT_LITERAL
            Cocos2dxContextType.MODULE_EXPORT -> ZestLeanContextCollector.CursorContextType.MODULE_EXPORT
            else -> ZestLeanContextCollector.CursorContextType.UNKNOWN
        }
    }
}