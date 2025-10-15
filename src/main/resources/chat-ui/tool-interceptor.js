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

            // Parse and inject tool server into request body
            if (newInit.body) {
                const bodyText = typeof newInit.body === 'string' ? newInit.body : await readBody(newInit.body);
                const modifiedBody = await injectToolServerIntoRequest(bodyText, toolServerUrl);
                newInit.body = modifiedBody;

                console.log('[Zest Tool Interceptor] Injected Zest tool server into request');
            }

            return originalFetch(input, newInit);

        } catch (error) {
            console.error('[Zest Tool Interceptor] Error during interception:', error);
            // On error, pass through original request
            return originalFetch(input, init);
        }
    }

    // Cache for OpenAPI spec
    let cachedOpenApiSpec = null;
    let cacheTimestamp = 0;
    const CACHE_DURATION = 60000; // 1 minute

    /**
     * Fetch OpenAPI spec from tool server
     */
    async function fetchOpenApiSpec(toolServerUrl) {
        const now = Date.now();

        // Return cached spec if still valid
        if (cachedOpenApiSpec && (now - cacheTimestamp) < CACHE_DURATION) {
            return cachedOpenApiSpec;
        }

        try {
            const response = await fetch(toolServerUrl + '/openapi.json');
            if (!response.ok) {
                throw new Error('Failed to fetch OpenAPI spec: ' + response.status);
            }

            cachedOpenApiSpec = await response.json();
            cacheTimestamp = now;
            console.log('[Zest Tool Interceptor] Fetched OpenAPI spec from', toolServerUrl);

            return cachedOpenApiSpec;
        } catch (error) {
            console.error('[Zest Tool Interceptor] Error fetching OpenAPI spec:', error);
            return null;
        }
    }


    /**
     * Inject Zest tool server into chat completion request
     */
    async function injectToolServerIntoRequest(bodyText, toolServerUrl) {
        try {
            const data = JSON.parse(bodyText);

            // Fetch OpenAPI spec from tool server
            const openApiSpec = await fetchOpenApiSpec(toolServerUrl);
            if (!openApiSpec) {
                console.warn('[Zest Tool Interceptor] No OpenAPI spec available, skipping injection');
                return bodyText;
            }

            // Initialize tool_servers array if not exists
            if (!data.tool_servers) {
                data.tool_servers = [];
            }

            // Remove any existing Zest tool server for this URL (deduplication)
            data.tool_servers = data.tool_servers.filter(server =>
                server.url !== toolServerUrl
            );

            // Build tool server entry with OpenAPI spec and specs array
            const toolServerEntry = {
                url: toolServerUrl,
                openapi: openApiSpec,
                info: openApiSpec.info || {},
                specs: convertOpenApiToSpecs(openApiSpec)
            };

            // Add to tool_servers
            data.tool_servers.push(toolServerEntry);

            console.log('[Zest Tool Interceptor] Injected Zest tool server:', toolServerUrl);
            console.log('[Zest Tool Interceptor] Total tool servers:', data.tool_servers.length);

            return JSON.stringify(data);

        } catch (error) {
            console.error('[Zest Tool Interceptor] Failed to inject tool server:', error);
            return bodyText;
        }
    }

    /**
     * Convert OpenAPI spec to specs array format (for OpenWebUI)
     */
    function convertOpenApiToSpecs(openApiSpec) {
        const specs = [];

        if (!openApiSpec || !openApiSpec.paths) {
            return specs;
        }

        for (const [path, pathItem] of Object.entries(openApiSpec.paths)) {
            if (pathItem.post) {
                const operation = pathItem.post;
                const toolName = operation.operationId || path.replace(/^\//, '').replace(/_/g, '');

                const spec = {
                    name: toolName,
                    description: operation.summary || operation.description || '',
                    parameters: {}
                };

                // Extract parameters from requestBody schema
                if (operation.requestBody?.content?.['application/json']?.schema) {
                    spec.parameters = operation.requestBody.content['application/json'].schema;
                }

                specs.push(spec);
            }
        }

        return specs;
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
