package com.zps.zest.completion.diff

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.DiffRequestPanel
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.zps.zest.completion.util.IndentationNormalizer
import java.awt.*
import java.awt.event.ActionEvent
import javax.swing.*
import java.util.HashSet

/**
 * Custom dialog for showing method rewrite diff with better syntax highlighting
 * Uses LightVirtualFile for proper language support
 */
class MethodRewriteDiffDialogV2(
    private val project: Project,
    private val editor: Editor,
    private val methodStartOffset: Int,
    private val methodEndOffset: Int,
    private val originalContent: String,
    private val modifiedContent: String,
    private val fileType: FileType,
    private val methodName: String,
    private val onAccept: () -> Unit,
    private val onReject: () -> Unit
) : DialogWrapper(project, false) {  // Non-modal
    
    companion object {
        private val logger = Logger.getInstance(MethodRewriteDiffDialogV2::class.java)
        
        fun show(
            project: Project,
            editor: Editor,
            methodStartOffset: Int,
            methodEndOffset: Int,
            originalContent: String,
            modifiedContent: String,
            fileType: FileType,
            methodName: String,
            onAccept: () -> Unit,
            onReject: () -> Unit
        ) {
            ApplicationManager.getApplication().invokeLater {
                val dialog = MethodRewriteDiffDialogV2(
                    project, editor, methodStartOffset, methodEndOffset,
                    originalContent, modifiedContent, fileType, methodName,
                    onAccept, onReject
                )
                dialog.show()
            }
        }
    }
    
    private var diffPanel: DiffRequestPanel? = null
    
    init {
        title = "Review Method Rewrite: $methodName"
        init()
        setSize(1200, 800)
    }
    
    override fun createCenterPanel(): JComponent {
        val panel = JBPanel<JBPanel<*>>(BorderLayout())
        
        // Create full file content with method replaced for proper syntax highlighting
        val fullFileContent = editor.document.text
        val originalFullFile = fullFileContent // Original file as-is
        val modifiedFullFile = buildString {
            append(fullFileContent.substring(0, methodStartOffset))
            append(modifiedContent)
            append(fullFileContent.substring(methodEndOffset))
        }
        
        // Normalize indentation for the full files
        val (normalizedOriginal, normalizedModified) = IndentationNormalizer.normalizeForDiff(
            originalFullFile,
            modifiedFullFile
        )
        
        // Create virtual files with proper file type for syntax highlighting
        val virtualFile = editor.virtualFile
        val originalFileName = virtualFile?.name ?: "${methodName}_original.${fileType.defaultExtension}"
        val modifiedFileName = virtualFile?.name ?: "${methodName}_modified.${fileType.defaultExtension}"
        
        val originalFile = LightVirtualFile(
            originalFileName,
            fileType,
            normalizedOriginal
        )
        val modifiedFile = LightVirtualFile(
            modifiedFileName,
            fileType,
            normalizedModified
        )
        
        // Create diff contents from virtual files
        val diffContentFactory = DiffContentFactory.getInstance()
        val leftContent = diffContentFactory.create(project, originalFile)
        val rightContent = diffContentFactory.create(project, modifiedFile)
        
        // Create diff request
        val diffRequest = SimpleDiffRequest(
            "Method Rewrite Comparison",
            leftContent,
            rightContent,
            "Original Method",
            "AI Improved Method"
        )
        
        // Create diff panel
        val diffPanel = DiffManager.getInstance().createRequestPanel(project, disposable, null)
        diffPanel.setRequest(diffRequest)
        this.diffPanel = diffPanel
        
        panel.add(diffPanel.component, BorderLayout.CENTER)
        
        // Add header with instructions
        val headerPanel = createHeaderPanel()
        panel.add(headerPanel, BorderLayout.NORTH)
        
        return panel
    }
    
    private fun createHeaderPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.background = UIUtil.getPanelBackground()
        panel.border = JBUI.Borders.compound(
            JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0),
            JBUI.Borders.empty(10)
        )
        
        val infoPanel = JPanel(GridBagLayout())
        infoPanel.isOpaque = false
        val gbc = GridBagConstraints()
        
        // Title
        gbc.gridx = 0
        gbc.gridy = 0
        gbc.anchor = GridBagConstraints.WEST
        gbc.insets = JBUI.insets(0, 0, 5, 0)
        
        val titleLabel = JLabel("AI Method Rewrite Comparison")
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, 16f)
        infoPanel.add(titleLabel, gbc)
        
        // Instructions
        gbc.gridy = 1
        val instructionLabel = JLabel("Review the changes carefully. Green = additions, Red = deletions")
        instructionLabel.foreground = JBColor.GRAY
        infoPanel.add(instructionLabel, gbc)
        
        panel.add(infoPanel, BorderLayout.WEST)
        
        // Button hints
        val buttonHintPanel = JPanel(FlowLayout(FlowLayout.RIGHT))
        buttonHintPanel.isOpaque = false
        
        val acceptHint = createButtonHint("Accept", JBColor.GREEN, AllIcons.Actions.Commit)
        val rejectHint = createButtonHint("Reject", JBColor.RED, AllIcons.Actions.Cancel)
        
        buttonHintPanel.add(acceptHint)
        buttonHintPanel.add(Box.createHorizontalStrut(20))
        buttonHintPanel.add(rejectHint)
        
        panel.add(buttonHintPanel, BorderLayout.EAST)
        
        return panel
    }
    
    private fun createButtonHint(text: String, color: Color, icon: Icon): JComponent {
        val panel = JPanel(FlowLayout(FlowLayout.LEFT, 5, 0))
        panel.isOpaque = false
        
        val iconLabel = JLabel(icon)
        panel.add(iconLabel)
        
        val textLabel = JLabel(text)
        textLabel.foreground = color
        textLabel.font = textLabel.font.deriveFont(Font.BOLD)
        panel.add(textLabel)
        
        return panel
    }
    
    
    override fun createActions(): Array<Action> {
        return emptyArray() // We'll create custom buttons in createSouthPanel
    }
    
    override fun createSouthPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.border = JBUI.Borders.empty(8, 12)
        
        // Create button panel
        val buttonPanel = JPanel(FlowLayout(FlowLayout.CENTER, 20, 0))
        
        // Accept button with custom styling
        val acceptButton = createCustomButton(
            "Accept Changes",
            AllIcons.Actions.Commit,
            JBColor(Color(46, 160, 67), Color(40, 120, 50)),
            "Accept changes (Tab)"
        ) {
            logger.info("User accepted method rewrite")
            onAccept()
            close(OK_EXIT_CODE)
        }
        
        // Reject button with custom styling
        val rejectButton = createCustomButton(
            "Reject Changes",
            AllIcons.Actions.Cancel,
            JBColor(Color(217, 83, 79), Color(160, 50, 50)),
            "Reject changes (Esc)"
        ) {
            logger.info("User rejected method rewrite")
            onReject()
            close(CANCEL_EXIT_CODE)
        }
        
        buttonPanel.add(acceptButton)
        buttonPanel.add(rejectButton)
        
        panel.add(buttonPanel, BorderLayout.CENTER)
        
        // Make Accept the default button
        rootPane.defaultButton = acceptButton
        
        // Add keyboard shortcuts
        val inputMap = rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
        val actionMap = rootPane.actionMap
        
        // Override focus traversal keys to allow Tab for accept
        val focusTraversalKeys = HashSet<AWTKeyStroke>()
        rootPane.setFocusTraversalKeys(KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS, focusTraversalKeys)
        
        // Tab to accept
        inputMap.put(KeyStroke.getKeyStroke("TAB"), "accept")
        actionMap.put("accept", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                acceptButton.doClick()
            }
        })
        
        // Add hint label
        val hintLabel = JLabel("Tab to accept, Esc to reject")
        hintLabel.foreground = JBColor.GRAY
        hintLabel.font = hintLabel.font.deriveFont(11f)
        hintLabel.horizontalAlignment = SwingConstants.CENTER
        
        val hintPanel = JPanel(FlowLayout(FlowLayout.CENTER))
        hintPanel.add(hintLabel)
        panel.add(hintPanel, BorderLayout.SOUTH)
        
        return panel
    }
    
    override fun doCancelAction() {
        logger.info("Dialog cancelled (ESC pressed)")
        onReject()
        super.doCancelAction()
    }
    
    private fun createCustomButton(
        text: String,
        icon: Icon,
        backgroundColor: JBColor,
        tooltip: String,
        onClick: () -> Unit
    ): JButton {
        val button = object : JButton(text, icon) {
            override fun paintComponent(g: Graphics) {
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                
                // Fill background
                g2.color = backgroundColor
                g2.fillRoundRect(0, 0, width, height, 8, 8)
                
                // Draw text and icon
                g2.color = JBColor(Color.WHITE, Color.WHITE)
                val fm = g2.fontMetrics
                val textWidth = fm.stringWidth(text)
                val iconWidth = icon.iconWidth
                val totalWidth = iconWidth + 8 + textWidth
                val startX = (width - totalWidth) / 2
                
                // Draw icon
                icon.paintIcon(this, g2, startX, (height - icon.iconHeight) / 2)
                
                // Draw text
                g2.drawString(text, startX + iconWidth + 8, (height + fm.ascent) / 2 - 2)
            }
        }
        
        button.font = button.font.deriveFont(Font.BOLD, 14f)
        button.preferredSize = Dimension(180, 40)
        button.isFocusPainted = false
        button.isOpaque = false  // Important for custom painting
        button.isBorderPainted = false
        button.isContentAreaFilled = false  // Important for custom painting
        button.toolTipText = tooltip
        button.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        button.addActionListener { onClick() }
        
        return button
    }
    
    override fun dispose() {
        diffPanel?.let { Disposer.dispose(it) }
        super.dispose()
    }
}