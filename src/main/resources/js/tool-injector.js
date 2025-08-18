/**
 * Tool Injector for OpenWebUI Settings
 * 
 * This module allows dynamic injection of tool servers into OpenWebUI settings.
 * It uses the settings update API to properly persist tool servers without
 * interfering with other OpenWebUI features.
 */

(function() {
  'use strict';

  // Initialize global flag for tool injection
  window.__enable_zest_tools__ = false;

  /**
   * Enables or disables automatic tool injection from IDE into settings
   * @param {boolean} enable - Whether to enable automatic injection
   */
  window.enableZestToolInjection = async function(enable) {
    // Check current mode - only allow in Agent Mode
    const currentMode = window.__zest_mode__ || 'Default Mode';
    const isAgentMode = currentMode === 'Agent Mode';
    
    // Override enable flag based on mode
    if (enable && !isAgentMode) {
      console.log('[ZEST-TOOLINJECTION] Tool injection blocked - not in Agent Mode (current: ' + currentMode + ')');
      window.__enable_zest_tools__ = false;
      return false;
    }
    
    window.__enable_zest_tools__ = !!enable;
    
    // Use debug logging if available
    const log = (msg, data) => {
      if (window.__zest_debug__ && window.__zest_debug__.toolInjection) {
        console.log('[ZEST-TOOLINJECTION]', msg, data || '');
      }
    };
    
    if (enable) {
      log('Enabling automatic tool injection into settings', {
        currentPath: window.location.pathname,
        hasBridge: !!window.intellijBridge,
        mode: currentMode
      });
      
      // Automatically inject tools now
      try {
        await window.injectZestTools();
        log('Successfully injected Zest tools into settings');
      } catch (e) {
        console.error('[ZEST-TOOLINJECTION] Failed to inject tools:', e);
      }
    } else {
      log('Disabled automatic tool injection');
      // Optionally remove Zest tools from settings
      try {
        await window.removeZestTools();
        log('Removed Zest tools from settings');
      } catch (e) {
        console.error('[ZEST-TOOLINJECTION] Failed to remove tools:', e);
      }
    }
    
    return window.__enable_zest_tools__;
  };

  /**
   * Gets the current tool injection status
   * @returns {boolean} Whether automatic tool injection is enabled
   */
  window.isZestToolInjectionEnabled = function() {
    return window.__enable_zest_tools__;
  };

  /**
   * Injects Zest tools into OpenWebUI settings using the update API
   */
  window.injectZestTools = async function() {
    try {
      // First, get current settings
      const settingsResponse = await fetch('/api/v1/users/user/settings', {
        credentials: 'include'
      });
      
      if (!settingsResponse.ok) {
        throw new Error('Failed to fetch current settings');
      }
      
      const settings = await settingsResponse.json();
      
      // Get tool servers from IDE
      if (!window.intellijBridge) {
        throw new Error('IntelliJ Bridge not available');
      }
      
      const toolResponse = await window.intellijBridge.callIDE('getToolServers', {});
      if (!toolResponse || !toolResponse.success || !toolResponse.servers) {
        throw new Error('No tool servers available from IDE');
      }
      
      // Ensure settings structure exists
      if (!settings.ui) settings.ui = {};
      if (!settings.ui.toolServers) settings.ui.toolServers = [];
      
      // Check if this specific project's tool server already exists
      const newServer = toolResponse.servers[0];
      const zestUrl = newServer?.url;
      const projectName = newServer?.info?.name || '';
      
      const existingIndex = settings.ui.toolServers.findIndex(server => {
        // Match by exact URL (same port = same project)
        if (server.url === zestUrl) return true;
        
        // Match by project name in the info
        if (server.info?.name === projectName && projectName.includes('Zest Code Explorer')) return true;
        
        return false;
      });
      
      if (existingIndex >= 0) {
        // Update existing server for this project
        settings.ui.toolServers[existingIndex] = newServer;
        console.log('[Tool Injector] Updated existing Zest tool server for project:', projectName);
      } else {
        // Add new server - this allows multiple project tools to coexist
        settings.ui.toolServers.push(...toolResponse.servers);
        console.log('[Tool Injector] Added new Zest tool server for project:', projectName);
      }
      
      // Update settings
      const updateResponse = await fetch('/api/v1/users/user/settings/update', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json'
        },
        credentials: 'include',
        body: JSON.stringify(settings)
      });
      
      if (!updateResponse.ok) {
        throw new Error('Failed to update settings');
      }
      
      return toolResponse.servers;
    } catch (e) {
      console.error('[Tool Injector] Error injecting tools:', e);
      throw e;
    }
  };
  
  /**
   * Removes Zest tools from OpenWebUI settings
   */
  window.removeZestTools = async function() {
    try {
      // First, get current settings
      const settingsResponse = await fetch('/api/v1/users/user/settings', {
        credentials: 'include'
      });
      
      if (!settingsResponse.ok) {
        throw new Error('Failed to fetch current settings');
      }
      
      const settings = await settingsResponse.json();
      
      if (!settings.ui?.toolServers) {
        console.log('[Tool Injector] No tool servers to remove');
        return;
      }
      
      // Get current project info to remove only this project's tool
      if (window.intellijBridge) {
        try {
          const toolResponse = await window.intellijBridge.callIDE('getToolServers', {});
          if (toolResponse && toolResponse.success && toolResponse.servers) {
            const currentProjectUrl = toolResponse.servers[0]?.url;
            const currentProjectName = toolResponse.servers[0]?.info?.name || '';
            
            // Remove only this project's tool server
            settings.ui.toolServers = settings.ui.toolServers.filter(server => {
              // Remove if it matches current project's URL
              if (server.url === currentProjectUrl) return false;
              
              // Remove if it matches current project's name
              if (server.info?.name === currentProjectName && currentProjectName.includes('Zest Code Explorer')) return false;
              
              return true;
            });
            
            console.log('[Tool Injector] Removed tool server for project:', currentProjectName);
          }
        } catch (e) {
          // Fallback: remove all Zest tools if we can't get project info
          settings.ui.toolServers = settings.ui.toolServers.filter(server => {
            if (server.url?.includes('localhost:') && server.url?.includes('/zest')) return false;
            if (server.info?.name?.includes('Zest Code Explorer')) return false;
            return true;
          });
          console.log('[Tool Injector] Removed all Zest tool servers (fallback)');
        }
      } else {
        console.log('[Tool Injector] Cannot determine current project, skipping removal');
        return;
      }
      
      // Update settings
      const updateResponse = await fetch('/api/v1/users/user/settings/update', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json'
        },
        credentials: 'include',
        body: JSON.stringify(settings)
      });
      
      if (!updateResponse.ok) {
        throw new Error('Failed to update settings');
      }
      
      console.log('[Tool Injector] Removed Zest tool servers from settings');
    } catch (e) {
      console.error('[Tool Injector] Error removing tools:', e);
      throw e;
    }
  };

  /**
   * Forces a settings refresh to apply tool server changes
   * This can be useful after enabling/disabling tool injection
   */
  window.refreshZestTools = function() {
    if (window.location.pathname.includes('/settings')) {
      // If on settings page, trigger a refresh
      console.log('[Tool Injector] Refreshing settings page...');
      window.location.reload();
    } else {
      // Otherwise, the tools will be applied next time settings are loaded
      console.log('[Tool Injector] Tools will be applied when settings are next loaded');
    }
  };

  /**
   * Checks if Zest tools are available in the current settings
   * This function can be used to verify if injection was successful
   */
  window.checkZestToolsInSettings = async function() {
    try {
      const response = await fetch('/api/v1/users/user/settings', {
        credentials: 'include'
      });
      
      if (response.ok) {
        const settings = await response.json();
        const toolServers = settings?.ui?.toolServers || [];
        const zestTools = toolServers.filter(server => 
          server.url?.includes('localhost:8765/zest') || 
          server.url?.includes('/zest')
        );
        
        console.log('[Tool Injector] Found ' + zestTools.length + ' Zest tool servers in settings');
        return zestTools;
      }
    } catch (e) {
      console.error('[Tool Injector] Error checking settings:', e);
    }
    return [];
  };

  /**
   * Example of the tool server structure that gets injected:
   * {
   *   url: "http://localhost:8765/zest",
   *   path: "openapi.json",
   *   auth_type: "bearer",
   *   key: "",
   *   config: { enable: false, access_control: {} },
   *   info: { name: "Zest Code Explorer", description: "Code exploration tools from IDE" }
   * }
   */

  // Debug helper function
  window.debugZestToolInjection = function() {
    console.log('=== ZEST TOOL INJECTION DEBUG INFO ===');
    console.log('Tool injection enabled:', window.__enable_zest_tools__);
    console.log('IntelliJ Bridge available:', !!window.intellijBridge);
    console.log('Debug flags:', window.__zest_debug__ || 'Not available');
    console.log('Current URL:', window.location.href);
    
    // Try to fetch current settings
    if (window.location.pathname.includes('/settings')) {
      console.log('Fetching current settings...');
      fetch('/api/v1/users/user/settings', { credentials: 'include' })
        .then(r => r.json())
        .then(data => {
          console.log('Current tool servers:', data.ui?.toolServers || 'None');
        })
        .catch(e => console.error('Failed to fetch settings:', e));
    }
    
    // Try to get tool servers from IDE
    if (window.intellijBridge) {
      console.log('Fetching tool servers from IDE...');
      window.intellijBridge.callIDE('getToolServers', {})
        .then(response => {
          console.log('IDE tool server response:', response);
        })
        .catch(e => console.error('Failed to get tool servers from IDE:', e));
    }
  };
  
  // Log initialization
  console.log('[Tool Injector] Zest tool server settings injection library loaded');
  console.log('[Tool Injector] Use enableZestToolInjection(true) to add Zest tools to settings');
  console.log('[Tool Injector] Use enableZestToolInjection(false) to remove Zest tools');
  console.log('[Tool Injector] Use debugZestToolInjection() to debug issues');
  console.log('[Tool Injector] Tools are persisted using the OpenWebUI settings update API');
})();