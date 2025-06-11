# Zest Inline Completion Feature Implementation Plan

## Overview

This document outlines the implementation plan for adding inline code completion to the Zest IntelliJ plugin, leveraging the existing `com.zps.zest.langchain4j.util.LLMService` and following best practices from successful implementations like Tabby.

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────┐
│                    Zest Inline Completion                   │
├─────────────────────────────────────────────────────────────┤
│  UI Layer: Renderer, Actions, Settings                     │
├─────────────────────────────────────────────────────────────┤
│  Service Layer: CompletionService, KeymapService           │
├─────────────────────────────────────────────────────────────┤
│  Integration: LLMService, DocumentSync, EventHandling      │
├─────────────────────────────────────────────────────────────┤
│  IntelliJ Platform APIs                                    │
└─────────────────────────────────────────────────────────────┘
```

## Core Components

### 1. Service Architecture

```kotlin
// Core completion service
@Service(Service.Level.PROJECT)
class ZestInlineCompletionService(private val project: Project) : Disposable {
    private val llmService = LLMService.getInstance()
    private val renderer = ZestInlineCompletionRenderer()
    private val scope = CoroutineScope(Dispatchers.IO)
    
    // Core methods to implement
    fun provideInlineCompletion(editor: Editor, offset: Int, manually: Boolean = false)
    fun accept(editor: Editor, offset: Int?, type: AcceptType)
    fun dismiss()
    fun cycle(editor: Editor, offset: Int?, direction: CycleDirection)
}

// Keymap management service
@Service(Service.Level.PROJECT)
class ZestKeymapSettings(private val project: Project) {
    enum class KeymapStyle { DEFAULT, ZEST_STYLE, CUSTOMIZE }
    
    fun getCurrentKeymapStyle(): KeymapStyle
    fun applyKeymapStyle(style: KeymapStyle)
}
```

### 2. Data Models

```kotlin
// Completion data structures
data class ZestInlineCompletionList(
    val isIncomplete: Boolean,
    val items: List<ZestInlineCompletionItem>
)

data class ZestInlineCompletionItem(
    val insertText: String,
    val replaceRange: Range,
    val confidence: Float,
    val metadata: CompletionMetadata? = null
)

data class CompletionMetadata(
    val model: String,
    val tokens: Int,
    val latency: Long
)
```

### 3. Action System

```kotlin
// Base action for all inline completion actions
abstract class ZestInlineCompletionAction(
    private val handler: ZestInlineCompletionActionHandler
) : EditorAction(ZestActionHandler(handler)), HasPriority {
    override val priority: Int = 10 // Higher than Tabby
}

// Tab acceptance with smart indentation handling
class ZestTabAccept : ZestInlineCompletionAction(object : ZestInlineCompletionActionHandler {
    override fun doExecute(editor: Editor, caret: Caret?, service: ZestInlineCompletionService) {
        service.accept(editor, caret?.offset, AcceptType.FULL_COMPLETION)
    }
    
    override fun isEnabledForCaret(editor: Editor, caret: Caret, service: ZestInlineCompletionService): Boolean {
        return service.isInlineCompletionVisibleAt(editor, caret.offset) && 
               !service.isCompletionStartingWithIndentation()
    }
})
```

## Tab Handling Strategy

### 1. Smart Tab Behavior

```kotlin
class ZestTabBehaviorManager {
    
    fun shouldAcceptCompletion(
        editor: Editor, 
        offset: Int, 
        completion: ZestInlineCompletionItem
    ): Boolean {
        val document = editor.document
        val lineNumber = document.getLineNumber(offset)
        val lineStart = document.getLineStartOffset(lineNumber)
        val linePrefix = document.getText(TextRange(lineStart, offset))
        
        // Don't intercept TAB if:
        // 1. Line is blank and completion starts with indentation
        // 2. User is in the middle of typing indentation
        // 3. Completion is just whitespace
        
        return when {
            linePrefix.isBlank() && completion.insertText.startsWith(Regex("\\s+")) -> false
            completion.insertText.trim().isEmpty() -> false
            isInIndentationContext(editor, offset) -> false
            else -> true
        }
    }
    
    private fun isInIndentationContext(editor: Editor, offset: Int): Boolean {
        // Check if cursor is in indentation area of the line
        val document = editor.document
        val lineNumber = document.getLineNumber(offset)
        val lineStart = document.getLineStartOffset(lineNumber)
        val lineText = document.getText(TextRange(lineStart, document.getLineEndOffset(lineNumber)))
        val prefixLength = offset - lineStart
        
        return prefixLength <= lineText.length - lineText.trimStart().length
    }
}
```

### 2. Keymap Schema Definition

```kotlin
companion object {
    val DEFAULT_KEYMAP_SCHEMA = mapOf(
        "Zest.InlineCompletion.Trigger" to listOf(
            KeyboardShortcut.fromString("ctrl SPACE"),
            KeyboardShortcut.fromString("alt SLASH")
        ),
        "Zest.InlineCompletion.TabAccept" to listOf(
            KeyboardShortcut.fromString("TAB")
        ),
        "Zest.InlineCompletion.AcceptNextLine" to listOf(
            KeyboardShortcut.fromString("ctrl TAB")
        ),
        "Zest.InlineCompletion.AcceptNextWord" to listOf(
            KeyboardShortcut.fromString("ctrl RIGHT")
        ),
        "Zest.InlineCompletion.Dismiss" to listOf(
            KeyboardShortcut.fromString("ESCAPE")
        ),
        "Zest.InlineCompletion.CycleNext" to listOf(
            KeyboardShortcut.fromString("alt DOWN")
        ),
        "Zest.InlineCompletion.CyclePrevious" to listOf(
            KeyboardShortcut.fromString("alt UP")
        )
    )
    
    val ZEST_STYLE_KEYMAP_SCHEMA = mapOf(
        // Alternative bindings for power users
        "Zest.InlineCompletion.TabAccept" to listOf(
            KeyboardShortcut.fromString("ctrl TAB")
        ),
        "Zest.InlineCompletion.AcceptNextLine" to listOf(
            KeyboardShortcut.fromString("TAB")
        ),
        // ... other mappings
    )
}
```

## LLMService Integration

### 1. Completion Request Handler

```kotlin
class ZestCompletionProvider {
    private val llmService = LLMService.getInstance()
    
    suspend fun requestCompletion(
        context: CompletionContext
    ): ZestInlineCompletionList? {
        return try {
            val prompt = buildCompletionPrompt(context)
            val response = llmService.generateCompletion(prompt)
            parseCompletionResponse(response, context)
        } catch (e: Exception) {
            logger.warn("Completion request failed", e)
            null
        }
    }
    
    private fun buildCompletionPrompt(context: CompletionContext): String {
        return """
        Complete the following code:
        
        File: ${context.fileName}
        Language: ${context.language}
        
        Code before cursor:
        ${context.prefixCode}
        
        Code after cursor:
        ${context.suffixCode}
        
        Complete at cursor position:
        """.trimIndent()
    }
}

data class CompletionContext(
    val fileName: String,
    val language: String,
    val prefixCode: String,
    val suffixCode: String,
    val offset: Int,
    val manually: Boolean
)
```

### 2. Response Processing

```kotlin
class ZestCompletionProcessor {
    
    fun parseCompletionResponse(
        response: String, 
        context: CompletionContext
    ): ZestInlineCompletionList {
        
        val completions = extractCompletions(response)
        val items = completions.mapIndexed { index, completion ->
            ZestInlineCompletionItem(
                insertText = completion.text,
                replaceRange = calculateReplaceRange(completion, context),
                confidence = completion.confidence,
                metadata = CompletionMetadata(
                    model = "zest-model",
                    tokens = completion.text.split("\\s+".toRegex()).size,
                    latency = System.currentTimeMillis() - context.requestTime
                )
            )
        }
        
        return ZestInlineCompletionList(
            isIncomplete = items.size >= MAX_COMPLETIONS,
            items = items
        )
    }
}
```

## Implementation Phases

### Phase 1: Core Infrastructure (Week 1-2)
- [ ] Create `ZestInlineCompletionService`
- [ ] Implement basic `ZestInlineCompletionRenderer`
- [ ] Set up document change listeners
- [ ] Create basic data models
- [ ] Integrate with existing `LLMService`

### Phase 2: Action System (Week 3)
- [ ] Implement `ZestInlineCompletionAction` base class
- [ ] Create action promoter for priority handling
- [ ] Implement basic trigger action
- [ ] Add accept/dismiss actions
- [ ] Test basic completion flow

### Phase 3: Tab Handling (Week 4)
- [ ] Implement `ZestTabAccept` with smart behavior
- [ ] Add indentation detection logic
- [ ] Create `ZestTabBehaviorManager`
- [ ] Test tab behavior in various contexts
- [ ] Handle edge cases (empty lines, mixed indentation)

### Phase 4: Keymap Management (Week 5)
- [ ] Implement `ZestKeymapSettings` service
- [ ] Create keymap schemas (DEFAULT, ZEST_STYLE, CUSTOMIZE)
- [ ] Add keymap detection and application logic
- [ ] Create settings UI for keymap selection
- [ ] Test keymap switching and persistence

### Phase 5: Advanced Features (Week 6-7)
- [ ] Implement cycling through multiple completions
- [ ] Add partial acceptance (next word, next line)
- [ ] Create completion metadata and telemetry
- [ ] Add completion caching and debouncing
- [ ] Implement auto-trigger based on typing

### Phase 6: Polish and Testing (Week 8)
- [ ] Comprehensive testing across different file types
- [ ] Performance optimization
- [ ] Error handling and edge cases
- [ ] Documentation and user guides
- [ ] Plugin compatibility testing

## File Structure

```
src/main/kotlin/com/zps/zest/
├── completion/
│   ├── ZestInlineCompletionService.kt
│   ├── ZestInlineCompletionRenderer.kt
│   ├── ZestCompletionProvider.kt
│   ├── ZestCompletionProcessor.kt
│   └── data/
│       ├── ZestInlineCompletionList.kt
│       ├── ZestInlineCompletionItem.kt
│       └── CompletionContext.kt
├── actions/
│   ├── ZestInlineCompletionAction.kt
│   ├── ZestTabAccept.kt
│   ├── ZestTrigger.kt
│   ├── ZestAccept.kt
│   ├── ZestDismiss.kt
│   └── ZestCycleActions.kt
├── keymap/
│   ├── ZestKeymapSettings.kt
│   ├── ZestTabBehaviorManager.kt
│   └── ZestActionPromoter.kt
├── events/
│   ├── ZestDocumentListener.kt
│   ├── ZestCaretListener.kt
│   └── ZestEditorFactoryListener.kt
└── settings/
    ├── ZestCompletionSettingsPanel.kt
    └── ZestCompletionConfigurable.kt
```

## Plugin Configuration (plugin.xml)

```xml
<extensions defaultExtensionNs="com.intellij">
    <postStartupActivity implementation="com.zps.zest.completion.ZestCompletionStartupActivity"/>
    <editorFactoryListener implementation="com.zps.zest.events.ZestEditorFactoryListener"/>
    <actionPromoter order="first" implementation="com.zps.zest.keymap.ZestActionPromoter"/>
    
    <projectConfigurable
        parentId="editor"
        instance="com.zps.zest.settings.ZestCompletionConfigurable"
        id="com.zps.zest.settings.ZestCompletionConfigurable"
        displayName="Zest Completion"/>
</extensions>

<actions>
    <group id="Zest.InlineCompletion" text="Zest Inline Completion">
        <action id="Zest.InlineCompletion.Trigger"
                class="com.zps.zest.actions.ZestTrigger"
                text="Trigger Completion">
            <keyboard-shortcut first-keystroke="ctrl SPACE" keymap="$default"/>
            <keyboard-shortcut first-keystroke="alt SLASH" keymap="$default"/>
        </action>
        
        <action id="Zest.InlineCompletion.TabAccept"
                class="com.zps.zest.actions.ZestTabAccept"
                text="Accept Completion (Tab)">
            <keyboard-shortcut first-keystroke="TAB" keymap="$default"/>
        </action>
        
        <action id="Zest.InlineCompletion.AcceptNextLine"
                class="com.zps.zest.actions.ZestAcceptNextLine"
                text="Accept Next Line">
            <keyboard-shortcut first-keystroke="ctrl TAB" keymap="$default"/>
        </action>
        
        <action id="Zest.InlineCompletion.Dismiss"
                class="com.zps.zest.actions.ZestDismiss"
                text="Dismiss Completion">
            <keyboard-shortcut first-keystroke="ESCAPE" keymap="$default"/>
        </action>
    </group>
</actions>
```

## Testing Strategy

### Unit Tests
- [ ] Test completion request/response processing
- [ ] Test tab behavior logic
- [ ] Test keymap schema application
- [ ] Test action enablement conditions

### Integration Tests
- [ ] Test with various file types (Java, Kotlin, Python, etc.)
- [ ] Test with different indentation styles
- [ ] Test keymap switching
- [ ] Test LLMService integration

### User Acceptance Tests
- [ ] Test in real coding scenarios
- [ ] Test performance with large files
- [ ] Test conflict resolution with other plugins
- [ ] Test accessibility and usability

## Performance Considerations

1. **Debouncing**: Implement proper debouncing for auto-trigger
2. **Caching**: Cache completions for repeated requests
3. **Async Processing**: Keep all LLM calls asynchronous
4. **Memory Management**: Properly dispose of resources
5. **Rate Limiting**: Limit frequency of LLM requests

## Risk Mitigation

1. **LLM Service Failures**: Implement proper error handling and fallbacks
2. **Performance Issues**: Add timeout mechanisms and progress indicators
3. **Keymap Conflicts**: Detect and resolve conflicts with other plugins
4. **User Experience**: Provide clear feedback and easy disable options

This implementation plan provides a comprehensive roadmap for adding inline completion to Zest while leveraging the existing LLMService and following industry best practices for tab handling and keymap management.