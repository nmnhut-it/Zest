package com.zps.zest.browser;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.ui.jcef.JBCefApp;
import com.intellij.ui.jcef.JBCefClient;

import static com.intellij.ui.jcef.JBCefClient.Properties.JS_QUERY_POOL_SIZE;

/**
 * Provides shared JBCefClient instance to prevent black screen issues.
 * Used by both main browser and lightweight chat browser.
 */
public class JCEFClientProvider {
    private static final Logger LOG = Logger.getInstance(JCEFClientProvider.class);

    private static volatile JBCefClient sharedClient;
    private static final Object clientLock = new Object();
    private static int clientRefCount = 0;

    /**
     * Gets or creates the shared JBCefClient instance.
     * This prevents the black screen issue when multiple browsers are open.
     */
    public static JBCefClient getSharedClient() {
        synchronized (clientLock) {
            if (sharedClient == null) {
                LOG.info("Creating shared JBCefClient for all browser instances");
                sharedClient = JBCefApp.getInstance().createClient();
                sharedClient.setProperty(JS_QUERY_POOL_SIZE, 10);
            }
            clientRefCount++;
            LOG.info("Shared client reference count: " + clientRefCount);
            return sharedClient;
        }
    }

    /**
     * Decrements the reference count for the shared client.
     * The shared client is never disposed to prevent black screen issues.
     */
    public static void releaseSharedClient() {
        synchronized (clientLock) {
            clientRefCount--;
            LOG.info("Released shared client reference, count: " + clientRefCount);
            // Never dispose the shared client - keep it alive for the lifetime of the IDE
        }
    }
}
