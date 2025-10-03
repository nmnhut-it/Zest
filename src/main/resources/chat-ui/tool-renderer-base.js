/**
 * Tool Renderer Base - Registry and Utilities
 *
 * This module provides the core infrastructure for rendering tool calls with specialized formatters.
 * Each tool type can register a custom renderer for better visualization.
 *
 * Architecture:
 * - Registry maps tool names to rendering functions
 * - Renderers take a visual chunk and return an HTML element
 * - Fallback to default renderer for unregistered tools
 *
 * Usage:
 *   window.ToolRenderers.register('toolName', function(chunk) { ... });
 *   const element = window.ToolRenderers.render(chunk);
 */

window.ToolRenderers = {
    registry: {},

    /**
     * Register a tool renderer
     * @param {string} toolName - Name of the tool (e.g., "readFile", "searchCode")
     * @param {Function} renderFunc - Function that takes a visual chunk and returns DOM element
     */
    register: function(toolName, renderFunc) {
        this.registry[toolName] = renderFunc;
    },

    /**
     * Render a tool call chunk using the appropriate renderer
     * @param {Object} chunk - Visual chunk with toolName, toolArgs, etc.
     * @returns {HTMLElement} Rendered tool display
     */
    render: function(chunk) {
        const renderer = this.registry[chunk.toolName] || this.registry['default'];
        return renderer ? renderer(chunk) : this.renderDefault(chunk);
    },

    /**
     * Render a tool result chunk
     * @param {Object} chunk - Visual chunk with content (result)
     * @returns {HTMLElement} Rendered result display
     */
    renderResult: function(chunk) {
        const div = document.createElement('div');
        div.className = 'tool-result-content';

        // Check if JSON result
        const trimmed = (chunk.content || '').trimStart();
        if (trimmed.startsWith('{') || trimmed.startsWith('[')) {
            const pre = document.createElement('pre');
            const code = document.createElement('code');
            code.className = 'language-json';
            code.textContent = chunk.content;
            pre.appendChild(code);
            div.appendChild(pre);

            // Syntax highlight if available
            if (typeof hljs !== 'undefined') {
                hljs.highlightElement(code);
            }
        } else {
            // Regular markdown content
            if (typeof marked !== 'undefined') {
                div.innerHTML = marked.parse(chunk.content);
            } else {
                div.textContent = chunk.content;
            }
        }

        return div;
    },

    /**
     * Default renderer: Tool name + truncated args
     */
    renderDefault: function(chunk) {
        const container = document.createElement('div');
        container.className = 'tool-display';

        // Tool name
        const nameDiv = document.createElement('div');
        nameDiv.className = 'tool-simple-name';
        nameDiv.textContent = chunk.toolName;
        container.appendChild(nameDiv);

        // Truncated args
        const argsText = chunk.toolArgs || '{}';
        const truncated = argsText.length > 100 ? argsText.substring(0, 100) + '...' : argsText;

        const argsDiv = document.createElement('div');
        argsDiv.className = 'tool-simple-args';
        argsDiv.textContent = truncated;
        container.appendChild(argsDiv);

        return container;
    }
};

/**
 * Escape HTML to prevent XSS
 * @param {string} text - Text to escape
 * @returns {string} HTML-safe text
 */
function escapeHtml(text) {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}
