package com.zps.zest.browser;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.openapi.util.registry.RegistryValue;
import com.intellij.openapi.util.registry.RegistryValueListener;

/**
 * Ensures that the JCEF developer tools registry key is properly set.
 * Based on the IntelliJ Platform documentation for JCEF browser devtools.
 */
@Service(Service.Level.APP)
public final class DevToolsRegistryManager {
    private static final Logger LOG = Logger.getInstance(DevToolsRegistryManager.class);
    private static final String DEV_TOOLS_REGISTRY_KEY = "ide.browser.jcef.contextMenu.devTools.enabled";
    
    /**
     * Gets the singleton instance of the DevToolsRegistryManager.
     */
    public static DevToolsRegistryManager getInstance() {
        return ApplicationManager.getApplication().getService(DevToolsRegistryManager.class);
    }
    
    /**
     * Constructor for the DevToolsRegistryManager.
     * Ensures the dev tools registry key is set correctly.
     */
    public DevToolsRegistryManager() {
        ensureDevToolsEnabled();
    }
    
    /**
     * Ensures that the developer tools registry key is enabled.
     * According to the documentation, this key must be set to true in 2021.3+
     * to enable the "Open DevTools" context menu item.
     */
    public void ensureDevToolsEnabled() {
        try {
            // Check if the registry key exists
            if (Registry.is(DEV_TOOLS_REGISTRY_KEY)) {
                // Get the current value
                RegistryValue registryValue = Registry.get(DEV_TOOLS_REGISTRY_KEY);
                
                // Enable if not already enabled
                if (!registryValue.asBoolean()) {
                    LOG.info("Enabling JCEF developer tools context menu");
                    registryValue.setValue(true);
                    
                    // Add a listener to ensure it stays enabled
                    registryValue.addListener(new RegistryValueListener() {
                        @Override
                        public void afterValueChanged(RegistryValue value) {
                            if (!value.asBoolean()) {
                                LOG.info("Re-enabling JCEF developer tools context menu after external change");
                                value.setValue(true);
                            }
                        }
                    }, ApplicationManager.getApplication());
                }
                
                LOG.info("JCEF developer tools context menu is enabled");
            } else {
                LOG.info("JCEF developer tools registry key not found - may be using an older IDE version");
            }
        } catch (Exception e) {
            LOG.error("Error setting JCEF developer tools registry key", e);
        }
    }
    
    /**
     * Gets the current debug port for JCEF.
     * 
     * @return The current debug port (default is 9222)
     */
    public int getDebugPort() {
        try {
            String key = "ide.browser.jcef.debug.port";
            if (Registry.is(key)) {
                return Registry.intValue(key);
            }
        } catch (Exception e) {
            LOG.error("Error getting JCEF debug port", e);
        }
        
        return 9222; // Default port
    }
}
