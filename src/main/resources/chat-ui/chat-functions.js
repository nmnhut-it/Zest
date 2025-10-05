/**
 * Zest Chat UI JavaScript Functions
 * Handles visual chunk rendering, streaming, and interactive features
 *
 * Architecture: Visual chunks from LangChain4j ChatMessages
 * - visualChunks array contains rendering units (text chunks and tools)
 * - Each AiMessage is split into: text chunk + tool chunks
 * - Tools are rendered using specialized renderers
 */

// Configuration
let showAllChunks = false;
const VISIBLE_CHUNK_COUNT = 10;
const PREVIEW_LENGTH = 100;

// Track collapse state for each chunk
const chunkCollapseState = {};

// Initialize on DOM ready
document.addEventListener('DOMContentLoaded', function() {
    renderMessages();

    // Initialize highlight.js
    if (typeof hljs !== 'undefined') {
        hljs.highlightAll();
        makeCodeBlocksCollapsible();
    }
});

/**
 * Find a visual chunk in the array by ID
 */
function findChunk(chunkId) {
    return visualChunks.find(chunk => chunk.id === chunkId);
}

/**
 * Render all visual chunks
 */
function renderMessages() {
    const container = document.getElementById('chat-container');
    container.innerHTML = '';

    const totalChunks = visualChunks.length;
    const hiddenCount = Math.max(0, totalChunks - VISIBLE_CHUNK_COUNT);

    // Add "Show More" button if there are hidden chunks
    if (!showAllChunks && hiddenCount > 0) {
        const showMoreDiv = document.createElement('div');
        showMoreDiv.className = 'show-more-container';
        showMoreDiv.innerHTML =
            '<button class="show-more-button" onclick="window.chatFunctions.toggleShowAllMessages()">' +
            '▼ Show ' + hiddenCount + ' older item' + (hiddenCount > 1 ? 's' : '') +
            '</button>';
        container.appendChild(showMoreDiv);

        // Add separator after button
        const separator = document.createElement('div');
        separator.className = 'message-separator';
        container.appendChild(separator);
    }

    // Determine which chunks to show
    const startIndex = showAllChunks ? 0 : Math.max(0, totalChunks - VISIBLE_CHUNK_COUNT);

    for (let i = startIndex; i < visualChunks.length; i++) {
        const chunk = visualChunks[i];

        // Add separator if needed (skip for first visible chunk)
        if (i > startIndex) {
            const separator = document.createElement('div');
            separator.className = 'message-separator';
            container.appendChild(separator);
        }

        // Create chunk container
        const chunkDiv = document.createElement('div');
        chunkDiv.id = chunk.id;
        chunkDiv.className = 'chat-message';
        chunkDiv.setAttribute('data-type', chunk.type);

        // Auto-collapse logic based on chunk type
        const isTool = chunk.type === 'tool_call' || chunk.type === 'tool_result';
        const isSystemPrompt = chunk.type === 'system';
        const isRecent = i >= visualChunks.length - 2;

        if (isTool || isSystemPrompt) {
            // ALWAYS collapse tools AND system prompts by default
            if (chunkCollapseState[chunk.id] === undefined) {
                chunkCollapseState[chunk.id] = true;
                chunkDiv.classList.add('collapsed');
            } else if (chunkCollapseState[chunk.id]) {
                chunkDiv.classList.add('collapsed');
            }
        } else if (!isRecent && chunkCollapseState[chunk.id] === undefined) {
            // Auto-collapse older user/AI messages
            chunkCollapseState[chunk.id] = true;
            chunkDiv.classList.add('collapsed');
        } else if (chunkCollapseState[chunk.id]) {
            // Respect manual collapse state
            chunkDiv.classList.add('collapsed');
        }

        // Create header with CORRECT collapse indicator based on actual state
        const headerDiv = document.createElement('div');
        headerDiv.className = 'message-header';
        const isCollapsed = chunkDiv.classList.contains('collapsed');
        const collapseIcon = isCollapsed ? '▶' : '▼';
        headerDiv.innerHTML = '<span class="collapse-indicator">' + collapseIcon + '</span>' +
                             escapeHtml(chunk.header + ' - (' + chunk.timestamp + ')');
        headerDiv.onclick = function() {
            window.chatFunctions.toggleMessageCollapse(chunk.id);
        };
        chunkDiv.appendChild(headerDiv);

        // Create content
        const contentDiv = document.createElement('div');
        contentDiv.className = 'message-content';

        // Render based on chunk type
        if (chunk.type === 'tool_call') {
            // Use tool renderer
            const rendered = window.ToolRenderers.render(chunk);
            contentDiv.appendChild(rendered);
        } else if (chunk.type === 'tool_result') {
            // Format tool result
            const rendered = window.ToolRenderers.renderResult(chunk);
            contentDiv.appendChild(rendered);
        } else if (chunk.content) {
            // Regular content - render as markdown
            if (typeof marked !== 'undefined') {
                contentDiv.innerHTML = marked.parse(chunk.content);
            } else {
                contentDiv.textContent = chunk.content;
            }
        }

        chunkDiv.appendChild(contentDiv);

        // Create preview for collapsed state
        const previewDiv = document.createElement('div');
        previewDiv.className = 'message-preview';
        previewDiv.innerHTML = generatePreview(chunk);
        chunkDiv.appendChild(previewDiv);

        container.appendChild(chunkDiv);
    }

    // Apply syntax highlighting
    if (typeof hljs !== 'undefined') {
        hljs.highlightAll();
        makeCodeBlocksCollapsible();
    }

    // Update collapse all button text
    updateCollapseAllButton();
}

/**
 * Create a chunk DOM element (used for both static and streaming chunks)
 * @param {Object} chunk - Visual chunk data
 * @returns {HTMLElement} Wrapper containing separator and chunk
 */
function createChunkElement(chunk) {
    // Separator
    const separator = document.createElement('div');
    separator.className = 'message-separator';

    // Chunk container
    const chunkDiv = document.createElement('div');
    chunkDiv.id = chunk.id;
    chunkDiv.className = 'chat-message';
    chunkDiv.setAttribute('data-type', chunk.type);

    // Header with collapse indicator
    const headerDiv = document.createElement('div');
    headerDiv.className = 'message-header';
    const collapseIcon = '▼';
    headerDiv.innerHTML = '<span class="collapse-indicator">' + collapseIcon + '</span>' +
                         escapeHtml(chunk.header + ' - (' + chunk.timestamp + ')');
    headerDiv.onclick = function() {
        window.chatFunctions.toggleMessageCollapse(chunk.id);
    };
    chunkDiv.appendChild(headerDiv);

    // Content
    const contentDiv = document.createElement('div');
    contentDiv.className = 'message-content';

    if (chunk.type === 'tool_call') {
        const rendered = window.ToolRenderers.render(chunk);
        contentDiv.appendChild(rendered);
    } else if (chunk.type === 'tool_result') {
        const rendered = window.ToolRenderers.renderResult(chunk);
        contentDiv.appendChild(rendered);
    } else if (chunk.content) {
        if (typeof marked !== 'undefined') {
            contentDiv.innerHTML = marked.parse(chunk.content);
        } else {
            contentDiv.textContent = chunk.content;
        }
    }

    chunkDiv.appendChild(contentDiv);

    // Wrap in container with separator
    const wrapper = document.createElement('div');
    wrapper.appendChild(separator);
    wrapper.appendChild(chunkDiv);

    // Syntax highlight if needed
    if (typeof hljs !== 'undefined') {
        contentDiv.querySelectorAll('pre code').forEach(function(block) {
            hljs.highlightElement(block);
        });
    }

    return wrapper;
}

/**
 * Escape HTML to prevent XSS
 */
function escapeHtml(text) {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}

/**
 * Generate preview text for collapsed chunks
 */
function generatePreview(chunk) {
    if (chunk.type === 'tool_call') {
        // Show spinner if temporary (still executing), otherwise just tool name
        return chunk.id.startsWith('temp-')
            ? '<span class="tool-spinner">⟳</span> Executing...'
            : 'Tool: ' + chunk.toolName;
    }

    if (chunk.type === 'tool_result') {
        return 'Result from ' + chunk.toolName;
    }

    if (!chunk.content) {
        return '(No content)';
    }

    const cleanText = chunk.content
        .replace(/```[\s\S]*?```/g, '[code block]') // Replace code blocks
        .replace(/`[^`]+`/g, '[code]') // Replace inline code
        .replace(/\n+/g, ' ') // Replace newlines with spaces
        .replace(/\s+/g, ' ') // Normalize whitespace
        .trim();

    if (cleanText.length <= PREVIEW_LENGTH) {
        return cleanText;
    }

    return cleanText.substring(0, PREVIEW_LENGTH) + '...';
}

/**
 * Add copy buttons to code blocks
 */
function addCopyButtons() {
    document.querySelectorAll('pre code').forEach(function(block) {
        const pre = block.parentNode;
        if (pre.querySelector('.copy-button')) return;

        const button = document.createElement('button');
        button.className = 'copy-button';
        button.textContent = 'Copy';
        button.onclick = function() {
            navigator.clipboard.writeText(block.textContent).then(function() {
                button.textContent = 'Copied!';
                setTimeout(function() {
                    button.textContent = 'Copy';
                }, 2000);
            });
        };

        pre.style.position = 'relative';
        pre.appendChild(button);
    });
}

/**
 * Update collapse all button text based on actual DOM state
 */
function updateCollapseAllButton() {
    const button = document.getElementById('collapse-all-btn');
    if (!button) return;

    // Check actual DOM state (not chunkCollapseState which has undefined values)
    const hasExpanded = visualChunks.some(function(chunk) {
        const element = document.getElementById(chunk.id);
        return element && !element.classList.contains('collapsed');
    });

    if (hasExpanded) {
        button.textContent = '▲ Collapse All';
    } else {
        button.textContent = '▼ Expand All';
    }
}

/**
 * Make code blocks collapsible
 */
function makeCodeBlocksCollapsible() {
    document.querySelectorAll('pre code').forEach(function(codeElement) {
        const pre = codeElement.parentNode;
        if (pre.querySelector('.collapse-button')) return;

        const codeText = codeElement.textContent || '';
        const lines = codeText.split('\n');
        let lineCount = lines.length;
        if (lines.length > 0 && lines[lines.length - 1].trim() === '') {
            lineCount--;
        }

        // Create placeholder for collapsed state
        const placeholder = document.createElement('div');
        placeholder.className = 'code-placeholder';
        placeholder.textContent = '▶ Show code ~ ' + lineCount + ' line' + (lineCount !== 1 ? 's' : '');
        placeholder.style.display = 'none';

        // Create collapse button
        const collapseButton = document.createElement('button');
        collapseButton.className = 'collapse-button';
        collapseButton.textContent = '▼ Hide';

        pre.style.position = 'relative';
        pre.parentNode.insertBefore(placeholder, pre);
        pre.appendChild(collapseButton);

        // Start collapsed by default
        pre.classList.add('collapsed');
        placeholder.style.display = 'inline-block';
        collapseButton.style.display = 'none';

        // Toggle function
        const toggle = function() {
            if (pre.classList.contains('collapsed')) {
                pre.classList.remove('collapsed');
                placeholder.style.display = 'none';
                collapseButton.style.display = 'block';
            } else {
                pre.classList.add('collapsed');
                placeholder.style.display = 'inline-block';
                collapseButton.style.display = 'none';
            }
        };

        placeholder.onclick = toggle;
        collapseButton.onclick = toggle;
    });
}

/**
 * Enhanced chat functions exposed to Kotlin
 */
window.chatFunctions = {
    toggleShowAllMessages: function() {
        showAllChunks = !showAllChunks;
        renderMessages();
    },

    /**
     * Append a tool call chunk during streaming (temporary)
     */
    appendToolCallChunk: function(toolName, toolArgs, toolId) {
        const container = document.getElementById('chat-container');

        const chunk = {
            id: 'temp-tool-' + Date.now(),
            type: 'tool_call',
            header: '🔧 ' + toolName,
            timestamp: new Date().toLocaleTimeString(),
            toolName: toolName,
            toolArgs: toolArgs,
            toolId: toolId
        };

        const chunkDiv = createChunkElement(chunk);
        chunkDiv.classList.add('streaming-chunk', 'temporary');

        // Auto-collapse with spinner in preview
        chunkDiv.classList.add('collapsed');
        chunkCollapseState[chunk.id] = true;

        const previewDiv = chunkDiv.querySelector('.message-preview');
        if (previewDiv) {
            previewDiv.innerHTML = '<span class="tool-spinner">⟳</span> Executing...';
        }

        container.appendChild(chunkDiv);

        // Fade in animation
        setTimeout(function() {
            chunkDiv.classList.add('visible');
        }, 10);

        // Auto-scroll
        window.scrollTo({ top: document.body.scrollHeight, behavior: 'smooth' });
    },

    /**
     * Append a tool result chunk during streaming (temporary, collapsed)
     */
    appendToolResultChunk: function(toolName, result) {
        const container = document.getElementById('chat-container');

        const chunk = {
            id: 'temp-result-' + Date.now(),
            type: 'tool_result',
            header: '📄 Result: ' + toolName,
            timestamp: new Date().toLocaleTimeString(),
            toolName: toolName,
            content: result
        };

        const chunkDiv = createChunkElement(chunk);
        chunkDiv.classList.add('streaming-chunk', 'temporary');

        // Auto-collapse tool results
        chunkDiv.classList.add('collapsed');
        chunkCollapseState[chunk.id] = true;

        container.appendChild(chunkDiv);

        // Fade in animation
        setTimeout(function() {
            chunkDiv.classList.add('visible');
        }, 10);

        // Auto-scroll
        window.scrollTo({ top: document.body.scrollHeight, behavior: 'smooth' });
    },

    /**
     * Finalize with smooth animation (fade out streaming chunks)
     */
    finalizeWithAnimation: function() {
        const container = document.getElementById('chat-container');

        // Remove streaming badges and states
        document.querySelectorAll('.streaming-badge').forEach(function(badge) {
            badge.remove();
        });
        document.querySelectorAll('.streaming').forEach(function(msg) {
            msg.classList.remove('streaming');
        });

        // Show finalizing indicator
        const indicator = document.createElement('div');
        indicator.className = 'finalizing-indicator';
        indicator.innerHTML = '<span class="spinner">⟳</span> Finalizing...';
        container.appendChild(indicator);

        // Fade out all content
        container.classList.add('finalizing');

        // The actual reload happens in Kotlin after 300ms
    },

    /**
     * Add a temporary chunk (DOM only, not in data model)
     * Will be replaced when reloading from ChatMemory
     */
    addTemporaryChunk: function(type, header, content) {
        const container = document.getElementById('chat-container');

        const chunk = {
            id: 'temp-' + Date.now() + '-' + Math.random(),
            type: type,
            header: header,
            timestamp: new Date().toLocaleTimeString(),
            content: content
        };

        const chunkDiv = createChunkElement(chunk);
        chunkDiv.classList.add('streaming-chunk', 'temporary');
        container.appendChild(chunkDiv);

        // Fade in
        setTimeout(function() {
            chunkDiv.classList.add('visible');
        }, 10);

        // Auto-scroll
        window.scrollTo({ top: document.body.scrollHeight, behavior: 'smooth' });
    },

    /**
     * Update the last message during streaming (blazingly fast char-by-char)
     */
    updateLastMessageStreaming: function(newChunk) {
        const container = document.getElementById('chat-container');
        const messages = container.querySelectorAll('.chat-message');
        const lastMessage = messages[messages.length - 1];

        if (!lastMessage) return;

        const contentDiv = lastMessage.querySelector('.message-content');
        if (!contentDiv) return;

        // Initialize streaming state
        if (!lastMessage._streamingState) {
            lastMessage._streamingState = {
                displayedContent: '',
                charQueue: [],
                isProcessing: false
            };
        }

        // Add streaming indicator (once)
        if (!lastMessage._hasStreamingBadge) {
            const headerDiv = lastMessage.querySelector('.message-header');
            if (headerDiv) {
                const badge = document.createElement('span');
                badge.className = 'streaming-badge';
                badge.textContent = '⏵';
                headerDiv.appendChild(badge);
                lastMessage._hasStreamingBadge = true;
            }
            lastMessage.classList.add('streaming');
        }

        const state = lastMessage._streamingState;

        // Add new characters to queue
        for (let i = 0; i < newChunk.length; i++) {
            state.charQueue.push(newChunk[i]);
        }

        // Start processing if not already
        if (!state.isProcessing) {
            this.processCharQueue(lastMessage);
        }

        // Auto-scroll
        window.scrollTo({ top: document.body.scrollHeight, behavior: 'smooth' });
    },

    /**
     * Process character queue for blazingly fast streaming animation
     */
    processCharQueue: function(messageElement) {
        if (!messageElement || !messageElement._streamingState) return;

        const state = messageElement._streamingState;
        const contentDiv = messageElement.querySelector('.message-content');

        if (state.charQueue.length === 0) {
            state.isProcessing = false;
            return;
        }

        state.isProcessing = true;

        // Process multiple characters per frame for blazing speed
        const charsPerFrame = Math.min(3, state.charQueue.length);
        let batch = '';
        for (let i = 0; i < charsPerFrame; i++) {
            batch += state.charQueue.shift();
        }

        state.displayedContent += batch;

        // Render with cursor
        contentDiv.innerHTML =
            '<pre style="white-space: pre-wrap; font-family: inherit; margin: 0; background: none; border: none; padding: 0;">' +
            escapeHtml(state.displayedContent) +
            '<span class="streaming-cursor">▎</span></pre>';

        // Blazingly fast: 10-20ms per frame (3 chars each = ~3-6ms per char)
        const delay = state.charQueue.length > 100 ? 10 : 20;

        const self = this;
        setTimeout(function() {
            self.processCharQueue(messageElement);
        }, delay);
    },


    escapeHtml: function(text) {
        const div = document.createElement('div');
        div.textContent = text;
        return div.innerHTML;
    },

    clearMessages: function() {
        document.getElementById('chat-container').innerHTML = '';
    },

    notifyJava: function(action, data) {
        if (window.intellijBridge) {
            window.intellijBridge.callIDE(action, data || '');
        }
    },

    toggleMessageCollapse: function(chunkId) {
        const chunkElement = document.getElementById(chunkId);
        if (!chunkElement) return;

        const isCollapsed = chunkElement.classList.contains('collapsed');
        chunkCollapseState[chunkId] = !isCollapsed;

        if (isCollapsed) {
            chunkElement.classList.remove('collapsed');
        } else {
            chunkElement.classList.add('collapsed');
        }

        // Update collapse indicator
        const indicator = chunkElement.querySelector('.collapse-indicator');
        if (indicator) {
            indicator.textContent = chunkCollapseState[chunkId] ? '▶' : '▼';
        }

        // Update button state if needed
        updateCollapseAllButton();
    },

    collapseAllMessages: function() {
        visualChunks.forEach(function(chunk) {
            chunkCollapseState[chunk.id] = true;
            const chunkElement = document.getElementById(chunk.id);
            if (chunkElement) {
                chunkElement.classList.add('collapsed');
                const indicator = chunkElement.querySelector('.collapse-indicator');
                if (indicator) indicator.textContent = '▶';
            }
        });
        updateCollapseAllButton();
    },

    expandAllMessages: function() {
        visualChunks.forEach(function(chunk) {
            chunkCollapseState[chunk.id] = false;
            const chunkElement = document.getElementById(chunk.id);
            if (chunkElement) {
                chunkElement.classList.remove('collapsed');
                const indicator = chunkElement.querySelector('.collapse-indicator');
                if (indicator) indicator.textContent = '▼';
            }
        });
        updateCollapseAllButton();
    },

    toggleCollapseAll: function() {
        // Check if ANY chunk is currently expanded (actual DOM state)
        const hasExpanded = visualChunks.some(function(chunk) {
            const element = document.getElementById(chunk.id);
            return element && !element.classList.contains('collapsed');
        });

        if (hasExpanded) {
            // At least one expanded → collapse all
            this.collapseAllMessages();
        } else {
            // All collapsed → expand all
            this.expandAllMessages();
        }
    }


};

// Notify Java that chat is ready
if (window.intellijBridge) {
    window.intellijBridge.callIDE('chat-ready', '');
}
