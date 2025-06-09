package com.zps.zest.inlinechat

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.zps.zest.CodeContext
import com.zps.zest.LlmApiCallStage
import com.zps.zest.ZestNotifications
import java.awt.*
import java.awt.event.ActionEvent
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.KeyEvent
import javax.swing.*
import javax.swing.JPopupMenu
import javax.swing.JMenuItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * A floating window that displays AI-suggested code changes with side-by-side diff
 */
class FloatingCodeWindow(
    private val project: Project,
    private val mainEditor: Editor,
    private var suggestedCode: String,
    private var originalCode: String,
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

        /**
         * Create a loading state window
         */
        fun createLoadingWindow(
            project: Project,
            mainEditor: Editor,
            originalCode: String,
            onAccept: () -> Unit,
            onReject: () -> Unit
        ): FloatingCodeWindow {
            return FloatingCodeWindow(
                project,
                mainEditor,
                "", // Empty suggested code initially
                originalCode,
                onAccept,
                onReject
            ).apply {
                isLoading = true
            }
        }
    }

    private var popup: JBPopup? = null
    private var browser: JBCefBrowser? = null
    private var warningPanel: JPanel? = null
    private var isLoading = false  // Default to false, will be set by factory method
    private var loadingPanel: JPanel? = null
    private var contentPanel: JPanel? = null
    private var mainPanel: JPanel? = null
    private var followUpField: JBTextField? = null
    private var followUpLoadingTimer: javax.swing.Timer? = null
    private var acceptButton: JButton? = null
    private var rejectButton: JButton? = null
    private var diffLoadingOverlay: JPanel? = null

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
            val windowHeight = minOf(700, maxOf(500, lineCount * 20 + 150))

            popup = JBPopupFactory.getInstance()
                .createComponentPopupBuilder(panel, followUpField) // Focus on follow-up field
                .setTitle("AI Suggested Changes")
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
                .setMinSize(Dimension(800, 500))  // Larger minimum for better usability
                .setDimensionServiceKey(project, "InlineChatFloatingDiffWindow", false)
                .createPopup()

            // Position at selection start
            val position = calculatePositionAtSelection(Dimension(windowWidth, windowHeight))

            popup?.size = Dimension(windowWidth, windowHeight)
            popup?.show(RelativePoint(Point(position)))

            // Setup keyboard shortcuts
            setupKeyboardShortcuts()

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

    private fun setupKeyboardShortcuts() {
        popup?.content?.let { content ->
            val inputMap = content.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
            val actionMap = content.actionMap

            // ESC to close
            inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "close")
            actionMap.put("close", object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent?) {
                    onReject()
                    hide()
                }
            })

            // Ctrl+Enter or Alt+A to accept
            inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.CTRL_DOWN_MASK), "accept")
            inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_A, KeyEvent.ALT_DOWN_MASK), "accept")
            actionMap.put("accept", object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent?) {
                    onAccept()
                    hide()
                }
            })

            // Ctrl+Shift+Enter or Alt+R to reject
            inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.CTRL_DOWN_MASK or KeyEvent.SHIFT_DOWN_MASK), "reject")
            inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_R, KeyEvent.ALT_DOWN_MASK), "reject")
            actionMap.put("reject", object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent?) {
                    onReject()
                    hide()
                }
            })

            // F12 for DevTools
            inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_F12, 0), "devtools")
            actionMap.put("devtools", object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent?) {
                    browser?.openDevtools()
                }
            })
        }
    }

    /**
     * Update the content of the floating window with the LLM response
     */
    fun updateContent(newSuggestedCode: String, isValid: Boolean = true) {
        ApplicationManager.getApplication().invokeLater {
            isLoading = false
            suggestedCode = newSuggestedCode

            // Stop any loading animation timer
            loadingPanel?.let { panel ->
                (panel.getClientProperty("loadingTimer") as? javax.swing.Timer)?.stop()
            }

            // Update warning if validation failed
            if (!isValid) {
                showWarning("The suggested changes may have significantly altered the code structure. Please review carefully.")
            }

            // Refresh the content
            mainPanel?.let { panel ->
                panel.removeAll()

                // Create top section with follow-up and actions
                val topSection = createTopSection()
                panel.add(topSection, BorderLayout.NORTH)

                // Create diff viewer
                val diffPanel = createDiffViewerPanel()
                panel.add(diffPanel, BorderLayout.CENTER)

                panel.revalidate()
                panel.repaint()

                // Focus on follow-up field
                followUpField?.requestFocusInWindow()
            }
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
                // Add warning to the top section if it exists
                mainPanel?.let { panel ->
                    (panel.getComponent(0) as? JPanel)?.let { topSection ->
                        topSection.add(warningPanel, BorderLayout.SOUTH)
                        topSection.revalidate()
                        topSection.repaint()
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
        mainPanel = JPanel(BorderLayout())
        mainPanel!!.border = JBUI.Borders.empty()

        if (isLoading) {
            // Show loading state
            loadingPanel = createLoadingPanel()
            mainPanel!!.add(loadingPanel!!, BorderLayout.CENTER)
        } else {
            // Create top section with follow-up and actions
            val topSection = createTopSection()
            mainPanel!!.add(topSection, BorderLayout.NORTH)

            // Create diff viewer
            val diffPanel = createDiffViewerPanel()
            mainPanel!!.add(diffPanel, BorderLayout.CENTER)
        }

        return mainPanel!!
    }

    private fun createLoadingPanel(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.background = UIUtil.getPanelBackground()
        panel.border = JBUI.Borders.empty(50)

        val centerPanel = JPanel()
        centerPanel.layout = BoxLayout(centerPanel, BoxLayout.Y_AXIS)
        centerPanel.background = panel.background

        // Add spinning icon
        val loadingIcon = JBLabel(AllIcons.Process.Big.Step_1)
        loadingIcon.alignmentX = Component.CENTER_ALIGNMENT
        centerPanel.add(loadingIcon)

        centerPanel.add(Box.createVerticalStrut(20))

        // Add loading text
        val loadingLabel = JBLabel("Processing your request...")
        loadingLabel.font = loadingLabel.font.deriveFont(Font.BOLD, 16f)
        loadingLabel.alignmentX = Component.CENTER_ALIGNMENT
        centerPanel.add(loadingLabel)

        centerPanel.add(Box.createVerticalStrut(10))

        val detailLabel = JBLabel("AI is analyzing your code and generating suggestions")
        detailLabel.font = detailLabel.font.deriveFont(14f)
        detailLabel.foreground = UIUtil.getContextHelpForeground()
        detailLabel.alignmentX = Component.CENTER_ALIGNMENT
        centerPanel.add(detailLabel)

        // Center the content
        val wrapper = JPanel(GridBagLayout())
        wrapper.background = panel.background
        wrapper.add(centerPanel)

        panel.add(wrapper, BorderLayout.CENTER)

        // Animate the loading icon
        val timer = javax.swing.Timer(100) { _ ->
            val icons = listOf(
                AllIcons.Process.Big.Step_1,
                AllIcons.Process.Big.Step_2,
                AllIcons.Process.Big.Step_3,
                AllIcons.Process.Big.Step_4,
                AllIcons.Process.Big.Step_5,
                AllIcons.Process.Big.Step_6,
                AllIcons.Process.Big.Step_7,
                AllIcons.Process.Big.Step_8
            )
            val currentIndex = icons.indexOf(loadingIcon.icon)
            val nextIndex = (currentIndex + 1) % icons.size
            loadingIcon.icon = icons[nextIndex]
        }
        timer.start()

        // Store timer reference to stop it later
        panel.putClientProperty("loadingTimer", timer)

        return panel
    }

    private fun createTopSection(): JPanel {
        val topSection = JPanel(BorderLayout())
        topSection.background = UIUtil.getPanelBackground()

        // Create follow-up panel with visible background
        val followUpPanel = createFollowUpPanel()
        topSection.add(followUpPanel, BorderLayout.CENTER)

        // Create minimal action panel
        val actionPanel = createMinimalActionPanel()
        topSection.add(actionPanel, BorderLayout.EAST)

        // Add warning panel if it exists
        warningPanel?.let {
            topSection.add(it, BorderLayout.SOUTH)
        }

        return topSection
    }

    private fun createFollowUpPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        
        // Make the background more visible - different color based on theme
        val bgColor = if (UIUtil.isUnderDarcula()) {
            JBColor(Color(60, 63, 65), Color(45, 48, 50))  // Darker than editor background
        } else {
            JBColor(Color(245, 245, 247), Color(230, 230, 235))  // Slightly darker than editor
        }
        panel.background = bgColor
        
        panel.border = JBUI.Borders.merge(
            JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0),
            JBUI.Borders.empty(12, 12, 12, 8),
            true
        )

        // Create text field with contrasting background
        followUpField = JBTextField()
        followUpField!!.toolTipText = "Type a refinement request and press Enter to update the suggestion (Ctrl+Enter to accept current)"
        
        // Make the text field background slightly different from panel background
        val fieldBgColor = if (UIUtil.isUnderDarcula()) {
            JBColor(Color(69, 73, 74), Color(50, 53, 55))
        } else {
            JBColor(Color(255, 255, 255), Color(240, 240, 243))
        }
        followUpField!!.background = fieldBgColor
        
        // Add some padding to the text field
        followUpField!!.border = BorderFactory.createCompoundBorder(
            JBUI.Borders.customLine(JBColor.border(), 1),
            JBUI.Borders.empty(8, 12)
        )
        
        // Increase font size slightly for better visibility
        followUpField!!.font = followUpField!!.font.deriveFont(followUpField!!.font.size + 1f)
        
        // Add key listener for Enter key
        followUpField!!.addActionListener {
            handleFollowUp()
        }

        // Add helpful placeholder text with examples
        followUpField!!.emptyText.text = "Refine suggestion (e.g., 'add error handling') — Press Enter"
        
        panel.add(followUpField!!, BorderLayout.CENTER)

        return panel
    }

    private fun createMinimalActionPanel(): JPanel {
        val panel = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0))
        panel.background = UIUtil.getPanelBackground()
        panel.border = JBUI.Borders.empty(12, 0, 12, 12)

        // Create compact buttons
        acceptButton = JButton("Accept")
        acceptButton!!.icon = AllIcons.Actions.Checked
        acceptButton!!.toolTipText = "Accept changes (Ctrl+Enter)"
        acceptButton!!.putClientProperty("JButton.buttonType", "segmented-only")
        acceptButton!!.addActionListener {
            onAccept()
            hide()
        }

        rejectButton = JButton("Reject")
        rejectButton!!.icon = AllIcons.Actions.Close
        rejectButton!!.toolTipText = "Reject changes (Escape)"
        rejectButton!!.putClientProperty("JButton.buttonType", "segmented-only")
        rejectButton!!.addActionListener {
            onReject()
            hide()
        }

        panel.add(acceptButton!!)
        panel.add(rejectButton!!)

        return panel
    }

    private fun createDiffViewerPanel(): JComponent {
        // Create a container panel that can hold both browser and overlay
        val containerPanel = JPanel(BorderLayout())
        containerPanel.border = JBUI.Borders.empty()
        
        // Use shared browser instance for better performance
        browser = getOrCreateBrowser()

        // Generate and load the diff HTML
        val diffHtml = generateDiffHtml(originalCode, suggestedCode)
        browser?.loadHTML(diffHtml)

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
                val devToolsItem = JMenuItem("Open DevTools (F12)")
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

        // Add browser to container
        containerPanel.add(browserComponent, BorderLayout.CENTER)
        
        return containerPanel
    }

    private fun handleFollowUp() {
        val followUpText = followUpField?.text?.trim() ?: ""
        if (followUpText.isEmpty()) return

        // Disable input during processing
        followUpField?.isEnabled = false
        acceptButton?.isEnabled = false
        rejectButton?.isEnabled = false
        
        // Show loading state in the follow-up field
        showFollowUpLoading()
        
        // Show loading overlay on the diff viewer
        showDiffViewerLoading()

        // Get the inline chat service
        val inlineChatService = project.getService(InlineChatService::class.java)
        
        // Create a new command that includes the follow-up context
        val contextualCommand = buildString {
            append("The user has reviewed your suggested changes and has the following follow-up request:\n")
            append("\"$followUpText\"\n\n")
            append("Please update your suggestion based on this feedback. ")
            append("Keep the same overall structure but apply the requested refinements.")
        }

        // Process the follow-up command
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Create ChatEditParams with the follow-up command
                val params = ChatEditParams(
                    location = inlineChatService.location ?: return@launch,
                    command = contextualCommand
                )

                // Process the command (this will get a new LLM response)
                processInlineChatCommand(project, params, object : LlmResponseProvider {
                    override suspend fun getLlmResponse(codeContext: CodeContext): String? {
                        // Include the current suggested code in the context
                        codeContext.setPrompt(buildString {
                            append("Original code:\n```\n")
                            append(originalCode)
                            append("\n```\n\n")
                            append("Current suggestion:\n```\n")
                            append(suggestedCode)
                            append("\n```\n\n")
                            append("User feedback: ")
                            append(followUpText)
                            append("\n\nPlease provide an updated code suggestion based on the user's feedback.")
                        })
                        
                        val llmStage = LlmApiCallStage()
                        llmStage.process(codeContext)
                        return codeContext.getApiResponse()
                    }
                })

                // Wait a bit for the response to be processed
                kotlinx.coroutines.delay(500)

                // Update the UI with the new suggestion
                ApplicationManager.getApplication().invokeLater {
                    val newSuggestedCode = inlineChatService.extractedCode
                    if (newSuggestedCode != null && newSuggestedCode != suggestedCode) {
                        // Update the content with the new suggestion
                        suggestedCode = newSuggestedCode
                        
                        // Refresh the diff viewer
                        val diffHtml = generateDiffHtml(originalCode, suggestedCode)
                        browser?.loadHTML(diffHtml)
                        
                        // Clear and re-enable the follow-up field
                        followUpField?.text = ""
                        followUpField?.isEnabled = true
                        acceptButton?.isEnabled = true
                        rejectButton?.isEnabled = true
                        followUpField?.requestFocusInWindow()
                        
                        // Stop loading animation
                        stopFollowUpLoading()
                        hideDiffViewerLoading()
                    } else {
                        // No new suggestion or error
                        followUpField?.isEnabled = true
                        acceptButton?.isEnabled = true
                        rejectButton?.isEnabled = true
                        followUpField?.requestFocusInWindow()
                        stopFollowUpLoading()
                        hideDiffViewerLoading()
                        
                        if (newSuggestedCode == null) {
                            ZestNotifications.showWarning(
                                project,
                                "Follow-up Request",
                                "Unable to process follow-up request. Please try rephrasing."
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                ApplicationManager.getApplication().invokeLater {
                    followUpField?.isEnabled = true
                    acceptButton?.isEnabled = true
                    rejectButton?.isEnabled = true
                    followUpField?.requestFocusInWindow()
                    stopFollowUpLoading()
                    hideDiffViewerLoading()
                    
                    ZestNotifications.showError(
                        project,
                        "Follow-up Error",
                        "Error processing follow-up: ${e.message}"
                    )
                }
            }
        }
    }

    private fun showFollowUpLoading() {
        // Change the field background to indicate processing
        val processingBgColor = if (UIUtil.isUnderDarcula()) {
            JBColor(Color(45, 60, 45), Color(35, 50, 35))  // Slight green tint
        } else {
            JBColor(Color(240, 250, 240), Color(225, 240, 225))  // Light green tint
        }
        followUpField?.background = processingBgColor
        
        followUpField?.emptyText?.text = "Processing..."
        
        // Animate dots in placeholder
        var dotCount = 0
        followUpLoadingTimer = javax.swing.Timer(400) { _ ->
            dotCount = (dotCount + 1) % 4
            val dots = ".".repeat(dotCount)
            followUpField?.emptyText?.text = "Processing$dots"
        }
        followUpLoadingTimer?.start()
    }

    private fun stopFollowUpLoading() {
        followUpLoadingTimer?.stop()
        followUpLoadingTimer = null
        followUpField?.emptyText?.text = "Refine suggestion (e.g., 'add error handling') — Press Enter"
        
        // Restore original background color
        val fieldBgColor = if (UIUtil.isUnderDarcula()) {
            JBColor(Color(69, 73, 74), Color(50, 53, 55))
        } else {
            JBColor(Color(255, 255, 255), Color(240, 240, 243))
        }
        followUpField?.background = fieldBgColor
    }

    private fun showDiffViewerLoading() {
        browser?.component?.let { browserComponent ->
            // Create loading overlay if it doesn't exist
            if (diffLoadingOverlay == null) {
                diffLoadingOverlay = createDiffLoadingOverlay()
            }
            
            // Get the parent container of the browser
            val parent = browserComponent.parent
            if (parent is JPanel && parent.layout is BorderLayout) {
                // Add overlay on top of browser
                parent.add(diffLoadingOverlay!!, BorderLayout.CENTER)
                parent.revalidate()
                parent.repaint()
                
                // Move browser to back
                parent.setComponentZOrder(diffLoadingOverlay!!, 0)
                parent.setComponentZOrder(browserComponent, 1)
            }
        }
    }

    private fun hideDiffViewerLoading() {
        diffLoadingOverlay?.let { overlay ->
            overlay.parent?.let { parent ->
                parent.remove(overlay)
                parent.revalidate()
                parent.repaint()
            }
        }
    }

    private fun createDiffLoadingOverlay(): JPanel {
        val overlay = object : JPanel() {
            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                // Semi-transparent background
                g.color = Color(0, 0, 0, 50)
                g.fillRect(0, 0, width, height)
            }
        }
        overlay.isOpaque = false
        overlay.layout = GridBagLayout()
        
        // Create loading content panel
        val loadingPanel = JPanel()
        loadingPanel.layout = BoxLayout(loadingPanel, BoxLayout.Y_AXIS)
        loadingPanel.background = UIUtil.getPanelBackground()
        loadingPanel.border = BorderFactory.createCompoundBorder(
            JBUI.Borders.customLine(JBColor.border()),
            JBUI.Borders.empty(20)
        )
        
        // Add loading icon
        val loadingIcon = JBLabel(AllIcons.Process.Big.Step_1)
        loadingIcon.alignmentX = Component.CENTER_ALIGNMENT
        loadingPanel.add(loadingIcon)
        
        loadingPanel.add(Box.createVerticalStrut(10))
        
        // Add loading text
        val loadingLabel = JBLabel("AI is refining the suggestion...")
        loadingLabel.font = UIUtil.getLabelFont().deriveFont(Font.BOLD, 14f)
        loadingLabel.alignmentX = Component.CENTER_ALIGNMENT
        loadingPanel.add(loadingLabel)
        
        loadingPanel.add(Box.createVerticalStrut(8))
        
        val tipLabel = JBLabel("This may take a few seconds")
        tipLabel.font = UIUtil.getLabelFont().deriveFont(12f)
        tipLabel.foreground = UIUtil.getContextHelpForeground()
        tipLabel.alignmentX = Component.CENTER_ALIGNMENT
        loadingPanel.add(tipLabel)
        
        overlay.add(loadingPanel)
        
        // Animate the loading icon
        javax.swing.Timer(100) { _ ->
            val icons = listOf(
                AllIcons.Process.Big.Step_1,
                AllIcons.Process.Big.Step_2,
                AllIcons.Process.Big.Step_3,
                AllIcons.Process.Big.Step_4,
                AllIcons.Process.Big.Step_5,
                AllIcons.Process.Big.Step_6,
                AllIcons.Process.Big.Step_7,
                AllIcons.Process.Big.Step_8
            )
            val currentIndex = icons.indexOf(loadingIcon.icon)
            val nextIndex = (currentIndex + 1) % icons.size
            loadingIcon.icon = icons[nextIndex]
        }.start()
        
        return overlay
    }

    private fun generateDiffHtml(original: String, suggested: String): String {
        try {
            println("=== generateDiffHtml called ===")
            println("Original length: ${original.length}")
            println("Suggested length: ${suggested.length}")

            // Detect if dark theme
            val isDarkTheme = UIUtil.isUnderDarcula()

            // Detect language from editor file type
            val virtualFile = FileDocumentManager.getInstance().getFile(mainEditor.document)
            val fileType = virtualFile?.fileType
            val language = when (fileType?.name?.toLowerCase()) {
                "java" -> "java"
                "kotlin" -> "kotlin"
                "javascript" -> "javascript"
                "typescript" -> "typescript"
                "python" -> "python"
                "c++" -> "cpp"
                "c#" -> "csharp"
                "go" -> "go"
                "rust" -> "rust"
                "ruby" -> "ruby"
                "php" -> "php"
                "swift" -> "swift"
                "scala" -> "scala"
                "html" -> "html"
                "css" -> "css"
                "xml" -> "xml"
                "json" -> "json"
                "yaml" -> "yaml"
                "sql" -> "sql"
                else -> {
                    // Try to get from file extension as fallback
                    val extension = virtualFile?.extension?.toLowerCase()
                    when (extension) {
                        "java" -> "java"
                        "kt", "kts" -> "kotlin"
                        "js", "jsx" -> "javascript"
                        "ts", "tsx" -> "typescript"
                        "py" -> "python"
                        "cpp", "cc", "cxx" -> "cpp"
                        "cs" -> "csharp"
                        "go" -> "go"
                        "rs" -> "rust"
                        "rb" -> "ruby"
                        "php" -> "php"
                        "swift" -> "swift"
                        "scala" -> "scala"
                        "html", "htm" -> "html"
                        "css" -> "css"
                        "xml" -> "xml"
                        "json" -> "json"
                        "yaml", "yml" -> "yaml"
                        "sql" -> "sql"
                        else -> "text" // default to plain text
                    }
                }
            }

            println("Detected language: $language")

            // Use the DiffResourceLoader to generate HTML with embedded resources
            return DiffResourceLoader.generateInlineHtml(original, suggested, isDarkTheme, language)
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
        // Stop any timers
        loadingPanel?.let { panel ->
            (panel.getClientProperty("loadingTimer") as? javax.swing.Timer)?.stop()
        }
        followUpLoadingTimer?.stop()

        // Don't dispose the shared browser - just clear our reference
        browser = null
    }
}
