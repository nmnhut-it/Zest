/**
 * Exploration UI for Agent Mode
 * Provides visual feedback for code exploration progress
 */

(function() {
  // Create and inject styles
  const styles = `
    /* Minimized notification style */
    .exploration-notification {
      position: fixed;
      bottom: 20px;
      right: 20px;
      width: 350px;
      background: #1e1e1e;
      border-radius: 8px;
      box-shadow: 0 4px 20px rgba(0, 0, 0, 0.5);
      color: #e0e0e0;
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
      z-index: 10000;
      display: none;
      transition: all 0.3s ease;
      overflow: hidden;
    }
    
    .exploration-notification-header {
      padding: 12px 16px;
      background: #2d2d2d;
      display: flex;
      justify-content: space-between;
      align-items: center;
      cursor: pointer;
      user-select: none;
    }
    
    .exploration-notification-header:hover {
      background: #353535;
    }
    
    .exploration-notification-title {
      display: flex;
      align-items: center;
      gap: 10px;
      font-size: 14px;
      font-weight: 500;
    }
    
    .exploration-spinner-mini {
      width: 16px;
      height: 16px;
      border: 2px solid #444;
      border-top-color: #0084ff;
      border-radius: 50%;
      animation: spin 1s linear infinite;
    }
    
    @keyframes spin {
      to { transform: rotate(360deg); }
    }
    
    .exploration-expand-icon {
      width: 20px;
      height: 20px;
      display: flex;
      align-items: center;
      justify-content: center;
      color: #999;
      transition: transform 0.2s;
    }
    
    .exploration-notification.expanded .exploration-expand-icon {
      transform: rotate(180deg);
    }
    
    .exploration-notification-body {
      max-height: 0;
      overflow: hidden;
      transition: max-height 0.3s ease;
    }
    
    .exploration-notification.expanded .exploration-notification-body {
      max-height: 400px;
      overflow-y: auto;
    }
    
    .exploration-notification-content {
      padding: 12px 16px;
      font-size: 13px;
    }
    
    .exploration-progress-item {
      padding: 8px 0;
      border-bottom: 1px solid #333;
    }
    
    .exploration-progress-item:last-child {
      border-bottom: none;
    }
    
    .exploration-tool-name {
      font-weight: 500;
      color: #0084ff;
      font-size: 12px;
    }
    
    .exploration-tool-status {
      display: inline-block;
      padding: 2px 6px;
      border-radius: 3px;
      font-size: 10px;
      font-weight: 500;
      margin-left: 8px;
    }
    
    .exploration-tool-status.success {
      background: #0f4c0f;
      color: #4caf50;
    }
    
    .exploration-tool-status.failed {
      background: #4c0f0f;
      color: #f44336;
    }
    
    .exploration-status-text {
      color: #999;
      font-size: 12px;
      margin-top: 4px;
    }
    
    .exploration-complete-indicator {
      display: inline-block;
      width: 8px;
      height: 8px;
      background: #4caf50;
      border-radius: 50%;
      margin-right: 6px;
    }
    
    /* Full overlay style (when expanded to full view) */
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
      opacity: 0;
      transition: opacity 0.3s ease;
    }
    
    .exploration-overlay.visible {
      opacity: 1;
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
      transform: translateY(-20px);
      transition: transform 0.3s ease;
    }
    
    .exploration-overlay.visible .exploration-container {
      transform: translateY(0);
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
    
    .exploration-view-full {
      background: #0084ff;
      color: white;
      border: none;
      padding: 6px 12px;
      border-radius: 4px;
      font-size: 12px;
      cursor: pointer;
      margin-top: 8px;
      transition: background 0.2s;
    }
    
    .exploration-view-full:hover {
      background: #0070e0;
    }
    
    .exploration-error-message {
      background: #4c0f0f;
      color: #f44336;
      padding: 12px 16px;
      border-radius: 6px;
      margin-bottom: 12px;
      font-size: 13px;
    }
  `;
  
  // Inject styles
  const styleSheet = document.createElement('style');
  styleSheet.textContent = styles;
  document.head.appendChild(styleSheet);
  
  // Create notification element
  const notification = document.createElement('div');
  notification.className = 'exploration-notification';
  notification.innerHTML = `
    <div class="exploration-notification-header">
      <div class="exploration-notification-title">
        <span class="exploration-spinner-mini"></span>
        <span>Exploring codebase...</span>
      </div>
      <div class="exploration-expand-icon">▼</div>
    </div>
    <div class="exploration-notification-body">
      <div class="exploration-notification-content">
        <div class="exploration-progress-list"></div>
        <button class="exploration-view-full">View Full Details</button>
      </div>
    </div>
  `;
  
  // Create full overlay element
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
      </div>
    </div>
  `;
  
  document.body.appendChild(notification);
  document.body.appendChild(overlay);
  
  // UI state
  let currentSessionId = null;
  let explorationData = {
    rounds: [],
    toolExecutions: [],
    summary: null
  };
  let notificationExpanded = false;
  
  // UI functions
  function showNotification() {
    notification.style.display = 'block';
  }
  
  function hideNotification() {
    notification.style.display = 'none';
    notificationExpanded = false;
    notification.classList.remove('expanded');
  }
  
  function toggleNotification() {
    notificationExpanded = !notificationExpanded;
    if (notificationExpanded) {
      notification.classList.add('expanded');
    } else {
      notification.classList.remove('expanded');
    }
  }
  
  function showFullView() {
    overlay.style.display = 'block';
    setTimeout(() => overlay.classList.add('visible'), 10);
    updateFullViewUI();
  }
  
  function hideFullView() {
    overlay.classList.remove('visible');
    setTimeout(() => overlay.style.display = 'none', 300);
  }
  
  function updateNotificationProgress() {
    const progressList = notification.querySelector('.exploration-progress-list');
    progressList.innerHTML = '';
    
    // Show latest tools
    const recentTools = explorationData.toolExecutions.slice(-3);
    recentTools.forEach(exec => {
      const statusClass = exec.success ? 'success' : 'failed';
      const statusText = exec.success ? 'Success' : 'Failed';
      
      const item = document.createElement('div');
      item.className = 'exploration-progress-item';
      item.innerHTML = `
        <div class="exploration-tool-name">
          ${exec.toolName}
          <span class="exploration-tool-status ${statusClass}">${statusText}</span>
        </div>
      `;
      progressList.appendChild(item);
    });
    
    // Add status text
    const statusDiv = document.createElement('div');
    statusDiv.className = 'exploration-status-text';
    statusDiv.textContent = `${explorationData.toolExecutions.length} tools executed`;
    progressList.appendChild(statusDiv);
  }
  
  function updateFullViewUI() {
    const roundsContainer = overlay.querySelector('.exploration-rounds');
    roundsContainer.innerHTML = '';
    
    explorationData.rounds.forEach((round, index) => {
      const roundEl = document.createElement('div');
      roundEl.className = 'exploration-round collapsed';
      
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
  notification.querySelector('.exploration-notification-header').addEventListener('click', toggleNotification);
  notification.querySelector('.exploration-view-full').addEventListener('click', showFullView);
  
  overlay.querySelector('.exploration-close').addEventListener('click', hideFullView);
  overlay.addEventListener('click', (e) => {
    if (e.target === overlay) {
      hideFullView();
    }
  });
  
  // Global functions
  window.startExploration = async function(query) {
    console.log('Starting exploration for query:', query);
    
    try {
      const response = await window.intellijBridge.callIDE('startExploration', { query });
      
      if (!response.success) {
        if (response.requiresIndexing) {
          // Show error in notification
          const errorHtml = `
            <div class="exploration-error-message">
              ⚠️ Project not indexed. Please index your project first to use Agent Mode exploration.
            </div>
          `;
          notification.querySelector('.exploration-notification-content').innerHTML = errorHtml;
          notification.querySelector('.exploration-spinner-mini').style.display = 'none';
          notification.querySelector('.exploration-notification-title span:last-child').textContent = 'Error';
          showNotification();
          
          // Auto-hide after 5 seconds
          setTimeout(hideNotification, 5000);
        }
        console.error('Failed to start exploration:', response.error);
        return null;
      }
      
      currentSessionId = response.sessionId;
      explorationData = { rounds: [], toolExecutions: [], summary: null };
      
      // Update notification
      notification.querySelector('.exploration-notification-title span:last-child').textContent = 'Exploring codebase...';
      notification.querySelector('.exploration-spinner-mini').style.display = 'block';
      
      // Update full view
      overlay.querySelector('.exploration-query').textContent = `Query: "${query}"`;
      overlay.querySelector('.exploration-title span:last-child').textContent = 'Exploring Codebase...';
      overlay.querySelector('.exploration-spinner').style.display = 'block';
      overlay.querySelector('.exploration-summary').style.display = 'none';
      
      showNotification();
      
      return response.sessionId;
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
        explorationData.toolExecutions.push(event.data);
        
        updateNotificationProgress();
        if (overlay.style.display === 'block') {
          updateFullViewUI();
        }
        break;
        
      case 'round_complete':
        // Update round data
        const roundIndex = explorationData.rounds.findIndex(r => r.name === event.data.name);
        if (roundIndex >= 0) {
          explorationData.rounds[roundIndex] = event.data;
        } else {
          explorationData.rounds.push(event.data);
        }
        
        if (overlay.style.display === 'block') {
          updateFullViewUI();
        }
        break;
        
      case 'exploration_complete':
        // Update UI for completion
        notification.querySelector('.exploration-spinner-mini').style.display = 'none';
        notification.querySelector('.exploration-notification-title span:last-child').innerHTML = 
          '<span class="exploration-complete-indicator"></span>Exploration Complete';
        
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
          summaryEl.classList.add('collapsed');
          
          // Add click handler for summary collapsing
          const summaryHeader = summaryEl.querySelector('.exploration-summary-header');
          summaryHeader.addEventListener('click', () => {
            summaryEl.classList.toggle('collapsed');
          });
        }
        
        // Store the exploration result for use in the prompt
        window.__exploration_result__ = event.data;
        
        // Auto-hide notification after 3 seconds
        setTimeout(() => {
          hideNotification();
          hideFullView();
        }, 3000);
        break;
    }
  };
  
  // Add function to mark exploration as used (for immediate closing)
  window.markExplorationUsed = function() {
    if (currentSessionId) {
      // Update notification to show it's being used
      notification.querySelector('.exploration-notification-title span:last-child').innerHTML = 
        '<span class="exploration-complete-indicator"></span>Enhancing query...';
      
      // Close immediately
      setTimeout(() => {
        hideNotification();
        hideFullView();
      }, 500);
    }
  };
  
  console.log('Exploration UI initialized');
})();
