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
                const modifiedBody = await filterToolsByProject(bodyText);
                newInit.body = modifiedBody;

                console.log('[Zest Tool Interceptor] Filtered tools by project');
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
     * Convert OpenAPI spec to inline tools format
     */
    function convertOpenApiToTools(openApiSpec) {
        const tools = [];

        if (!openApiSpec || !openApiSpec.paths) {
            return tools;
        }

        // Iterate through paths and extract tool definitions
        for (const [path, pathItem] of Object.entries(openApiSpec.paths)) {
            if (pathItem.post) {
                const operation = pathItem.post;
                const toolName = operation.operationId || path.replace(/^\/api\/tools\//, '');

                const tool = {
                    type: 'function',
                    function: {
                        name: toolName,
                        description: operation.summary || operation.description || '',
                        parameters: {
                            type: 'object',
                            properties: {},
                            required: []
                        }
                    }
                };

                // Extract parameters from requestBody schema
                if (operation.requestBody?.content?.['application/json']?.schema) {
                    const schema = operation.requestBody.content['application/json'].schema;
                    tool.function.parameters = schema;
                }

                tools.push(tool);
            }
        }

        console.log('[Zest Tool Interceptor] Converted', tools.length, 'tools from OpenAPI spec');
        return tools;
    }

    /**
     * Filter tools to only include current project's tools
     */
    async function filterToolsByProject(bodyText) {
        try {
            const data = JSON.parse(bodyText);

            // Get current project path
            const currentProjectPath = window.__project_info__?.projectFilePath;
            if (!currentProjectPath) {
                console.warn('[Zest Tool Interceptor] No project path available, skipping filter');
                return bodyText;
            }

            // Filter tools array if it exists
            if (data.tools && Array.isArray(data.tools)) {
                const originalCount = data.tools.length;

                // Keep only tools that match current project path in description
                data.tools = data.tools.filter(tool => {
                    const description = tool.function?.description || '';
                    // Check if description contains current project path
                    return description.includes('[Project: ' + currentProjectPath + ']');
                });

                const filteredCount = originalCount - data.tools.length;
                if (filteredCount > 0) {
                    console.log('[Zest Tool Interceptor] Filtered out', filteredCount, 'tools from other projects');
                }
                console.log('[Zest Tool Interceptor] Keeping', data.tools.length, 'tools for project:', currentProjectPath);
            }

            return JSON.stringify(data);

        } catch (error) {
            console.error('[Zest Tool Interceptor] Failed to filter tools:', error);
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
