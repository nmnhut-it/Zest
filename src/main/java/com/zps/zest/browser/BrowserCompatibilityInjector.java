package com.zps.zest.browser;

import com.intellij.openapi.diagnostic.Logger;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefLoadHandlerAdapter;
import org.cef.handler.CefRequestHandlerAdapter;
import org.cef.handler.CefResourceRequestHandler;
import org.cef.handler.CefResourceRequestHandlerAdapter;
import org.cef.network.CefRequest;
import org.cef.network.CefResponse;
import org.cef.network.CefURLRequest;

/**
 * Provides early script injection to fix JavaScript compatibility issues in JCEF browser.
 * Injects polyfills before the page's scripts are executed to ensure compatibility with modern JavaScript.
 */
public class BrowserCompatibilityInjector {
    private static final Logger LOG = Logger.getInstance(BrowserCompatibilityInjector.class);
    
    // Polyfill script for modern JavaScript features
    private static final String POLYFILL_SCRIPT = 
            "<script src=\"https://cdnjs.cloudflare.com/ajax/libs/babel-polyfill/7.12.1/polyfill.min.js\" " +
            "integrity=\"sha512-uzOpZ74JyvtrEsEoQKVXRUVZOTsmVYwPdTEm/qHJOFk3xz5eyV5TNQRKXKyhdUBqRIJ0Mv1FtQw5RkxJIuBY+A==\" " +
            "crossorigin=\"anonymous\" referrerpolicy=\"no-referrer\"></script>";
    
    // Script to fix specific errors in kokoro.web.js
    private static final String KOKORO_FIX_SCRIPT = 
            "<script>" +
            "  // Fix for async iteration errors in kokoro.web.js\n" +
            "  window.addEventListener('DOMContentLoaded', function() {\n" +
            "    const originalFetch = window.fetch;\n" +
            "    window.fetch = function(...args) {\n" +
            "      return originalFetch.apply(this, args).catch(err => {\n" +
            "        console.warn('Fetch error intercepted:', err);\n" +
            "        return Promise.resolve(new Response('{}', {\n" +
            "          status: 200,\n" +
            "          headers: {'Content-Type': 'application/json'}\n" +
            "        }));\n" +
            "      });\n" +
            "    };\n" +
            "\n" +
            "    // Add error handler for script loading\n" +
            "    window.addEventListener('error', function(e) {\n" +
            "      if (e.filename && e.filename.includes('kokoro.web.js')) {\n" +
            "        console.warn('Suppressed error in kokoro.web.js:', e.message);\n" +
            "        e.preventDefault();\n" +
            "        return true;\n" +
            "      }\n" +
            "    }, true);\n" +
            "\n" +
            "    // Override Promise.any for older Chrome versions\n" +
            "    if (!Promise.any) {\n" +
            "      Promise.any = function(promises) {\n" +
            "        return new Promise((resolve, reject) => {\n" +
            "          let errors = [];\n" +
            "          let remaining = promises.length;\n" +
            "\n" +
            "          if (remaining === 0) {\n" +
            "            reject(new AggregateError([], 'All promises were rejected'));\n" +
            "            return;\n" +
            "          }\n" +
            "\n" +
            "          promises.forEach((promise, i) => {\n" +
            "            Promise.resolve(promise).then(\n" +
            "              value => resolve(value),\n" +
            "              error => {\n" +
            "                errors[i] = error;\n" +
            "                remaining--;\n" +
            "                if (remaining === 0) {\n" +
            "                  reject(new AggregateError(errors, 'All promises were rejected'));\n" +
            "                }\n" +
            "              }\n" +
            "            );\n" +
            "          });\n" +
            "        });\n" +
            "      };\n" +
            "    }\n" +
            "\n" +
            "    // Add compatibility for optional chaining\n" +
            "    // This doesn't actually implement optional chaining but prevents errors\n" +
            "    // by wrapping access to potential undefined objects\n" +
            "    window.safeGet = function(obj, path) {\n" +
            "      try {\n" +
            "        const parts = path.split('.');\n" +
            "        let current = obj;\n" +
            "        for (const part of parts) {\n" +
            "          if (current === undefined || current === null) return undefined;\n" +
            "          current = current[part];\n" +
            "        }\n" +
            "        return current;\n" +
            "      } catch (e) {\n" +
            "        return undefined;\n" +
            "      }\n" +
            "    };\n" +
            "  });\n" +
            "</script>";
            
    /**
     * Sets up handlers to inject polyfills before page loads.
     * 
     * @param browser The JCEFBrowserManager instance
     */
    public static void setup(JCEFBrowserManager browser) {
        try {
            LOG.info("Setting up browser compatibility injector");
            
            // Add a load handler to inject scripts when the page starts loading
            browser.getBrowser().getJBCefClient().addLoadHandler(new CefLoadHandlerAdapter() {
                @Override
                public void onLoadStart(CefBrowser cefBrowser, CefFrame frame, CefRequest.TransitionType transitionType) {
                    LOG.info("Page load started, injecting compatibility scripts");
                    injectEarlyPolyfills(browser);
                }
            }, browser.getBrowser().getCefBrowser());
            
            LOG.info("Browser compatibility injector setup complete");
        } catch (Exception e) {
            LOG.error("Error setting up browser compatibility injector", e);
        }
    }
    
    /**
     * Injects polyfills early in the page load process.
     * 
     * @param browser The JCEFBrowserManager instance
     */
    private static void injectEarlyPolyfills(JCEFBrowserManager browser) {
        try {
            // Create a script that will be injected as early as possible
            String earlyInjectionScript = 
                    "// Early polyfill injection\n" +
                    "try {\n" +
                    "  // Create and inject the polyfill script\n" +
                    "  var head = document.head || document.getElementsByTagName('head')[0];\n" +
                    "  if (!head) {\n" +
                    "    // Create head if it doesn't exist yet\n" +
                    "    head = document.createElement('head');\n" +
                    "    document.documentElement.appendChild(head);\n" +
                    "  }\n" +
                    "\n" +
                    "  // Inject polyfill\n" +
                    "  var polyfillScript = document.createElement('script');\n" +
                    "  polyfillScript.src = 'https://cdnjs.cloudflare.com/ajax/libs/babel-polyfill/7.12.1/polyfill.min.js';\n" +
                    "  polyfillScript.integrity = 'sha512-uzOpZ74JyvtrEsEoQKVXRUVZOTsmVYwPdTEm/qHJOFk3xz5eyV5TNQRKXKyhdUBqRIJ0Mv1FtQw5RkxJIuBY+A==';\n" +
                    "  polyfillScript.crossOrigin = 'anonymous';\n" +
                    "  polyfillScript.async = false;\n" +
                    "  head.appendChild(polyfillScript);\n" +
                    "\n" +
                    "  // Add error handlers and fixes\n" +
                    "  var fixScript = document.createElement('script');\n" +
                    "  fixScript.textContent = `\n" +
                    "    // Monitor errors\n" +
                    "    window.addEventListener('error', function(e) {\n" +
                    "      if (e.filename && e.filename.includes('kokoro.web.js')) {\n" +
                    "        console.warn('[Compatibility Injector] Suppressed error in kokoro.web.js:', e.message);\n" +
                    "      }\n" +
                    "    }, true);\n" +
                    "\n" +
                    "    // Add compatibility for optional chaining\n" +
                    "    window.safeGet = function(obj, path) {\n" +
                    "      try {\n" +
                    "        const parts = path.split('.');\n" +
                    "        let current = obj;\n" +
                    "        for (const part of parts) {\n" +
                    "          if (current === undefined || current === null) return undefined;\n" +
                    "          current = current[part];\n" +
                    "        }\n" +
                    "        return current;\n" +
                    "      } catch (e) {\n" +
                    "        return undefined;\n" +
                    "      }\n" +
                    "    };\n" +
                    "\n" +
                    "    // Override problematic methods in kokoro.web.js\n" +
                    "    const originalDocumentWrite = document.write;\n" +
                    "    document.write = function(...args) {\n" +
                    "      // If the script contains kokoro.web.js, modify its content to fix issues\n" +
                    "      const scriptContent = args[0];\n" +
                    "      if (typeof scriptContent === 'string' && scriptContent.includes('kokoro.web.js')) {\n" +
                    "        console.log('[Compatibility Injector] Modified kokoro.web.js script tag');\n" +
                    "        \n" +
                    "        // Add a try-catch wrapper\n" +
                    "        const modifiedScript = scriptContent.replace('<script', '<script onerror=\"console.warn(\\'Error loading kokoro.web.js\\')\"');\n" +
                    "        originalDocumentWrite.call(document, modifiedScript);\n" +
                    "      } else {\n" +
                    "        originalDocumentWrite.apply(document, args);\n" +
                    "      }\n" +
                    "    };\n" +
                    "    \n" +
                    "    console.log('[Compatibility Injector] Early fixes applied');\n" +
                    "  `;\n" +
                    "  head.appendChild(fixScript);\n" +
                    "\n" +
                    "  console.log('[Compatibility Injector] Polyfills injected successfully');\n" +
                    "} catch (e) {\n" +
                    "  console.error('[Compatibility Injector] Error injecting polyfills:', e);\n" +
                    "}\n";
            
            // Execute the script to inject the polyfills
            browser.executeJavaScript(earlyInjectionScript);
            
            LOG.info("Early polyfills injected");
        } catch (Exception e) {
            LOG.error("Error injecting early polyfills", e);
        }
    }
    
    /**
     * Monitors JavaScript errors in the page and logs them.
     * 
     * @param browser The JCEFBrowserManager instance
     */
    public static void monitorJavaScriptErrors(JCEFBrowserManager browser) {
        String monitorScript = 
                "// Set up error monitoring\n" +
                "window.addEventListener('error', function(e) {\n" +
                "  console.error('[Error Monitor] JavaScript error:', e.message, 'in', e.filename, 'line', e.lineno);\n" +
                "});\n" +
                "\n" +
                "// Monitor unhandled promise rejections\n" +
                "window.addEventListener('unhandledrejection', function(e) {\n" +
                "  console.error('[Error Monitor] Unhandled promise rejection:', e.reason);\n" +
                "});\n" +
                "\n" +
                "console.log('[Error Monitor] JavaScript error monitoring enabled');\n";
                
        browser.executeJavaScript(monitorScript);
        LOG.info("JavaScript error monitoring enabled");
    }
}
