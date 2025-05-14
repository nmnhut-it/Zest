package com.zps.zest.browser;

import javax.swing.*;

/**
 * Interface defining a browser mode.
 */
public interface BrowserMode {
    /**
     * Returns the name of the mode to display in UI.
     */
    String getName();
    
    /**
     * Returns the icon to represent this mode.
     */
    Icon getIcon();
    
    /**
     * Returns the tooltip text for this mode.
     */
    String getTooltip();
    
    /**
     * Returns the system prompt for this mode, or null if none.
     */
    String getSystemPrompt();
    
    /**
     * Called when this mode is activated.
     * @param browserManager The browser manager to interact with
     */
    void onActivate(JCEFBrowserManager browserManager);
}
