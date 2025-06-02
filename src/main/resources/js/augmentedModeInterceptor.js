/**
 * Augmented Mode Interceptor for Intelligent Query Enhancement
 *
 * This interceptor enhances chat requests with intelligently discovered code context
 * based on query patterns and current IDE state.
 */

(function() {
  console.log('[Augmented Mode] Interceptor loaded');

  /**
   * Enhances request body with augmented query context
   * @param {Object} data - The request data object
   * @returns {Promise<boolean>} True if enhancement was applied
   */
  window.enhanceWithAugmentedContext = async function(data) {
    // Check if this is a chat completion request
    if (!data.messages || !Array.isArray(data.messages)) {
      return false;
    }

    // Add augmented mode flag
    data.custom_tool = 'Zest|AUGMENTED_MODE_CHAT';

    // Find the latest user message
    let userMessageIndex = -1;
    for (let i = data.messages.length - 1; i >= 0; i--) {
      if (data.messages[i].role === 'user') {
        userMessageIndex = i;
        break;
      }
    }

    if (userMessageIndex === -1) {
      console.log('[Augmented Mode] No user message found');
      return false;
    }

    const userMessage = data.messages[userMessageIndex].content;
    console.log('[Augmented Mode] Augmenting query:', userMessage.substring(0, 100) + '...');

    try {
      // Call the augmentation service
      const augmentationResult = await window.intellijBridge.callIDE('augmentQuery', {
        query: userMessage
      });

      if (augmentationResult && augmentationResult.success && augmentationResult.result) {
        const augmentedContext = augmentationResult.result;
        console.log('[Augmented Mode] Received augmented context:', augmentedContext.length, 'chars');

        // Check if agent is asking questions or exploring
        const hasAgentAnalysis = augmentedContext.includes('### Agent Analysis ###');
        const hasAgentExploration = augmentedContext.includes('### Agent Exploration ###');
        
        if (hasAgentAnalysis || hasAgentExploration) {
          console.log('[Augmented Mode] Agent is actively thinking and exploring...');
          
          // Could show a visual indicator that the agent is working
          const indicator = document.createElement('div');
          indicator.style.cssText = 'position: fixed; top: 10px; right: 10px; background: #4CAF50; color: white; padding: 10px; border-radius: 5px; z-index: 9999;';
          indicator.textContent = '🤖 Agent is exploring your codebase...';
          document.body.appendChild(indicator);
          
          // Remove after a few seconds
          setTimeout(() => indicator.remove(), 3000);
        }

        // Add augmented context to the user message
        if (augmentedContext.trim()) {
          const contextBlock = `<augmented_context>
${augmentedContext}
</augmented_context>

`;
          data.messages[userMessageIndex].content = contextBlock + userMessage;
          console.log('[Augmented Mode] Added augmented context to user message');
        }

        // Add or update system message for augmented mode
        const augmentedSystemMessage = `You are an AI assistant with access to augmented code context from the project. 
The user's query has been enhanced with relevant code patterns, relationships, and context discovered through intelligent search.

When you see <augmented_context> blocks, pay special attention to:
1. **Clarifying Questions**: If provided, these indicate ambiguities in the user's request. Consider asking these questions to better understand what they need.
2. **Current IDE Context**: Shows where the user is currently working
3. **Relevant Code Found**: Organized by component type (Controllers, Services, etc.)
4. **Exploration Suggestions**: Additional areas the user might want to investigate
5. **Pattern-Specific Guidance**: Best practices for the detected code patterns

Your role is to:
- Help the user explore and understand their codebase
- Ask clarifying questions when the request is ambiguous
- Suggest related areas to investigate
- Provide specific, actionable guidance based on the code context
- Reference specific classes, methods, and patterns shown in the context
- Consider the relationships between components when suggesting changes
- Follow the existing patterns and conventions shown in the code

Be proactive in helping the user discover relevant code and understand their project structure.`;

        const systemMsgIndex = data.messages.findIndex(msg => msg.role === 'system');
        if (systemMsgIndex >= 0) {
          // Prepend to existing system message
          data.messages[systemMsgIndex].content = augmentedSystemMessage + '\n\n' + data.messages[systemMsgIndex].content;
        } else {
          // Add new system message at the beginning
          data.messages.unshift({
            role: 'system',
            content: augmentedSystemMessage
          });
        }

        return true;
      } else {
        console.warn('[Augmented Mode] Failed to get augmented context:', augmentationResult);
        return false;
      }
    } catch (error) {
      console.error('[Augmented Mode] Error augmenting query:', error);
      return false;
    }
  };

  /**
   * Checks if augmented mode is active
   */
  window.isAugmentedModeActive = function() {
    return window.__zest_mode__ === 'Augmented Mode';
  };

  /**
   * Monitors for pattern keywords in user input
   */
  window.monitorPatternKeywords = function() {
    // This could be enhanced to provide real-time suggestions
    const patterns = [
      'controller', 'service', 'handler', 'command', 
      'repository', 'dto', 'config', 'util', 'test'
    ];
    
    const inputElement = document.querySelector('textarea[placeholder*="Ask anything"]');
    if (inputElement) {
      const value = inputElement.value.toLowerCase();
      const detectedPatterns = patterns.filter(p => value.includes(p));
      
      if (detectedPatterns.length > 0) {
        console.log('[Augmented Mode] Detected patterns:', detectedPatterns);
        // Could show a hint to the user about detected patterns
      }
    }
  };

  /**
   * Initializes augmented mode features
   */
  window.initAugmentedMode = function() {
    console.log('[Augmented Mode] Initializing features...');
    
    // Monitor input for pattern keywords (optional enhancement)
    const inputElement = document.querySelector('textarea[placeholder*="Ask anything"]');
    if (inputElement) {
      inputElement.addEventListener('input', window.monitorPatternKeywords);
    }
  };

  // Initialize when the page loads
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', function() {
      if (window.isAugmentedModeActive()) {
        window.initAugmentedMode();
      }
    });
  } else {
    if (window.isAugmentedModeActive()) {
      window.initAugmentedMode();
    }
  }

  // Re-initialize when switching to Augmented Mode
  window.addEventListener('zestModeChanged', function(event) {
    if (event.detail && event.detail.mode === 'Augmented Mode') {
      console.log('[Augmented Mode] Mode activated');
      window.initAugmentedMode();
    }
  });

})();
