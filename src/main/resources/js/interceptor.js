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

  // Initialize project info structure
  window.__project_info__ = {
    projectName: '',
    projectFilePath: '',
    currentOpenFile: '',
    codeContext: ''
  };

  /**
   * Updates project information by calling the IDE bridge
   * @returns {Promise} Promise resolving to the updated project info
   */
  window.updateProjectInfo = function() {
    return new Promise((resolve, reject) => {
      if (window.intellijBridge) {
        console.log('Fetching current project info from IDE...');
        window.intellijBridge.callIDE('getProjectInfo', {})
          .then(function(response) {
            if (response && response.success) {
              window.__project_info__ = response.result;
              console.log('Updated project info');
              resolve(window.__project_info__);
            } else {
              reject('Failed to get project info, invalid response');
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
   * Handles exploration for Agent Mode
   * @param {Object} data - The request data
   * @returns {Promise} Promise that resolves when exploration is complete
   */
  async function handleAgentModeExploration(data) {
    // Find the latest user message
    let userMessage = null;
    for (let i = data.messages.length - 1; i >= 0; i--) {
      if (data.messages[i].role === 'user') {
        userMessage = data.messages[i].content;
        break;
      }
    }
    
    if (!userMessage) {
      console.log('No user message found for exploration');
      return;
    }
    
    // Extract the actual query (remove project info if present)
    const infoEndIndex = userMessage.indexOf('</info>');
    const actualQuery = infoEndIndex >= 0 
      ? userMessage.substring(infoEndIndex + 7).trim()
      : userMessage;
    
    console.log('Starting exploration for query:', actualQuery);
    
    // Start exploration
    if (window.startExploration) {
      const sessionId = await window.startExploration(actualQuery);
      
      if (sessionId) {
        // Wait for exploration to complete (with timeout)
        const maxWaitTime = 30000; // 30 seconds
        const startTime = Date.now();
        
        while (Date.now() - startTime < maxWaitTime) {
          // Check if exploration is complete
          if (window.__exploration_result__) {
            console.log('Exploration complete, enhancing system prompt');
            
            // Add exploration results to system prompt
            const explorationContext = `\n\n# CODE EXPLORATION RESULTS\n${window.__exploration_result__.summary || 'No summary available'}`;
            
            const systemMsgIndex = data.messages.findIndex(msg => msg.role === 'system');
            if (systemMsgIndex >= 0) {
              data.messages[systemMsgIndex].content += explorationContext;
            }
            
            // Mark exploration as used (will close the UI)
            if (window.markExplorationUsed) {
              window.markExplorationUsed();
            }
            
            // Clear the result
            window.__exploration_result__ = null;
            break;
          }
          
          // Wait a bit before checking again
          await new Promise(resolve => setTimeout(resolve, 500));
        }
        
        if (Date.now() - startTime >= maxWaitTime) {
          console.warn('Exploration timed out');
        }
      }
    }
  }

  /**
   * Enhances request body with real-time project info and dynamic model selection
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
        // Optionally log:
        console.log('[Interceptor] Overwrote model with', window.__selected_model_name__);
      }

      // Check if this is a chat completion request
      if (data.messages && Array.isArray(data.messages)) {
        // Handle Agent Mode exploration first
        if (window.__zest_mode__ === 'Agent Mode' && window.startExploration) {
          await handleAgentModeExploration(data);
        }
        
        // Handle Project Mode enhancement
        if (window.__zest_mode__ === 'Project Mode' && window.enhanceWithProjectKnowledge) {
          window.enhanceWithProjectKnowledge(data);
        }
        
        // Handle system message based on mode
        if (window.__zest_mode__ !== 'Neutral Mode' && window.__injected_system_prompt__) {
          const systemMsgIndex = data.messages.findIndex(msg => msg.role === 'system');
          if (systemMsgIndex >= 0) {
            // Check if we already added exploration results
            const hasExplorationResults = data.messages[systemMsgIndex].content.includes('# CODE EXPLORATION RESULTS');
            if (hasExplorationResults) {
              // Don't override, the exploration results are already there
              console.log('Preserving system message with exploration results');
            } else {
              // Override existing system message
              data.messages[systemMsgIndex].content = window.__injected_system_prompt__;
            }
          } else {
            // Add new system message at the beginning
            console.log("Adding system message to the beginning of messages", window.__injected_system_prompt__);
            data.messages.unshift({
              role: 'system',
              content: window.__injected_system_prompt__
            });
          }
        } else if (!data.messages.some(msg => msg.role === 'system') && window.__injected_system_prompt__) {
          // Not in Agent Mode, add system message only if it doesn't exist
          data.messages.unshift({
            role: 'system',
            content: window.__injected_system_prompt__
          });
        }

        // Add project context info to user messages if in Agent Mode
        if (window.__zest_mode__ === 'Agent Mode' && window.__project_info__) {
          for (let i = data.messages.length - 1; i >= 0; i--) {
            if (data.messages[i].role === 'user') {
              const info = window.__project_info__;
              const projectInfoText = "<info>\n" +
                "\n" +
                "Project Name: " + info.projectName + "\n" +
                "\n" +
                "Project Path: " + info.projectFilePath + "\n" +
                "\n" +
                "Current File: " + info.currentOpenFile + "\n" +
                "\n" +
                "Code Context:\n```\n" + info.codeContext + "\n```\n" +
                "\n" +
                "</info>\n\n";
              data.messages[i].content = projectInfoText + data.messages[i].content;
              if (window.__should_use_native_function_calling__){
                data.params.function_calling =  'native';
              } else {
                data.params.function_calling =  'default';
              }
              break;
            }
          }
        }
      }

      // Knowledge collection integration if present
      if (data.files && Array.isArray(data.files)) {
        console.log('Request includes files/collections');
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
  function handleResponse(response) {
    const responseClone = response.clone();
    const url = responseClone.url || '';

    if (url.includes('completed') || url.includes('/api/conversation')) {
      window.__selected_model_name__ = null;
      window.__zest_usage__ = null;
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
      if (window.__zest_mode__ === 'Agent Mode') {
        console.log('Agent Mode active: Adding project context to user message');
      }

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
    } else {
      return originalFetch(input, init).then(handleResponse);
    }
  };
})();