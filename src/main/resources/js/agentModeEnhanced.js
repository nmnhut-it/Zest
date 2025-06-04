/**
 * Enhanced Agent Mode Interceptor with Exploration-Based Augmentation
 * 
 * This interceptor enhances Agent Mode and Project Mode to use the 
 * ImprovedToolCallingAutonomousAgent for comprehensive code exploration
 * and query augmentation.
 */

(function() {
  console.log('[Enhanced Agent Mode] Interceptor loaded');

  /**
   * Enhances request with exploration-based augmentation for Agent/Project Mode
   * @param {Object} data - The request data object
   * @returns {Promise<boolean>} True if enhancement was applied
   */
  window.enhanceWithExplorationAugmentation = async function(data) {
    // Check if this is a chat completion request
    if (!data.messages || !Array.isArray(data.messages)) {
      return false;
    }

    const mode = window.__zest_mode__;
    if (mode !== 'Agent Mode' && mode !== 'Project Mode') {
      return false;
    }

    // Find the latest user message
    let userMessageIndex = -1;
    for (let i = data.messages.length - 1; i >= 0; i--) {
      if (data.messages[i].role === 'user') {
        userMessageIndex = i;
        break;
      }
    }

    if (userMessageIndex === -1) {
      console.log('[Enhanced Agent Mode] No user message found');
      return false;
    }

    const userMessage = data.messages[userMessageIndex].content;
    console.log('[Enhanced Agent Mode] Starting exploration-based augmentation for:', 
                userMessage.substring(0, 100) + '...');

    try {
      // Show exploration indicator
      showExplorationIndicator();

      // Call the enhanced augmentation service
      const augmentationResult = await window.intellijBridge.callIDE('augmentQueryWithExploration', {
        query: userMessage,
        mode: mode
      });

      if (augmentationResult && augmentationResult.success) {
        if (augmentationResult.status === 'processing') {
          // For async processing, we'll need to handle this differently
          console.log('[Enhanced Agent Mode] Exploration started asynchronously');
          
          // Poll for results or use a different mechanism
          const augmentedQuery = await waitForAugmentationResult(userMessage, mode);
          
          if (augmentedQuery) {
            data.messages[userMessageIndex].content = augmentedQuery;
            console.log('[Enhanced Agent Mode] Applied exploration-based augmentation');
          }
        } else if (augmentationResult.result) {
          // Direct result
          data.messages[userMessageIndex].content = augmentationResult.result;
          console.log('[Enhanced Agent Mode] Applied exploration-based augmentation');
        }
      }

      hideExplorationIndicator();
      return true;

    } catch (error) {
      console.error('[Enhanced Agent Mode] Error during exploration augmentation:', error);
      hideExplorationIndicator();
      return false;
    }
  };

  /**
   * Waits for asynchronous augmentation result
   */
  async function waitForAugmentationResult(query, mode, maxAttempts = 30) {
    for (let i = 0; i < maxAttempts; i++) {
      await new Promise(resolve => setTimeout(resolve, 1000)); // Wait 1 second
      
      // Check if result is ready (this would need a polling endpoint)
      // For now, return the original query
      // In a real implementation, we'd poll a status endpoint
      console.log('[Enhanced Agent Mode] Waiting for augmentation result...', i + 1);
    }
    
    console.log('[Enhanced Agent Mode] Augmentation timeout, using original query');
    return null;
  }

  /**
   * Shows visual indicator for exploration
   */
  function showExplorationIndicator() {
    // Remove any existing indicator
    hideExplorationIndicator();
    
    const indicator = document.createElement('div');
    indicator.id = 'zest-exploration-indicator';
    indicator.style.cssText = `
      position: fixed;
      top: 20px;
      right: 20px;
      background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
      color: white;
      padding: 15px 25px;
      border-radius: 10px;
      box-shadow: 0 4px 15px rgba(0,0,0,0.2);
      z-index: 10000;
      font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
      font-size: 14px;
      display: flex;
      align-items: center;
      gap: 10px;
      animation: slideIn 0.3s ease-out;
    `;
    
    // Add spinner
    const spinner = document.createElement('div');
    spinner.style.cssText = `
      width: 20px;
      height: 20px;
      border: 2px solid rgba(255,255,255,0.3);
      border-top-color: white;
      border-radius: 50%;
      animation: spin 1s linear infinite;
    `;
    
    indicator.appendChild(spinner);
    indicator.appendChild(document.createTextNode('🤖 Exploring your codebase...'));
    
    // Add animation styles
    const style = document.createElement('style');
    style.textContent = `
      @keyframes slideIn {
        from { transform: translateX(100%); opacity: 0; }
        to { transform: translateX(0); opacity: 1; }
      }
      @keyframes spin {
        to { transform: rotate(360deg); }
      }
    `;
    document.head.appendChild(style);
    
    document.body.appendChild(indicator);
  }

  /**
   * Hides the exploration indicator
   */
  function hideExplorationIndicator() {
    const indicator = document.getElementById('zest-exploration-indicator');
    if (indicator) {
      indicator.style.animation = 'slideOut 0.3s ease-in';
      setTimeout(() => indicator.remove(), 300);
    }
  }

  /**
   * Override the original enhanceRequestBody to support exploration augmentation
   */
  const originalEnhanceRequestBody = window.enhanceRequestBody;
  
  window.enhanceRequestBody = async function(body) {
    if (!body) return body;

    try {
      const data = JSON.parse(body);
      
      // Check if we should use exploration-based augmentation
      const mode = window.__zest_mode__;
      if ((mode === 'Agent Mode' || mode === 'Project Mode') && 
          data.messages && Array.isArray(data.messages)) {
        
        // Use exploration-based augmentation
        await window.enhanceWithExplorationAugmentation(data);
      }
      
      // Continue with original enhancement
      if (originalEnhanceRequestBody) {
        return await originalEnhanceRequestBody(body);
      }
      
      return JSON.stringify(data);
      
    } catch (e) {
      console.error('[Enhanced Agent Mode] Failed to enhance request:', e);
      return body;
    }
  };

  /**
   * Configuration for exploration depth based on mode
   */
  window.getExplorationConfig = function() {
    const mode = window.__zest_mode__;
    
    if (mode === 'Agent Mode') {
      return {
        maxToolCalls: 15,
        includeTests: true,
        deepExploration: true,
        includeImplementationDetails: true
      };
    } else if (mode === 'Project Mode') {
      return {
        maxToolCalls: 10,
        includeTests: false,
        deepExploration: false,
        includeImplementationDetails: false
      };
    }
    
    return null;
  };

  /**
   * Initialize enhanced features when mode changes
   */
  window.addEventListener('zestModeChanged', function(event) {
    const mode = event.detail && event.detail.mode;
    if (mode === 'Agent Mode' || mode === 'Project Mode') {
      console.log('[Enhanced Agent Mode] Mode activated:', mode);
      console.log('Exploration config:', window.getExplorationConfig());
    }
  });

  console.log('[Enhanced Agent Mode] Interceptor initialized');
})();
