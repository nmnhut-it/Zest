package com.zps.zest.inlinechat

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.notification.NotificationType
import com.zps.zest.ZestNotifications
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import javax.swing.*
import java.awt.BorderLayout
import java.awt.Dimension

/**
 * Diagnostic action to check JCef status and configuration
 */
class JCefDiagnosticAction : AnAction("JCef Diagnostics"), DumbAware {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        
        val diagnostics = StringBuilder()
        diagnostics.appendLine("=== JCef Diagnostic Report ===")
        diagnostics.appendLine()
        
        // Check JCef support
        val isSupported = JBCefApp.isSupported()
        diagnostics.appendLine("JCef Supported: $isSupported")
        
        // Check if JCef browser can be created
        try {
            if (isSupported) {
                val testBrowser = JBCefBrowser()
                diagnostics.appendLine("JBCefBrowser creation: SUCCESS")
                testBrowser.dispose()
            } else {
                diagnostics.appendLine("JBCefBrowser creation: SKIPPED (not supported)")
            }
        } catch (e: Exception) {
            diagnostics.appendLine("JBCefBrowser creation: FAILED")
            diagnostics.appendLine("Error: ${e.message}")
        }
        
        // Check system properties
        diagnostics.appendLine()
        diagnostics.appendLine("System Properties:")
        val properties = listOf(
            "ide.browser.jcef.enabled",
            "ide.browser.jcef.debug.port",
            "ide.browser.jcef.headless",
            "ide.browser.jcef.sandbox",
            "ide.browser.jcef.debug"
        )
        
        properties.forEach { prop ->
            val value = System.getProperty(prop)
            diagnostics.appendLine("  $prop = ${value ?: "not set"}")
        }
        
        // Check resources
        diagnostics.appendLine()
        diagnostics.appendLine("Resource Check:")
        val resources = listOf(
            "js/diff.min.js",
            "js/diff2html.min.css",
            "js/diff2html-ui.min.js",
            "js/github.css",
            "js/github-dark.css"
        )
        
        resources.forEach { resource ->
            val url = this::class.java.classLoader.getResource(resource)
            diagnostics.appendLine("  $resource: ${if (url != null) "FOUND" else "MISSING"}")
        }
        
        // Display results
        val message = diagnostics.toString()
        println(message)
        
        // Show dialog with test button
        val dialog = object : DialogWrapper(project) {
            init {
                title = "JCef Diagnostics"
                init()
            }
            
            override fun createCenterPanel(): JComponent {
                val panel = JPanel(BorderLayout())
                
                val textArea = JTextArea(message)
                textArea.isEditable = false
                textArea.font = textArea.font.deriveFont(12f)
                
                val scrollPane = JScrollPane(textArea)
                scrollPane.preferredSize = Dimension(600, 400)
                
                panel.add(scrollPane, BorderLayout.CENTER)
                
                val buttonPanel = JPanel()
                
                val testButton = JButton("Test JCef Window")
                testButton.addActionListener {
                    testJCefWindow(project)
                }
                buttonPanel.add(testButton)
                
                val instructionsButton = JButton("Show Instructions")
                instructionsButton.addActionListener {
                    showInstructions(project)
                }
                buttonPanel.add(instructionsButton)
                
                panel.add(buttonPanel, BorderLayout.SOUTH)
                
                return panel
            }
        }
        
        dialog.show()
    }
    
    private fun testJCefWindow(project: Project) {
        if (!JBCefApp.isSupported()) {
            ZestNotifications.showError(
                project,
                "JCef Not Supported",
                "JCef is not enabled. Please enable it in Registry and restart."
            )
            return
        }
        
        val dialog = object : DialogWrapper(project) {
            val browser = JBCefBrowser()
            
            init {
                title = "JCef Test Window"
                init()
                
                val html = """
                    <!DOCTYPE html>
                    <html>
                    <head>
                        <style>
                            body { 
                                font-family: Arial, sans-serif; 
                                padding: 20px;
                                background: linear-gradient(45deg, #667eea 0%, #764ba2 100%);
                                color: white;
                            }
                            .test-box {
                                background: rgba(255,255,255,0.1);
                                padding: 20px;
                                border-radius: 10px;
                                margin: 10px 0;
                            }
                            button {
                                background: white;
                                color: #667eea;
                                border: none;
                                padding: 10px 20px;
                                border-radius: 5px;
                                cursor: pointer;
                                font-size: 16px;
                                margin: 5px;
                            }
                            button:hover {
                                background: #f0f0f0;
                            }
                        </style>
                    </head>
                    <body>
                        <h1>🎉 JCef is Working!</h1>
                        <div class="test-box">
                            <h2>If you can see this colorful page, JCef is properly configured!</h2>
                            <p>Time: <span id="time"></span></p>
                            <button onclick="alert('JavaScript is working!')">Test JavaScript</button>
                            <button onclick="console.log('Console test'); document.getElementById('console-test').innerText = 'Check DevTools console!'">Test Console</button>
                            <button onclick="testError()">Test Error Handling</button>
                            <p id="console-test"></p>
                        </div>
                        <div class="test-box">
                            <h3>DevTools Access:</h3>
                            <p>Right-click anywhere and select "Open DevTools" to inspect this page.</p>
                            <p>Or press F12 if available.</p>
                        </div>
                        <script>
                            document.getElementById('time').textContent = new Date().toLocaleString();
                            setInterval(() => {
                                document.getElementById('time').textContent = new Date().toLocaleString();
                            }, 1000);
                            
                            function testError() {
                                try {
                                    throw new Error('Test error - this is intentional!');
                                } catch (e) {
                                    alert('Error caught: ' + e.message);
                                    console.error('Test error:', e);
                                }
                            }
                            
                            console.log('JCef test page loaded successfully!');
                        </script>
                    </body>
                    </html>
                """.trimIndent()
                
                browser.loadHTML(html)
                
                // Add right-click menu
                browser.component.addMouseListener(object : java.awt.event.MouseAdapter() {
                    override fun mousePressed(e: java.awt.event.MouseEvent) {
                        if (e.isPopupTrigger || e.button == java.awt.event.MouseEvent.BUTTON3) {
                            showMenu(e)
                        }
                    }
                    
                    override fun mouseReleased(e: java.awt.event.MouseEvent) {
                        if (e.isPopupTrigger) {
                            showMenu(e)
                        }
                    }
                    
                    private fun showMenu(e: java.awt.event.MouseEvent) {
                        val popup = JPopupMenu()
                        val devToolsItem = JMenuItem("Open DevTools (F12)")
                        devToolsItem.addActionListener {
                            browser.openDevtools()
                        }
                        popup.add(devToolsItem)
                        popup.show(e.component, e.x, e.y)
                    }
                })
            }
            
            override fun createCenterPanel(): JComponent {
                val panel = JPanel(BorderLayout())
                panel.add(browser.component, BorderLayout.CENTER)
                panel.preferredSize = Dimension(800, 600)
                return panel
            }
            
            override fun dispose() {
                browser.dispose()
                super.dispose()
            }
        }
        
        dialog.show()
    }
    
    private fun showInstructions(project: Project) {
        val instructions = """
            JCef Setup Instructions:
            
            1. Enable JCef in Registry:
               - Press Shift+Shift (Search Everywhere)
               - Type "Registry" and open it
               - Find "ide.browser.jcef.enabled"
               - Set it to "true"
               - RESTART IntelliJ IDEA (Required!)
            
            2. After restart, run "JCef Diagnostics" again
            
            3. If still not working:
               - Check if you're using a compatible IDE version
               - Try invalidating caches: File → Invalidate Caches
               - Check IDE logs for errors
            
            4. For the diff viewer to work:
               - Ensure all JS/CSS files are in src/main/resources/js/
               - Clean and rebuild the project
               - Run "Test Simple JCef" first
            
            Remote DevTools Access:
            - If DevTools won't open, navigate to:
              http://localhost:9222
            
            This will show all active JCef instances.
        """.trimIndent()
        
        ZestNotifications.showInfo(
            project,
            "JCef Setup Instructions",
            instructions
        )
    }
}
