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

  // Track conversation state
  window.__conversation_state__ = {
    currentConversationId: null,
    hasPerformedExploration: false,
    lastUserMessageCount: 0
  };

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
   * Gets the conversation ID from various possible sources
   * @param {Object} data - The request data
   * @returns {string|null} The conversation ID or null
   */
  function extractConversationId(data) {
    // Try from request data
    let conversationId = data.conversation_id || data.conversationId ||
                        (data.metadata && data.metadata.conversation_id) ||
                        null;
    
    // If not found, try from current page URL
    if (!conversationId) {
      try {
        const currentUrl = window.location.pathname;
        const urlParts = currentUrl.split('/');
        // OpenWebUI typically has URLs like /c/[conversation-id]
        if (urlParts[1] === 'c' && urlParts[2]) {
          conversationId = urlParts[2];
          console.log('Using conversation ID from page URL:', conversationId);
        }
      } catch (e) {
        console.error('Error extracting conversation ID from page URL:', e);
      }
    }
    
    return conversationId;
  }

  /**
   * Determines if this is the start of a new conversation
   * @param {Object} data - The request data
   * @returns {boolean} True if this is a new conversation
   */
  function isNewConversation(data) {
    // Check if messages array exists
    if (!data.messages || !Array.isArray(data.messages)) {
      return false;
    }

    // Count user messages
    const userMessageCount = data.messages.filter(msg => msg.role === 'user').length;

    // Get conversation ID
    const conversationId = extractConversationId(data);

    // Detect new conversation:
    // 1. First message (only 1 user message)
    // 2. New conversation ID
    // 3. Reset after completion
    if (userMessageCount === 1) {
      console.log('New conversation detected: First user message');
      return true;
    }

    if (conversationId && conversationId !== window.__conversation_state__.currentConversationId) {
      console.log('New conversation detected: Different conversation ID');
      window.__conversation_state__.currentConversationId = conversationId;
      return true;
    }

    // If user message count decreased (new conversation started)
    if (userMessageCount < window.__conversation_state__.lastUserMessageCount) {
      console.log('New conversation detected: Message count reset');
      return true;
    }

    window.__conversation_state__.lastUserMessageCount = userMessageCount;
    return false;
  }

  /**
   * Resets conversation state
   */
  function resetConversationState() {
    window.__conversation_state__.hasPerformedExploration = false;
    window.__conversation_state__.lastUserMessageCount = 0;
  }

  /**
   * Handles exploration for Agent Mode and returns the exploration context
   * @param {string} query - The user's query
   * @param {string} conversationId - The conversation ID for context management
   * @returns {Promise<string>} The exploration context to add to system prompt
   */
  async function performExploration(query, conversationId) {
    console.log('Starting exploration for query:', query, 'conversation:', conversationId);

    // Start exploration
    if (window.startExploration) {
      const sessionId = await window.startExploration(query, conversationId);

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

          // For Agent Mode, always add exploration context if available (except for git commits)
          if (window.__zest_mode__ === 'Agent Mode' && window.__zest_usage__ !== 'CHAT_GIT_COMMIT_MESSAGE') {
            const explorationContext = window.__pending_exploration_context__;
            if (explorationContext) {
              systemPrompt += explorationContext;
              window.__pending_exploration_context__ = null; // Clear pending after use
              console.log('Added exploration context to system prompt');
            } else {
              console.warn('Agent Mode but no exploration context available');
            }
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
        } else if (window.__zest_mode__ === 'Agent Mode' && isNewConversation(data)) {
          console.warn('⚠️ Agent Mode new conversation but no exploration results in system prompt');
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
      // Clear model and usage on completion
      window.__selected_model_name__ = null;
      window.__zest_usage__ = null;

      // Check if conversation is ending
      if (url.includes('completed')) {
        console.log('Conversation completed, resetting state');
        resetConversationState();
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
      
      // Try to extract conversation ID from URL if present
      let urlConversationId = null;
      try {
        const urlObj = new URL(url, window.location.origin);
        const pathParts = urlObj.pathname.split('/');
        // Check if URL contains a conversation ID (typically a UUID)
        for (const part of pathParts) {
          if (part.match(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i)) {
            urlConversationId = part;
            console.log('Found conversation ID in URL:', urlConversationId);
            break;
          }
        }
      } catch (e) {
        console.error('Error parsing URL for conversation ID:', e);
      }

      // For Agent Mode, we need to check if exploration is needed BEFORE processing
      // Skip exploration for git commit messages
      if (window.__zest_mode__ === 'Agent Mode' &&
          window.startExploration &&
          window.__zest_usage__ !== 'CHAT_GIT_COMMIT_MESSAGE') {
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

              // Debug: Log the entire data structure to see where conversation ID is
              console.log('Request data structure:', data);
              console.log('Request data keys:', Object.keys(data));
              if (data.metadata) {
                console.log('Metadata keys:', Object.keys(data.metadata));
              }

              // Extract conversation ID using the helper function
              const conversationId = extractConversationId(data) || urlConversationId;
              
              console.log('Final extracted conversation ID:', conversationId);
              
              // Update context debugger
              if (window.contextDebugger) {
                window.contextDebugger.update({
                  conversationId: conversationId,
                  mode: window.__zest_mode__
                });
              }

              // Check if this is a new conversation
              if (isNewConversation(data)) {
                console.log('New conversation detected - checking if exploration needed');

                // First check if we already have context stored in Java for this conversation
                let hasExistingContext = false;
                if (conversationId && window.intellijBridge) {
                  // Update debugger
                  if (window.contextDebugger) {
                    window.contextDebugger.update({
                      explorationStatus: 'Checking for existing context...',
                      timestamp: Date.now()
                    });
                  }
                  
                  try {
                    const contextCheckResponse = await window.intellijBridge.callIDE('getExplorationContext', {
                      conversationId: conversationId || ""
                    });
                    
                    if (contextCheckResponse && contextCheckResponse.success && contextCheckResponse.context) {
                      // We already have context for this conversation
                      explorationContext = `\n\n# CODE EXPLORATION RESULTS\n${contextCheckResponse.context}`;
                      hasExistingContext = true;
                      console.log('Found existing exploration context for conversation:', conversationId);
                      
                      // Update debugger
                      if (window.contextDebugger) {
                        window.contextDebugger.update({
                          explorationStatus: 'Found existing context',
                          contextSource: 'Java Storage (Existing)',
                          context: explorationContext,
                          timestamp: Date.now()
                        });
                      }
                    }
                  } catch (e) {
                    console.error('Error checking for existing exploration context:', e);
                  }
                }

                // Only perform new exploration if we don't have existing context
                if (!hasExistingContext) {
                    // Reset exploration state for new conversation
                    window.__conversation_state__.hasPerformedExploration = false;

                    // Check if this is a new user message (not a continuation)
                    if (data.messages && Array.isArray(data.messages)) {
                      const userMessages = data.messages.filter(msg => msg.role === 'user');
                      if (userMessages.length > 0 && !window.__conversation_state__.hasPerformedExploration) {
                        // Get the latest user message
                        const latestUserMsg = userMessages[userMessages.length - 1].content;

                        // Extract the actual query (remove project info if present)
                        const infoEndIndex = latestUserMsg.indexOf('</info>');
                        const actualQuery = infoEndIndex >= 0
                          ? latestUserMsg.substring(infoEndIndex + 7).trim()
                          : latestUserMsg;

                        // Only explore if this is a new query (not empty)
                        if (actualQuery.trim()) {
                          console.log('Performing new exploration for conversation:', conversationId);
                          
                          // Update debugger
                          if (window.contextDebugger) {
                            window.contextDebugger.update({
                              explorationStatus: 'Starting new exploration...',
                              contextSource: 'New Exploration',
                              timestamp: Date.now()
                            });
                          }
                          
                          explorationContext = await performExploration(actualQuery, conversationId);
                          window.__conversation_state__.hasPerformedExploration = true;
                          
                          // Update debugger with result
                          if (window.contextDebugger) {
                            window.contextDebugger.update({
                              explorationStatus: 'Exploration complete',
                              context: explorationContext,
                              timestamp: Date.now()
                            });
                          }
                        }
                      }
                    }
                } else {
                    console.log('Skipping exploration - already have context for this conversation');
                    
                    // Update debugger
                    if (window.contextDebugger) {
                      window.contextDebugger.update({
                        explorationStatus: 'Using existing context',
                        contextSource: 'Cached from previous exploration',
                        timestamp: Date.now()
                      });
                    }
                }
              } else {
                console.log('Continuing existing conversation - checking for stored context in Java');
                
                // Try to get context from Java service
                if (conversationId && window.intellijBridge && !window.__pending_exploration_context__) {
                  // Update debugger
                  if (window.contextDebugger) {
                    window.contextDebugger.update({
                      explorationStatus: 'Fetching stored context...',
                      timestamp: Date.now()
                    });
                  }
                  
                  try {
                    const contextResponse = await window.intellijBridge.callIDE('getExplorationContext', {
                      conversationId: conversationId || ""  // Send empty string instead of null
                    });
                    
                    if (contextResponse && contextResponse.success && contextResponse.context) {
                      explorationContext = `\n\n# CODE EXPLORATION RESULTS\n${contextResponse.context}`;
                      console.log('Retrieved exploration context from Java for conversation:', conversationId);
                      
                      // Update debugger
                      if (window.contextDebugger) {
                        window.contextDebugger.update({
                          explorationStatus: 'Retrieved stored context',
                          contextSource: 'Java Storage (Continuing)',
                          context: explorationContext,
                          timestamp: Date.now()
                        });
                      }
                    } else {
                      console.log('No stored context found for conversation:', conversationId);
                      
                      // Update debugger
                      if (window.contextDebugger) {
                        window.contextDebugger.update({
                          explorationStatus: 'No context found',
                          contextSource: 'None',
                          context: '',
                          timestamp: Date.now()
                        });
                      }
                    }
                  } catch (e) {
                    console.error('Error retrieving exploration context:', e);
                  }
                } else if (window.__pending_exploration_context__) {
                  console.log('Already have pending exploration context, skipping retrieval');
                  
                  // Update debugger
                  if (window.contextDebugger) {
                    window.contextDebugger.update({
                      explorationStatus: 'Using pending context',
                      contextSource: 'Memory (Pending)',
                      timestamp: Date.now()
                    });
                  }
                }
              }
            } catch (e) {
              console.error('Error parsing body for exploration check:', e);
            }
          }

          // Store exploration context for later use
          if (explorationContext) {
            window.__pending_exploration_context__ = explorationContext;
            console.log('✓ Exploration context available:', explorationContext.substring(0, 100) + '...');
          } else if (window.__zest_mode__ === 'Agent Mode' && window.__zest_usage__ !== 'CHAT_GIT_COMMIT_MESSAGE') {
            console.warn('⚠️ No exploration context available for Agent Mode conversation');
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