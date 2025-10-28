/**
 * Zest Chat UI - Simplified
 * Handles message rendering and streaming with minimal complexity
 */

console.log('[INIT] chat-functions.js loading...');

// Initialize on DOM ready
document.addEventListener('DOMContentLoaded', function() {
    renderMessages();

    if (typeof hljs !== 'undefined') {
        hljs.highlightAll();
    }
});

/**
 * Render all messages from visualChunks data
 */
function renderMessages() {
    const container = document.getElementById('chat-container');
    container.innerHTML = '';

    visualChunks.forEach(function(chunk, index) {
        // Add separator between messages
        if (index > 0) {
            const separator = document.createElement('div');
            separator.className = 'message-separator';
            container.appendChild(separator);
        }

        container.appendChild(createMessageElement(chunk));
    });

    // Apply syntax highlighting
    if (typeof hljs !== 'undefined') {
        hljs.highlightAll();
    }

    scrollToBottom();
}

/**
 * Create a message DOM element
 */
function createMessageElement(chunk) {
    const messageDiv = document.createElement('div');
    messageDiv.id = chunk.id;
    messageDiv.className = 'chat-message';
    messageDiv.setAttribute('data-type', chunk.type);

    // Header
    const headerDiv = document.createElement('div');
    headerDiv.className = 'message-header';
    headerDiv.textContent = chunk.header + ' - (' + chunk.timestamp + ')';
    messageDiv.appendChild(headerDiv);

    // Content
    const contentDiv = document.createElement('div');
    contentDiv.className = 'message-content';

    if (chunk.content) {
        if (typeof marked !== 'undefined') {
            contentDiv.innerHTML = marked.parse(chunk.content);
        } else {
            contentDiv.textContent = chunk.content;
        }
    }

    // Make code blocks collapsible
    makeCodeBlocksCollapsible(contentDiv);

    // Tool badges
    if (chunk.toolCalls && chunk.toolCalls.length > 0) {
        const badges = createToolBadges(chunk.toolCalls);
        contentDiv.appendChild(badges);
    }

    messageDiv.appendChild(contentDiv);

    return messageDiv;
}

/**
 * Create tool badges container
 */
function createToolBadges(toolCalls) {
    const container = document.createElement('div');
    container.className = 'tool-badges';

    toolCalls.forEach(function(tool) {
        const badge = document.createElement('span');
        badge.className = 'tool-badge';
        badge.setAttribute('data-tool-id', tool.id);

        const hasResult = tool.result !== null && tool.result !== undefined;
        if (hasResult) {
            badge.classList.add('completed');
        }

        const icon = hasResult ? '✓' : '🔧';
        badge.innerHTML = icon + ' ' + escapeHtml(tool.name);

        // Details popup on click
        badge.onclick = function(e) {
            e.stopPropagation();
            showToolDetails(tool);
        };

        container.appendChild(badge);
    });

    return container;
}

/**
 * Show tool details in custom modal
 */
function showToolDetails(tool) {
    let argsText;
    try {
        const argsObj = JSON.parse(tool.args);
        argsText = JSON.stringify(argsObj, null, 2);
    } catch (e) {
        argsText = tool.args;
    }

    const overlay = document.createElement('div');
    overlay.className = 'tool-modal-overlay';
    overlay.onclick = function() { closeToolModal(); };

    const modal = document.createElement('div');
    modal.className = 'tool-modal-content';
    modal.onclick = function(e) { e.stopPropagation(); };

    const header = document.createElement('div');
    header.className = 'tool-modal-header';
    header.innerHTML = '<h3>🔧 ' + escapeHtml(tool.name) + '</h3>' +
                       '<button class="tool-modal-close" onclick="closeToolModal()">✕</button>';

    const body = document.createElement('div');
    body.className = 'tool-modal-body';

    const argsSection = document.createElement('div');
    argsSection.className = 'tool-modal-section';
    argsSection.innerHTML = '<h4>Arguments:</h4>' +
                            '<pre><code class="json">' + escapeHtml(argsText) + '</code></pre>' +
                            '<button class="copy-btn" onclick="copyToolContent(\'' +
                            escapeHtml(argsText).replace(/'/g, "\\'") + '\')">📋 Copy</button>';

    body.appendChild(argsSection);

    if (tool.result) {
        const resultSection = document.createElement('div');
        resultSection.className = 'tool-modal-section';
        resultSection.innerHTML = '<h4>Result:</h4>' +
                                  '<pre><code>' + escapeHtml(tool.result) + '</code></pre>' +
                                  '<button class="copy-btn" onclick="copyToolContent(\'' +
                                  escapeHtml(tool.result).replace(/'/g, "\\'") + '\')">📋 Copy</button>';
        body.appendChild(resultSection);
    }

    modal.appendChild(header);
    modal.appendChild(body);
    overlay.appendChild(modal);
    document.body.appendChild(overlay);

    if (typeof hljs !== 'undefined') {
        hljs.highlightElement(modal.querySelector('code.json'));
    }

    document.addEventListener('keydown', handleModalEscape);
}

/**
 * Close tool modal
 */
function closeToolModal() {
    const overlay = document.querySelector('.tool-modal-overlay');
    if (overlay) {
        overlay.remove();
    }
    document.removeEventListener('keydown', handleModalEscape);
}

/**
 * Handle Escape key for modal
 */
function handleModalEscape(e) {
    if (e.key === 'Escape') {
        closeToolModal();
    }
}

/**
 * Copy tool content to clipboard
 */
function copyToolContent(text) {
    navigator.clipboard.writeText(text).then(function() {
        const btn = event.target;
        const originalText = btn.textContent;
        btn.textContent = '✓ Copied!';
        setTimeout(function() {
            btn.textContent = originalText;
        }, 2000);
    });
}

/**
 * Add a temporary message (used during streaming)
 */
function addTemporaryChunk(type, header, content) {
    console.log('[STREAM] Adding temporary chunk:', type);

    const container = document.getElementById('chat-container');

    // Add separator
    if (container.children.length > 0) {
        const separator = document.createElement('div');
        separator.className = 'message-separator';
        container.appendChild(separator);
    }

    const chunk = {
        id: 'temp-' + Date.now(),
        type: type,
        header: header,
        timestamp: new Date().toLocaleTimeString(),
        content: content
    };

    container.appendChild(createMessageElement(chunk));
    scrollToBottom();
}

/**
 * Update the last message with streaming text
 */
function updateLastMessageStreaming(newContent) {
    console.log('[STREAM] Updating last message');

    const container = document.getElementById('chat-container');
    const messages = container.querySelectorAll('.chat-message');
    const lastMessage = messages[messages.length - 1];

    if (!lastMessage) {
        console.error('[STREAM] No last message found');
        return;
    }

    const contentDiv = lastMessage.querySelector('.message-content');
    if (!contentDiv) {
        console.error('[STREAM] No content div found');
        return;
    }

    // Add streaming indicator to header
    if (!lastMessage.classList.contains('streaming')) {
        lastMessage.classList.add('streaming');
        const headerDiv = lastMessage.querySelector('.message-header');
        const badge = document.createElement('span');
        badge.className = 'streaming-badge';
        badge.textContent = '⏵';
        headerDiv.appendChild(badge);
    }

    // Update content directly (preserve tool badges if they exist)
    const badgesContainer = contentDiv.querySelector('.tool-badges');

    if (typeof marked !== 'undefined') {
        contentDiv.innerHTML = marked.parse(newContent);
    } else {
        contentDiv.textContent = newContent;
    }

    // Make code blocks collapsible
    makeCodeBlocksCollapsible(contentDiv);

    // Re-append badges if they existed
    if (badgesContainer) {
        contentDiv.appendChild(badgesContainer);
    }

    scrollToBottom();
}

/**
 * Add tool badge to current message during streaming
 */
function appendToolBadge(toolName, toolArgs, toolId) {
    console.log('[STREAM] Adding tool badge:', toolName);

    const container = document.getElementById('chat-container');
    const messages = container.querySelectorAll('.chat-message');
    const lastMessage = messages[messages.length - 1];

    if (!lastMessage) {
        console.error('[STREAM] No message for badge');
        return;
    }

    const contentDiv = lastMessage.querySelector('.message-content');

    // Get or create badges container
    let badgesContainer = contentDiv.querySelector('.tool-badges');
    if (!badgesContainer) {
        badgesContainer = document.createElement('div');
        badgesContainer.className = 'tool-badges';
        contentDiv.appendChild(badgesContainer);
    }

    // Create badge
    const badge = document.createElement('span');
    badge.className = 'tool-badge executing';
    badge.setAttribute('data-tool-id', toolId);
    badge.innerHTML = '<span class="tool-spinner">⟳</span> ' + escapeHtml(toolName);

    // Store tool data for later
    badge._toolData = {
        id: toolId,
        name: toolName,
        args: toolArgs,
        result: null
    };

    badge.onclick = function(e) {
        e.stopPropagation();
        showToolDetails(badge._toolData);
    };

    badgesContainer.appendChild(badge);
    scrollToBottom();
}

/**
 * Update tool badge with result
 */
function updateToolBadgeWithResult(toolId, result) {
    console.log('[STREAM] Updating tool badge result:', toolId);

    const badge = document.querySelector('.tool-badge[data-tool-id="' + toolId + '"]');
    if (!badge) {
        console.error('[STREAM] Badge not found:', toolId);
        return;
    }

    badge.classList.remove('executing');
    badge.classList.add('completed');

    const toolName = badge._toolData ? badge._toolData.name : toolId;
    badge.innerHTML = '✓ ' + escapeHtml(toolName);

    // Update stored data
    if (badge._toolData) {
        badge._toolData.result = result;
    }
}

/**
 * Finalize streaming and reload from ChatMemory
 */
function finalizeWithAnimation() {
    console.log('[STREAM] Finalizing...');

    // Remove streaming indicators
    document.querySelectorAll('.streaming-badge').forEach(function(el) {
        el.remove();
    });
    document.querySelectorAll('.streaming').forEach(function(el) {
        el.classList.remove('streaming');
    });

    // Kotlin will reload the page after a short delay
}

/**
 * Scroll to bottom of chat
 */
function scrollToBottom() {
    window.scrollTo({
        top: document.body.scrollHeight,
        behavior: 'smooth'
    });
}

/**
 * Make code blocks collapsible with headers and controls
 */
function makeCodeBlocksCollapsible(container) {
    const COLLAPSE_THRESHOLD = 15; // Lines
    const COLLAPSED_HEIGHT = 200; // pixels (~8 lines)

    const preElements = container.querySelectorAll('pre');

    preElements.forEach(function(pre) {
        // Skip if already wrapped
        if (pre.parentElement.classList.contains('code-block-wrapper')) {
            return;
        }

        const code = pre.querySelector('code');
        if (!code) return;

        // Detect language from highlight.js class
        let language = 'text';
        const classList = code.className.split(' ');
        for (let i = 0; i < classList.length; i++) {
            const cls = classList[i];
            if (cls.startsWith('language-') || cls.startsWith('hljs-')) {
                language = cls.replace('language-', '').replace('hljs-', '');
                break;
            }
        }

        // Count lines
        const codeText = code.textContent || code.innerText;
        const lineCount = codeText.split('\n').length;

        // Create wrapper
        const wrapper = document.createElement('div');
        wrapper.className = 'code-block-wrapper';

        // Create header
        const header = document.createElement('div');
        header.className = 'code-block-header';

        const headerLeft = document.createElement('div');
        headerLeft.className = 'code-block-header-left';
        headerLeft.innerHTML = '<span class="code-lang-icon">📄</span> ' +
                               '<span class="code-lang-name">' + escapeHtml(language) + '</span>' +
                               '<span class="code-line-count">' + lineCount + ' lines</span>';

        const headerRight = document.createElement('div');
        headerRight.className = 'code-block-header-right';

        // Copy button
        const copyBtn = document.createElement('button');
        copyBtn.className = 'code-copy-btn';
        copyBtn.textContent = '📋 Copy';
        copyBtn.onclick = function(e) {
            e.stopPropagation();
            navigator.clipboard.writeText(codeText).then(function() {
                const originalText = copyBtn.textContent;
                copyBtn.textContent = '✓ Copied!';
                setTimeout(function() {
                    copyBtn.textContent = originalText;
                }, 2000);
            });
        };

        // Toggle button
        const toggleBtn = document.createElement('button');
        toggleBtn.className = 'code-block-toggle';

        headerRight.appendChild(copyBtn);
        headerRight.appendChild(toggleBtn);

        header.appendChild(headerLeft);
        header.appendChild(headerRight);

        // Create content wrapper
        const contentWrapper = document.createElement('div');
        contentWrapper.className = 'code-block-content';

        // Move pre into content wrapper
        pre.parentNode.insertBefore(wrapper, pre);
        contentWrapper.appendChild(pre);
        wrapper.appendChild(header);
        wrapper.appendChild(contentWrapper);

        // Auto-collapse if lines > threshold
        if (lineCount > COLLAPSE_THRESHOLD) {
            contentWrapper.classList.add('collapsed');
            toggleBtn.textContent = '▶ Expand';

            // Add fade overlay
            const fade = document.createElement('div');
            fade.className = 'code-block-fade';
            contentWrapper.appendChild(fade);
        } else {
            toggleBtn.textContent = '▼ Collapse';
        }

        // Toggle collapse on header click
        header.onclick = function(e) {
            // Don't toggle if clicking copy button
            if (e.target === copyBtn || e.target.closest('.code-copy-btn')) {
                return;
            }

            const isCollapsed = contentWrapper.classList.contains('collapsed');

            if (isCollapsed) {
                contentWrapper.classList.remove('collapsed');
                toggleBtn.textContent = '▼ Collapse';

                // Remove fade overlay
                const fade = contentWrapper.querySelector('.code-block-fade');
                if (fade) fade.remove();
            } else {
                contentWrapper.classList.add('collapsed');
                toggleBtn.textContent = '▶ Expand';

                // Add fade overlay
                const fade = document.createElement('div');
                fade.className = 'code-block-fade';
                contentWrapper.appendChild(fade);
            }
        };
    });
}

/**
 * Escape HTML
 */
function escapeHtml(text) {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}

/**
 * Clear all messages
 */
function clearMessages() {
    document.getElementById('chat-container').innerHTML = '';
}

/**
 * Exported functions for Kotlin bridge
 */
window.chatFunctions = {
    addTemporaryChunk: addTemporaryChunk,
    updateLastMessageStreaming: updateLastMessageStreaming,
    appendToolBadge: appendToolBadge,
    updateToolBadgeWithResult: updateToolBadgeWithResult,
    finalizeWithAnimation: finalizeWithAnimation,
    clearMessages: clearMessages,

    // Utility to call IDE
    notifyJava: function(action, data) {
        if (window.intellijBridge) {
            window.intellijBridge.callIDE(action, data || '');
        }
    }
};

console.log('[INIT] chat-functions.js loaded');

// Notify IDE that chat is ready
if (window.intellijBridge) {
    window.intellijBridge.callIDE('chat-ready', '');
}
