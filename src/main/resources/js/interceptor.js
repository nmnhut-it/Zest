/**
 * Request Interceptor for IDE Integration
 *
 * This script intercepts API requests to OpenWebUI and enhances them with
 * real-time project information from the IDE.
 */

(function() {
  // Store the original fetch function
  const originalFetch = window.fetch;
  let textToReplace = window.__text_to_replace_ide___;

  // Debug flags for controlling log verbosity
  window.__zest_debug__ = {
    toolInjection: true,     // Log tool injection details
    requests: false          // Log all request interception (disabled by default)
  };

  // Helper function for conditional logging
  function debugLog(category, ...args) {
    if (window.__zest_debug__ && window.__zest_debug__[category]) {
      console.log(`[ZEST-${category.toUpperCase()}]`, ...args);
    }
  }

  // Initialize minimal project info structure
  window.__project_info__ = {
    projectName: '',
    projectFilePath: ''
  };

  /**
   * Updates minimal project information
   * @returns {Promise} Promise resolving to the updated project info
   */
  window.updateProjectInfo = function() {
    return new Promise((resolve, reject) => {
      if (window.intellijBridge) {
        window.intellijBridge.callIDE('getProjectInfo', {})
          .then(function(response) {
            if (response && response.success) {
              window.__project_info__ = {
                projectName: response.result?.projectName || 'Unknown',
                projectFilePath: response.result?.projectFilePath || 'Unknown'
              };
              console.log('Updated minimal project info:', window.__project_info__);
              resolve(window.__project_info__);
            } else {
              reject('Failed to get project info');
            }
          })
          .catch(function(error) {
            console.error('Failed to get project info:', error);
            reject(error);
          });
      } else {
        reject('IntelliJ bridge not available');
      }
    });
  };


  /**
   * Enhances request body with minimal project info and tool servers
   * @param {string} body - The original request body
   * @returns {Promise<string>} The modified request body
   */
  async function enhanceRequestBody(body) {
    if (!body) return body;

    try {
      const data = JSON.parse(body);

      // === Add isFromZest flag ===
      data.custom_tool = 'Zest|' + (window.__zest_usage__  ? window.__zest_usage__ : "NORMAL_CHAT");

      // === Inject the dynamically selected model if present ===
      if (window.__selected_model_name__) {
        data.model = window.__selected_model_name__;
        console.log('[Interceptor] Overwrote model with', window.__selected_model_name__);
      }

      // === Inject tool servers dynamically based on mode ===
      const currentMode = window.__zest_mode__ || 'Default Mode';
      const isAgentMode = currentMode === 'Agent Mode';
      
      if (window.__zest_tool_servers__ && Array.isArray(window.__zest_tool_servers__)) {
        // Merge with existing tool servers if any
        if (!data.tool_servers) {
          data.tool_servers = [];
        }
        
        // Add Zest tool servers
        data.tool_servers = [...data.tool_servers, ...window.__zest_tool_servers__];
        console.log('[Interceptor] Injected ' + window.__zest_tool_servers__.length + ' tool servers');
      } else if (window.__enable_zest_tools__ && window.intellijBridge) {
        // Only inject tools in Agent Mode or if explicitly enabled
        if (isAgentMode) {
          try {
            const toolResponse = await window.intellijBridge.callIDE('getToolServers', {});
            if (toolResponse && toolResponse.success && toolResponse.servers) {
              if (!data.tool_servers) {
                data.tool_servers = [];
              }
              data.tool_servers = [...data.tool_servers, ...toolResponse.servers];
              console.log('[Interceptor] Injected ' + toolResponse.servers.length + ' tool servers from IDE (Agent Mode)');
            }
          } catch (e) {
            console.error('Failed to get tool servers from IDE:', e);
          }
        }
      }

      // Check if this is a chat completion request
      if (data.messages && Array.isArray(data.messages)) {
        // Add mode-specific instructions
        if (!isAgentMode && data.messages.length > 0) {
          // For non-Agent modes, add restrictions on tool usage
          const systemMessage = data.messages.find(msg => msg.role === 'system');
          if (systemMessage) {
            const modeRestrictions = '\n\nMODE RESTRICTIONS:\n' +
              '- Minimize use of external tools - rely on your knowledge\n' +
              '- NEVER use tools that modify, create, or delete files\n' +
              '- Only use read-only exploration tools when absolutely necessary\n' +
              '- Focus on providing guidance and explanations rather than direct file manipulation';
            
            systemMessage.content = systemMessage.content + modeRestrictions;
            console.log('[Interceptor] Added mode restrictions for non-Agent mode:', currentMode);
          }
        }
        

        // Handle system message - always process when we have a system prompt
        if (window.__injected_system_prompt__) {
          const systemMsgIndex = data.messages.findIndex(msg => msg.role === 'system');
          
          if (systemMsgIndex >= 0) {
            // Override existing system message
            data.messages[systemMsgIndex].content = window.__injected_system_prompt__;
          } else {
            // Add new system message at the beginning
            console.log("Adding system message to the beginning of messages");
            data.messages.unshift({
              role: 'system',
              content: window.__injected_system_prompt__
            });
          }
        }

        // Add minimal project info for Agent Mode tools
        if (isAgentMode && window.__project_info__) {
          // Add just project name and path as a simple system message
          const minimalContext = `Current project: ${window.__project_info__.projectName || 'Unknown'}\nProject path: ${window.__project_info__.projectFilePath || 'Unknown'}`;
          
          // Find or add system message
          const systemMsgIndex = data.messages.findIndex(msg => msg.role === 'system');
          if (systemMsgIndex >= 0) {
            // Append to existing system message
            data.messages[systemMsgIndex].content += '\n\n' + minimalContext;
          } else {
            // Create new system message
            data.messages.unshift({
              role: 'system',
              content: minimalContext
            });
          }
          
          console.log('Added minimal project context for tools:', window.__project_info__.projectName);
        }
      }


      return JSON.stringify(data);
    } catch (e) {
      console.error('Failed to modify request body:', e);
      return body;
    }
  }

  /**
   * Handles the response from fetch
   * @param {Response} response - The fetch response
   * @returns {Response} The original response
   */
  async function handleResponse(response) {
    const responseClone = response.clone();
    const url = responseClone.url || '';

    if (url.includes('completed') || url.includes('/api/conversation')) {
      // Clear model and usage on completion
      window.__selected_model_name__ = null;
      window.__zest_usage__ = null;

      // Check if conversation is ending
      if (url.includes('completed')) {
        console.log('Conversation completed');
      }

      console.log('Detected API response with completion data:', url);

      responseClone.json().then(data => {
        if (window.parseResponseForCode && window.processExtractedCode) {
           setTimeout(()=>{
                    window.injectToIDEButtons();
                  },1);
          const codeBlocks = window.parseResponseForCode(data);
          if (codeBlocks && codeBlocks.length > 0) {
            window.processExtractedCode(codeBlocks);
          } else {
            fallbackToHtmlExtraction();
          }
        } else {
          fallbackToHtmlExtraction();

        }
        if (window.intellijBridge && window.intellijBridge.notifyChatResponse && data.messages) {
          const assistantMessages = data.messages.filter(msg => msg.role === 'assistant');
          if (assistantMessages.length > 0) {
            const latestMessage = assistantMessages[assistantMessages.length - 1];
            window.intellijBridge.notifyChatResponse({
              content: latestMessage.content,
              id: latestMessage.id
            });
          }
        }
      }).catch(error => {
        console.error('Error parsing API response:', error);
        fallbackToHtmlExtraction();
      });
    }

    return response;
  }

  function fallbackToHtmlExtraction() {
    console.log('Falling back to HTML-based code extraction');
    setTimeout(() => {
      window.extractCodeToIntelliJ(!window.__text_to_replace_ide___ ? '__##use_selected_text##__' : window.__text_to_replace_ide___);
      window.__text_to_replace_ide___ = null;
      if (window.intellijBridge) {
        window.intellijBridge.callIDE('contentUpdated', { url: window.location.href });
      }
    }, 1000);
  }

  function isOpenWebUIEndpoint(url) {
    return typeof url === 'string' && (
      url.includes('/api/chat/completions') ||
      url.includes('/api/conversation') ||
      url.includes('/v1/chat/completions')
    );
  }

  // Override the fetch function to intercept and modify requests
  window.fetch = function(input, init) {
    let newInit = init ? {...init} : {};
    let url = input instanceof Request ? input.url : input;

    if (isOpenWebUIEndpoint(url)) {
      console.log('Intercepting OpenWebUI API request:', url);

      // Process the request
      return processRequest(input, init, newInit, url);
    } else {
      return originalFetch(input, init).then(handleResponse);
    }
  };
  
  // Helper function to process the request after exploration check
  function processRequest(input, init, newInit, url) {
    return window.updateProjectInfo()
      .then(async () => {
        if (newInit.body) {
          const originalBody = newInit.body;
          if (typeof originalBody === 'string') {
            newInit.body = await enhanceRequestBody(originalBody);
            console.log('Modified string request body');
            return originalFetch(input, newInit).then(handleResponse);
          } else if (originalBody instanceof FormData || originalBody instanceof URLSearchParams) {
            console.log('FormData or URLSearchParams body not modified');
            return originalFetch(input, newInit).then(handleResponse);
          } else if (originalBody instanceof Blob) {
            return new Promise((resolve, reject) => {
              const reader = new FileReader();
              reader.onload = async function() {
                try {
                  const bodyText = reader.result;
                  const modifiedBody = await enhanceRequestBody(bodyText);
                  newInit.body = modifiedBody;
                  resolve(originalFetch(input, newInit)
                    .then(handleResponse));
                } catch (error) {
                  reject(error);
                }
              };
              reader.onerror = function() {
                reject(reader.error);
              };
              reader.readAsText(originalBody);
            });
          }
        } else if (input instanceof Request) {
          return input.clone().text().then(async body => {
            const modifiedBody = await enhanceRequestBody(body);
            const newRequest = new Request(input, {
              method: input.method,
              headers: input.headers,
              body: modifiedBody,
              mode: input.mode,
              credentials: input.credentials,
              cache: input.cache,
              redirect: input.redirect,
              referrer: input.referrer,
              integrity: input.integrity
            });
            return originalFetch(newRequest)
              .then(handleResponse);
          });
        }

        return originalFetch(input, newInit).then(handleResponse);
      })
      .catch(error => {
        console.error('Error updating project info:', error);
        return originalFetch(input, init).then(handleResponse);
      });
  }
})();