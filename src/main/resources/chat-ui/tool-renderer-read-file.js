/**
 * Tool Renderer for readFile
 * Simple display: icon + file path
 */
window.ToolRenderers.register('readFile', function(chunk) {
    try {
        const args = JSON.parse(chunk.toolArgs);
        const filePath = args.filePath || args.file_path || 'unknown';

        const container = document.createElement('div');
        container.className = 'tool-display';
        container.innerHTML = `📄 <code>${escapeHtml(filePath)}</code>`;

        return container;
    } catch (e) {
        return window.ToolRenderers.renderDefault(chunk);
    }
});
