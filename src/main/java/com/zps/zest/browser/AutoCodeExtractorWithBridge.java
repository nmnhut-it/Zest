package com.zps.zest.browser;

import org.cef.browser.CefBrowser;
import org.cef.handler.CefLoadHandlerAdapter;
import org.cef.browser.CefFrame;
import com.intellij.openapi.diagnostic.Logger;

/**
 * Handler that injects JavaScript to automatically extract code
 * from CodeMirror editors using the existing intellijBridge.
 */
public class AutoCodeExtractorWithBridge extends CefLoadHandlerAdapter {
    private static final Logger LOG = Logger.getInstance(AutoCodeExtractorWithBridge.class);

    @Override
    public void onLoadEnd(CefBrowser browser, CefFrame frame, int httpStatusCode) {
        if (frame.isMain()) {
            injectAutoCodeExtractorScript(browser);
        }
    }

    private void injectAutoCodeExtractorScript(CefBrowser browser) {
        String script =
                "window.extractCodeToIntelliJ = function(textToReplace) {\n" +
                        "    // Find all CodeMirror editors first\n" +
                        "    const cmEditors = document.querySelectorAll('.cm-editor');\n" +
                        "    \n" +
                        "    if (cmEditors.length === 0) {\n" +
                        "        console.log('No CodeMirror editors found');\n" +
                        "        return false;\n" +
                        "    }\n" +
                        "    \n" +
                        "    // Get the most recent editor (last one)\n" +
                        "    const cmEditor = cmEditors[cmEditors.length - 1];\n" +
                        "    \n" +
                        "    // Find the containing code block (parent .relative)\n" +
                        "    let codeBlock = cmEditor;\n" +
                        "    while (codeBlock && (!codeBlock.classList || !codeBlock.classList.contains('relative'))) {\n" +
                        "        codeBlock = codeBlock.parentElement;\n" +
                        "        if (!codeBlock) break;\n" +
                        "    }\n" +
                        "    \n" +
                        "    if (!codeBlock) {\n" +
                        "        console.log('Could not find parent code block');\n" +
                        "        // Try to extract code directly from editor if parent block not found\n" +
                        "        return extractFromEditor(cmEditor, textToReplace);\n" +
                        "    }\n" +
                        "    \n" +
                        "    // Extract language\n" +
                        "    const langElement = codeBlock.querySelector('.text-text-300');\n" +
                        "    const language = langElement ? langElement.textContent.trim() : '';\n" +
                        "    \n" +
                        "    // Extract code from CodeMirror lines\n" +
                        "    return extractFromEditor(cmEditor, textToReplace);\n" +
                        "};\n" +
                        "\n" +
                        "// Helper function to extract code from a CodeMirror editor\n" +
                        "function extractFromEditor(cmEditor,textToReplace ) {\n" +
                        "    if (!cmEditor) return false;\n" +
                        "    \n" +
                        "    // Extract code from CodeMirror lines\n" +
                        "    const codeLines = cmEditor.querySelectorAll('.cm-line');\n" +
                        "    const code = Array.from(codeLines)\n" +
                        "        .map(line => line.textContent)\n" +
                        "        .join('\\n');\n" +
                        "    \n" +
                        "    // Skip if no code\n" +
                        "    if (!code) {\n" +
                        "        console.log('No code content found');\n" +
                        "        return false;\n" +
                        "    }\n" +
                        "    \n" +
                        "    // Send to IntelliJ using the bridge\n" +
                        "    console.log('code', code);\n" +
                        "    if (window.intellijBridge && window.intellijBridge.callIDE) {\n" +
                        "        window.intellijBridge.callIDE('codeCompleted', { text: code, textToReplace: textToReplace })\n" +
                        "            .then(function() {\n" +
                        "                console.log('Code sent to IntelliJ successfully');\n" +
                        "            })\n" +
                        "            .catch(function(error) {\n" +
                        "                console.error('Failed to send code to IntelliJ:', error);\n" +
                        "            });\n" +
                        "        return true;\n" +
                        "    } else {\n" +
                        "        console.error('IntelliJ Bridge not found');\n" +
                        "        return false;\n" +
                        "    }\n" +
                        "}\n" +
                        "\n" +

                        "\n" +
                        "console.log('Compatible code extractor function initialized');\n";
        browser.executeJavaScript(script, browser.getURL(), 0);
        LOG.info("Injected auto code extractor script using intellijBridge");
    }
}