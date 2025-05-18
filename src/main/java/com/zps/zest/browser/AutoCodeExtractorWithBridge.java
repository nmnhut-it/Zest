package com.zps.zest.browser;

import org.cef.browser.CefBrowser;
import org.cef.handler.CefLoadHandlerAdapter;
import org.cef.browser.CefFrame;
import com.intellij.openapi.diagnostic.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

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
        try {
            // Load the code extractor script from the resource file
            String script = loadResourceAsString("/js/codeExtractor.js");
            browser.executeJavaScript(script, browser.getURL(), 0);
            LOG.info("Injected auto code extractor script using intellijBridge");
        } catch (IOException e) {
            LOG.error("Failed to load code extractor script", e);
        }
    }
    
    /**
     * Helper method to load a resource file as a string
     * @param path Path to the resource
     * @return The content of the resource as a string
     * @throws IOException If the resource cannot be read
     */
    private String loadResourceAsString(String path) throws IOException {
        try (InputStream is = getClass().getResourceAsStream(path)) {
            if (is == null) {
                throw new IOException("Resource not found: " + path);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}