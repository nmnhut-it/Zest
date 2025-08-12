# Test Plan UX Improvement & Code Guardian Refactor Plan

## Current State Analysis

**Existing Code Health Review Tool Window (`CodeGuardianReportPanel.kt`):**
- ✅ **Tabbed interface** - Already has `JBTabbedPane` for different days/reports
- ✅ **Responsive design** - Auto-switches between condensed/full modes
- ✅ **Async integration** - Uses background analyzers with progress 
- ✅ **Navigation support** - Can jump to methods/files
- ✅ **AI integration** - "Fix now with AI" buttons send prompts to chatbox

**Problems with Current Test Writing UX:**
- ❌ **Poor case-by-case flow** - Takes too much code and manual intervention
- ❌ **No testability analysis** - Can't identify which methods are highly testable
- ❌ **Scattered test plans** - No centralized view or markdown export
- ❌ **No progress indication** - User can't see analysis progress
- ❌ **Manual test creation** - No automated test scaffolding

## Proposed Solution: Editor Tab-Based Test Plans

### 1. **Open Test Plans as Editor Documents (Like Diffs)**

```kotlin
// Instead of cramming into tool window, open as editor tabs
class TestPlanEditorProvider : FileEditorProvider {
    
    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        return when {
            file.name.endsWith("_TestPlan.zest") -> TestPlanEditor(project, file)
            file.name == "TestPlanOverview.zest" -> TestPlanOverviewEditor(project, file)
            else -> throw IllegalArgumentException("Unsupported file type")
        }
    }
}
```

### 2. **Test Plan Architecture - Document-Based**

```
src/main/kotlin/com/zps/zest/codehealth/testplan/
├── editor/TestPlanEditor.kt              // Custom editor for test plans (like diff viewer)
├── editor/TestPlanOverviewEditor.kt      // Master overview editor
├── analysis/TestabilityAnalyzer.kt       // Async method testability scoring
├── generation/TestPlanGenerator.kt       // Markdown test plan generation
├── storage/VirtualTestPlanFile.kt        // Virtual files for test plans
└── models/TestPlanModels.kt             // Data structures
```

### 3. **Test Plan Editor Design (Full Editor Tab)**

#### **Split-Panel Editor Layout:**
```
┌─────────────────────────────────────────────────────────────┐
│ TestPlan: UserService.authenticate()        [🔧 Generate] [💾 Export] [⚙️] │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│ ┌─────────────────┬─────────────────────────────────────────┐ │
│ │   ANALYSIS      │           TEST PLAN DOCUMENT            │ │
│ │                 │                                         │ │
│ │ 🎯 Score: 85/100│ # Test Plan: UserService.authenticate() │ │
│ │                 │                                         │ │
│ │ 📊 Complexity: 3│ **Generated:** 2025-01-15              │ │
│ │ 🔗 Dependencies:│ **Testability Score:** 85/100          │ │
│ │   - UserRepo    │                                         │ │
│ │   - AuthService │ ## Overview                            │ │
│ │                 │ This method handles user authentication │ │
│ │ 🎭 Side Effects:│ with username/password validation...    │ │
│ │   - DB queries  │                                         │ │
│ │   - Logging     │ ## Test Cases                          │ │
│ │                 │                                         │ │
│ │ 📋 Mocks Needed:│ ### ✅ Happy Path Tests                 │ │
│ │ ✓ UserRepo      │ 1. **Valid credentials return token**  │ │
│ │ ✓ AuthService   │    - Setup: Valid user in database    │ │
│ │                 │    - Input: username="john", pwd="123" │ │
│ │ [Regenerate]    │    - Expected: Valid JWT token         │ │
│ │                 │                                         │ │
│ └─────────────────┴─────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────┘
```

### 4. **Testability Analysis Engine**

#### **Async Analysis with Progress:**
```kotlin
class TestabilityAnalyzer(private val project: Project) {
    
    suspend fun analyzeMethodTestability(methods: List<PsiMethod>): Flow<TestabilityResult> = flow {
        val total = methods.size
        methods.forEachIndexed { index, method ->
            val result = analyzeMethod(method)
            emit(result.copy(progress = (index + 1) / total.toFloat()))
            delay(50) // Don't block UI
        }
    }
    
    private fun analyzeMethod(method: PsiMethod): TestabilityResult {
        return TestabilityResult(
            method = method,
            score = calculateTestabilityScore(method),
            complexity = CyclomaticComplexityCalculator.calculate(method),
            dependencies = DependencyAnalyzer.analyze(method),
            mockingRequirements = MockingAnalyzer.analyze(method),
            sideEffects = SideEffectAnalyzer.analyze(method),
            recommendations = generateTestingRecommendations(method)
        )
    }
}
```

#### **Testability Scoring Criteria:**
- **🟢 Easy (80-100):** Pure functions, simple logic, no external dependencies
- **🟡 Medium (50-79):** Some dependencies, moderate complexity, mockable
- **🔴 Hard (0-49):** High complexity, file I/O, threading, static dependencies

### 5. **Intelligent Test Plan Generation**

#### **Markdown-Based Test Plans:**
```kotlin
class TestPlanGenerator {
    
    fun generateTestPlan(method: TestabilityResult): TestPlanDocument {
        return TestPlanDocument(
            method = method.method.name,
            file = "test_plans/${method.method.containingClass?.name}_TestPlan.md",
            content = buildMarkdownPlan(method)
        )
    }
    
    private fun buildMarkdownPlan(method: TestabilityResult): String = """
        # Test Plan: ${method.method.qualifiedName}
        
        **Generated:** ${LocalDateTime.now()}  
        **Testability Score:** ${method.score}/100  
        **Complexity:** ${method.complexity}
        
        ## Overview
        ${generateMethodOverview(method)}
        
        ## Test Cases
        
        ### ✅ Happy Path Tests
        ${generateHappyPathTests(method)}
        
        ### ⚠️ Edge Case Tests  
        ${generateEdgeCaseTests(method)}
        
        ### 🚨 Error Condition Tests
        ${generateErrorTests(method)}
        
        ## Setup Requirements
        ${generateSetupRequirements(method)}
        
        ## Mock Configuration
        ${generateMockSetup(method)}
        
        ## Implementation Template
        ```java
        ${generateTestTemplate(method)}
        ```
    """.trimIndent()
}
```

### 6. **Opening Test Plans from Code Health Tool Window**

#### **Integration with Code Health Panel:**
```kotlin
// In CodeGuardianReportPanel.kt - Add "Generate Test Plan" button
private fun createFullIssuePanel(issue: CodeHealthAnalyzer.HealthIssue, methodFqn: String): JComponent {
    // ... existing code ...
    
    val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT))
    buttonPanel.background = panel.background
    
    // Existing "Fix now with AI" button
    val fixButton = JButton("🔧 Fix now with AI")
    // ... existing fix button code ...
    buttonPanel.add(fixButton)
    
    // NEW: Generate Test Plan button
    val testPlanButton = JButton("🧪 Generate Test Plan")
    testPlanButton.foreground = JBUI.CurrentTheme.Link.Foreground.ENABLED
    testPlanButton.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    testPlanButton.addActionListener {
        openTestPlanEditor(methodFqn)
    }
    buttonPanel.add(testPlanButton)
    
    return wrapper
}

private fun openTestPlanEditor(methodFqn: String) {
    // Create virtual test plan file
    val virtualFile = TestPlanVirtualFileSystem.createTestPlanFile(methodFqn)
    
    // Open in editor tab (like diff viewer)
    ApplicationManager.getApplication().invokeLater {
        FileEditorManager.getInstance(project).openFile(virtualFile, true)
    }
}
```

#### **Virtual File System for Test Plans:**
```kotlin
class TestPlanVirtualFileSystem {
    
    companion object {
        fun createTestPlanFile(methodFqn: String): VirtualFile {
            val fileName = "${extractClassName(methodFqn)}_TestPlan.zest"
            
            return object : VirtualFile() {
                override fun getName() = fileName
                override fun getPath() = "testplan://$fileName"
                override fun isWritable() = true
                override fun isDirectory() = false
                override fun getFileSystem() = TestPlanFileSystem.getInstance()
                
                // Store test plan data
                private val testPlanData = TestPlanData(methodFqn)
                
                fun getTestPlanData(): TestPlanData = testPlanData
            }
        }
        
        fun createOverviewFile(): VirtualFile {
            return object : VirtualFile() {
                override fun getName() = "TestPlanOverview.zest"
                override fun getPath() = "testplan://TestPlanOverview.zest"
                // ... similar implementation
            }
        }
    }
}
```

### 7. **Markdown File Management**

#### **Test Plan Storage Structure:**
```
project_root/
├── .zest/                           
│   └── test_plans/                  # Generated test plans
│       ├── UserService_TestPlan.md
│       ├── PaymentProcessor_TestPlan.md
│       └── index.md                 # Master index
├── src/test/java/                   # Actual test files
│   └── [generated test stubs]      # Optional: generate test stubs
└── docs/testing/                    # Optional: documentation
    └── testability_report.md       # Overall project report
```

#### **Master Index Generation:**
```markdown
# Test Plans Index

**Generated:** 2025-01-15 14:30  
**Project:** Zest Plugin  
**Total Methods Analyzed:** 127  
**Test Plans Generated:** 15  

## Summary

| Testability | Count | Percentage |
|-------------|-------|------------|
| 🟢 Easy     | 23    | 18.1%      |
| 🟡 Medium   | 96    | 75.6%      |
| 🔴 Hard     | 8     | 6.3%       |

## Test Plans

### High Priority (Easy to Test)
- [UserService](UserService_TestPlan.md) - Score: 95/100
- [StringUtils](StringUtils_TestPlan.md) - Score: 92/100

### Medium Priority  
- [PaymentProcessor](PaymentProcessor_TestPlan.md) - Score: 67/100

### Low Priority (Hard to Test)
- [FileSystemWatcher](FileSystemWatcher_TestPlan.md) - Score: 34/100
```

### 8. **Integration with Agent Mode**

#### **Agent-Triggered Test Planning:**
```kotlin
// Add to langchain4j tools
class GenerateTestPlanTool(project: Project) : BaseCodeExplorationTool(
    project, 
    "generate_test_plan", 
    "Generate comprehensive test plan for a method or class with testability analysis"
) {
    
    override fun doExecute(parameters: JsonObject): ToolResult {
        val methodFqn = getRequiredString(parameters, "methodFqn")
        val includeSetup = getOptionalBoolean(parameters, "includeSetup", true)
        
        // Create virtual test plan file
        val virtualFile = TestPlanVirtualFileSystem.createTestPlanFile(methodFqn)
        
        // Generate test plan content
        val testPlan = testPlanGenerator.generateForMethod(methodFqn, includeSetup)
        
        // Auto-open in editor tab
        ApplicationManager.getApplication().invokeLater {
            FileEditorManager.getInstance(project).openFile(virtualFile, true)
        }
        
        return ToolResult.success(
            "Test plan generated and opened in editor tab: ${virtualFile.name}", 
            createMetadata()
        )
    }
}
```

#### **Agent Integration Flow:**
1. **Agent analyzes code** → Identifies methods needing tests
2. **Auto-triggers testability analysis** → Scores methods 
3. **Generates test plans** → Creates virtual files
4. **Opens editor tabs** → Full-screen test plan documents
5. **User reviews & refines** → Can edit/export from editor

### 9. **Configuration & Settings**

#### **Test Plan Settings:**
```kotlin
@State(name = "TestPlanSettings", storages = [Storage("testplan.xml")])
class TestPlanSettings : PersistentStateComponent<TestPlanSettings.State> {
    
    class State {
        var autoGenerateOnAnalysis = true
        var includeComplexityThreshold = 10
        var mockingFramework = "Mockito"
        var testFramework = "JUnit 5" 
        var exportDirectory = ".zest/test_plans"
        var includeImplementationTemplates = true
        var maxMethodsPerBatch = 50
    }
}
```

### 10. **Implementation Timeline**

#### **Phase 1: Virtual File System & Editor (Week 1)**
- [ ] Create `TestPlanVirtualFileSystem.kt` for virtual files
- [ ] Implement `TestPlanEditor.kt` with split-panel layout
- [ ] Add `TestPlanEditorProvider.kt` registration
- [ ] Create basic editor toolbar actions

#### **Phase 2: Analysis & Generation (Week 2)**  
- [ ] Implement `TestabilityAnalyzer.kt` with async progress
- [ ] Create `TestPlanGenerator.kt` with markdown output
- [ ] Add intelligent test case generation
- [ ] Build analysis panel UI component

#### **Phase 3: Integration (Week 3)**
- [ ] Add "Generate Test Plan" buttons to Code Health panel
- [ ] Implement editor-to-file export functionality
- [ ] Create overview editor for multiple test plans
- [ ] Add progress indication in editor

#### **Phase 4: Agent & Polish (Week 4)**
- [ ] Add langchain4j `GenerateTestPlanTool`
- [ ] Implement auto-opening from agent calls
- [ ] Add configuration and settings
- [ ] Polish UI and testing

## Implementation Status - Test Plan UX Improvement (COMPLETED ✅)

### 🎉 **ALL PHASES 1-4 COMPLETED!**

**✅ Phase 1: Code Guardian Refactor**
- [x] Individual issue editor with full-screen analysis and metadata panel
- [x] Project dashboard with overview editor showing health trends and metrics  
- [x] Refactored tool window with "📋 Open in Editor" and "🧪 Generate Test Plan" buttons
- [x] Virtual file system providing clean abstraction for health issues

**✅ Phase 2: Test Plan Editor System**
- [x] Split-panel test plan editor with analysis on left, test plan document on right
- [x] Virtual file system enabling test plans to open as proper editor tabs
- [x] Testability analyzer with smart scoring based on complexity, dependencies, and side effects
- [x] Mock test case generation covering happy path, edge case, and error condition tests
- [x] Editor providers registered in plugin.xml for seamless integration

**✅ Phase 3: Bulk Test Generation System**
- [x] TestGenerationService with async bulk test file generation and progress tracking
- [x] BulkTestGenerationDialog providing professional UI with framework selection and statistics
- [x] Multi-framework support (JUnit 4/5, TestNG, Mockito, EasyMock, PowerMock, JMockit)
- [x] Test code generation creating complete test classes with imports, setup, and test methods
- [x] Storage system with persistent test plan database and statistics

**✅ Phase 4: Tutorial & Agent Integration**
- [x] Interactive tutorial providing step-by-step guidance for the complete workflow
- [x] Agent integration via `GenerateTestPlanTool` for langchain4j autonomous agents
- [x] Tools menu actions with professional menu organization
- [x] Cleanup removing old cramped tool windows and obsolete features

---

## NEW PHASE 2: TypeScript LSP Integration for langchain4j Tools

### 🔍 **Analysis of Current langchain4j Tools System**

**Current State:**
- ✅ CodeExplorationToolRegistry with 15+ tools for Java/Kotlin analysis
- ✅ GenerateTestPlanTool successfully integrated 
- ✅ Tools categorized by DISCOVERY → ANALYSIS → DETAIL workflow
- ❌ Limited JavaScript/TypeScript support in existing tools
- ❌ No LSP integration for accurate TS/JS symbol resolution
- ❌ File-based analysis only (no semantic understanding)

### 🎯 **TypeScript LSP Integration Goals**

**What needs to be maintained/added:**
1. **LSP Client Integration** - Connect to TypeScript Language Server for semantic analysis
2. **Symbol Resolution** - Accurate function/class/interface definitions across TS/JS files
3. **Cross-reference Analysis** - Find usages, implementations, and relationships
4. **Type Information** - Extract type definitions and signatures for better test generation
5. **Import/Export Tracking** - Understand module dependencies and relationships
6. **Tool Extension** - Extend existing langchain4j tools to support TS/JS alongside Java/Kotlin

### 🏗️ **Implementation Plan for TypeScript LSP Support**

#### **Phase 2.1: LSP Client Foundation (Week 1)**
- [ ] Research IntelliJ TypeScript plugin LSP integration points
- [ ] Create `TypeScriptLSPClient.kt` for communicating with TS language server
- [ ] Implement `TypeScriptSymbolResolver.kt` for semantic analysis
- [ ] Add configuration for TypeScript project detection

#### **Phase 2.2: Tool Extensions (Week 2)**  
- [ ] Extend `SearchCodeTool` to include TypeScript/JavaScript files with semantic search
- [ ] Enhance `FindByNameTool` to resolve TS/JS symbols (functions, classes, interfaces)
- [ ] Update `FindRelationshipsTool` to track import/export relationships
- [ ] Modify `GetClassInfoTool` to work with TypeScript classes and interfaces

#### **Phase 2.3: Advanced Analysis (Week 3)**
- [ ] Extend `FindCallersTool` to find TS/JS function call sites
- [ ] Update `FindImplementationsTool` for interface implementations in TypeScript
- [ ] Enhance `FindUsagesTool` to track variable and function usage across TS/JS files
- [ ] Add `GetTypeInfoTool` for TypeScript type analysis

#### **Phase 2.4: Test Integration (Week 4)**
- [ ] Update `GenerateTestPlanTool` to support TypeScript/JavaScript functions
- [ ] Extend testability analysis for TS/JS code patterns
- [ ] Add support for popular JS/TS testing frameworks (Jest, Mocha, Jasmine, Vitest)
- [ ] Create TS/JS test templates and mock generation

### 🔧 **Technical Architecture**

#### **LSP Client Integration:**
```kotlin
@Service(Service.Level.PROJECT)
class TypeScriptLSPClient(private val project: Project) {
    
    private var lspConnection: LSPConnection? = null
    
    suspend fun findDefinition(file: VirtualFile, offset: Int): List<Location> {
        return lspConnection?.textDocument?.definition(
            DefinitionParams(
                TextDocumentIdentifier(file.url),
                Position(line, character)
            )
        ) ?: emptyList()
    }
    
    suspend fun findReferences(file: VirtualFile, offset: Int): List<Location> {
        return lspConnection?.textDocument?.references(
            ReferenceParams(
                TextDocumentIdentifier(file.url),
                Position(line, character),
                ReferenceContext(includeDeclaration = true)
            )
        ) ?: emptyList()
    }
    
    suspend fun getHover(file: VirtualFile, offset: Int): Hover? {
        return lspConnection?.textDocument?.hover(
            HoverParams(
                TextDocumentIdentifier(file.url),
                Position(line, character)
            )
        )
    }
}
```

#### **Enhanced Tool Implementation:**
```kotlin
// Example: Enhanced SearchCodeTool with TS/JS support
class SearchCodeTool(project: Project) : ThreadSafeCodeExplorationTool(
    project, "search_code", "Search for code patterns in Java/Kotlin/TypeScript/JavaScript"
) {
    
    private val tsLspClient = TypeScriptLSPClient.getInstance(project)
    
    override fun doExecuteInReadAction(parameters: JsonObject): ToolResult {
        val query = getRequiredString(parameters, "query")
        val includeTypes = getOptionalString(parameters, "file_types", "java,kt,ts,js")
        
        val results = mutableListOf<SearchResult>()
        
        // Existing Java/Kotlin search
        results.addAll(searchJavaKotlinCode(query))
        
        // NEW: TypeScript/JavaScript semantic search  
        if (includeTypes.contains("ts") || includeTypes.contains("js")) {
            results.addAll(searchTypeScriptCode(query))
        }
        
        return ToolResult.success(formatSearchResults(results), createMetadata())
    }
    
    private suspend fun searchTypeScriptCode(query: String): List<SearchResult> {
        // Use LSP for semantic search instead of just text matching
        return tsLspClient.searchSymbols(query)
    }
}
```

#### **TypeScript Test Plan Integration:**
```kotlin
// Enhanced GenerateTestPlanTool for TypeScript/JavaScript
class GenerateTestPlanTool(project: Project) : ThreadSafeCodeExplorationTool(
    project, "generate_test_plan", "Generate test plan for Java/Kotlin/TypeScript/JavaScript methods"
) {
    
    override fun doExecuteInReadAction(parameters: JsonObject): ToolResult {
        val methodFqn = getRequiredString(parameters, "methodFqn")
        
        // Detect if this is TS/JS file based on methodFqn format
        if (methodFqn.contains(":") && isTypeScriptFile(methodFqn)) {
            return generateTypeScriptTestPlan(methodFqn, parameters)
        } else {
            return generateJavaTestPlan(methodFqn, parameters)
        }
    }
    
    private fun generateTypeScriptTestPlan(methodFqn: String, parameters: JsonObject): ToolResult {
        val virtualFile = TestPlanVirtualFileSystem.createTestPlanFile(methodFqn, "typescript")
        
        // Open in editor with TypeScript-specific analysis
        ApplicationManager.getApplication().invokeLater {
            FileEditorManager.getInstance(project).openFile(virtualFile, true)
        }
        
        return ToolResult.success(buildString {
            appendLine("✅ TypeScript/JavaScript test plan generated")
            appendLine("**Function:** `$methodFqn`")
            appendLine("**Framework Support:** Jest, Mocha, Jasmine, Vitest")
            appendLine("**Features:** Type analysis, mock generation, async testing")
        }, createMetadata())
    }
}
```

### 📋 **Maintenance Requirements**

#### **Dependencies to Track:**
1. **IntelliJ TypeScript Plugin** - Ensure compatibility with latest versions
2. **TypeScript Language Server** - Monitor LSP protocol changes
3. **Node.js/npm Integration** - Handle different project structures (Vite, Webpack, etc.)
4. **Testing Framework Evolution** - Keep up with Jest, Vitest, Playwright updates

#### **Configuration Management:**
```kotlin
@State(name = "TypeScriptLSPSettings", storages = [Storage("typescript-lsp.xml")])
class TypeScriptLSPSettings : PersistentStateComponent<TypeScriptLSPSettings.State> {
    
    class State {
        var enableLSPIntegration = true
        var tsServerPath = "auto" // auto-detect or custom path
        var supportedFrameworks = listOf("Jest", "Mocha", "Jasmine", "Vitest")
        var includeNodeModules = false
        var maxSymbolResults = 100
        var lspTimeout = 5000L // milliseconds
    }
}
```

### 🎯 **Expected Benefits**

#### **For JavaScript/TypeScript Projects:**
- ✅ **Semantic Code Search** - Find functions, classes, interfaces by meaning, not just text
- ✅ **Accurate Symbol Resolution** - Jump to definitions across module boundaries  
- ✅ **Type-Aware Test Generation** - Generate tests that respect TypeScript types
- ✅ **Framework Integration** - Support for modern JS/TS testing ecosystems
- ✅ **Cross-Language Analysis** - Analyze projects mixing Java/Kotlin with TypeScript

#### **For Agent Workflows:**
- 🤖 **Better Context Understanding** - Agents can understand TS/JS project structure
- 🔍 **Semantic Tool Usage** - More accurate code exploration and analysis
- 📝 **Improved Test Planning** - Generate realistic tests for async/Promise-based code
- 🚀 **Modern Web Development** - Support for React, Vue, Angular, Node.js projects

### 📈 **Success Metrics**

- **Tool Coverage**: All 15+ langchain4j tools support TypeScript/JavaScript
- **Accuracy**: LSP-based symbol resolution vs text-based search accuracy comparison
- **Performance**: LSP response times under 1 second for typical operations
- **Test Quality**: Generated test plans cover async patterns, mocking, and modern JS/TS idioms
- **User Adoption**: Agents successfully analyze and improve TS/JS codebases

## NEW PHASE 2B: Chat Editor Migration (COMPLETED ✅)

### 🎯 **Chat Window → Editor Tab Migration**

Following the same UX improvement principles as Test Plans and Code Health, the ZPS Chat has been migrated from a cramped tool window to full-screen editor tabs.

**✅ Implementation Complete:**
- **ZestChatVirtualFileSystem** - Virtual file system for chat sessions
- **ZestChatEditor** - Full-screen chat editor with JCEF browser integration  
- **ZestChatEditorProvider** - Editor provider registration for seamless integration
- **OpenChatInEditorAction** - Action to open chat in editor tabs (Tools menu + ZestGroup)
- **Full cleanup** - All old tool window code and references completely removed

**🎯 Enhanced Chat Experience:**
- **Full-screen real estate** - Chat opens in spacious editor tabs instead of narrow tool window
- **Multiple chat sessions** - Each session gets its own editor tab with unique session IDs
- **Toolbar integration** - Refresh, new session, dev tools, and mode switching actions
- **JCEF browser integration** - Complete web browser functionality maintained
- **Session persistence** - Chat sessions persist across IDE restarts

**📍 Access Points:**
- **Right-click menu**: Right-click in editor → Zest → 💬 Open Chat in Editor
- **Tools menu**: Tools → 💬 ZPS Chat (Editor)

**🔄 Migration Benefits:**
- **Consistent UX** - Same document-first approach as Test Plans and Code Health
- **Better conversation flow** - More space for complex AI interactions
- **Multi-session support** - Work on different tasks in separate chat tabs
- **Familiar IntelliJ UX** - Uses standard editor tab behavior users expect

### 🗑️ **Complete Cleanup Summary:**

**Removed Files:**
- ✅ `WebBrowserToolWindow.java` - Old tool window factory completely deleted
- ✅ All references to "ZPS Chat" tool window removed from actions
- ✅ All commented out tool windows removed from plugin.xml
- ✅ Old test writing and refactoring tool window references cleaned

**Updated Files:**
- ✅ `ChatboxUtilities.java` - Now uses editor-based chat
- ✅ `SendToBrowserAction.java` - Opens chat in editor
- ✅ `SendCodeReviewToChatBox.java` - Uses editor tabs
- ✅ `SendTestPipelineToChatBox.java` - Uses editor tabs
- ✅ `ToggleDevToolsAction.java` - Works with editor-based chat
- ✅ `plugin.xml` - All old tool windows and commented code removed

---

### 🎯 **Complete User Experience (Fully Functional):**

1. **Run Code Health Analysis** → Issues appear in Code Guardian tool window
2. **Click "📋 Open in Editor"** → Issue opens in full-screen editor with detailed analysis
3. **Click "🧪 Generate Test Plan"** → Test plan opens in editor with:
   - **Left panel:** Testability score, complexity, dependencies, mocking requirements
   - **Right panel:** Generated test cases, setup requirements, implementation template
4. **Edit test plans** in spacious editor environment
5. **Tools → Zest Test Generation → Generate All Tests** → Bulk generate all test files
6. **Tutorial available** → Tools → Zest Test Generation → Tutorial for guided learning
7. **Agent support** → AI agents can auto-generate test plans via `generate_test_plan` tool

## Expected Benefits

### **For Users (✅ ACHIEVED):**
- ✅ **Full-screen real estate** - Editor tabs provide maximum space (like diff viewer)
- ✅ **Familiar interface** - Uses standard IntelliJ editor tabs, not cramped tool windows
- ✅ **Side-by-side analysis** - Split panel with testability insights + test plan document
- ✅ **Direct editing** - Edit test plans in-place like any document
- ⏳ **Easy export** - Export to `.md` files or copy to clipboard (Phase 3)
- ⏳ **Agent integration** - Auto-opens test plans from agent analysis (Phase 4)

### **For Code Quality (✅ IN PROGRESS):**
- 🎯 **Higher test coverage** - Focus on most testable methods first with visual scoring ✅
- 🔍 **Better test design** - Generated plans include comprehensive edge cases ✅
- 📊 **Testability metrics** - Track code testability over time in dedicated editors ✅
- ⏳ **AI-assisted testing** - Seamless agent workflow: analyze → generate → open editor (Phase 4)
- 📋 **Professional documentation** - Test plans look like proper documents, not tool window cramping ✅

## Key Advantage: Document-First Approach ✅ ACHIEVED

The implementation successfully treats test plans as **first-class documents** that open in full editor tabs, just like how IntelliJ handles diffs, markdown files, or any other document. This provides:

- **Maximum screen space** for complex test planning ✅
- **Professional document feel** rather than cramped UI panels ✅
- **Standard IntelliJ UX** that users already know ✅
- **Easy sharing** - test plans are proper documents, not UI screenshots ✅

The Code Health tool window now serves as a **launching pad** with working "🧪 Generate Test Plan" buttons, while the actual work happens in spacious editor tabs.

## Code Guardian Refactor Plan

### 1. **Refactor Code Health Issues to Use Editor Tabs**

#### **Current Problem:**
Code Health issues are displayed in cramped tool window panels, making it hard to read detailed issue descriptions, impacts, and suggested fixes.

#### **Solution: Issue Detail Editor**
```kotlin
// New editor for individual code health issues
class CodeHealthIssueEditor(
    private val project: Project,
    private val virtualFile: CodeHealthIssueVirtualFile
) : FileEditor {
    
    override fun getComponent(): JComponent {
        return createSplitPanelEditor()
    }
    
    private fun createSplitPanelEditor(): JComponent {
        val splitter = JBSplitter(false, 0.3f) // 30% left, 70% right
        
        // Left panel: Issue metadata & actions
        splitter.firstComponent = createIssueMetadataPanel()
        
        // Right panel: Full issue details with rich formatting
        splitter.secondComponent = createIssueDetailsPanel()
        
        return splitter
    }
}
```

#### **Issue Detail Editor Layout:**
```
┌─────────────────────────────────────────────────────────────┐
│ CodeHealth Issue: UserService.authenticate()    [🔧 Fix] [📝 Test] [🚫 Dismiss] │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│ ┌─────────────────┬─────────────────────────────────────────┐ │
│ │   METADATA      │         ISSUE DETAILS                   │ │
│ │                 │                                         │ │
│ │ 🎯 Severity: 4/5│ # Security Vulnerability                │ │
│ │ 📂 Category:    │ ## SQL Injection Risk                   │ │
│ │    Security     │                                         │ │
│ │                 │ ### Description                         │ │
│ │ 📅 Found:       │ The `authenticate` method constructs    │ │
│ │    2025-01-15   │ SQL queries using string concatenation  │ │
│ │                 │ which opens the door for SQL injection │ │
│ │ 🏥 Health: 34/100│ attacks...                             │ │
│ │                 │                                         │ │
│ │ 📊 Impact:      │ ### Impact Analysis                     │ │
│ │ • 15 callers    │ - **Security Risk:** High              │ │
│ │ • 3 files       │ - **Data Loss:** Potential complete... │ │
│ │ • Critical path │                                         │ │
│ │                 │ ### Suggested Fix                       │ │
│ │ [🔧 AI Fix]     │ ```java                                 │ │
│ │ [📝 Test Plan]  │ // Use PreparedStatement instead       │ │
│ │ [📍 Navigate]   │ PreparedStatement stmt = conn.prepare(  │ │
│ │                 │     "SELECT * FROM users WHERE..."     │ │
│ └─────────────────┴─────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────┘
```

### 2. **Enhanced Code Health Overview Editor**

#### **Project-Wide Health Dashboard:**
```kotlin
class CodeHealthOverviewEditor(private val project: Project) : FileEditor {
    
    private fun createDashboard(): JComponent {
        return JBSplitter(true, 0.25f).apply {
            // Top 25%: Summary metrics
            firstComponent = createHealthSummaryPanel()
            
            // Bottom 75%: Tabbed detailed views
            secondComponent = createDetailedViewTabs()
        }
    }
    
    private fun createDetailedViewTabs(): JComponent {
        return JBTabbedPane().apply {
            addTab("🚨 Critical Issues", createCriticalIssuesPanel())
            addTab("📊 Trends", createHealthTrendsPanel()) 
            addTab("📂 By File", createFileHealthPanel())
            addTab("👥 By Author", createAuthorHealthPanel())
            addTab("🔄 Recent Changes", createRecentChangesPanel())
        }
    }
}
```

### 3. **Quick Test Generation from Database**

#### **Test Database Integration:**
```kotlin
@Service(Service.Level.PROJECT)
class TestGenerationService(private val project: Project) {
    
    private val testDatabase = TestPlanStorage.getInstance(project)
    
    /**
     * Generates all pending test files from stored test plans
     */
    suspend fun generateAllTests(
        framework: TestFramework = TestFramework.JUNIT5,
        mockingFramework: MockingFramework = MockingFramework.MOCKITO,
        progressCallback: (TestGenerationProgress) -> Unit = {}
    ) {
        val pendingPlans = testDatabase.getAllPendingTestPlans()
        val total = pendingPlans.size
        
        pendingPlans.forEachIndexed { index, plan ->
            progressCallback(TestGenerationProgress(
                current = index + 1,
                total = total,
                currentPlan = plan.methodFqn,
                status = "Generating test for ${plan.methodFqn}..."
            ))
            
            generateTestFile(plan, framework, mockingFramework)
            testDatabase.markTestGenerated(plan.id)
            
            delay(100) // Don't overwhelm the system
        }
    }
    
    private suspend fun generateTestFile(
        plan: TestPlanData,
        framework: TestFramework,
        mockingFramework: MockingFramework
    ) {
        val testCode = TestCodeGenerator.generateTestClass(plan, framework, mockingFramework)
        val testFile = createTestFile(plan.methodFqn, testCode)
        
        // Auto-format and organize imports
        ApplicationManager.getApplication().invokeLater {
            CodeStyleManager.getInstance(project).reformat(testFile)
        }
    }
}
```

#### **Bulk Test Generation Dialog:**
```kotlin
class BulkTestGenerationDialog(private val project: Project) : DialogWrapper(project) {
    
    private lateinit var frameworkCombo: ComboBox<TestFramework>
    private lateinit var mockingCombo: ComboBox<MockingFramework>
    private lateinit var targetDirField: TextFieldWithBrowseButton
    private lateinit var progressBar: JProgressBar
    private lateinit var statusLabel: JLabel
    private lateinit var testPlansList: JBList<TestPlanData>
    
    override fun createCenterPanel(): JComponent {
        return panel {
            row("Test Framework:") { 
                frameworkCombo = comboBox(TestFramework.entries.toTypedArray())
                    .component.apply { selectedItem = TestFramework.JUNIT5 }
            }
            row("Mocking Framework:") { 
                mockingCombo = comboBox(MockingFramework.entries.toTypedArray())
                    .component.apply { selectedItem = MockingFramework.MOCKITO }
            }
            row("Target Directory:") {
                targetDirField = textFieldWithBrowseButton("src/test/java")
            }
            
            row {
                scrollPane(JBList(loadPendingTestPlans())).apply {
                    preferredSize = Dimension(600, 300)
                    testPlansList = component as JBList<TestPlanData>
                }
            }.rowComment("${loadPendingTestPlans().size} test plans ready for generation")
            
            row {
                progressBar = progressBar()
                statusLabel = label("Ready to generate tests")
            }
        }
    }
    
    override fun doOKAction() {
        startTestGeneration()
    }
    
    private fun startTestGeneration() {
        val service = TestGenerationService.getInstance(project)
        
        lifecycleScope.launch {
            try {
                service.generateAllTests(
                    framework = frameworkCombo.selectedItem as TestFramework,
                    mockingFramework = mockingCombo.selectedItem as MockingFramework
                ) { progress ->
                    SwingUtilities.invokeLater {
                        progressBar.value = (progress.current * 100) / progress.total
                        statusLabel.text = progress.status
                    }
                }
                
                SwingUtilities.invokeLater {
                    Messages.showInfoMessage(
                        project,
                        "Successfully generated ${loadPendingTestPlans().size} test files!",
                        "Test Generation Complete"
                    )
                    close(OK_EXIT_CODE)
                }
                
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    Messages.showErrorDialog(
                        project, 
                        "Error generating tests: ${e.message}",
                        "Test Generation Failed"
                    )
                }
            }
        }
    }
}
```

### 4. **User Tutorial System**

#### **Interactive Tutorial Overlay:**
```kotlin
@Service(Service.Level.PROJECT) 
class TestGenerationTutorialService(private val project: Project) {
    
    fun showBulkTestGenerationTutorial() {
        val steps = listOf(
            TutorialStep(
                title = "Welcome to Bulk Test Generation!",
                description = "Generate all your pending test files with a single click.",
                target = null,
                action = "Let's get started!"
            ),
            TutorialStep(
                title = "Step 1: Analyze Your Code",
                description = "First, run Code Health Review to identify methods that need tests.",
                target = "codehealth.analyze.button",
                action = "Click 'Run Analysis' in Code Guardian tool window"
            ),
            TutorialStep(
                title = "Step 2: Generate Test Plans", 
                description = "Click '🧪 Generate Test Plan' on methods with issues.",
                target = "codehealth.generate.testplan.button",
                action = "This creates comprehensive test plans in our database"
            ),
            TutorialStep(
                title = "Step 3: Review Test Plans",
                description = "Test plans open in editor tabs where you can review and modify them.",
                target = "testplan.editor.tab",
                action = "Make any adjustments to the generated test cases"
            ),
            TutorialStep(
                title = "Step 4: Bulk Generate Tests",
                description = "Use Tools → Zest → Generate All Tests to create actual test files.",
                target = "tools.zest.generate.all.tests",
                action = "Choose your test framework and target directory"
            ),
            TutorialStep(
                title = "Complete!",
                description = "All your test files are now generated and ready to run!",
                target = null,
                action = "Start writing more specific test cases as needed"
            )
        )
        
        TutorialManager.getInstance(project).startTutorial("bulk-test-generation", steps)
    }
}
```

#### **Tutorial Action Integration:**
```kotlin
// Add to Tools menu
class ShowTestGenerationTutorialAction : AnAction(
    "🎓 Test Generation Tutorial",
    "Learn how to generate tests from Code Health analysis",
    AllIcons.General.Help
) {
    
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        TestGenerationTutorialService.getInstance(project)
            .showBulkTestGenerationTutorial()
    }
}

// Register in plugin.xml
<action id="zest.tutorial.test.generation" 
        class="com.zps.zest.codehealth.actions.ShowTestGenerationTutorialAction"
        text="Test Generation Tutorial"
        description="Learn bulk test generation workflow">
    <add-to-group group-id="ToolsMenu" anchor="last"/>
</action>
```

### 5. **Enhanced Menu Actions**

#### **Tools Menu Integration:**
```kotlin
// Add comprehensive test generation actions
<group id="ZestTestGeneration" text="🧪 Zest Test Generation" popup="true">
    <action id="zest.generate.all.tests" 
            class="com.zps.zest.codehealth.actions.GenerateAllTestsAction"
            text="Generate All Tests from Plans"
            description="Generate test files from all stored test plans"/>
    
    <action id="zest.open.test.overview"
            class="com.zps.zest.codehealth.actions.OpenTestOverviewAction" 
            text="Open Test Plans Overview"
            description="View all generated test plans"/>
            
    <separator/>
    
    <action id="zest.tutorial.test.generation"
            class="com.zps.zest.codehealth.actions.ShowTestGenerationTutorialAction"
            text="🎓 Test Generation Tutorial"
            description="Learn the test generation workflow"/>
            
    <add-to-group group-id="ToolsMenu" anchor="last"/>
</group>
```

### 6. **Updated Implementation Timeline**

#### **Phase 1: Code Guardian Refactor (Week 1)** ✅ COMPLETED
- [x] Create `CodeHealthIssueEditor.kt` for individual issues
- [x] Implement `CodeHealthOverviewEditor.kt` for project dashboard
- [x] Refactor existing tool window to use "Open in Editor" buttons
- [x] Add virtual file system for health issues

#### **Phase 2: Test Plan Editor System (Week 2)** ✅ COMPLETED
- [x] Create `TestPlanVirtualFileSystem.kt` for virtual files
- [x] Implement `TestPlanEditor.kt` with split-panel layout
- [x] Add `TestPlanEditorProvider.kt` registration
- [x] Build testability analysis engine

#### **Phase 3: Bulk Test Generation (Week 3)** ✅ COMPLETED
- [x] Implement `TestGenerationService.kt` with async processing
- [x] Create `BulkTestGenerationDialog.kt` with progress tracking
- [x] Add test code generation with multiple framework support
- [x] Build test plan database storage system

#### **Phase 4: Tutorial & Polish (Week 4)** ✅ COMPLETED
- [x] Implement `TestGenerationTutorialService.kt`
- [x] Create interactive tutorial overlay system
- [x] Add comprehensive Tools menu actions
- [x] Add langchain4j `GenerateTestPlanTool` for agent integration
- [x] Clean up old cramped tool windows and actions

## Complete Workflow: Code Analysis → Test Generation

### **User Journey:**
1. **🔍 Analyze Code** - Run Code Guardian → Issues found
2. **📋 Review Issues** - Click issue → Opens in full editor tab
3. **🧪 Generate Test Plan** - Click "Generate Test Plan" → Opens test plan editor
4. **✏️ Refine Plan** - Edit test plan in spacious editor
5. **⚡ Bulk Generate** - Tools → Generate All Tests → All test files created
6. **🎯 Run Tests** - Standard IntelliJ test runner

### **Tutorial Integration:**
- **First-time users** get guided tutorial
- **Tooltips and hints** throughout the workflow
- **Progress indicators** for bulk operations
- **Success notifications** with next steps

This creates a comprehensive, professional test generation system that scales from individual methods to entire codebases!