package com.zps.zest.inlinechat

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import java.awt.event.ActionEvent
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.KeyEvent
import javax.swing.*
import javax.swing.JPopupMenu
import javax.swing.JMenuItem

/**
 * A floating window that displays AI-suggested code changes with side-by-side diff
 */
class FloatingCodeWindow(
    private val project: Project,
    private val mainEditor: Editor,
    private val suggestedCode: String,
    private val originalCode: String,
    private val onAccept: () -> Unit,
    private val onReject: () -> Unit
) : Disposable {
    
    companion object {
        // Reusable browser instance for performance
        private var sharedBrowser: JBCefBrowser? = null
        
        private fun getOrCreateBrowser(): JBCefBrowser {
            println("=== getOrCreateBrowser called ===")
            
            if (sharedBrowser == null || sharedBrowser!!.isDisposed) {
                println("Creating new JBCefBrowser instance...")
                
                // Enable JCef DevTools
                System.setProperty("ide.browser.jcef.debug.port", "9222")
                System.setProperty("ide.browser.jcef.headless", "false")
                
                try {
                    sharedBrowser = JBCefBrowser()
                    println("JBCefBrowser created successfully")
                } catch (e: Exception) {
                    println("ERROR creating JBCefBrowser: ${e.message}")
                    e.printStackTrace()
                    throw e
                }
                
                // Enable DevTools
                sharedBrowser?.jbCefClient?.setProperty("chromiumSwitches", "--remote-debugging-port=9223")
            } else {
                println("Reusing existing JBCefBrowser instance")
            }
            return sharedBrowser!!
        }
    }
    
    private var popup: JBPopup? = null
    private var browser: JBCefBrowser? = null
    private var warningPanel: JPanel? = null
    
    fun show() {
        ApplicationManager.getApplication().invokeLater {
            if (popup?.isVisible == true) {
                return@invokeLater
            }
            
            val panel = createMainPanel()
            
            // Calculate window dimensions based on editor width and content
            val editorWidth = mainEditor.component.width
            val windowWidth = minOf(editorWidth - 40, 1400)  // Leave some margin, max 1400px
            val lineCount = maxOf(originalCode.lines().size, suggestedCode.lines().size)
            val windowHeight = minOf(600, maxOf(400, lineCount * 20 + 150))  // Dynamic height based on content
            
            popup = JBPopupFactory.getInstance()
                .createComponentPopupBuilder(panel, null)
                .setTitle("AI Suggested Changes - Side by Side Diff")
                .setMovable(true)
                .setResizable(true)
                .setRequestFocus(true)
                .setCancelOnClickOutside(false)
                .setCancelOnOtherWindowOpen(false)
                .setCancelKeyEnabled(true)
                .setCancelCallback { 
                    // Ensure cleanup when cancelled
                    val inlineChatService = project.getService(InlineChatService::class.java)
                    inlineChatService.floatingCodeWindow = null
                    inlineChatService.clearState()
                    true
                }
                .setMinSize(Dimension(800, 400))  // Wider minimum for diff view
                .setDimensionServiceKey(project, "InlineChatFloatingDiffWindow", false)
                .createPopup()
            
            // Position at selection start
            val position = calculatePositionAtSelection(Dimension(windowWidth, windowHeight))
            
            popup?.size = Dimension(windowWidth, windowHeight)
            popup?.show(RelativePoint(Point(position)))
            
            // Add ESC key handler
            popup?.content?.let { content ->
                val inputMap = content.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                val actionMap = content.actionMap
                
                inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "close")
                actionMap.put("close", object : AbstractAction() {
                    override fun actionPerformed(e: ActionEvent?) {
                        onReject()
                        hide()
                    }
                })
            }
            
            // Add listener to handle popup close and position changes
            popup?.addListener(object : com.intellij.openapi.ui.popup.JBPopupListener {
                private val resizeListener = object : ComponentAdapter() {
                    override fun componentResized(e: ComponentEvent) {
                        repositionWindow()
                    }
                    
                    override fun componentMoved(e: ComponentEvent) {
                        repositionWindow()
                    }
                }
                
                override fun beforeShown(event: com.intellij.openapi.ui.popup.LightweightWindowEvent) {
                    mainEditor.component.addComponentListener(resizeListener)
                }
                
                override fun onClosed(event: com.intellij.openapi.ui.popup.LightweightWindowEvent) {
                    mainEditor.component.removeComponentListener(resizeListener)
                    
                    val inlineChatService = project.getService(InlineChatService::class.java)
                    if (inlineChatService.floatingCodeWindow == this@FloatingCodeWindow) {
                        inlineChatService.floatingCodeWindow = null
                        inlineChatService.clearState()
                    }
                }
            })
        }
    }
    
    private fun calculatePositionAtSelection(windowSize: Dimension): Point {
        val inlineChatService = project.getService(InlineChatService::class.java)
        val selectionStart = if (inlineChatService.selectionStartOffset > 0) {
            inlineChatService.selectionStartOffset
        } else {
            mainEditor.selectionModel.selectionStart
        }
        
        // Get the position of the selection start in the editor
        val selectionPoint = mainEditor.offsetToXY(selectionStart)
        val editorLocation = mainEditor.component.locationOnScreen
        
        // Calculate position relative to screen
        var x = editorLocation.x + selectionPoint.x + 50  // Offset to the right of selection
        var y = editorLocation.y + selectionPoint.y - 50   // Slightly above selection
        
        // Ensure window fits on screen
        val screenSize = Toolkit.getDefaultToolkit().screenSize
        if (x + windowSize.width > screenSize.width) {
            x = screenSize.width - windowSize.width - 20
        }
        if (y + windowSize.height > screenSize.height) {
            y = screenSize.height - windowSize.height - 20
        }
        if (y < 0) y = 20
        
        return Point(x, y)
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
        panel.border = JBUI.Borders.empty()
        
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
        
        // Create diff viewer
        val diffPanel = createDiffViewerPanel()
        panel.add(diffPanel, BorderLayout.CENTER)
        
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
        
        val titleLabel = JBLabel("AI Suggested Changes - Side by Side Comparison")
        titleLabel.font = UIUtil.getLabelFont().deriveFont(Font.BOLD)
        titleLabel.icon = AllIcons.Actions.Diff
        
        panel.add(titleLabel, BorderLayout.WEST)
        
        // Add DevTools button to the right side
        val rightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 5, 0))
        rightPanel.background = panel.background
        
        val devToolsButton = JButton("DevTools")
        devToolsButton.icon = AllIcons.Debugger.Console
        devToolsButton.toolTipText = "Open Chrome DevTools (F12)"
        devToolsButton.addActionListener {
            browser?.openDevtools()
        }
        
        rightPanel.add(devToolsButton)
        panel.add(rightPanel, BorderLayout.EAST)
        
        return panel
    }
    
    private fun createDiffViewerPanel(): JComponent {
        // Use shared browser instance for better performance
        browser = getOrCreateBrowser()
        
        // For debugging: print the HTML to console
        val diffHtml = generateDiffHtml(originalCode, suggestedCode)
        println("=== Generated HTML length: ${diffHtml.length} ===")
        println("=== First 500 chars of HTML: ${diffHtml.take(500)} ===")
        
        // Test with simple HTML first (comment out for production)
        val testSimpleHtml = false  // Changed to false to test the actual diff viewer
        if (testSimpleHtml) {
            val simpleTestHtml = """
                <!DOCTYPE html>
                <html>
                <head>
                    <title>Test</title>
                    <meta charset="UTF-8">
                </head>
                <body style="background: lightblue; padding: 20px; font-family: Arial, sans-serif;">
                    <h1>🎉 JCef Test - It's Working!</h1>
                    <p><strong>If you see this, JCef is properly configured!</strong></p>
                    <div style="background: white; padding: 15px; border-radius: 5px; margin: 10px 0;">
                        <p>Original code length: <strong>${originalCode.length}</strong> characters</p>
                        <p>Suggested code length: <strong>${suggestedCode.length}</strong> characters</p>
                    </div>
                    <div style="background: #ffffcc; padding: 15px; border-radius: 5px; margin: 10px 0; border: 2px solid #ff9900;">
                        <p style="color: red; font-weight: bold;">👉 Right-click anywhere for DevTools menu!</p>
                        <p style="color: red;">Or press F12 to open DevTools</p>
                    </div>
                    <div style="background: #ccffcc; padding: 15px; border-radius: 5px; margin: 10px 0;">
                        <p><strong>To see the actual diff view:</strong></p>
                        <ol>
                            <li>Edit FloatingCodeWindow.kt</li>
                            <li>Change line ~291: <code>val testSimpleHtml = false</code></li>
                            <li>Rebuild and test again</li>
                        </ol>
                    </div>
                    <script>
                        console.log('Test page loaded successfully!');
                        console.log('Original code length:', ${originalCode.length});
                        console.log('Suggested code length:', ${suggestedCode.length});
                    </script>
                </body>
                </html>
            """.trimIndent()
            println("Loading simple test HTML...")
            browser?.loadHTML(simpleTestHtml)
            println("Simple test HTML loaded")
        } else {
            // Load the diff HTML
            browser?.loadHTML(diffHtml)
        }
        
        val browserComponent = browser?.component ?: JPanel()
        browserComponent.border = JBUI.Borders.empty()
        
        // Add keyboard shortcuts for DevTools
        browserComponent.isFocusable = true
        browserComponent.addKeyListener(object : java.awt.event.KeyAdapter() {
            override fun keyPressed(e: java.awt.event.KeyEvent) {
                if (e.keyCode == java.awt.event.KeyEvent.VK_F12) {
                    browser?.openDevtools()
                }
            }
        })
        
        // Add context menu for DevTools
        browserComponent.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mousePressed(e: java.awt.event.MouseEvent) {
                if (e.isPopupTrigger || e.button == java.awt.event.MouseEvent.BUTTON3) {
                    showDevToolsMenu(e)
                }
            }
            
            override fun mouseReleased(e: java.awt.event.MouseEvent) {
                if (e.isPopupTrigger) {
                    showDevToolsMenu(e)
                }
            }
            
            private fun showDevToolsMenu(e: java.awt.event.MouseEvent) {
                val popup = JPopupMenu()
                val devToolsItem = JMenuItem("Open DevTools")
                devToolsItem.addActionListener {
                    browser?.openDevtools()
                }
                val reloadItem = JMenuItem("Reload")
                reloadItem.addActionListener {
                    browser?.cefBrowser?.reload()
                }
                popup.add(devToolsItem)
                popup.add(reloadItem)
                popup.show(e.component, e.x, e.y)
            }
        })
        
        return browserComponent
    }
    
    private fun generateDiffHtml(original: String, suggested: String): String {
        try {
            println("=== generateDiffHtml called ===")
            println("Original length: ${original.length}")
            println("Suggested length: ${suggested.length}")
            
            // Detect if dark theme
            val isDarkTheme = UIUtil.isUnderDarcula()
            
            // Use the DiffResourceLoader to generate HTML with embedded resources
            return DiffResourceLoader.generateInlineHtml(original, suggested, isDarkTheme)
        } catch (e: Exception) {
            println("ERROR in generateDiffHtml: ${e.message}")
            e.printStackTrace()
            // Return a simple error page
            return """
<!DOCTYPE html>
<html>
<head><title>Error</title></head>
<body style="background: #ffcccc; padding: 20px;">
    <h1>Error generating diff</h1>
    <p>${e.message}</p>
    <pre>Original length: ${original.length}</pre>
    <pre>Suggested length: ${suggested.length}</pre>
</body>
</html>
            """.trimIndent()
        }
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
    
    private fun repositionWindow() {
        val currentPopup = popup
        if (currentPopup?.isVisible == true) {
            ApplicationManager.getApplication().invokeLater {
                val size = currentPopup.size
                val newPosition = calculatePositionAtSelection(size)
                currentPopup.setLocation(newPosition)
            }
        }
    }
    
    override fun dispose() {
        // Don't dispose the shared browser - just clear our reference
        browser = null
    }
}
