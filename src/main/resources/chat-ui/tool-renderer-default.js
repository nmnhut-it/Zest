/**
 * Default and Common Tool Renderers
 * All follow pattern: icon + key info (single line)
 */

window.ToolRenderers.register('default', function(chunk) {
    return window.ToolRenderers.renderDefault(chunk);
});

window.ToolRenderers.register('analyzeClass', function(chunk) {
    try {
        const args = JSON.parse(chunk.toolArgs);
        const className = args.className || args.class_name || 'unknown';

        const container = document.createElement('div');
        container.className = 'tool-display';
        container.innerHTML = `🔍 <code>${escapeHtml(className)}</code>`;

        return container;
    } catch (e) {
        return window.ToolRenderers.renderDefault(chunk);
    }
});

window.ToolRenderers.register('listFiles', function(chunk) {
    try {
        const args = JSON.parse(chunk.toolArgs);
        const path = args.directoryPath || args.path || '.';

        const container = document.createElement('div');
        container.className = 'tool-display';
        container.innerHTML = `📁 <code>${escapeHtml(path)}</code>`;

        return container;
    } catch (e) {
        return window.ToolRenderers.renderDefault(chunk);
    }
});

window.ToolRenderers.register('lookupMethod', function(chunk) {
    try {
        const args = JSON.parse(chunk.toolArgs);
        const methodName = args.methodName || args.method_name || 'unknown';
        const className = args.className || args.class_name || '';
        const fullName = className ? `${className}.${methodName}` : methodName;

        const container = document.createElement('div');
        container.className = 'tool-display';
        container.innerHTML = `🔎 <code>${escapeHtml(fullName)}</code>`;

        return container;
    } catch (e) {
        return window.ToolRenderers.renderDefault(chunk);
    }
});

window.ToolRenderers.register('lookupClass', function(chunk) {
    try {
        const args = JSON.parse(chunk.toolArgs);
        const className = args.className || args.class_name || 'unknown';

        const container = document.createElement('div');
        container.className = 'tool-display';
        container.innerHTML = `🔎 <code>${escapeHtml(className)}</code>`;

        return container;
    } catch (e) {
        return window.ToolRenderers.renderDefault(chunk);
    }
});
