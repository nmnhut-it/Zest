/**
 * Tool Server Interceptor for OpenWebUI
 *
 * Automatically injects OpenAPI tool server into requests when in Agent Mode.
 * The tool server exposes IntelliJ code exploration and modification capabilities.
 */

(function() {
    'use strict';

    // Store original fetch for interception
    const originalFetch = window.fetch;

    console.log('[Zest Tool Interceptor] Loaded');

    /**
     * Override fetch to inject tool server in Agent Mode
     */
    window.fetch = function(input, init) {
        const url = input instanceof Request ? input.url : input;

        // Only intercept OpenWebUI chat completion requests
        if (isOpenWebUIEndpoint(url)) {
            return interceptAndInjectTools(input, init, originalFetch);
        }

        // Pass through other requests unchanged
        return originalFetch(input, init);
    };

    /**
     * Check if URL is an OpenWebUI chat endpoint
     */
    function isOpenWebUIEndpoint(url) {
        return typeof url === 'string' && (
            url.includes('/api/chat/completions') ||
            url.includes('/api/conversation') ||
            url.includes('/v1/chat/completions')
        );
    }

    /**
     * Intercept request and inject tool server if in Agent Mode
     */
    async function interceptAndInjectTools(input, init, originalFetch) {
        try {
            // Check if we're in Agent Mode
            const currentMode = window.__zest_mode__ || 'Default Mode';
            const isAgentMode = currentMode === 'Agent Mode';

            // Check if tool server URL is available
            const toolServerUrl = window.__tool_server_url__;

            if (!isAgentMode || !toolServerUrl) {
                // Not Agent Mode or no tool server - pass through
                console.log('[Zest Tool Interceptor] Skipping injection: mode=' + currentMode + ', toolServerUrl=' + toolServerUrl);
                return originalFetch(input, init);
            }

            // Clone init to avoid mutating original
            const newInit = init ? {...init} : {};

            // Parse and modify request body
            if (newInit.body) {
                const bodyText = typeof newInit.body === 'string' ? newInit.body : await readBody(newInit.body);
                const modifiedBody = await injectToolServer(bodyText, toolServerUrl);
                newInit.body = modifiedBody;

                console.log('[Zest Tool Interceptor] Injected tool server:', toolServerUrl);
            }

            return originalFetch(input, newInit);

        } catch (error) {
            console.error('[Zest Tool Interceptor] Error during interception:', error);
            // On error, pass through original request
            return originalFetch(input, init);
        }
    }

    /**
     * Inject tool server into request body
     */
    async function injectToolServer(bodyText, toolServerUrl) {
        try {
            const data = JSON.parse(bodyText);

            // Add tool_servers array for OpenWebUI
            if (!data.tool_servers) {
                data.tool_servers = [];
            }

            // Check if our tool server is already in the array
            const alreadyExists = data.tool_servers.some(server =>
                server.url && server.url.includes(toolServerUrl)
            );

            if (!alreadyExists) {
                // Add our OpenAPI tool server
                data.tool_servers.push({
                    url: toolServerUrl,
                    path: "openapi.json",
                    config: {
                        enable: true,
                        access_control: {}
                    },
                    info: {
                        name: "Zest Code Tools",
                        description: "IntelliJ code exploration and modification tools"
                    }
                });

                console.log('[Zest Tool Interceptor] Added tool server to request');
            }

            return JSON.stringify(data);

        } catch (error) {
            console.error('[Zest Tool Interceptor] Failed to inject tool server:', error);
            return bodyText;
        }
    }

    /**
     * Read body from various formats
     */
    async function readBody(body) {
        if (body instanceof Blob) {
            return await body.text();
        }
        if (body instanceof FormData || body instanceof URLSearchParams) {
            return body.toString();
        }
        return String(body);
    }

    // Expose status check function
    window.checkZestToolInjection = function() {
        console.log('=== ZEST TOOL INJECTION STATUS ===');
        console.log('Current Mode:', window.__zest_mode__ || 'Not set');
        console.log('Tool Server URL:', window.__tool_server_url__ || 'Not set');
        console.log('IntelliJ Bridge:', !!window.intellijBridge);
        console.log('Will inject tools:',
            (window.__zest_mode__ === 'Agent Mode') && !!window.__tool_server_url__);
    };

    console.log('[Zest Tool Interceptor] Ready - will inject tools in Agent Mode');
    console.log('[Zest Tool Interceptor] Use checkZestToolInjection() to debug');
})();
