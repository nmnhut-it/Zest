/**
 * Context Debugger for Agent Mode
 * Shows current exploration context and conversation ID
 */

(function() {
  // Create and inject styles
  const styles = `
    .context-debugger {
      position: fixed;
      top: 20px;
      left: 20px;
      width: 400px;
      max-height: 600px;
      background: #1e1e1e;
      border-radius: 8px;
      box-shadow: 0 4px 20px rgba(0, 0, 0, 0.5);
      color: #e0e0e0;
      font-family: "Consolas", "Monaco", monospace;
      font-size: 12px;
      z-index: 10001;
      display: none;
      overflow: hidden;
      transition: all 0.3s ease;
    }
    
    .context-debugger.active {
      display: block;
    }
    
    .context-debugger-header {
      padding: 10px 15px;
      background: #2d2d2d;
      border-bottom: 1px solid #3e3e3e;
      display: flex;
      justify-content: space-between;
      align-items: center;
      cursor: move;
      user-select: none;
    }
    
    .context-debugger-title {
      font-weight: 600;
      color: #0084ff;
    }
    
    .context-debugger-controls {
      display: flex;
      gap: 10px;
    }
    
    .context-debugger-minimize {
      width: 20px;
      height: 20px;
      background: #ffbd2e;
      border-radius: 50%;
      cursor: pointer;
      display: flex;
      align-items: center;
      justify-content: center;
      font-size: 14px;
      color: #1e1e1e;
    }
    
    .context-debugger-close {
      width: 20px;
      height: 20px;
      background: #ff5f56;
      border-radius: 50%;
      cursor: pointer;
      display: flex;
      align-items: center;
      justify-content: center;
      font-size: 14px;
      color: #1e1e1e;
    }
    
    .context-debugger-body {
      padding: 15px;
      max-height: 500px;
      overflow-y: auto;
    }
    
    .context-debugger.minimized .context-debugger-body {
      display: none;
    }
    
    .context-debugger.minimized {
      width: 200px;
    }
    
    .context-info-section {
      margin-bottom: 15px;
      padding: 10px;
      background: #252525;
      border-radius: 4px;
      border: 1px solid #3e3e3e;
    }
    
    .context-info-label {
      color: #999;
      font-weight: 600;
      margin-bottom: 5px;
    }
    
    .context-info-value {
      color: #4caf50;
      word-break: break-all;
    }
    
    .context-info-value.empty {
      color: #f44336;
      font-style: italic;
    }
    
    .context-content {
      background: #1a1a1a;
      padding: 10px;
      border-radius: 4px;
      white-space: pre-wrap;
      max-height: 200px;
      overflow-y: auto;
      margin-top: 5px;
      font-size: 11px;
    }
    
    .context-timestamp {
      color: #666;
      font-size: 10px;
      margin-top: 5px;
    }
    
    .context-mode-badge {
      display: inline-block;
      padding: 2px 8px;
      background: #0084ff;
      color: white;
      border-radius: 4px;
      font-size: 10px;
      font-weight: 600;
      margin-left: 10px;
    }
    
    .context-mode-badge.inactive {
      background: #666;
    }
  `;

  // Inject styles
  const styleSheet = document.createElement('style');
  styleSheet.textContent = styles;
  document.head.appendChild(styleSheet);

  // Create debugger element
  const debuggerElement = document.createElement('div');
  debuggerElement.className = 'context-debugger';
  debuggerElement.innerHTML = `
    <div class="context-debugger-header">
      <div class="context-debugger-title">
        Context Debugger
        <span class="context-mode-badge">Agent Mode</span>
      </div>
      <div class="context-debugger-controls">
        <div class="context-debugger-minimize">−</div>
        <div class="context-debugger-close">×</div>
      </div>
    </div>
    <div class="context-debugger-body">
      <div class="context-info-section">
        <div class="context-info-label">Conversation ID:</div>
        <div class="context-info-value conversation-id empty">None</div>
      </div>
      
      <div class="context-info-section">
        <div class="context-info-label">Current Mode:</div>
        <div class="context-info-value current-mode">Unknown</div>
      </div>
      
      <div class="context-info-section">
        <div class="context-info-label">Exploration Status:</div>
        <div class="context-info-value exploration-status">Not started</div>
        <div class="context-timestamp exploration-timestamp"></div>
      </div>
      
      <div class="context-info-section">
        <div class="context-info-label">Context Source:</div>
        <div class="context-info-value context-source empty">None</div>
      </div>
      
      <div class="context-info-section">
        <div class="context-info-label">Exploration Context:</div>
        <div class="context-info-value context-length">0 characters</div>
        <div class="context-content">No context loaded</div>
      </div>
    </div>
  `;

  document.body.appendChild(debuggerElement);

  // Make debugger draggable
  let isDragging = false;
  let currentX;
  let currentY;
  let initialX;
  let initialY;
  let xOffset = 0;
  let yOffset = 0;

  const header = debuggerElement.querySelector('.context-debugger-header');

  function dragStart(e) {
    if (e.target.closest('.context-debugger-controls')) return;
    
    initialX = e.clientX - xOffset;
    initialY = e.clientY - yOffset;

    if (e.target === header || header.contains(e.target)) {
      isDragging = true;
    }
  }

  function dragEnd(e) {
    initialX = currentX;
    initialY = currentY;
    isDragging = false;
  }

  function drag(e) {
    if (isDragging) {
      e.preventDefault();
      currentX = e.clientX - initialX;
      currentY = e.clientY - initialY;

      xOffset = currentX;
      yOffset = currentY;

      debuggerElement.style.transform = `translate(${currentX}px, ${currentY}px)`;
    }
  }

  header.addEventListener('mousedown', dragStart);
  document.addEventListener('mousemove', drag);
  document.addEventListener('mouseup', dragEnd);

  // Controls
  debuggerElement.querySelector('.context-debugger-minimize').addEventListener('click', () => {
    debuggerElement.classList.toggle('minimized');
  });

  debuggerElement.querySelector('.context-debugger-close').addEventListener('click', () => {
    debuggerElement.classList.remove('active');
  });

  // Update functions
  function updateDebugger(data) {
    if (!data) return;

    if (data.conversationId !== undefined) {
      const convIdElement = debuggerElement.querySelector('.conversation-id');
      convIdElement.textContent = data.conversationId || 'None';
      convIdElement.classList.toggle('empty', !data.conversationId);
    }

    if (data.mode !== undefined) {
      debuggerElement.querySelector('.current-mode').textContent = data.mode || 'Unknown';
      const badge = debuggerElement.querySelector('.context-mode-badge');
      badge.textContent = data.mode || 'Unknown';
      badge.classList.toggle('inactive', data.mode !== 'Agent Mode');
    }

    if (data.explorationStatus !== undefined) {
      debuggerElement.querySelector('.exploration-status').textContent = data.explorationStatus;
      if (data.timestamp) {
        debuggerElement.querySelector('.exploration-timestamp').textContent = 
          new Date(data.timestamp).toLocaleTimeString();
      }
    }

    if (data.contextSource !== undefined) {
      const sourceElement = debuggerElement.querySelector('.context-source');
      sourceElement.textContent = data.contextSource || 'None';
      sourceElement.classList.toggle('empty', !data.contextSource);
    }

    if (data.context !== undefined) {
      const length = data.context ? data.context.length : 0;
      debuggerElement.querySelector('.context-length').textContent = `${length} characters`;
      
      const contentElement = debuggerElement.querySelector('.context-content');
      if (data.context) {
        // Show first 500 characters of context
        const preview = data.context.substring(0, 500);
        contentElement.textContent = preview + (data.context.length > 500 ? '\n...' : '');
      } else {
        contentElement.textContent = 'No context loaded';
      }
    }
  }

  // Global functions
  window.contextDebugger = {
    show: function() {
      debuggerElement.classList.add('active');
    },
    
    hide: function() {
      debuggerElement.classList.remove('active');
    },
    
    update: updateDebugger,
    
    toggle: function() {
      debuggerElement.classList.toggle('active');
    }
  };

  // Keyboard shortcut to toggle debugger (Ctrl+Shift+D)
  document.addEventListener('keydown', (e) => {
    if (e.ctrlKey && e.shiftKey && e.key === 'D') {
      window.contextDebugger.toggle();
    }
  });

  // Auto-show in Agent Mode
  setInterval(() => {
    if (window.__zest_mode__ === 'Agent Mode') {
      if (!debuggerElement.classList.contains('active')) {
        window.contextDebugger.show();
      }
      window.contextDebugger.update({ mode: window.__zest_mode__ });
    } else {
      window.contextDebugger.hide();
    }
  }, 1000);

  console.log('Context Debugger initialized. Press Ctrl+Shift+D to toggle.');
})();
