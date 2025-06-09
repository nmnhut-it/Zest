package com.zps.zest.inlinechat

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.jcef.JBCefBrowser
import javax.swing.JComponent
import javax.swing.JPanel
import java.awt.BorderLayout
import java.awt.Dimension
import com.intellij.ide.BrowserUtil
import com.intellij.ui.jcef.JBCefApp

/**
 * Simple test to verify JCef is working
 */
class TestSimpleJCefAction : AnAction("Test Simple JCef"), DumbAware {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        
        println("=== Testing Simple JCef ===")
        
        // Check if JCef is supported
        if (!JBCefApp.isSupported()) {
            println("ERROR: JCef is not supported!")
            println("You may need to enable it in Registry:")
            println("1. Press Shift+Shift and search for 'Registry'")
            println("2. Find 'ide.browser.jcef.enabled' and set it to true")
            println("3. Restart IDE")
            return
        }
        
        println("JCef is supported!")
        
        // Simple HTML content
        val simpleHtml = """
<!DOCTYPE html>
<html>
<head>
    <title>JCef Test</title>
    <style>
        body {
            font-family: Arial, sans-serif;
            padding: 20px;
            background-color: #f0f0f0;
        }
        h1 {
            color: #333;
        }
        .test-div {
            background-color: white;
            padding: 20px;
            border-radius: 8px;
            box-shadow: 0 2px 4px rgba(0,0,0,0.1);
        }
        button {
            padding: 10px 20px;
            background-color: #007acc;
            color: white;
            border: none;
            border-radius: 4px;
            cursor: pointer;
        }
        button:hover {
            background-color: #005a9e;
        }
    </style>
</head>
<body>
    <h1>JCef Test Page</h1>
    <div class="test-div">
        <p>If you can see this, JCef is working!</p>
        <p>Current time: <span id="time"></span></p>
        <button onclick="alert('Button clicked!')">Test Button</button>
        <button onclick="console.log('Console log test')">Log to Console</button>
        <button onclick="testDiff()">Test Diff Generation</button>
        <pre id="output"></pre>
    </div>
    <script>
        // Update time
        document.getElementById('time').textContent = new Date().toLocaleString();
        
        // Test console
        console.log('JCef console test');
        console.log('Page loaded successfully');
        
        function testDiff() {
            const output = document.getElementById('output');
            output.textContent = 'Testing diff...\n';
            
            // Test if Diff library would be available
            if (typeof Diff !== 'undefined') {
                output.textContent += 'Diff library is loaded!';
            } else {
                output.textContent += 'Diff library NOT loaded (this is expected in simple test)';
            }
        }
    </script>
</body>
</html>
        """.trimIndent()
        
        // Create dialog
        val dialog = object : DialogWrapper(project) {
            val browser = JBCefBrowser()
            
            init {
                title = "Simple JCef Test"
                init()
                
                // Enable DevTools
                println("Loading HTML into browser...")
                browser.loadHTML(simpleHtml)
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
            
            override fun getPreferredFocusedComponent(): JComponent? {
                return browser.component
            }
        }
        
        dialog.show()
        
        // Also open DevTools URL in external browser
        println("You can also debug at: http://localhost:9222")
        println("=== Test Complete ===")
    }
}
