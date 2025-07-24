package com.zps.zest.completion.diff

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffContext
import com.intellij.diff.DiffManager
import com.intellij.diff.DiffRequestFactory
import com.intellij.diff.contents.DocumentContent
import com.intellij.diff.requests.ContentDiffRequest
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.tools.simple.SimpleDiffTool
import com.intellij.diff.tools.simple.SimpleDiffViewer
import com.intellij.diff.tools.util.base.DiffViewerBase
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.diff.util.DiffUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import java.awt.event.ActionEvent
import javax.swing.*

/**
 * Embedded diff panel using IntelliJ's diff viewer components
 * This creates a proper diff viewer that can be embedded inline
 */
class EmbeddedDiffPanel(
    private val project: Project,
    private val originalContent: String,
    private val modifiedContent: String,
    private val fileType: FileType,
    private val methodName: String,
    private val onAccept: () -> Unit,
    private val onReject: () -> Unit,
    parentDisposable: Disposable
) : JBPanel<EmbeddedDiffPanel>(BorderLayout()), Disposable {
    
    companion object {
        private val logger = Logger.getInstance(EmbeddedDiffPanel::class.java)
    }
    
    private var diffViewer: SimpleDiffViewer? = null
    
    init {
        Disposer.register(parentDisposable, this)
        
        preferredSize = Dimension(800, 400)
        background = UIUtil.getPanelBackground()
        
        // Create header
        add(createHeaderPanel(), BorderLayout.NORTH)
        
        // Create diff viewer
        createDiffViewer()
        
        // Create button panel
        add(createButtonPanel(), BorderLayout.SOUTH)
    }
    
    private fun createHeaderPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.background = UIUtil.getPanelBackground()
        panel.border = JBUI.Borders.compound(
            JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0),
            JBUI.Borders.empty(8, 10)
        )
        
        val titleLabel = JLabel("Method Rewrite: $methodName()")
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, 14f)
        panel.add(titleLabel, BorderLayout.WEST)
        
        return panel
    }
    
    private fun createDiffViewer() {
        try {
            // Create diff contents
            val diffContentFactory = DiffContentFactory.getInstance()
            val leftContent = diffContentFactory.create(originalContent)
            val rightContent = diffContentFactory.create(modifiedContent)
            
            // Create diff request
            val diffRequest = SimpleDiffRequest(
                "Method Diff",
                leftContent,
                rightContent,
                "Original",
                "AI Improved"
            )
            
            // Create diff context
            val diffContext = object : DiffContext() {
                override fun isFocusedInWindow(): Boolean = true
                override fun requestFocusInWindow(): Unit {}
                override fun getProject(): Project? = this@EmbeddedDiffPanel.project
                override fun isWindowFocused(): Boolean = true
            }
            diffContext.putUserData(DiffUserDataKeys.PLACE, "DEFAULT")
            diffContext.putUserData(DiffUserDataKeys.FORCE_READ_ONLY, true)
            
            // Create diff tool
            val diffTool = SimpleDiffTool()
            val diffViewer = diffTool.createComponent(diffContext, diffRequest) as SimpleDiffViewer
            
            // Configure viewer
            diffViewer.init()
            
            // Add viewer component
            val viewerComponent = diffViewer.component
            viewerComponent.preferredSize = Dimension(800, 300)
            
            // Wrap in panel for better layout
            val wrapperPanel = JPanel(BorderLayout())
            wrapperPanel.add(viewerComponent, BorderLayout.CENTER)
            wrapperPanel.border = JBUI.Borders.customLine(JBColor.border())
            
            add(wrapperPanel, BorderLayout.CENTER)
            
            this.diffViewer = diffViewer
            
            // Apply syntax highlighting
            ApplicationManager.getApplication().invokeLater {
                diffViewer.rediff()
            }
            
        } catch (e: Exception) {
            logger.error("Failed to create diff viewer", e)
            
            // Fallback to simple text areas
            createFallbackView()
        }
    }
    
    private fun createFallbackView() {
        val splitPane = JSplitPane(JSplitPane.HORIZONTAL_SPLIT)
        splitPane.dividerLocation = splitPane.width / 2
        splitPane.resizeWeight = 0.5
        
        val leftArea = JTextArea(originalContent)
        leftArea.isEditable = false
        leftArea.font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        
        val rightArea = JTextArea(modifiedContent)
        rightArea.isEditable = false
        rightArea.font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        
        splitPane.leftComponent = JScrollPane(leftArea)
        splitPane.rightComponent = JScrollPane(rightArea)
        
        add(splitPane, BorderLayout.CENTER)
    }
    
    private fun createButtonPanel(): JComponent {
        val panel = JPanel(FlowLayout(FlowLayout.CENTER, 10, 8))
        panel.background = UIUtil.getPanelBackground()
        panel.border = JBUI.Borders.compound(
            JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0),
            JBUI.Borders.empty(5)
        )
        
        // Accept button
        val acceptButton = JButton("Accept Changes")
        acceptButton.putClientProperty("JButton.buttonType", "default")
        acceptButton.addActionListener {
            logger.info("User accepted method rewrite")
            onAccept()
        }
        
        // Reject button
        val rejectButton = JButton("Reject")
        rejectButton.addActionListener {
            logger.info("User rejected method rewrite")
            onReject()
        }
        
        // Keyboard shortcuts info
        val shortcutLabel = JLabel("TAB to accept, ESC to reject")
        shortcutLabel.foreground = JBColor.GRAY
        shortcutLabel.font = shortcutLabel.font.deriveFont(11f)
        
        panel.add(acceptButton)
        panel.add(rejectButton)
        panel.add(Box.createHorizontalStrut(20))
        panel.add(shortcutLabel)
        
        // Set up keyboard shortcuts
        setupKeyboardShortcuts(acceptButton, rejectButton)
        
        return panel
    }
    
    private fun setupKeyboardShortcuts(acceptButton: JButton, rejectButton: JButton) {
        val inputMap = getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
        val actionMap = getActionMap()
        
        // TAB to accept
        inputMap.put(KeyStroke.getKeyStroke("TAB"), "accept")
        actionMap.put("accept", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                acceptButton.doClick()
            }
        })
        
        // ESC to reject
        inputMap.put(KeyStroke.getKeyStroke("ESCAPE"), "reject")
        actionMap.put("reject", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                rejectButton.doClick()
            }
        })
    }
    
    override fun dispose() {
        diffViewer?.let {
            Disposer.dispose(it)
        }
    }
}

/**
 * Custom renderer that properly handles embedded diff panel with interaction
 */
class EmbeddedDiffPanelRenderer(
    private val panel: EmbeddedDiffPanel,
    private val editor: Editor
) : InteractiveInlinePanel() {
    
    override fun getComponent(): JComponent = panel
    
    override fun getPreferredWidth(editor: Editor): Int {
        return editor.contentComponent.width - 40
    }
    
    override fun getPreferredHeight(editor: Editor): Int {
        return 450 // Fixed height for diff panel
    }
    
    override fun dispose() {
        super.dispose()
        Disposer.dispose(panel)
    }
}