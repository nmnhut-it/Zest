/**
 * Tool Renderer for Code Modification
 * Simple display: icon + file name only
 */
window.ToolRenderers.register('replaceCodeInFile', function(chunk) {
    try {
        const args = JSON.parse(chunk.toolArgs);
        const filePath = args.filePath || args.file_path || 'unknown';
        const fileName = filePath.split(/[/\\]/).pop();

        const container = document.createElement('div');
        container.className = 'tool-display';
        container.innerHTML = `✏️ Editing <code>${escapeHtml(fileName)}</code>`;

        return container;
    } catch (e) {
        return window.ToolRenderers.renderDefault(chunk);
    }
});

window.ToolRenderers.register('createNewFile', function(chunk) {
    try {
        const args = JSON.parse(chunk.toolArgs);
        const filePath = args.filePath || args.file_path || 'unknown';
        const fileName = filePath.split(/[/\\]/).pop();

        const container = document.createElement('div');
        container.className = 'tool-display';
        container.innerHTML = `📝 Creating <code>${escapeHtml(fileName)}</code>`;

        return container;
    } catch (e) {
        return window.ToolRenderers.renderDefault(chunk);
    }
});

window.ToolRenderers.register('replaceCodeByLines', function(chunk) {
    try {
        const args = JSON.parse(chunk.toolArgs);
        const filePath = args.filePath || args.file_path || 'unknown';
        const fileName = filePath.split(/[/\\]/).pop();
        const startLine = args.startLine || args.start_line || '?';
        const endLine = args.endLine || args.end_line || '?';

        const container = document.createElement('div');
        container.className = 'tool-display';
        container.innerHTML = `✏️ Editing <code>${escapeHtml(fileName)}</code> L${startLine}-${endLine}`;

        return container;
    } catch (e) {
        return window.ToolRenderers.renderDefault(chunk);
    }
});
