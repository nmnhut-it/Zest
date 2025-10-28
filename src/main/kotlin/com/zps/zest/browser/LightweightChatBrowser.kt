package com.zps.zest.browser

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import javax.swing.JComponent

/**
 * Lightweight browser for chat UI only - no auth, cookies, or network interceptors.
 * Uses shared JBCefClient to avoid black screen issues.
 */
class LightweightChatBrowser(
    private val project: Project
) : Disposable {

    companion object {
        private val LOG = Logger.getInstance(LightweightChatBrowser::class.java)
    }

    private val browser: JBCefBrowser
    private var jsQuery: JBCefJSQuery? = null
    private var jsBridge: ChatJavaScriptBridge? = null
    @Volatile
    private var isDisposed = false

    init {
        LOG.info("Creating lightweight chat browser for project: ${project.name}")

        val client = JCEFClientProvider.getSharedClient()
        browser = JBCefBrowser.createBuilder()
            .setClient(client)
            .setOffScreenRendering(false)
            .setEnableOpenDevToolsMenuItem(true)
            .setUrl("about:blank")
            .build()

        setupChatBridge()

        browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(cefBrowser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                injectChatBridge(cefBrowser, frame)
            }
        }, browser.cefBrowser)
    }

    private fun setupChatBridge() {
        jsBridge = ChatJavaScriptBridge(project)
        jsQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)

        jsQuery?.addHandler { query ->
            try {
                val result = jsBridge?.handleQuery(query) ?: "{}"
                JBCefJSQuery.Response(result)
            } catch (e: Exception) {
                LOG.error("Error handling chat query", e)
                JBCefJSQuery.Response(null, 500, e.message)
            }
        }
    }

    private fun injectChatBridge(cefBrowser: CefBrowser, frame: CefFrame) {
        val query = jsQuery ?: return

        val bridgeScript = """
            window.chatBridge = {
                send: function(action, data) {
                    return new Promise((resolve, reject) => {
                        const request = JSON.stringify({ action: action, data: data });
                        ${query.inject("request",
                            "function(response) { try { resolve(JSON.parse(response)); } catch(e) { resolve(response); } }",
                            "function(errorCode, errorMessage) { reject({code: errorCode, message: errorMessage}); }"
                        )}
                    });
                }
            };
            console.log('Chat bridge initialized');
        """.trimIndent()

        cefBrowser.executeJavaScript(bridgeScript, frame.url, 0)
    }

    fun loadURL(url: String) {
        if (isDisposed) {
            LOG.warn("Attempted to load URL on disposed browser: $url")
            return
        }
        LOG.info("Loading URL in chat browser: $url")
        browser.loadURL(url)
    }

    fun executeJavaScript(script: String) {
        if (isDisposed) {
            LOG.warn("Attempted to execute JavaScript on disposed browser")
            return
        }
        browser.cefBrowser.executeJavaScript(script, browser.cefBrowser.url, 0)
    }

    fun getComponent(): JComponent? {
        if (isDisposed) {
            LOG.warn("Attempted to get component from disposed browser")
            return null
        }
        return browser.component
    }

    fun getCefBrowser(): CefBrowser? {
        if (isDisposed) {
            LOG.warn("Attempted to get CEF browser from disposed instance")
            return null
        }
        return browser.cefBrowser
    }

    override fun dispose() {
        if (isDisposed) {
            LOG.warn("LightweightChatBrowser already disposed, skipping")
            return
        }

        LOG.info("Disposing lightweight chat browser")
        isDisposed = true

        // Dispose jsQuery first
        jsQuery?.let {
            try {
                Disposer.dispose(it)
            } catch (e: Exception) {
                LOG.warn("Error disposing jsQuery", e)
            }
        }
        jsQuery = null
        jsBridge = null

        // Dispose browser
        try {
            browser.cefBrowser.loadURL("about:blank")
            Thread.sleep(100)
            browser.cefBrowser.close(true)
            Thread.sleep(200)
            Disposer.dispose(browser)
            JCEFClientProvider.releaseSharedClient()
            System.gc()
        } catch (e: Exception) {
            LOG.warn("Error disposing browser", e)
        }

        LOG.info("Lightweight chat browser disposed")
    }

    /**
     * Check if this browser instance has been disposed
     */
    fun isDisposed(): Boolean = isDisposed

    /**
     * Minimal JavaScript bridge for chat-specific actions only
     */
    private class ChatJavaScriptBridge(private val project: Project) {
        fun handleQuery(query: String): String {
            // Placeholder for chat-specific bridge actions
            // Can be extended later if needed
            LOG.info("Chat bridge query: $query")
            return "{\"status\":\"ok\"}"
        }

        companion object {
            private val LOG = Logger.getInstance(ChatJavaScriptBridge::class.java)
        }
    }
}
