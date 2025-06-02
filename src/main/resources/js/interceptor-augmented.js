/**
 * Enhanced Interceptor with Augmented Mode Support
 * 
 * This is an enhanced version of the interceptor that supports async augmentation
 */

// Save the original enhanceRequestBody function
window.__original_enhanceRequestBody = window.enhanceRequestBody;

// Override the enhanceRequestBody to support async augmentation
window.enhanceRequestBody = async function(body) {
    if (!body) return body;

    try {
        const data = JSON.parse(body);

        // === Add isFromZest flag ===
        data.custom_tool = 'Zest|' + (window.__zest_usage__ ? window.__zest_usage__ : "NORMAL_CHAT");

        // === Inject the dynamically selected model if present ===
        if (window.__selected_model_name__) {
            data.model = window.__selected_model_name__;
            console.log('[Interceptor] Overwrote model with', window.__selected_model_name__);
        }

        // Check if this is a chat completion request
        if (data.messages && Array.isArray(data.messages)) {
            // Handle different modes
            if (window.__zest_mode__ === 'Project Mode' && window.enhanceWithProjectKnowledge) {
                window.enhanceWithProjectKnowledge(data);
            } else if (window.__zest_mode__ === 'Augmented Mode' && window.enhanceWithAugmentedContext) {
                // Handle Augmented Mode enhancement (async)
                try {
                    await window.enhanceWithAugmentedContext(data);
                    console.log('[Interceptor] Augmented mode enhancement completed');
                } catch (err) {
                    console.error('[Interceptor] Augmented mode enhancement failed:', err);
                }
            }
            
            // Handle system message based on mode
            if (window.__zest_mode__ !== 'Neutral Mode' && window.__injected_system_prompt__) {
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
                // Not in a special mode, add system message only if it doesn't exist
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
                            data.params.function_calling = 'native';
                        } else {
                            data.params.function_calling = 'default';
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
};

// Also need to update the fetch override to handle async enhanceRequestBody
(function() {
    const originalFetch = window.fetch;
    
    window.fetch = async function(input, init) {
        let newInit = init ? {...init} : {};
        let url = input instanceof Request ? input.url : input;

        if (isOpenWebUIEndpoint(url)) {
            console.log('Intercepting OpenWebUI API request:', url);
            
            try {
                await window.updateProjectInfo();
                
                if (newInit.body) {
                    const originalBody = newInit.body;
                    if (typeof originalBody === 'string') {
                        newInit.body = await window.enhanceRequestBody(originalBody);
                        console.log('Modified string request body');
                        return originalFetch(input, newInit).then(handleResponse);
                    } else if (originalBody instanceof FormData || originalBody instanceof URLSearchParams) {
                        console.log('FormData or URLSearchParams body not modified');
                        return originalFetch(input, newInit).then(handleResponse);
                    } else if (originalBody instanceof Blob) {
                        const bodyText = await originalBody.text();
                        const modifiedBody = await window.enhanceRequestBody(bodyText);
                        newInit.body = modifiedBody;
                        return originalFetch(input, newInit).then(handleResponse);
                    }
                } else if (input instanceof Request) {
                    const body = await input.clone().text();
                    const modifiedBody = await window.enhanceRequestBody(body);
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
                    return originalFetch(newRequest).then(handleResponse);
                }

                return originalFetch(input, newInit).then(handleResponse);
            } catch (error) {
                console.error('Error in request enhancement:', error);
                return originalFetch(input, init).then(handleResponse);
            }
        } else {
            return originalFetch(input, init).then(handleResponse);
        }
    };
    
    function isOpenWebUIEndpoint(url) {
        return typeof url === 'string' && (
            url.includes('/api/chat/completions') ||
            url.includes('/api/conversation') ||
            url.includes('/v1/chat/completions')
        );
    }
    
    function handleResponse(response) {
        // Use the existing handleResponse logic
        const responseClone = response.clone();
        const url = responseClone.url || '';

        if (url.includes('completed') || url.includes('/api/conversation')) {
            window.__selected_model_name__ = null;
            window.__zest_usage__ = null;
            console.log('Detected API response with completion data:', url);

            responseClone.json().then(data => {
                if (window.parseResponseForCode && window.processExtractedCode) {
                    setTimeout(() => {
                        window.injectToIDEButtons();
                    }, 1);
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
})();

console.log('[Interceptor] Enhanced interceptor with async support loaded');
