/**
 * Exploration UI for Agent Mode
 * Provides visual feedback for code exploration progress
 */

(function() {
  // Create and inject styles
  const styles = `
    .exploration-overlay {
      position: fixed;
      top: 0;
      left: 0;
      right: 0;
      bottom: 0;
      background: rgba(0, 0, 0, 0.8);
      z-index: 10000;
      display: none;
      overflow: auto;
    }
    
    .exploration-container {
      position: relative;
      max-width: 900px;
      margin: 50px auto;
      background: #1e1e1e;
      border-radius: 12px;
      box-shadow: 0 10px 40px rgba(0, 0, 0, 0.5);
      color: #e0e0e0;
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
    }
    
    .exploration-header {
      padding: 20px 30px;
      background: #2d2d2d;
      border-radius: 12px 12px 0 0;
      border-bottom: 1px solid #3e3e3e;
      display: flex;
      justify-content: space-between;
      align-items: center;
    }
    
    .exploration-title {
      font-size: 18px;
      font-weight: 600;
      color: #fff;
      display: flex;
      align-items: center;
      gap: 10px;
    }
    
    .exploration-spinner {
      width: 20px;
      height: 20px;
      border: 3px solid #444;
      border-top-color: #0084ff;
      border-radius: 50%;
      animation: spin 1s linear infinite;
    }
    
    @keyframes spin {
      to { transform: rotate(360deg); }
    }
    
    .exploration-close {
      background: none;
      border: none;
      color: #999;
      font-size: 24px;
      cursor: pointer;
      padding: 0;
      width: 30px;
      height: 30px;
      display: flex;
      align-items: center;
      justify-content: center;
      border-radius: 4px;
      transition: all 0.2s;
    }
    
    .exploration-close:hover {
      background: #3e3e3e;
      color: #fff;
    }
    
    .exploration-body {
      padding: 20px 30px;
      max-height: 600px;
      overflow-y: auto;
    }
    
    .exploration-query {
      background: #2d2d2d;
      padding: 15px 20px;
      border-radius: 8px;
      margin-bottom: 20px;
      font-size: 14px;
      line-height: 1.5;
      border: 1px solid #3e3e3e;
    }
    
    .exploration-round {
      margin-bottom: 20px;
      border: 1px solid #3e3e3e;
      border-radius: 8px;
      overflow: hidden;
    }
    
    .exploration-round-header {
      padding: 12px 20px;
      background: #2d2d2d;
      font-weight: 500;
      cursor: pointer;
      display: flex;
      justify-content: space-between;
      align-items: center;
      transition: background 0.2s;
      user-select: none;
    }
    
    .exploration-round-header:hover {
      background: #353535;
    }
    
    .exploration-round-header::before {
      content: '▼';
      margin-right: 8px;
      font-size: 12px;
      transition: transform 0.2s;
    }
    
    .exploration-round.collapsed .exploration-round-header::before {
      transform: rotate(-90deg);
    }
    
    .exploration-round-content {
      padding: 15px 20px;
      background: #252525;
      display: block;
      max-height: 400px;
      overflow-y: auto;
    }
    
    .exploration-round.collapsed .exploration-round-content {
      display: none;
    }
    
    .tool-execution {
      margin-bottom: 15px;
      padding: 12px 15px;
      background: #1e1e1e;
      border-radius: 6px;
      border: 1px solid #3e3e3e;
    }
    
    .tool-execution:last-child {
      margin-bottom: 0;
    }
    
    .tool-execution-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      cursor: pointer;
      user-select: none;
    }
    
    .tool-name {
      font-weight: 600;
      color: #0084ff;
      font-size: 14px;
    }
    
    .tool-params {
      font-size: 12px;
      color: #999;
      margin-top: 8px;
      font-family: "Consolas", "Monaco", monospace;
    }
    
    .tool-result {
      font-size: 13px;
      line-height: 1.5;
      white-space: pre-wrap;
      max-height: 200px;
      overflow-y: auto;
      padding: 10px;
      background: #1a1a1a;
      border-radius: 4px;
      border: 1px solid #333;
      margin-top: 8px;
    }
    
    .tool-execution.collapsed .tool-params,
    .tool-execution.collapsed .tool-result {
      display: none;
    }
    
    .tool-status {
      display: inline-block;
      padding: 2px 8px;
      border-radius: 4px;
      font-size: 11px;
      font-weight: 500;
    }
    
    .tool-status.success {
      background: #0f4c0f;
      color: #4caf50;
    }
    
    .tool-status.failed {
      background: #4c0f0f;
      color: #f44336;
    }
    
    .exploration-summary {
      margin-top: 20px;
      padding: 0;
      background: transparent;
      border-radius: 8px;
      border: 1px solid #3e3e3e;
      overflow: hidden;
    }
    
    .exploration-summary-header {
      padding: 12px 20px;
      background: #2d2d2d;
      font-weight: 600;
      cursor: pointer;
      display: flex;
      justify-content: space-between;
      align-items: center;
      transition: background 0.2s;
      user-select: none;
      color: #fff;
      font-size: 16px;
    }
    
    .exploration-summary-header:hover {
      background: #353535;
    }
    
    .exploration-summary-header::before {
      content: '▼';
      margin-right: 8px;
      font-size: 12px;
      transition: transform 0.2s;
    }
    
    .exploration-summary.collapsed .exploration-summary-header::before {
      transform: rotate(-90deg);
    }
    
    .summary-content {
      font-size: 14px;
      line-height: 1.6;
      white-space: pre-wrap;
      padding: 20px;
      background: #252525;
      display: block;
      max-height: 400px;
      overflow-y: auto;
    }
    
    .exploration-summary.collapsed .summary-content {
      display: none;
    }
    
    .exploration-complete-badge {
      display: inline-block;
      padding: 4px 12px;
      background: #0f4c0f;
      color: #4caf50;
      border-radius: 4px;
      font-size: 12px;
      font-weight: 500;
    }
    
    .exploration-status {
      text-align: center;
      padding: 10px;
      color: #999;
      font-size: 13px;
      font-style: italic;
    }
  `;
  
  // Inject styles
  const styleSheet = document.createElement('style');
  styleSheet.textContent = styles;
  document.head.appendChild(styleSheet);
  
  // Create exploration UI container
  const overlay = document.createElement('div');
  overlay.className = 'exploration-overlay';
  overlay.innerHTML = `
    <div class="exploration-container">
      <div class="exploration-header">
        <div class="exploration-title">
          <span class="exploration-spinner"></span>
          <span>Exploring Codebase...</span>
        </div>
        <button class="exploration-close">×</button>
      </div>
      <div class="exploration-body">
        <div class="exploration-query"></div>
        <div class="exploration-rounds"></div>
        <div class="exploration-summary" style="display: none;"></div>
        <div class="exploration-status">Enhancing your query with code context...</div>
      </div>
    </div>
  `;
  
  document.body.appendChild(overlay);
  
  // UI state
  let currentSessionId = null;
  let explorationData = {
    rounds: [],
    toolExecutions: [],
    summary: null
  };
  
  // UI functions
  function showExplorationUI() {
    overlay.style.display = 'block';
  }
  
  function hideExplorationUI() {
    overlay.style.display = 'none';
    currentSessionId = null;
    explorationData = { rounds: [], toolExecutions: [], summary: null };
  }
  
  function updateExplorationUI() {
    const roundsContainer = overlay.querySelector('.exploration-rounds');
    roundsContainer.innerHTML = '';
    
    explorationData.rounds.forEach((round, index) => {
      const roundEl = document.createElement('div');
      roundEl.className = 'exploration-round collapsed'; // Start collapsed
      
      const toolExecutionsHtml = round.toolExecutions.map(exec => {
        const statusClass = exec.success ? 'success' : 'failed';
        const statusText = exec.success ? 'Success' : 'Failed';
        
        return `
          <div class="tool-execution collapsed">
            <div class="tool-execution-header">
              <div class="tool-name">${exec.toolName}</div>
              <span class="tool-status ${statusClass}">${statusText}</span>
            </div>
            <div class="tool-params">Parameters: ${JSON.stringify(exec.parameters)}</div>
            <div class="tool-result">${escapeHtml(exec.result)}</div>
          </div>
        `;
      }).join('');
      
      roundEl.innerHTML = `
        <div class="exploration-round-header">
          <span>${round.name}</span>
          <span>${round.toolExecutions.length} tool calls</span>
        </div>
        <div class="exploration-round-content">
          ${toolExecutionsHtml}
        </div>
      `;
      
      // Add click handler for round collapsing
      const roundHeader = roundEl.querySelector('.exploration-round-header');
      roundHeader.addEventListener('click', () => {
        roundEl.classList.toggle('collapsed');
      });
      
      // Add click handlers for tool execution collapsing
      const toolExecutions = roundEl.querySelectorAll('.tool-execution');
      toolExecutions.forEach(toolEl => {
        const header = toolEl.querySelector('.tool-execution-header');
        header.addEventListener('click', () => {
          toolEl.classList.toggle('collapsed');
        });
      });
      
      roundsContainer.appendChild(roundEl);
    });
  }
  
  function escapeHtml(text) {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
  }
  
  // Event handlers
  overlay.querySelector('.exploration-close').addEventListener('click', hideExplorationUI);
  
  overlay.addEventListener('click', (e) => {
    if (e.target === overlay) {
      hideExplorationUI();
    }
  });
  
  // Global functions
  window.startExploration = async function(query) {
    console.log('Starting exploration for query:', query);
    
    try {
      const response = await window.intellijBridge.callIDE('startExploration', { query });
      
      if (response.success) {
        currentSessionId = response.sessionId;
        explorationData = { rounds: [], toolExecutions: [], summary: null };
        
        // Update UI
        overlay.querySelector('.exploration-query').textContent = `Query: "${query}"`;
        overlay.querySelector('.exploration-title span:last-child').textContent = 'Exploring Codebase...';
        overlay.querySelector('.exploration-spinner').style.display = 'block';
        overlay.querySelector('.exploration-summary').style.display = 'none';
        overlay.querySelector('.exploration-status').style.display = 'block';
        overlay.querySelector('.exploration-status').textContent = 'Enhancing your query with code context...';
        
        showExplorationUI();
        
        return response.sessionId;
      } else {
        console.error('Failed to start exploration:', response.error);
        return null;
      }
    } catch (error) {
      console.error('Error starting exploration:', error);
      return null;
    }
  };
  
  window.handleExplorationProgress = function(event) {
    if (!currentSessionId || event.sessionId !== currentSessionId) {
      return;
    }
    
    console.log('Exploration progress:', event);
    
    switch (event.eventType) {
      case 'tool_execution':
        // Add tool execution to current round
        if (explorationData.rounds.length === 0) {
          explorationData.rounds.push({
            name: 'Round 1',
            toolExecutions: []
          });
        }
        
        const currentRound = explorationData.rounds[explorationData.rounds.length - 1];
        currentRound.toolExecutions.push(event.data);
        updateExplorationUI();
        break;
        
      case 'round_complete':
        // Update round data
        const roundIndex = explorationData.rounds.findIndex(r => r.name === event.data.name);
        if (roundIndex >= 0) {
          explorationData.rounds[roundIndex] = event.data;
        } else {
          explorationData.rounds.push(event.data);
        }
        updateExplorationUI();
        break;
        
      case 'exploration_complete':
        // Show summary
        overlay.querySelector('.exploration-spinner').style.display = 'none';
        overlay.querySelector('.exploration-title span:last-child').innerHTML = 
          'Exploration Complete <span class="exploration-complete-badge">Done</span>';
        
        if (event.data.summary) {
          const summaryEl = overlay.querySelector('.exploration-summary');
          summaryEl.innerHTML = `
            <div class="exploration-summary-header">
              <span>Summary</span>
            </div>
            <div class="summary-content">${escapeHtml(event.data.summary)}</div>
          `;
          summaryEl.style.display = 'block';
          summaryEl.classList.add('collapsed'); // Start collapsed
          
          // Add click handler for summary collapsing
          const summaryHeader = summaryEl.querySelector('.exploration-summary-header');
          summaryHeader.addEventListener('click', () => {
            summaryEl.classList.toggle('collapsed');
          });
        }
        
        // Update status
        overlay.querySelector('.exploration-status').textContent = 'Query enhanced! Closing in 3 seconds...';
        
        // Store the exploration result for use in the prompt
        window.__exploration_result__ = event.data;
        
        // Auto-close after 3 seconds
        setTimeout(() => {
          hideExplorationUI();
        }, 3000);
        break;
    }
  };
  
  // Add function to mark exploration as used (for immediate closing)
  window.markExplorationUsed = function() {
    if (currentSessionId) {
      overlay.querySelector('.exploration-status').textContent = 'Query enhanced successfully!';
      // Close immediately
      setTimeout(() => {
        hideExplorationUI();
      }, 500);
    }
  };
  
  console.log('Exploration UI initialized');
})();
