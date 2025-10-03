/**
 * Tool Renderer for searchCode
 * Simple display: icon + pattern + optional file filter
 */
window.ToolRenderers.register('searchCode', function(chunk) {
    try {
        const args = JSON.parse(chunk.toolArgs);
        const pattern = args.pattern || args.query || '';
        const fileFilter = args.filePattern || args.glob || '';

        const container = document.createElement('div');
        container.className = 'tool-display';

        let html = `🔍 <code>${escapeHtml(pattern)}</code>`;
        if (fileFilter) {
            html += ` in <code>${escapeHtml(fileFilter)}</code>`;
        }

        container.innerHTML = html;
        return container;
    } catch (e) {
        return window.ToolRenderers.renderDefault(chunk);
    }
});
