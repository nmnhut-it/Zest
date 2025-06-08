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
        
        return panel
    }
    
    private fun createDiffViewerPanel(): JComponent {
        // Create JCef browser for diff view
        browser = JBCefBrowser()
        
        // Load the diff HTML
        val diffHtml = generateDiffHtml(originalCode, suggestedCode)
        browser?.loadHTML(diffHtml)
        
        val browserComponent = browser?.component ?: JPanel()
        browserComponent.border = JBUI.Borders.empty()
        
        return browserComponent
    }
    
    private fun generateDiffHtml(original: String, suggested: String): String {
        // Escape HTML characters
        fun escapeHtml(text: String): String {
            return text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;")
        }
        
        // Escape string for JavaScript
        fun escapeForJavaScript(text: String): String {
            return text
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
        }
        
        // Get file extension for syntax highlighting
        val fileExtension = mainEditor.virtualFile?.extension ?: "java"
        
        // Detect if dark theme
        val isDarkTheme = UIUtil.isUnderDarcula()
        val themeClass = if (isDarkTheme) "dark" else "light"
        
        return """
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <title>Code Diff</title>
    <link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/styles/${if (isDarkTheme) "github-dark" else "github"}.min.css">
    <link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/diff2html/3.4.47/bundles/css/diff2html.min.css">
    <script src="https://cdnjs.cloudflare.com/ajax/libs/jsdiff/5.1.0/diff.min.js"></script>
    <script src="https://cdnjs.cloudflare.com/ajax/libs/diff2html/3.4.47/bundles/js/diff2html-ui.min.js"></script>
    <style>
        body {
            margin: 0;
            padding: 0;
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
            background: ${if (isDarkTheme) "#1e1e1e" else "#ffffff"};
            color: ${if (isDarkTheme) "#d4d4d4" else "#333333"};
        }
        #diff-container {
            height: 100vh;
            overflow: auto;
        }
        .d2h-wrapper {
            font-size: 13px;
        }
        .d2h-file-header {
            display: none;
        }
        /* Custom styling for better IDE integration */
        .d2h-code-side-linenumber {
            background: ${if (isDarkTheme) "#2d2d2d" else "#f7f7f7"};
            color: ${if (isDarkTheme) "#858585" else "#666666"};
            border-right: 1px solid ${if (isDarkTheme) "#464647" else "#e1e4e8"};
        }
        .d2h-code-side-line {
            padding: 0 10px;
        }
        .d2h-code-side-line.d2h-ins {
            background: ${if (isDarkTheme) "rgba(87, 171, 90, 0.2)" else "rgba(40, 167, 69, 0.15)"};
        }
        .d2h-code-side-line.d2h-del {
            background: ${if (isDarkTheme) "rgba(203, 36, 49, 0.2)" else "rgba(215, 58, 73, 0.15)"};
        }
        .d2h-code-line-ctn {
            font-family: 'JetBrains Mono', Consolas, 'Courier New', monospace;
        }
        /* Hide file wrapper borders */
        .d2h-file-wrapper {
            border: none;
        }
        /* Adjust side-by-side view */
        .d2h-file-side-diff {
            width: 100%;
        }
        .d2h-code-wrapper {
            width: 50%;
        }
    </style>
</head>
<body class="${themeClass}">
    <div id="diff-container"></div>
    <script>
        // Enable console logging for debugging
        console.log('Diff viewer initialized');
        
        // Create a unified diff using jsdiff library
        function createUnifiedDiff(original, suggested) {
            console.log('Creating diff...');
            console.log('Original length:', original.length);
            console.log('Suggested length:', suggested.length);
            
            // Use jsdiff to create a proper unified diff
            const patch = Diff.createPatch(
                'code',           // filename
                original,         // oldStr
                suggested,        // newStr
                'Original',       // oldHeader
                'AI Suggested',   // newHeader
                { context: 3 }    // options: 3 lines of context
            );
            
            console.log('Diff created, patch length:', patch.length);
            
            // Remove the file header lines that diff2html will add anyway
            const lines = patch.split('\\n');
            const diffContent = lines.slice(2).join('\\n'); // Skip the 'Index:' and '===' lines
            
            console.log('Processed diff content');
            return diffContent;
        }
        
        const original = "${escapeForJavaScript(escapeHtml(original))}";
        const suggested = "${escapeForJavaScript(escapeHtml(suggested))}";
        
        console.log('Input data loaded');
        
        // Generate diff
        const diffString = createUnifiedDiff(original, suggested);
        console.log('Diff string generated');
        
        // Configure diff2html
        const configuration = {
            outputFormat: 'side-by-side',
            drawFileList: false,
            matching: 'lines',
            highlight: true,
            fileContentToggle: false,
            renderNothingWhenEmpty: false,
            synchronisedScroll: true,
            rawTemplates: {
                'generic-wrapper': '<div class="d2h-wrapper">{{content}}</div>'
            }
        };
        
        console.log('Configuration:', configuration);
        
        // Render the diff
        const targetElement = document.getElementById('diff-container');
        console.log('Target element found:', targetElement !== null);
        
        try {
            const diff2htmlUi = new Diff2HtmlUI(targetElement, diffString, configuration);
            diff2htmlUi.draw();
            console.log('Diff drawn successfully');
            
            // Apply syntax highlighting
            diff2htmlUi.highlightCode();
            console.log('Syntax highlighting applied');
        } catch (error) {
            console.error('Error rendering diff:', error);
            targetElement.innerHTML = '<div style="color: red; padding: 20px;">Error rendering diff: ' + error.message + '</div>';
        }
    </script>
</body>
</html>
        """.trimIndent()
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
        browser?.let { 
            Disposer.dispose(it)
        }
        browser = null
    }
}
