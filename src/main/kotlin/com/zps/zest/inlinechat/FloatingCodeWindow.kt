package com.zps.zest.inlinechat

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import java.awt.event.ActionEvent
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.KeyEvent
import javax.swing.*

/**
 * A floating window that displays AI-suggested code changes alongside the editor
 */
class FloatingCodeWindow(
    private val project: Project,
    private val mainEditor: Editor,
    private val suggestedCode: String,
    private val originalCode: String,
    private val onAccept: () -> Unit,
    private val onReject: () -> Unit
) : Disposable {
    
    private var popup: JBPopup? = null
    private var codeViewer: EditorEx? = null
    private val positionManager = WindowPositionManager(mainEditor)
    private var warningPanel: JPanel? = null
    
    companion object {
        private const val WINDOW_WIDTH = 450    // Further reduced width
        private const val WINDOW_HEIGHT = 300   // Smaller height
        private const val MARGIN = 20
    }
    
    fun show() {
        ApplicationManager.getApplication().invokeLater {
            if (popup?.isVisible == true) {
                return@invokeLater
            }
            
            val panel = createMainPanel()
            
            popup = JBPopupFactory.getInstance()
                .createComponentPopupBuilder(panel, null)
                .setTitle("Zest AI - Suggested Changes")
                .setMovable(true)
                .setResizable(true)
                .setRequestFocus(true)  // Changed to allow interaction
                .setCancelOnClickOutside(false)
                .setCancelOnOtherWindowOpen(false)
                .setCancelKeyEnabled(true)
                .setMinSize(Dimension(350, 250))  // Smaller minimum size
                .setDimensionServiceKey(project, "InlineChatFloatingWindow", false)
                .setAlpha(0.97f)  // Slight transparency for better visual separation
                .createPopup()
            
            // Calculate and set position
            val windowSize = Dimension(WINDOW_WIDTH, WINDOW_HEIGHT)
            val position = positionManager.calculateOptimalPosition(windowSize)
            
            // Create a relative point based on the editor component
            val relativePoint = RelativePoint(mainEditor.component, Point(0, 0))
            
            // Show at the calculated position
            popup?.show(RelativePoint(Point(position)))
            
            // Add ESC key handler
            popup?.content?.let { content ->
                val inputMap = content.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                val actionMap = content.actionMap
                
                inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "close")
                actionMap.put("close", object : AbstractAction() {
                    override fun actionPerformed(e: ActionEvent?) {
                        hide()
                    }
                })
            }
            
            // Add listener to reposition on editor changes
            setupPositionListeners()
        }
    }
    
    fun hide() {
        ApplicationManager.getApplication().invokeLater {
            popup?.cancel()
            popup = null
            dispose()
        }
    }
    
    fun initializeWarning(message: String) {
        warningPanel = createWarningPanel(message)
    }
    
    fun showWarning(message: String) {
        ApplicationManager.getApplication().invokeLater {
            if (warningPanel == null) {
                warningPanel = createWarningPanel(message)
                // If popup is already shown, we need to rebuild the UI
                popup?.content?.let { content ->
                    if (content is JPanel && content.layout is BorderLayout) {
                        val topPanel = content.getComponent(0) as? JPanel
                        if (topPanel != null && topPanel.layout is BorderLayout) {
                            topPanel.add(warningPanel, BorderLayout.SOUTH)
                            topPanel.revalidate()
                            topPanel.repaint()
                        }
                    }
                }
            } else {
                // Update existing warning
                val innerPanel = warningPanel!!.getComponent(0) as? JPanel
                if (innerPanel != null) {
                    val label = innerPanel.getComponent(1) as? JBLabel
                    label?.text = message
                }
            }
        }
    }
    
    private fun createWarningPanel(message: String): JPanel {
        val panel = JPanel(BorderLayout())
        val warningBorderColor = JBColor(Color(255, 190, 0), Color(210, 130, 0))
        val warningBackgroundColor = JBColor(Color(255, 250, 220), Color(80, 70, 40))
        
        panel.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(warningBorderColor, 1, true),
            BorderFactory.createEmptyBorder(8, 8, 8, 8)
        )
        panel.background = warningBackgroundColor
        
        val warningIcon = JBLabel(AllIcons.General.BalloonWarning)
        val warningLabel = JBLabel(message, SwingConstants.LEFT)
        warningLabel.font = warningLabel.font.deriveFont(Font.PLAIN, 12f)
        
        val innerPanel = JPanel(BorderLayout(8, 0))
        innerPanel.background = panel.background
        innerPanel.add(warningIcon, BorderLayout.WEST)
        innerPanel.add(warningLabel, BorderLayout.CENTER)
        
        panel.add(innerPanel, BorderLayout.CENTER)
        
        return panel
    }
    
    private fun createMainPanel(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.border = JBUI.Borders.merge(
            BorderFactory.createLineBorder(JBColor.border(), 1),
            JBUI.Borders.empty(2),
            true
        )
        panel.preferredSize = Dimension(WINDOW_WIDTH, WINDOW_HEIGHT)
        panel.background = UIUtil.getPanelBackground()
        
        // Create toolbar
        val toolbar = createToolbar()
        
        // Create top panel that includes toolbar and potential warning
        val topPanel = JPanel(BorderLayout())
        topPanel.add(toolbar, BorderLayout.NORTH)
        
        // Add warning panel if it exists
        warningPanel?.let {
            topPanel.add(it, BorderLayout.SOUTH)
        }
        
        panel.add(topPanel, BorderLayout.NORTH)
        
        // Create code viewer
        val codePanel = createCodeViewerPanel()
        panel.add(codePanel, BorderLayout.CENTER)
        
        // Create action panel
        val actionPanel = createActionPanel()
        panel.add(actionPanel, BorderLayout.SOUTH)
        
        return panel
    }
    
    private fun createToolbar(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.background = UIUtil.getPanelBackground()
        panel.border = JBUI.Borders.merge(
            JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0),
            JBUI.Borders.empty(8, 12),
            true
        )
        
        val titleLabel = JBLabel("Suggested Code")
        titleLabel.font = UIUtil.getLabelFont().deriveFont(Font.BOLD, 13f)
        titleLabel.icon = AllIcons.Actions.SuggestedRefactoringBulb
        
        panel.add(titleLabel, BorderLayout.WEST)
        
        return panel
    }
    
    private fun createCodeViewerPanel(): JComponent {
        // Create a read-only editor for displaying the suggested code
        val document = EditorFactory.getInstance().createDocument(suggestedCode)
        codeViewer = EditorFactory.getInstance().createViewer(
            document,
            project
        ) as EditorEx
        
        // Configure the viewer
        codeViewer?.apply {
            settings.apply {
                isLineNumbersShown = true
                isWhitespacesShown = mainEditor.settings.isWhitespacesShown
                isIndentGuidesShown = mainEditor.settings.isIndentGuidesShown
                isFoldingOutlineShown = false
                isRightMarginShown = false
                additionalLinesCount = 0
                additionalColumnsCount = 1  // Small buffer
                isCaretRowShown = false
                isUseSoftWraps = false  // Disable soft wraps for better display
                isAnimatedScrolling = false
            }
            
            // Make the viewer truly read-only
            isViewer = true
            
            // Apply syntax highlighting based on file type
            val fileType = mainEditor.virtualFile?.fileType 
                ?: FileTypeManager.getInstance().getFileTypeByExtension("java")
            highlighter = EditorHighlighterFactory.getInstance()
                .createEditorHighlighter(fileType, EditorColorsManager.getInstance().globalScheme, project)
            
            // Apply diff highlighting
            applyDiffHighlighting()
        }
        
        val scrollPane = JBScrollPane(codeViewer?.component)
        scrollPane.border = JBUI.Borders.empty()
        scrollPane.preferredSize = Dimension(WINDOW_WIDTH - 20, WINDOW_HEIGHT - 120) // Account for toolbar and buttons
        
        return scrollPane
    }
    
    private fun createActionPanel(): JComponent {
        val panel = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 8))
        panel.background = UIUtil.getPanelBackground()
        panel.border = JBUI.Borders.merge(
            JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0),
            JBUI.Borders.empty(8),
            true
        )
        
        val acceptButton = JButton("Accept")
        acceptButton.icon = AllIcons.Actions.Checked
        acceptButton.isDefaultButton = true  // Make it visually prominent
        acceptButton.addActionListener {
            onAccept()
            hide()
        }
        
        val rejectButton = JButton("Reject")
        rejectButton.icon = AllIcons.Actions.Close
        rejectButton.addActionListener {
            onReject()
            hide()
        }
        
        panel.add(rejectButton)
        panel.add(acceptButton)
        
        // Make Accept the default button
        SwingUtilities.getRootPane(panel)?.defaultButton = acceptButton
        
        return panel
    }
    
    private fun applyDiffHighlighting() {
        val viewer = codeViewer ?: return
        val service = project.getService(InlineChatService::class.java)
        
        // Generate diff segments
        val segments = service.generateDiffSegments(originalCode, suggestedCode, 0)
        
        // Apply highlighting to the viewer
        val markupModel = viewer.markupModel
        
        segments.forEach { segment ->
            // Skip header and footer segments
            if (segment.type == DiffSegmentType.HEADER || segment.type == DiffSegmentType.FOOTER) {
                return@forEach
            }
            
            if (segment.startLine >= 0 && segment.startLine < viewer.document.lineCount && 
                segment.endLine >= 0 && segment.endLine < viewer.document.lineCount) {
                
                try {
                    val startOffset = viewer.document.getLineStartOffset(segment.startLine)
                    val endOffset = viewer.document.getLineEndOffset(segment.endLine)
                    
                    val attributes = when (segment.type) {
                        DiffSegmentType.INSERTED -> com.intellij.openapi.editor.markup.TextAttributes(
                            null,
                            JBColor(Color(220, 255, 220), Color(45, 65, 45)),  // Lighter green background
                            null,
                            null,
                            Font.PLAIN
                        )
                        DiffSegmentType.DELETED -> com.intellij.openapi.editor.markup.TextAttributes(
                            null,
                            JBColor(Color(255, 230, 230), Color(65, 45, 45)),  // Lighter red background
                            null,
                            null,
                            Font.PLAIN
                        ).apply {
                            effectType = com.intellij.openapi.editor.markup.EffectType.STRIKEOUT
                            effectColor = JBColor.RED
                        }
                        DiffSegmentType.UNCHANGED -> null
                        else -> null
                    }
                    
                    if (attributes != null && startOffset >= 0 && endOffset > startOffset && endOffset <= viewer.document.textLength) {
                        markupModel.addRangeHighlighter(
                            startOffset,
                            endOffset,
                            com.intellij.openapi.editor.markup.HighlighterLayer.SYNTAX,
                            attributes,
                            com.intellij.openapi.editor.markup.HighlighterTargetArea.EXACT_RANGE
                        )
                    }
                } catch (e: Exception) {
                    // Skip invalid offsets
                }
            }
        }
    }
    
    private fun setupPositionListeners() {
        // Listen for editor component resize/move
        val resizeListener = object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                repositionWindow()
            }
            
            override fun componentMoved(e: ComponentEvent) {
                repositionWindow()
            }
        }
        
        mainEditor.component.addComponentListener(resizeListener)
        
        // Clean up listener when popup is closed
        popup?.addListener(object : com.intellij.openapi.ui.popup.JBPopupListener {
            override fun onClosed(event: com.intellij.openapi.ui.popup.LightweightWindowEvent) {
                mainEditor.component.removeComponentListener(resizeListener)
            }
        })
    }
    
    private fun repositionWindow() {
        val currentPopup = popup
        if (currentPopup?.isVisible == true) {
            ApplicationManager.getApplication().invokeLater {
                val size = currentPopup.size
                val newPosition = positionManager.calculateOptimalPosition(size)
                currentPopup.setLocation(newPosition)
            }
        }
    }
    
    override fun dispose() {
        codeViewer?.let { EditorFactory.getInstance().releaseEditor(it) }
        codeViewer = null
    }
}