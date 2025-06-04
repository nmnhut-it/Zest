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
   * Handles exploration for Agent Mode and returns the exploration context
   * @param {string} query - The user's query
   * @returns {Promise<string>} The exploration context to add to system prompt
   */
  async function performExploration(query) {
    console.log('Starting exploration for query:', query);
    
    // Start exploration
    if (window.startExploration) {
      const sessionId = await window.startExploration(query);
      
      if (sessionId === 'indexing') {
        // Project is being indexed, wait for it to complete
        console.log('Project is being indexed, waiting for completion...');
        
        // Wait for indexing to complete (with timeout)
        const maxIndexingTime = 300000; // 5 minutes for indexing
        const startTime = Date.now();
        
        return new Promise((resolve) => {
          let explorationStarted = false;
          
          // Store the original handlers
          const originalComplete = window.handleIndexingComplete;
          const originalError = window.handleIndexingError;
          
          // Override handlers to resolve our promise
          window.handleIndexingComplete = async function() {
            // Call original handler
            if (originalComplete) await originalComplete();
            
            // Restore original handlers
            window.handleIndexingComplete = originalComplete;
            window.handleIndexingError = originalError;
            
            console.log('Indexing complete, now waiting for exploration to complete...');
            explorationStarted = true;
            
            // Now wait for the actual exploration to complete
            const explorationMaxTime = 600000; // 10 minutes for exploration after indexing
            const explorationStartTime = Date.now();
            
            while (Date.now() - explorationStartTime < explorationMaxTime) {
              // Check if exploration result is available
              if (window.__exploration_result__) {
                console.log('Exploration complete after indexing');
                
                const explorationContext = `\n\n# CODE EXPLORATION RESULTS\n${window.__exploration_result__.summary || 'No summary available'}`;
                
                // Mark exploration as used
                if (window.markExplorationUsed) {
                  window.markExplorationUsed();
                }
                
                // Clear the result
                window.__exploration_result__ = null;
                
                resolve(explorationContext);
                return;
              }
              
              // Wait before checking again
              await new Promise(r => setTimeout(r, 500));
            }
            
            // Exploration timed out after indexing
            console.warn('Exploration timed out after indexing');
            resolve('\n\n# CODE EXPLORATION RESULTS\nExploration timed out after indexing.');
          };
          
          window.handleIndexingError = function(error) {
            // Call original handler
            if (originalError) originalError(error);
            
            // Restore original handlers
            window.handleIndexingComplete = originalComplete;
            window.handleIndexingError = originalError;
            
            // Resolve with error context
            resolve('\n\n# CODE EXPLORATION RESULTS\nFailed to index project: ' + error);
          };
          
          // Also set a timeout for the entire process
          setTimeout(() => {
            if (!explorationStarted) {
              console.warn('Indexing timeout reached');
              // Restore original handlers
              window.handleIndexingComplete = originalComplete;
              window.handleIndexingError = originalError;
              resolve('\n\n# CODE EXPLORATION RESULTS\nIndexing timed out.');
            }
          }, maxIndexingTime);
        });
      } else if (sessionId) {
        // Wait for exploration to complete (with timeout)
        const maxWaitTime = 600000; // 600 seconds (10 minutes) for exploration
        const startTime = Date.now();
        
        while (Date.now() - startTime < maxWaitTime) {
          // Check if exploration is complete
          if (window.__exploration_result__) {
            console.log('Exploration complete');
            
            const explorationContext = `\n\n# CODE EXPLORATION RESULTS\n${window.__exploration_result__.summary || 'No summary available'}`;
            
            // Mark exploration as used (will close the UI)
            if (window.markExplorationUsed) {
              window.markExplorationUsed();
            }
            
            // Clear the result
            window.__exploration_result__ = null;
            
            return explorationContext;
          }
          
          // Wait a bit before checking again
          await new Promise(resolve => setTimeout(resolve, 500));
        }
        
        if (Date.now() - startTime >= maxWaitTime) {
          console.warn('Exploration timed out after ' + maxWaitTime + 'ms');
          // Still check one more time in case it just completed
          if (window.__exploration_result__) {
            console.log('Found exploration result at timeout boundary');
            const explorationContext = `\n\n# CODE EXPLORATION RESULTS\n${window.__exploration_result__.summary || 'No summary available'}`;
            if (window.markExplorationUsed) {
              window.markExplorationUsed();
            }
            window.__exploration_result__ = null;
            return explorationContext;
          }
          return '\n\n# CODE EXPLORATION RESULTS\nExploration timed out after ' + (maxWaitTime/1000) + ' seconds. The codebase might be too large or complex for automatic exploration.';
        }
      }
    }
    
    return '';
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
        // Handle Project Mode enhancement
        if (window.__zest_mode__ === 'Project Mode' && window.enhanceWithProjectKnowledge) {
          window.enhanceWithProjectKnowledge(data);
        }
        
        // Handle system message based on mode
        if (window.__zest_mode__ !== 'Neutral Mode' && window.__injected_system_prompt__) {
          const systemMsgIndex = data.messages.findIndex(msg => msg.role === 'system');
          let systemPrompt = window.__injected_system_prompt__;
          
          // Add pending exploration context if available
          if (window.__pending_exploration_context__) {
            systemPrompt += window.__pending_exploration_context__;
            window.__pending_exploration_context__ = null; // Clear after use
            console.log('Added exploration context to system prompt');
          }
          
          if (systemMsgIndex >= 0) {
            // Override existing system message
            data.messages[systemMsgIndex].content = systemPrompt;
          } else {
            // Add new system message at the beginning
            console.log("Adding system message to the beginning of messages");
            data.messages.unshift({
              role: 'system',
              content: systemPrompt
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

      // Debug: Log the final system prompt
      const systemMsg = data.messages.find(msg => msg.role === 'system');
      if (systemMsg) {
        console.log('Final system prompt length:', systemMsg.content.length);
        console.log('Final system prompt:', systemMsg.content);
        // Also log if exploration results are included
        if (systemMsg.content.includes('# CODE EXPLORATION RESULTS')) {
          console.log('✓ Exploration results successfully included in system prompt');
        } else if (window.__zest_mode__ === 'Agent Mode') {
          console.warn('⚠️ Agent Mode but no exploration results in system prompt');
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
      
      // For Agent Mode, we need to check if exploration is needed BEFORE processing
      if (window.__zest_mode__ === 'Agent Mode' && window.startExploration) {
        console.log('Agent Mode active: Checking if exploration is needed');
        
        // Parse the body to check if this is a new user message
        let bodyPromise;
        if (newInit.body) {
          if (typeof newInit.body === 'string') {
            bodyPromise = Promise.resolve(newInit.body);
          } else if (newInit.body instanceof Blob) {
            bodyPromise = newInit.body.text();
          } else if (input instanceof Request) {
            bodyPromise = input.clone().text();
          } else {
            bodyPromise = Promise.resolve(null);
          }
        } else if (input instanceof Request) {
          bodyPromise = input.clone().text();
        } else {
          bodyPromise = Promise.resolve(null);
        }
        
        return bodyPromise.then(async (bodyText) => {
          let explorationContext = '';
          
          if (bodyText) {
            try {
              const data = JSON.parse(bodyText);
              
              // Check if this is a new user message (not a continuation)
              if (data.messages && Array.isArray(data.messages)) {
                const userMessages = data.messages.filter(msg => msg.role === 'user');
                if (userMessages.length > 0) {
                  // Get the latest user message
                  const latestUserMsg = userMessages[userMessages.length - 1].content;
                  
                  // Extract the actual query (remove project info if present)
                  const infoEndIndex = latestUserMsg.indexOf('</info>');
                  const actualQuery = infoEndIndex >= 0 
                    ? latestUserMsg.substring(infoEndIndex + 7).trim()
                    : latestUserMsg;
                  
                  // Only explore if this is a new query (not empty)
                  if (actualQuery.trim()) {
                    explorationContext = await performExploration(actualQuery);
                  }
                }
              }
            } catch (e) {
              console.error('Error parsing body for exploration check:', e);
            }
          }
          
          // Store exploration context for later use
          window.__pending_exploration_context__ = explorationContext;
          
          if (explorationContext) {
            console.log('✓ Exploration context obtained:', explorationContext.substring(0, 100) + '...');
          } else {
            console.warn('⚠️ No exploration context obtained for Agent Mode');
          }
          
          // Now continue with the normal flow
          return processRequest(input, init, newInit, url);
        });
      } else {
        // Not Agent Mode, process normally
        return processRequest(input, init, newInit, url);
      }
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