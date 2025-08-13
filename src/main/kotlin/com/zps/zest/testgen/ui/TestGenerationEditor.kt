package com.zps.zest.testgen.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorLocation
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.*
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.zps.zest.testgen.TestGenerationService
import com.zps.zest.testgen.model.*
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.util.concurrent.CompletableFuture
import java.beans.PropertyChangeListener
import javax.swing.*
import javax.swing.border.EmptyBorder
import com.intellij.openapi.util.Key
import com.intellij.ui.JBColor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType

/**
 * Test Generation Editor with tab-based interface for managing AI-powered test generation
 */
class TestGenerationEditor(
    private val project: Project,
    private val virtualFile: TestGenerationVirtualFile
) : UserDataHolderBase(), FileEditor {
    
    companion object {
        private val LOG = Logger.getInstance(TestGenerationEditor::class.java)
        private val DIALOG_SHOWN_KEY = Key.create<Boolean>("dialog_shown")
    }
    
    // Track dialog state
    private fun getUserData(key: String): Boolean? {
        return getUserData(Key.create<Boolean>(key))
    }
    
    private fun putUserData(key: String, value: Boolean) {
        putUserData(Key.create<Boolean>(key), value)
    }
    
    private val testGenService = project.getService(TestGenerationService::class.java)
    private val component: JPanel
    private var currentSession: TestGenerationSession? = null
    private val progressBar = JProgressBar()
    private val statusLabel = JBLabel("Ready to generate tests")
    // Track which sessions have shown the scenario selection dialog
    private val shownSelectionDialogs = mutableSetOf<String>()
    
    // UI Components that need updating when data changes
    private var tabbedPane: JBTabbedPane? = null
    private var planTab: JComponent? = null
    private var testsTab: JComponent? = null
    private var validationTab: JComponent? = null
    private var progressTab: JComponent? = null
    private var streamingTab: JComponent? = null
    
    // Panels that need content updates
    private var overviewPanel: JPanel? = null
    private var sessionInfoPanel: JPanel? = null
    private var planContentPanel: JPanel? = null
    private var testsContentPanel: JPanel? = null
    private var validationContentPanel: JPanel? = null
    private var progressContentPanel: JPanel? = null
    private var codePreviewPanel: JPanel? = null
    private var streamingContentPanel: JPanel? = null
    private var streamingTextArea: JBTextArea? = null
    
    init {
        component = JPanel(BorderLayout())
        component.background = UIUtil.getPanelBackground()
        setupUI()
        setupProgressListener()
        updateDataDependentComponents()
    }
    
    override fun getComponent(): JComponent = component
    
    override fun getPreferredFocusedComponent(): JComponent? = component
    
    override fun getName(): String = "Test Generation"
    
    override fun isValid(): Boolean = true
    
    override fun isModified(): Boolean = false
    
    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
    
    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}
    
    override fun getCurrentLocation(): FileEditorLocation? = null
    
    override fun dispose() {
        currentSession?.let { session ->
            testGenService.removeProgressListener(session.sessionId, ::onProgress)
        }
    }
    
    override fun setState(state: FileEditorState) {}
    
    override fun getFile(): VirtualFile = virtualFile
    
    private fun setupUI() {
        // Add toolbar at top
        component.add(createToolbar(), BorderLayout.NORTH)
        
        // Add status bar at bottom
        component.add(createStatusBar(), BorderLayout.SOUTH)
        
        // Create overview panel with code preview
        val topPanel = JPanel(BorderLayout(10, 5))
        topPanel.background = UIUtil.getPanelBackground()
        topPanel.preferredSize = Dimension(800, 150)
        topPanel.minimumSize = Dimension(600, 100)
        
        // Create horizontal splitter for overview and code preview
        val horizontalSplitter = JBSplitter(false, 0.35f) // 35% overview, 65% code preview
        
        // Create and store overview panel (left side)
        overviewPanel = JPanel(GridBagLayout())
        overviewPanel!!.background = UIUtil.getPanelBackground()
        overviewPanel!!.border = EmptyBorder(10, 10, 10, 10)
        overviewPanel!!.preferredSize = Dimension(300, 130)
        horizontalSplitter.firstComponent = JBScrollPane(overviewPanel)
        
        // Create code preview panel (right side)
        codePreviewPanel = JPanel(BorderLayout())
        codePreviewPanel!!.background = UIUtil.getPanelBackground()
        codePreviewPanel!!.border = EmptyBorder(5, 5, 5, 5)
        codePreviewPanel!!.preferredSize = Dimension(500, 130)
        horizontalSplitter.secondComponent = codePreviewPanel
        
        topPanel.add(horizontalSplitter, BorderLayout.CENTER)
        
        // Create tabbed pane
        tabbedPane = JBTabbedPane()
        
        // Add main content with tabs - more space for tabs
        val splitter = JBSplitter(true, 0.18f) // 18% top for overview+preview, 82% for tabs
        splitter.firstComponent = topPanel
        splitter.secondComponent = tabbedPane
        
        component.add(splitter, BorderLayout.CENTER)
        
        // Setup tabs (empty initially)
        setupTabs()
    }
    
    private fun setupTabs() {
        // Create content panels for tabs
        planContentPanel = JPanel(BorderLayout())
        planContentPanel!!.background = UIUtil.getPanelBackground()
        planContentPanel!!.border = EmptyBorder(15, 15, 15, 15)
        
        testsContentPanel = JPanel(BorderLayout())
        testsContentPanel!!.background = UIUtil.getPanelBackground()
        testsContentPanel!!.border = EmptyBorder(15, 15, 15, 15)
        
        validationContentPanel = JPanel(BorderLayout())
        validationContentPanel!!.background = UIUtil.getPanelBackground()
        validationContentPanel!!.border = EmptyBorder(15, 15, 15, 15)
        
        progressContentPanel = JPanel(BorderLayout())
        progressContentPanel!!.background = UIUtil.getPanelBackground()
        progressContentPanel!!.border = EmptyBorder(15, 15, 15, 15)
        
        streamingContentPanel = JPanel(BorderLayout())
        streamingContentPanel!!.background = UIUtil.getPanelBackground()
        streamingContentPanel!!.border = EmptyBorder(15, 15, 15, 15)
        
        // Create scrollable tabs
        planTab = JBScrollPane(planContentPanel)
        testsTab = JBScrollPane(testsContentPanel)
        validationTab = JBScrollPane(validationContentPanel)
        progressTab = progressContentPanel
        streamingTab = JBScrollPane(streamingContentPanel)
        
        tabbedPane!!.addTab("📝 Raw AI Response", streamingTab)
        tabbedPane!!.addTab("📋 Test Plan", planTab)
        tabbedPane!!.addTab("🧪 Generated Tests", testsTab)
        tabbedPane!!.addTab("✅ Validation", validationTab)
        tabbedPane!!.addTab("📊 Progress", progressTab)
    }
    
    private fun updateDataDependentComponents() {
        SwingUtilities.invokeLater {
            updateOverviewPanel()
            updateCodePreviewPanel()
            updateStreamingTab()
            updateTestPlanTab()
            updateGeneratedTestsTab()
            updateValidationTab()
            updateProgressTab()
            component.revalidate()
            component.repaint()
        }
    }
    
    private fun createToolbar(): JComponent {
        val actionGroup = DefaultActionGroup()
        
        // Generate tests action
        actionGroup.add(object : AnAction("Generate Tests", "Start AI-powered test generation", AllIcons.Actions.Execute) {
            override fun actionPerformed(e: AnActionEvent) {
                startTestGeneration()
            }
            
            override fun update(e: AnActionEvent) {
                val presentation = e.presentation
                presentation.isEnabled = currentSession?.status?.isActive != true
                presentation.text = if (currentSession?.status?.isActive == true) "Generating..." else "Generate Tests"
                presentation.description = if (currentSession?.status?.isActive == true) 
                    "Test generation in progress" else "Start AI-powered test generation"
            }
        })
        
        // Cancel generation action
        actionGroup.add(object : AnAction("Stop", "Stop test generation", AllIcons.Actions.Suspend) {
            override fun actionPerformed(e: AnActionEvent) {
                cancelTestGeneration()
            }
            
            override fun update(e: AnActionEvent) {
                val presentation = e.presentation
                presentation.isEnabled = currentSession?.status?.isActive == true
                presentation.isVisible = currentSession?.status?.isActive == true
                presentation.text = "Stop Generation"
                presentation.description = "Cancel the current test generation process"
            }
        })
        
        actionGroup.addSeparator()
        
        // Write tests to files action
        actionGroup.add(object : AnAction("Save All Tests", "Write all generated tests to files", AllIcons.Actions.MenuSaveall) {
            override fun actionPerformed(e: AnActionEvent) {
                writeTestsToFiles()
            }
            
            override fun update(e: AnActionEvent) {
                val presentation = e.presentation
                val hasTests = currentSession?.generatedTests?.isNotEmpty() == true
                val isCompleted = currentSession?.status?.isCompleted == true
                presentation.isEnabled = isCompleted && hasTests
                presentation.text = if (hasTests) 
                    "Save All (${currentSession?.generatedTests?.size ?: 0} tests)" 
                else "Save All Tests"
                presentation.description = when {
                    !isCompleted -> "Complete test generation first"
                    !hasTests -> "No tests to save"
                    else -> "Write ${currentSession?.generatedTests?.size ?: 0} generated tests to files"
                }
            }
        })
        
        // Copy to clipboard action
        actionGroup.add(object : AnAction("Copy Tests", "Copy all tests to clipboard", AllIcons.Actions.Copy) {
            override fun actionPerformed(e: AnActionEvent) {
                copyAllTestsToClipboard()
            }
            
            override fun update(e: AnActionEvent) {
                val presentation = e.presentation
                val hasTests = currentSession?.generatedTests?.isNotEmpty() == true
                presentation.isEnabled = hasTests
                presentation.text = "Copy to Clipboard"
                presentation.description = if (hasTests)
                    "Copy all generated test code to clipboard"
                else "No tests to copy"
            }
        })
        
        actionGroup.addSeparator()
        
        // Refresh action
        actionGroup.add(object : AnAction("Refresh", "Refresh session data", AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) {
                refreshSessionData()
            }
            
            override fun update(e: AnActionEvent) {
                e.presentation.text = "Refresh View"
                e.presentation.description = "Refresh the current session data and UI"
            }
        })
        
        val toolbar = ActionManager.getInstance().createActionToolbar(
            "TestGenerationEditor", 
            actionGroup, 
            true
        )
        toolbar.targetComponent = component
        
        return toolbar.component
    }
    
    private fun createStatusBar(): JComponent {
        val statusPanel = JPanel(BorderLayout())
        statusPanel.background = UIUtil.getPanelBackground()
        statusPanel.border = EmptyBorder(5, 10, 5, 10)
        
        progressBar.isVisible = false
        progressBar.preferredSize = Dimension(200, 20)
        
        statusLabel.foreground = UIUtil.getLabelForeground()
        
        statusPanel.add(statusLabel, BorderLayout.WEST)
        statusPanel.add(progressBar, BorderLayout.EAST)
        
        return statusPanel
    }
    
    private fun updateOverviewPanel() {
        overviewPanel?.removeAll()
        
        val gbc = GridBagConstraints()
        gbc.insets = JBUI.insets(5)
        gbc.anchor = GridBagConstraints.WEST
        
        // Title
        gbc.gridx = 0
        gbc.gridy = 0
        gbc.gridwidth = 2
        val titleLabel = JBLabel("🧪 AI Test Generation")
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, 18f)
        overviewPanel!!.add(titleLabel, gbc)
        
        // Show selected code info
        gbc.gridy = 1
        gbc.gridwidth = 2
        val fileInfoLabel = JBLabel("📄 File: ${virtualFile.targetFile.name}")
        fileInfoLabel.font = fileInfoLabel.font.deriveFont(Font.BOLD, 14f)
        overviewPanel!!.add(fileInfoLabel, gbc)
        
        // Show selection info
        gbc.gridy = 2
        val selectionInfo = if (virtualFile.selectionStart != virtualFile.selectionEnd) {
            val chars = virtualFile.selectionEnd - virtualFile.selectionStart
            "🎯 Selection: ${chars} characters (lines ${getLineNumber(virtualFile.selectionStart)}-${getLineNumber(virtualFile.selectionEnd)})"
        } else {
            "📄 Entire file selected"
        }
        val selectionLabel = JBLabel(selectionInfo)
        selectionLabel.foreground = UIUtil.getLabelForeground()
        overviewPanel!!.add(selectionLabel, gbc)
        
        // Session info
        if (currentSession != null) {
            gbc.gridy = 3
            gbc.gridwidth = 1
            overviewPanel!!.add(JBLabel("Session:"), gbc)
            
            gbc.gridx = 1
            val sessionLabel = JBLabel(currentSession!!.sessionId.substring(0, 8) + "...")
            sessionLabel.foreground = UIUtil.getInactiveTextColor()
            overviewPanel!!.add(sessionLabel, gbc)
            
            gbc.gridx = 0
            gbc.gridy = 4
            overviewPanel!!.add(JBLabel("Status:"), gbc)
            
            gbc.gridx = 1
            val statusText = if (currentSession!!.status == TestGenerationSession.Status.AWAITING_USER_SELECTION) {
                "⏸️ Waiting for test scenario selection..."
            } else {
                currentSession!!.status.description
            }
            val statusLabel = JBLabel(statusText)
            statusLabel.foreground = when (currentSession!!.status) {
                TestGenerationSession.Status.COMPLETED -> Color(76, 175, 80)
                TestGenerationSession.Status.FAILED, TestGenerationSession.Status.CANCELLED -> Color(244, 67, 54)
                TestGenerationSession.Status.AWAITING_USER_SELECTION -> Color(33, 150, 243)
                else -> Color(255, 152, 0)
            }
            overviewPanel!!.add(statusLabel, gbc)
        } else {
            gbc.gridy = 3
            gbc.gridwidth = 2
            val readyLabel = JBLabel("✅ Ready to generate tests! Click 'Generate Tests' in the toolbar to start.")
            readyLabel.font = readyLabel.font.deriveFont(Font.BOLD, 12f)
            readyLabel.foreground = Color(76, 175, 80)
            overviewPanel!!.add(readyLabel, gbc)
            
            // Add hint about what will be tested
            gbc.gridy = 4
            val hintLabel = JBLabel("💡 Tip: AI will plan test scenarios, then you can select which ones to generate")
            hintLabel.font = hintLabel.font.deriveFont(11f)
            hintLabel.foreground = UIUtil.getInactiveTextColor()
            overviewPanel!!.add(hintLabel, gbc)
        }
    }
    
    private fun getLineNumber(offset: Int): Int {
        return try {
            val document = virtualFile.targetFile.viewProvider.document
            if (document != null) {
                document.getLineNumber(offset) + 1
            } else {
                0
            }
        } catch (e: Exception) {
            0
        }
    }
    
    private fun updateCodePreviewPanel() {
        codePreviewPanel?.removeAll()
        
        // Clean up any existing editor
        val oldEditor = codePreviewPanel?.getClientProperty("editor") as? EditorEx
        oldEditor?.let { EditorFactory.getInstance().releaseEditor(it) }
        
        // Add label
        val previewLabel = JBLabel("🔍 Selected Code Preview:")
        previewLabel.font = previewLabel.font.deriveFont(Font.BOLD, 12f)
        codePreviewPanel!!.add(previewLabel, BorderLayout.NORTH)
        
        // Get selected code text
        val selectedCode = try {
            val text = virtualFile.targetFile.text
            if (virtualFile.selectionStart != virtualFile.selectionEnd && 
                virtualFile.selectionStart >= 0 && 
                virtualFile.selectionEnd <= text.length) {
                text.substring(virtualFile.selectionStart, virtualFile.selectionEnd)
            } else {
                // Show first 500 chars if entire file
                if (text.length > 500) {
                    text.substring(0, 500) + "\n// ... [Full file selected]"
                } else {
                    text
                }
            }
        } catch (e: Exception) {
            "// Unable to preview selected code"
        }
        
        // Create editor with syntax highlighting
        val document = EditorFactory.getInstance().createDocument(selectedCode)
        val editor = EditorFactory.getInstance().createEditor(document, project, virtualFile.targetFile.fileType, true) as EditorEx
        
        // Configure editor to be compact and read-only
        editor.settings.apply {
            isLineNumbersShown = true
            isWhitespacesShown = false
            isIndentGuidesShown = true
            isFoldingOutlineShown = true
            additionalColumnsCount = 0
            additionalLinesCount = 0
            isCaretRowShown = false
            isLineMarkerAreaShown = false
        }
        
        editor.isViewer = true
        editor.colorsScheme = EditorColorsManager.getInstance().globalScheme
        
        val editorComponent = editor.component
        // Ensure the editor component has proper size
        editorComponent.preferredSize = Dimension(450, 100)
        editorComponent.minimumSize = Dimension(300, 80)
        
        // Wrap in scroll pane for better display
        val scrollPane = JBScrollPane(editorComponent)
        scrollPane.border = JBUI.Borders.customLine(UIUtil.getBoundsColor(), 1)
        
        codePreviewPanel!!.add(scrollPane, BorderLayout.CENTER)
        
        // Store editor reference for cleanup
        codePreviewPanel!!.putClientProperty("editor", editor)
    }
    
    private fun updateTestPlanTab() {
        planContentPanel?.removeAll()
        
        println("[DEBUG-UI] updateTestPlanTab called")
        println("[DEBUG-UI] currentSession is null? ${currentSession == null}")
        println("[DEBUG-UI] testPlan is null? ${currentSession?.testPlan == null}")
        
        currentSession?.testPlan?.let { testPlan ->
            println("[DEBUG-UI] Test plan found with ${testPlan.scenarioCount} scenarios")
            val content = JPanel()
            content.layout = BoxLayout(content, BoxLayout.Y_AXIS)
            content.background = UIUtil.getPanelBackground()
            
            // Plan summary
            val summaryCard = createInfoCard("Test Plan Summary", """
                Target Class: ${testPlan.targetClass}
                Target Method: ${testPlan.targetMethod}
                Recommended Type: ${testPlan.recommendedTestType.description}
                Scenarios: ${testPlan.scenarioCount}
            """.trimIndent())
            content.add(summaryCard)
            content.add(Box.createVerticalStrut(15))
            
            // Reasoning
            if (testPlan.reasoning.isNotEmpty()) {
                val reasoningCard = createInfoCard("Planning Reasoning", testPlan.reasoning)
                content.add(reasoningCard)
                content.add(Box.createVerticalStrut(15))
            }
            
            // Scenarios
            val scenariosPanel = JPanel()
            scenariosPanel.layout = BoxLayout(scenariosPanel, BoxLayout.Y_AXIS)
            scenariosPanel.background = UIUtil.getPanelBackground()
            
            for ((index, scenario) in testPlan.testScenarios.withIndex()) {
                val scenarioCard = createScenarioCard(index + 1, scenario)
                scenariosPanel.add(scenarioCard)
                scenariosPanel.add(Box.createVerticalStrut(10))
            }
            
            content.add(JBLabel("Test Scenarios:").apply { 
                font = font.deriveFont(Font.BOLD, 14f) 
            })
            content.add(Box.createVerticalStrut(10))
            content.add(scenariosPanel)
            
            planContentPanel!!.add(content, BorderLayout.NORTH)
        } ?: run {
            val placeholderLabel = JBLabel("Test plan will appear here after generation starts")
            placeholderLabel.horizontalAlignment = SwingConstants.CENTER
            placeholderLabel.foreground = UIUtil.getInactiveTextColor()
            planContentPanel!!.add(placeholderLabel, BorderLayout.CENTER)
        }
    }
    
    private fun updateGeneratedTestsTab() {
        testsContentPanel?.removeAll()
        
        println("[DEBUG-UI] updateGeneratedTestsTab called")
        println("[DEBUG-UI] currentSession is null? ${currentSession == null}")
        println("[DEBUG-UI] generatedTests is null? ${currentSession?.generatedTests == null}")
        println("[DEBUG-UI] generatedTests size: ${currentSession?.generatedTests?.size ?: 0}")
        
        currentSession?.generatedTests?.let { tests ->
            if (tests.isNotEmpty()) {
                val content = JPanel()
                content.layout = BoxLayout(content, BoxLayout.Y_AXIS)
                content.background = UIUtil.getPanelBackground()
                
                for ((index, test) in tests.withIndex()) {
                    val testCard = createTestCard(index + 1, test)
                    content.add(testCard)
                    content.add(Box.createVerticalStrut(15))
                }
                
                testsContentPanel!!.add(content, BorderLayout.NORTH)
            } else {
                val placeholderLabel = JBLabel("Generated tests will appear here")
                placeholderLabel.horizontalAlignment = SwingConstants.CENTER
                placeholderLabel.foreground = UIUtil.getInactiveTextColor()
                testsContentPanel!!.add(placeholderLabel, BorderLayout.CENTER)
            }
        } ?: run {
            val placeholderLabel = JBLabel("Generated tests will appear here")
            placeholderLabel.horizontalAlignment = SwingConstants.CENTER
            placeholderLabel.foreground = UIUtil.getInactiveTextColor()
            testsContentPanel!!.add(placeholderLabel, BorderLayout.CENTER)
        }
    }
    
    private fun updateValidationTab() {
        validationContentPanel?.removeAll()
        
        currentSession?.validationResult?.let { validation ->
            val content = JPanel()
            content.layout = BoxLayout(content, BoxLayout.Y_AXIS)
            content.background = UIUtil.getPanelBackground()
            
            // Validation summary
            val summaryText = """
                Validation Status: ${if (validation.isSuccessful) "✅ Passed" else "❌ Issues Found"}
                Errors: ${validation.errorCount}
                Warnings: ${validation.warningCount}
                Fixes Applied: ${validation.appliedFixes.size}
            """.trimIndent()
            
            val summaryCard = createInfoCard("Validation Summary", summaryText)
            content.add(summaryCard)
            content.add(Box.createVerticalStrut(15))
            
            // Issues
            if (validation.issues.isNotEmpty()) {
                content.add(JBLabel("Validation Issues:").apply { 
                    font = font.deriveFont(Font.BOLD, 14f) 
                })
                content.add(Box.createVerticalStrut(10))
                
                for (issue in validation.issues) {
                    val issueCard = createIssueCard(issue)
                    content.add(issueCard)
                    content.add(Box.createVerticalStrut(8))
                }
            }
            
            // Applied fixes
            if (validation.appliedFixes.isNotEmpty()) {
                content.add(Box.createVerticalStrut(15))
                content.add(JBLabel("Applied Fixes:").apply { 
                    font = font.deriveFont(Font.BOLD, 14f) 
                })
                content.add(Box.createVerticalStrut(10))
                
                val fixesText = validation.appliedFixes.joinToString("\n") { "• $it" }
                val fixesCard = createInfoCard("Fixes", fixesText)
                content.add(fixesCard)
            }
            
            validationContentPanel!!.add(content, BorderLayout.NORTH)
        } ?: run {
            val placeholderLabel = JBLabel("Validation results will appear here")
            placeholderLabel.horizontalAlignment = SwingConstants.CENTER
            placeholderLabel.foreground = UIUtil.getInactiveTextColor()
            validationContentPanel!!.add(placeholderLabel, BorderLayout.CENTER)
        }
    }
    
    private fun updateProgressTab() {
        progressContentPanel?.removeAll()
        
        // This would contain detailed progress information
        val placeholderLabel = JBLabel("Detailed progress information will be shown here")
        placeholderLabel.horizontalAlignment = SwingConstants.CENTER
        placeholderLabel.foreground = UIUtil.getInactiveTextColor()
        progressContentPanel!!.add(placeholderLabel, BorderLayout.CENTER)
    }
    
    private fun updateStreamingTab() {
        if (streamingTextArea == null) {
            streamingContentPanel?.removeAll()
            
            // Add label
            val streamingLabel = JBLabel("🤖 AI Response Stream:")
            streamingLabel.font = streamingLabel.font.deriveFont(Font.BOLD, 12f)
            streamingContentPanel!!.add(streamingLabel, BorderLayout.NORTH)
            
            // Create text area for streaming content
            streamingTextArea = JBTextArea()
            streamingTextArea!!.isEditable = false
            streamingTextArea!!.font = Font(Font.MONOSPACED, Font.PLAIN, 12)
            streamingTextArea!!.background = if (UIUtil.isUnderDarcula()) Color(43, 43, 43) else Color(250, 250, 250)
            streamingTextArea!!.foreground = UIUtil.getLabelForeground()
            streamingTextArea!!.border = EmptyBorder(10, 10, 10, 10)
            streamingTextArea!!.lineWrap = true
            streamingTextArea!!.wrapStyleWord = true
            
            val scrollPane = JBScrollPane(streamingTextArea)
            scrollPane.border = JBUI.Borders.customLine(UIUtil.getBoundsColor(), 1)
            
            streamingContentPanel!!.add(scrollPane, BorderLayout.CENTER)
            
            // Add status panel at bottom
            val statusPanel = JPanel(FlowLayout(FlowLayout.LEFT))
            statusPanel.background = UIUtil.getPanelBackground()
            val statusLabel = JBLabel("🔄 Waiting for AI response...")
            statusLabel.foreground = UIUtil.getInactiveTextColor()
            statusPanel.add(statusLabel)
            streamingContentPanel!!.add(statusPanel, BorderLayout.SOUTH)
        }
    }
    
    fun appendStreamingText(text: String) {
        println("[DEBUG-UI] appendStreamingText called with text length: ${text.length}")
        println("[DEBUG-UI] streamingTextArea is null? ${streamingTextArea == null}")
        SwingUtilities.invokeLater {
            println("[DEBUG-UI] Inside EDT - streamingTextArea is null? ${streamingTextArea == null}")
            streamingTextArea?.let { textArea ->
                println("[DEBUG-UI] Appending text to text area")
                textArea.append(text)
                // Auto-scroll to bottom
                textArea.caretPosition = textArea.document.length
                // Force immediate repaint of the text area
                textArea.revalidate()
                textArea.repaint()
                // Also repaint the parent scroll pane
                streamingTab?.revalidate()
                streamingTab?.repaint()
                println("[DEBUG-UI] Text appended and UI refreshed")
            } ?: println("[DEBUG-UI] WARNING: streamingTextArea is null!")
        }
    }
    
    fun clearStreamingText() {
        SwingUtilities.invokeLater {
            streamingTextArea?.text = ""
        }
    }
    
    fun setStreamingStatus(status: String) {
        SwingUtilities.invokeLater {
            // Update status in the streaming tab
            val statusPanel = streamingContentPanel?.getComponent(2) as? JPanel
            if (statusPanel != null && statusPanel.componentCount > 0) {
                val statusLabel = statusPanel.getComponent(0) as? JBLabel
                statusLabel?.text = status
            }
        }
    }
    
    
    private fun createInfoCard(title: String, content: String): JComponent {
        val card = JPanel(BorderLayout())
        card.background = if (UIUtil.isUnderDarcula()) Color(60, 63, 65) else Color(245, 245, 245)
        card.border = JBUI.Borders.compound(
            JBUI.Borders.customLine(UIUtil.getBoundsColor(), 1),
            EmptyBorder(15, 15, 15, 15)
        )
        
        val titleLabel = JBLabel(title)
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, 14f)
        card.add(titleLabel, BorderLayout.NORTH)
        
        val contentArea = JBTextArea(content)
        contentArea.isEditable = false
        contentArea.background = card.background
        contentArea.border = EmptyBorder(10, 0, 0, 0)
        card.add(contentArea, BorderLayout.CENTER)
        
        return card
    }
    
    private fun createScenarioCard(number: Int, scenario: TestPlan.TestScenario): JComponent {
        val card = JPanel(BorderLayout())
        card.background = if (UIUtil.isUnderDarcula()) Color(50, 50, 50) else Color(250, 250, 250)
        card.border = JBUI.Borders.compound(
            JBUI.Borders.customLine(getTypeColor(scenario.type), 3, 0, 0, 0),
            EmptyBorder(12, 15, 12, 15)
        )
        
        val leftPanel = JPanel()
        leftPanel.layout = BoxLayout(leftPanel, BoxLayout.Y_AXIS)
        leftPanel.background = card.background
        
        val nameLabel = JBLabel("$number. ${scenario.name}")
        nameLabel.font = Font(Font.MONOSPACED, Font.BOLD, 12)
        leftPanel.add(nameLabel)
        
        val infoLabel = JBLabel("${scenario.type.displayName} • ${scenario.priority.displayName}")
        infoLabel.font = infoLabel.font.deriveFont(10f)
        infoLabel.foreground = UIUtil.getInactiveTextColor()
        leftPanel.add(infoLabel)
        
        val descLabel = JBLabel(scenario.description)
        descLabel.font = descLabel.font.deriveFont(11f)
        leftPanel.add(descLabel)
        
        card.add(leftPanel, BorderLayout.CENTER)
        
        return card
    }
    
    private fun createTestCard(number: Int, test: GeneratedTest): JComponent {
        val card = JPanel(BorderLayout())
        card.background = if (UIUtil.isUnderDarcula()) Color(50, 50, 50) else Color(250, 250, 250)
        card.border = JBUI.Borders.compound(
            JBUI.Borders.customLine(getTypeColor(test.scenario.type), 3, 0, 0, 0),
            EmptyBorder(12, 15, 12, 15)
        )
        
        val leftPanel = JPanel()
        leftPanel.layout = BoxLayout(leftPanel, BoxLayout.Y_AXIS)
        leftPanel.background = card.background
        
        val nameLabel = JBLabel("$number. ${test.testName}")
        nameLabel.font = Font(Font.MONOSPACED, Font.BOLD, 12)
        leftPanel.add(nameLabel)
        
        val classLabel = JBLabel("${test.testClassName} (${test.framework})")
        classLabel.font = classLabel.font.deriveFont(10f)
        classLabel.foreground = UIUtil.getInactiveTextColor()
        leftPanel.add(classLabel)
        
        val contentPreview = test.testContent.take(100) + if (test.testContent.length > 100) "..." else ""
        val previewLabel = JBLabel(contentPreview)
        previewLabel.font = Font(Font.MONOSPACED, Font.PLAIN, 11)
        leftPanel.add(previewLabel)
        
        card.add(leftPanel, BorderLayout.WEST)
        
        // Action buttons
        val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT))
        buttonPanel.background = card.background
        
        val viewButton = JButton("View Code")
        viewButton.addActionListener {
            showTestCode(test)
        }
        buttonPanel.add(viewButton)
        
        card.add(buttonPanel, BorderLayout.EAST)
        
        return card
    }
    
    private fun createIssueCard(issue: ValidationResult.ValidationIssue): JComponent {
        val card = JPanel(BorderLayout())
        card.background = if (UIUtil.isUnderDarcula()) Color(50, 50, 50) else Color(250, 250, 250)
        
        val severityColor = when (issue.severity) {
            ValidationResult.ValidationIssue.Severity.ERROR -> Color(244, 67, 54)
            ValidationResult.ValidationIssue.Severity.WARNING -> Color(255, 152, 0)
            else -> Color(76, 175, 80)
        }
        
        card.border = JBUI.Borders.compound(
            JBUI.Borders.customLine(severityColor, 3, 0, 0, 0),
            EmptyBorder(10, 15, 10, 15)
        )
        
        val leftPanel = JPanel()
        leftPanel.layout = BoxLayout(leftPanel, BoxLayout.Y_AXIS)
        leftPanel.background = card.background
        
        val issueLabel = JBLabel(issue.description)
        issueLabel.font = issueLabel.font.deriveFont(12f)
        leftPanel.add(issueLabel)
        
        val detailsLabel = JBLabel("${issue.severity.displayName} in ${issue.testName}")
        detailsLabel.font = detailsLabel.font.deriveFont(10f)
        detailsLabel.foreground = UIUtil.getInactiveTextColor()
        leftPanel.add(detailsLabel)
        
        if (issue.fixSuggestion.isNotEmpty()) {
            val suggestionLabel = JBLabel("💡 ${issue.fixSuggestion}")
            suggestionLabel.font = suggestionLabel.font.deriveFont(11f)
            suggestionLabel.foreground = Color(76, 175, 80)
            leftPanel.add(suggestionLabel)
        }
        
        card.add(leftPanel, BorderLayout.CENTER)
        
        return card
    }
    
    private fun getTypeColor(type: TestPlan.TestScenario.Type): Color {
        return when (type) {
            TestPlan.TestScenario.Type.UNIT -> Color(76, 175, 80)
            TestPlan.TestScenario.Type.INTEGRATION -> Color(33, 150, 243)
            TestPlan.TestScenario.Type.EDGE_CASE -> Color(255, 152, 0)
            TestPlan.TestScenario.Type.ERROR_HANDLING -> Color(244, 67, 54)
        }
    }
    
    private fun setupProgressListener() {
        // This will be called when progress updates are received
    }
    
    private fun onProgress(progress: TestGenerationProgress) {
        SwingUtilities.invokeLater {
            statusLabel.text = progress.message
            progressBar.value = progress.progressPercent
            progressBar.isVisible = progress.progressPercent < 100
            
            // Always refresh session data to get latest updates
            refreshSessionData()
            
            // Check if we're awaiting user selection
            currentSession?.let { session ->
                if (session.status == TestGenerationSession.Status.AWAITING_USER_SELECTION) {
                    handleTestScenarioSelection(session)
                }
            }
            
            if (progress.isComplete) {
                refreshSessionData()
            }
        }
    }
    
    private fun handleTestScenarioSelection(session: TestGenerationSession) {
        // Check if dialog has already been shown for this session
        synchronized(shownSelectionDialogs) {
            if (shownSelectionDialogs.contains(session.sessionId)) {
                LOG.debug("Scenario selection dialog already shown for session: ${session.sessionId}")
                return
            }
            // Mark dialog as shown immediately to prevent duplicates
            shownSelectionDialogs.add(session.sessionId)
        }
        
        // Ensure we have a test plan
        val testPlan = session.testPlan
        if (testPlan == null || testPlan.scenarioCount == 0) {
            LOG.warn("No test plan available for scenario selection")
            return
        }
        
        // Switch to test plan tab to show what was planned
        tabbedPane?.selectedIndex = 1 // Test Plan tab
        
        // Show selection dialog after a short delay to let UI update
        SwingUtilities.invokeLater {
            val dialog = TestScenarioSelectionDialog(project, testPlan)
            if (dialog.showAndGet()) {
                val selectedScenarios = dialog.getSelectedScenarios()
                if (selectedScenarios.isNotEmpty()) {
                    // Update UI to show continuing
                    statusLabel.text = "Continuing with ${selectedScenarios.size} selected scenarios..."
                    setStreamingStatus("🚀 Generating tests for ${selectedScenarios.size} selected scenarios...")
                    
                    // Continue generation with selected scenarios
                    testGenService.continueWithSelectedScenarios(session.sessionId, selectedScenarios)
                    
                    // Update the test plan display to show only selected scenarios
                    updateTestPlanWithSelection(selectedScenarios)
                } else {
                    // User didn't select any scenarios
                    Messages.showWarningDialog(
                        project,
                        "No test scenarios were selected. Test generation cancelled.",
                        "No Scenarios Selected"
                    )
                    testGenService.cancelSession(session.sessionId)
                }
            } else {
                // User cancelled the dialog
                testGenService.cancelSession(session.sessionId)
                statusLabel.text = "Test generation cancelled by user"
                // Remove from shown dialogs to allow retry if user restarts
                synchronized(shownSelectionDialogs) {
                    shownSelectionDialogs.remove(session.sessionId)
                }
            }
        }
    }
    
    private fun updateTestPlanWithSelection(selectedScenarios: List<TestPlan.TestScenario>) {
        SwingUtilities.invokeLater {
            // Update the test plan tab to highlight selected scenarios
            currentSession?.testPlan?.let { plan ->
                // Only update if not already updated (prevent duplicate messages)
                if (!plan.reasoning.contains("✅ User selected")) {
                    // Create a new plan with only selected scenarios for display
                    val updatedPlan = TestPlan(
                        plan.targetMethod,
                        plan.targetClass,
                        selectedScenarios,
                        plan.dependencies,
                        plan.recommendedTestType,
                        plan.reasoning + "\n\n✅ User selected ${selectedScenarios.size} of ${plan.scenarioCount} scenarios."
                    )
                    currentSession?.setTestPlan(updatedPlan)
                    updateTestPlanTab()
                    planTab?.revalidate()
                    planTab?.repaint()
                }
            }
        }
    }
    
    private fun startTestGeneration() {
        // Clean up any previous session dialog tracking
        currentSession?.let { oldSession ->
            synchronized(shownSelectionDialogs) {
                shownSelectionDialogs.remove(oldSession.sessionId)
            }
        }
        
        // Generate description based on selection
        val description = if (virtualFile.selectionStart != virtualFile.selectionEnd) {
            "Generate tests for selected code"
        } else {
            "Generate tests for entire file"
        }
        
        val request = TestGenerationRequest(
            virtualFile.targetFile,
            virtualFile.selectionStart,
            virtualFile.selectionEnd,
            description,
            TestGenerationRequest.TestType.AUTO_DETECT,
            null
        )
        
        // Clear streaming text before starting
        clearStreamingText()
        setStreamingStatus("🔄 Starting AI agents...")
        
        // Define streaming consumer BEFORE starting generation
        val streamingConsumer: java.util.function.Consumer<String> = java.util.function.Consumer { text ->
            println("[DEBUG-UI] Streaming consumer called!")
            println("[DEBUG-UI] Text length received: ${text.length}")
            println("[DEBUG-UI] First 100 chars: ${text.take(100)}")
            LOG.info("Streaming text received: ${text.take(100)}") // Debug log
            // First append the text to the streaming area
            appendStreamingText(text)
            // Then parse it for UI updates (need to pass session once we have it)
        }
        
        // Start generation with streaming consumer already set
        testGenService.startTestGeneration(request, streamingConsumer).thenAccept { session ->
            SwingUtilities.invokeLater {
                currentSession = session
                testGenService.addProgressListener(session.sessionId, ::onProgress)
                
                // Update the streaming consumer to include session for parsing
                testGenService.setStreamingConsumer(session.sessionId) { text ->
                    println("[DEBUG-UI] Streaming consumer called!")
                    println("[DEBUG-UI] Text length received: ${text.length}")
                    println("[DEBUG-UI] First 100 chars: ${text.take(100)}")
                    LOG.info("Streaming text received: ${text.take(100)}") // Debug log
                    // First append the text to the streaming area
                    appendStreamingText(text)
                    // Then parse it for UI updates
                    parseStreamingOutputForUI(text, session)
                }
                
                // Switch to streaming tab to show progress
                tabbedPane?.selectedIndex = 0 // Raw AI Response tab
                
                // Clear any previous dialog shown flags
                val dialogShownKey = "dialog_shown_${session.sessionId}"
                putUserData(dialogShownKey, false)
                
                // Force immediate UI update
                updateDataDependentComponents()
                component.revalidate()
                component.repaint()
            }
        }.exceptionally { throwable ->
            SwingUtilities.invokeLater {
                Messages.showErrorDialog(
                    project,
                    "Failed to start test generation: ${throwable.message}",
                    "Test Generation Error"
                )
            }
            null
        }
    }
    
    
    private fun cancelTestGeneration() {
        currentSession?.let { session ->
            testGenService.cancelSession(session.sessionId)
            statusLabel.text = "Generation cancelled"
            progressBar.isVisible = false
        }
    }
    
    private fun writeTestsToFiles() {
        currentSession?.let { session ->
            testGenService.writeTestsToFiles(session.sessionId).thenAccept { writtenFiles ->
                SwingUtilities.invokeLater {
                    val message = "Successfully wrote ${writtenFiles.size} test files:\n" + 
                                 writtenFiles.joinToString("\n") { "• $it" }
                    Messages.showInfoMessage(project, message, "Tests Written")
                }
            }.exceptionally { throwable ->
                SwingUtilities.invokeLater {
                    Messages.showErrorDialog(
                        project,
                        "Failed to write test files: ${throwable.message}",
                        "Write Error"
                    )
                }
                null
            }
        }
    }
    
    private fun copyAllTestsToClipboard() {
        currentSession?.generatedTests?.let { tests ->
            if (tests.isNotEmpty()) {
                val allTestCode = tests.joinToString("\n\n" + "=".repeat(80) + "\n\n") { test ->
                    "// Test Class: ${test.testClassName}\n" +
                    "// Test Method: ${test.testName}\n\n" +
                    test.fullContent
                }
                
                val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                val selection = StringSelection(allTestCode)
                clipboard.setContents(selection, selection)
                
                Messages.showInfoMessage(
                    project,
                    "Copied ${tests.size} test(s) to clipboard",
                    "Tests Copied"
                )
            }
        }
    }
    
    private fun refreshSessionData() {
        currentSession?.let { session ->
            println("[DEBUG-UI] refreshSessionData called for session: ${session.sessionId}")
            val updatedSession = testGenService.getSession(session.sessionId)
            println("[DEBUG-UI] updatedSession is null? ${updatedSession == null}")
            if (updatedSession != null) {
                println("[DEBUG-UI] Updated session status: ${updatedSession.status}")
                println("[DEBUG-UI] Updated session has test plan? ${updatedSession.testPlan != null}")
                println("[DEBUG-UI] Updated session has generated tests? ${updatedSession.generatedTests != null}")
                currentSession = updatedSession
                // Ensure UI update happens on EDT with proper refresh
                SwingUtilities.invokeLater {
                    updateDataDependentComponents()
                    // Force refresh of all tabs
                    tabbedPane?.revalidate()
                    tabbedPane?.repaint()
                    component.revalidate()
                    component.repaint()
                }
            }
        }
    }
    
    private fun refreshUI() {
        // Use the new update method instead of recreating everything
        updateDataDependentComponents()
    }
    
    private fun showTestCode(test: GeneratedTest) {
        val dialog = JDialog()
        dialog.title = "Generated Test: ${test.testName}"
        dialog.setSize(900, 700)
        dialog.setLocationRelativeTo(component)
        
        // Create editor with syntax highlighting
        val document = EditorFactory.getInstance().createDocument(test.fullContent)
        val javaFileType = FileTypeManager.getInstance().getFileTypeByExtension("java")
        val editor = EditorFactory.getInstance().createEditor(document, project, javaFileType, false) as EditorEx
        
        // Configure editor settings
        editor.settings.apply {
            isLineNumbersShown = true
            isWhitespacesShown = false
            isIndentGuidesShown = true
            isFoldingOutlineShown = true
            additionalColumnsCount = 1
            additionalLinesCount = 1
            isCaretRowShown = true
        }
        
        // Set color scheme
        editor.colorsScheme = EditorColorsManager.getInstance().globalScheme
        
        val editorPanel = JPanel(BorderLayout())
        editorPanel.add(editor.component, BorderLayout.CENTER)
        
        // Add button panel
        val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT))
        buttonPanel.background = UIUtil.getPanelBackground()
        
        val copyButton = JButton("Copy to Clipboard")
        copyButton.addActionListener {
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            val selection = StringSelection(test.fullContent)
            clipboard.setContents(selection, selection)
            Messages.showInfoMessage(project, "Test code copied to clipboard", "Copied")
        }
        buttonPanel.add(copyButton)
        
        val closeButton = JButton("Close")
        closeButton.addActionListener {
            EditorFactory.getInstance().releaseEditor(editor)
            dialog.dispose()
        }
        buttonPanel.add(closeButton)
        
        dialog.add(editorPanel, BorderLayout.CENTER)
        dialog.add(buttonPanel, BorderLayout.SOUTH)
        
        dialog.addWindowListener(object : java.awt.event.WindowAdapter() {
            override fun windowClosing(e: java.awt.event.WindowEvent) {
                EditorFactory.getInstance().releaseEditor(editor)
            }
        })
        
        dialog.isVisible = true
    }
    
    private fun parseStreamingOutputForUI(text: String, session: TestGenerationSession) {
        // Parse streaming output and progressively update UI tabs
        try {
            // Update streaming status based on agent activity - do this on EDT
            SwingUtilities.invokeLater {
                when {
                    text.contains("=== CoordinatorAgent Starting ===") -> {
                        setStreamingStatus("🎯 Planning test scenarios...")
                        currentSession?.status = TestGenerationSession.Status.PLANNING
                        updateOverviewPanel()
                        overviewPanel?.revalidate()
                        overviewPanel?.repaint()
                    }
                    text.contains("=== ContextAgent Starting ===") -> {
                        setStreamingStatus("🔍 Gathering code context...")
                        currentSession?.status = TestGenerationSession.Status.GATHERING_CONTEXT
                        updateOverviewPanel()
                        overviewPanel?.revalidate()
                        overviewPanel?.repaint()
                    }
                    text.contains("=== TestWriterAgent Starting ===") -> {
                        setStreamingStatus("✍️ Generating test code...")
                        currentSession?.status = TestGenerationSession.Status.GENERATING
                        updateOverviewPanel()
                        overviewPanel?.revalidate()
                        overviewPanel?.repaint()
                    }
                    text.contains("=== ValidatorAgent Starting ===") -> {
                        setStreamingStatus("✅ Validating generated tests...")
                        currentSession?.status = TestGenerationSession.Status.VALIDATING
                        updateOverviewPanel()
                        overviewPanel?.revalidate()
                        overviewPanel?.repaint()
                    }
                    text.contains("✅ Task completed!") -> {
                        setStreamingStatus("✨ Processing complete!")
                        // Refresh session data to get the parsed results
                        refreshSessionData()
                    }
                    text.contains("❌ Error:") -> {
                        setStreamingStatus("⚠️ Error occurred - check logs")
                        currentSession?.status = TestGenerationSession.Status.FAILED
                        updateOverviewPanel()
                        overviewPanel?.revalidate()
                        overviewPanel?.repaint()
                    }
                }
                
                // Update progress bar based on step indicators
                val stepPattern = """--- Step (\d+) ---""".toRegex()
                stepPattern.find(text)?.let { match ->
                    val step = match.groupValues[1].toIntOrNull() ?: 0
                    val progress = minOf(step * 20, 90) // Max 90% until completion
                    progressBar.value = progress
                    progressBar.isVisible = true
                }
            }
            
            // Parse test plan if present (these methods now handle EDT properly)
            if (text.contains("TARGET_METHOD:") || text.contains("TARGET_CLASS:")) {
                println("[DEBUG-UI] Found test plan markers in streaming text")
                parseTestPlanFromStream(text)
            }
            
            // Parse test scenarios (these methods now handle EDT properly)
            if (text.contains("SCENARIO:") && text.contains("TYPE:")) {
                parseScenarioFromStream(text)
            }
            
            // Parse generated tests (these methods now handle EDT properly)
            if (text.contains("TEST_GENERATED:")) {
                parseGeneratedTestFromStream(text)
            }
            
        } catch (e: Exception) {
            LOG.warn("Error parsing streaming output", e)
        }
    }
    
    private fun parseTestPlanFromStream(text: String) {
        // Extract test plan information from streaming text
        try {
            var targetMethod = ""
            var targetClass = ""
            var recommendedType = TestGenerationRequest.TestType.AUTO_DETECT
            
            // Parse target method
            val methodPattern = """TARGET_METHOD:\s*(.+)""".toRegex()
            methodPattern.find(text)?.let { match ->
                targetMethod = match.groupValues[1].trim()
            }
            
            // Parse target class
            val classPattern = """TARGET_CLASS:\s*(.+)""".toRegex()
            classPattern.find(text)?.let { match ->
                targetClass = match.groupValues[1].trim()
            }
            
            // Parse recommended type
            val typePattern = """RECOMMENDED_TYPE:\s*(.+)""".toRegex()
            typePattern.find(text)?.let { match ->
                val typeStr = match.groupValues[1].trim()
                recommendedType = try {
                    TestGenerationRequest.TestType.valueOf(typeStr)
                } catch (e: Exception) {
                    TestGenerationRequest.TestType.AUTO_DETECT
                }
            }
            
            // If we have enough info, create a temporary test plan
            if (targetMethod.isNotEmpty() || targetClass.isNotEmpty()) {
                val tempPlan = TestPlan(
                    targetMethod,
                    targetClass,
                    emptyList(), // Scenarios will be added separately
                    emptyList(),
                    recommendedType,
                    "AI is analyzing your code..."
                )
                currentSession?.let { session ->
                    session.setTestPlan(tempPlan)
                }
                // Ensure UI update happens on EDT
                SwingUtilities.invokeLater {
                    updateTestPlanTab()
                    planTab?.revalidate()
                    planTab?.repaint()
                }
            }
        } catch (e: Exception) {
            LOG.warn("Error parsing test plan from stream", e)
        }
    }
    
    private fun parseGeneratedTestFromStream(text: String) {
        // Extract generated test info and update UI progressively
        try {
            val testPattern = """TEST_GENERATED:\s*([^|]+)\s*\|\s*CLASS:\s*(.+)""".toRegex()
            testPattern.find(text)?.let { match ->
                val testName = match.groupValues[1].trim()
                val className = match.groupValues[2].trim()
                
                // Create a placeholder test for progressive display
                val placeholderTest = GeneratedTest(
                    testName,
                    className,
                    "// Test code is being generated...",
                    TestPlan.TestScenario(
                        testName,
                        "Generating...",
                        TestPlan.TestScenario.Type.UNIT,
                        emptyList(),
                        "",
                        TestPlan.TestScenario.Priority.MEDIUM
                    ),
                    "$className.java",
                    "",
                    emptyList(),
                    emptyList(),
                    "JUnit"
                )
                
                // Add to session's generated tests list
                currentSession?.let { session ->
                    val currentTests = session.generatedTests?.toMutableList() ?: mutableListOf()
                    currentTests.add(placeholderTest)
                    session.setGeneratedTests(currentTests)
                    
                    // Update the Generated Tests tab immediately on EDT
                    SwingUtilities.invokeLater {
                        updateGeneratedTestsTab()
                        testsTab?.revalidate()
                        testsTab?.repaint()
                    }
                }
            }
        } catch (e: Exception) {
            LOG.warn("Error parsing generated test from stream", e)
        }
    }
    
    private fun parseScenarioFromStream(text: String) {
        // Extract test scenario from streaming text
        try {
            val scenarioPattern = """SCENARIO:\s*([^|]+)\s*\|\s*TYPE:\s*([^|]+)""".toRegex()
            scenarioPattern.find(text)?.let { match ->
                val scenarioName = match.groupValues[1].trim()
                val scenarioTypeStr = match.groupValues[2].trim()
                
                val scenarioType = try {
                    TestPlan.TestScenario.Type.valueOf(scenarioTypeStr)
                } catch (e: Exception) {
                    TestPlan.TestScenario.Type.UNIT
                }
                
                // Create a new scenario
                val scenario = TestPlan.TestScenario(
                    scenarioName,
                    "AI-generated test scenario",
                    scenarioType,
                    emptyList(),
                    "Expected result",
                    TestPlan.TestScenario.Priority.MEDIUM
                )
                
                // Add to existing test plan if available
                currentSession?.testPlan?.let { plan ->
                    val updatedScenarios = plan.testScenarios.toMutableList()
                    updatedScenarios.add(scenario)
                    
                    // Create updated plan
                    val updatedPlan = TestPlan(
                        plan.targetMethod,
                        plan.targetClass,
                        updatedScenarios,
                        plan.dependencies,
                        plan.recommendedTestType,
                        plan.reasoning
                    )
                    currentSession?.setTestPlan(updatedPlan)
                    // Ensure UI update happens on EDT
                    SwingUtilities.invokeLater {
                        updateTestPlanTab()
                        planTab?.revalidate()
                        planTab?.repaint()
                    }
                }
            }
        } catch (e: Exception) {
            LOG.warn("Error parsing scenario from stream", e)
        }
    }
}