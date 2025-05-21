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
   * Enhances request body with real-time project info
   * @param {string} body - The original request body
   * @returns {string} The modified request body
   */
  function enhanceRequestBody(body) {
    if (!body) return body;

    try {
      const data = JSON.parse(body);

      // Check if this is a chat completion request
      if (data.messages && Array.isArray(data.messages)) {
        // Handle system message based on mode
        if (window.__zest_mode__ !== 'Neutral Mode' && window.__injected_system_prompt__) {
          // In Agent Mode, override any existing system message or add a new one
          const systemMsgIndex = data.messages.findIndex(msg => msg.role === 'system');
          if (systemMsgIndex >= 0) {
            // Override existing system message
            data.messages[systemMsgIndex].content = window.__injected_system_prompt__;
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
          // Find the most recent user message
          for (let i = data.messages.length - 1; i >= 0; i--) {
            if (data.messages[i].role === 'user') {
              const info = window.__project_info__;
              // Format the project info
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
              // Prepend project info to the user message
              data.messages[i].content = projectInfoText + data.messages[i].content;
              if (window.__should_use_native_function_calling__){
                data.params.function_calling =  'native';
              }
              else {
                data.params.function_calling =  'default';
              }
              break;
            }
          }
        }
      }

      // Handle knowledge collection integration if present
      if (data.files && Array.isArray(data.files)) {
        // Ensure any file-related parameters are properly set
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

    // Look for API responses that might contain completed chat data
    if (url.includes('completed') || url.includes('/api/conversation')) {
      console.log('Detected API response with completion data:', url);

      // Try to handle it with the response parser
      responseClone.json().then(data => {
//        console.log('Parsed response data:', data);

        // If we have the parser functions available, use them
        if (window.parseResponseForCode && window.processExtractedCode) {
          const codeBlocks = window.parseResponseForCode(data);
          
          if (codeBlocks && codeBlocks.length > 0) {
            // We found code blocks, process them
            window.processExtractedCode(codeBlocks);
          } else {
            // Fall back to DOM-based extraction if no code blocks found
            fallbackToHtmlExtraction();
          }
        } else {
          // Fall back to DOM-based extraction if parser not available
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
        // Fall back to DOM-based extraction if parsing fails
        fallbackToHtmlExtraction();
      });
    }

    return response;
  }

  /**
   * Falls back to HTML-based code extraction
   */
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

  /**
   * Determines if a URL is an OpenWebUI API endpoint
   * @param {string} url - The URL to check
   * @returns {boolean} True if the URL is an OpenWebUI API endpoint
   */
  function isOpenWebUIEndpoint(url) {
    return typeof url === 'string' && (
      url.includes('/api/chat/completions') ||
      url.includes('/api/conversation') ||
      url.includes('/v1/chat/completions')
    );
  }

  // Override the fetch function to intercept and modify requests
  window.fetch = function(input, init) {
    // Clone the init object to avoid modifying the original
    let newInit = init ? {...init} : {};
    let url = input instanceof Request ? input.url : input;

    // Check if this is an API request to OpenWebUI
    if (isOpenWebUIEndpoint(url)) {
      console.log('Intercepting OpenWebUI API request:', url);
      if (window.__zest_mode__ === 'Agent Mode') {
        console.log('Agent Mode active: Adding project context to user message');
      }

      // Get real-time project info from IDE before processing the request
      return window.updateProjectInfo()
        .then(() => {
          // If there's a body in the request, modify it
          if (newInit.body) {
            const originalBody = newInit.body;
            if (typeof originalBody === 'string') {
              newInit.body = enhanceRequestBody(originalBody);
              console.log('Modified string request body');
              return originalFetch(input, newInit).then(handleResponse);
            } else if (originalBody instanceof FormData || originalBody instanceof URLSearchParams) {
              console.log('FormData or URLSearchParams body not modified');
              return originalFetch(input, newInit).then(handleResponse);
            } else if (originalBody instanceof Blob) {
              // Handle Blob body (requires async processing)
              return new Promise((resolve, reject) => {
                const reader = new FileReader();
                reader.onload = function() {
                  const bodyText = reader.result;
                  const modifiedBody = enhanceRequestBody(bodyText);
                  newInit.body = modifiedBody;
                  resolve(originalFetch(input, newInit)
                    .then(handleResponse));
                };
                reader.onerror = function() {
                  reject(reader.error);
                };
                reader.readAsText(originalBody);
              });
            }
          } else if (input instanceof Request) {
            // If input is a Request object with a body
            return input.clone().text().then(body => {
              const modifiedBody = enhanceRequestBody(body);
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

          // Default case if none of the above conditions match
          return originalFetch(input, newInit).then(handleResponse);
        })
        .catch(error => {
          console.error('Error updating project info:', error);
          // Continue with the request even if project info update fails
          return originalFetch(input, init).then(handleResponse);
        });
    } else {
      // For non-API requests, just use the original fetch
      return originalFetch(input, init).then(handleResponse);
    }
  };
})();