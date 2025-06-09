/**
 * Exploration UI for Agent Mode
 * Provides visual feedback for code exploration progress with enhanced UX
 */

(function() {
  // Create and inject styles
  const styles = `
    /* Minimized notification style */
    .exploration-notification {
      position: fixed;
      bottom: 20px;
      right: 20px;
      width: 380px;
      background: linear-gradient(135deg, #1e1e1e 0%, #252525 100%);
      border-radius: 12px;
      box-shadow: 0 8px 32px rgba(0, 0, 0, 0.4), 0 0 0 1px rgba(255, 255, 255, 0.1);
      color: #e0e0e0;
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
      z-index: 10000;
      display: none;
      transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
      overflow: hidden;
      backdrop-filter: blur(10px);
    }
    
    .exploration-notification.shake {
      animation: shake 0.5s ease-in-out;
    }
    
    @keyframes shake {
      0%, 100% { transform: translateX(0); }
      25% { transform: translateX(-5px); }
      75% { transform: translateX(5px); }
    }
    
    .exploration-notification-header {
      padding: 16px 20px;
      background: linear-gradient(135deg, #2d2d2d 0%, #333333 100%);
      display: flex;
      justify-content: space-between;
      align-items: center;
      cursor: pointer;
      user-select: none;
      min-height: 56px;
      position: relative;
      overflow: hidden;
    }
    
    .exploration-notification-header::after {
      content: '';
      position: absolute;
      bottom: 0;
      left: 0;
      right: 0;
      height: 2px;
      background: linear-gradient(90deg, transparent 0%, #0084ff 50%, transparent 100%);
      transform: translateX(-100%);
      animation: scanline 2s linear infinite;
    }
    
    @keyframes scanline {
      to { transform: translateX(100%); }
    }
    
    .exploration-notification-header:hover {
      background: linear-gradient(135deg, #353535 0%, #3a3a3a 100%);
    }
    
    .exploration-notification-title {
      display: flex;
      align-items: center;
      gap: 12px;
      font-size: 14px;
      font-weight: 500;
      flex: 1;
      min-height: 24px;
    }
    
    .exploration-status-container {
      flex: 1;
      display: flex;
      flex-direction: column;
      gap: 4px;
    }
    
    .exploration-status-main {
      display: flex;
      align-items: center;
      gap: 8px;
    }
    
    .exploration-status-detail {
      font-size: 13px;
      line-height: 1.4;
      color: #ccc;
      font-weight: normal;
      word-wrap: break-word;
      white-space: normal;
    }
    
    .exploration-status-sub {
      font-size: 11px;
      color: #888;
      display: flex;
      align-items: center;
      gap: 12px;
    }
    
    .exploration-tool-count {
      display: inline-flex;
      align-items: center;
      gap: 4px;
      background: rgba(0, 132, 255, 0.1);
      padding: 2px 8px;
      border-radius: 12px;
      font-size: 10px;
      color: #0084ff;
    }
    
    .exploration-elapsed-time {
      display: inline-flex;
      align-items: center;
      gap: 4px;
      font-size: 10px;
      color: #888;
    }
    
    .exploration-progress-bar {
      position: absolute;
      bottom: 0;
      left: 0;
      right: 0;
      height: 3px;
      background: rgba(0, 132, 255, 0.1);
      overflow: hidden;
    }
    
    .exploration-progress-fill {
      height: 100%;
      background: linear-gradient(90deg, #0084ff 0%, #00d4ff 100%);
      transition: width 0.3s ease;
      box-shadow: 0 0 10px rgba(0, 132, 255, 0.5);
    }
    
    .exploration-spinner-mini {
      width: 18px;
      height: 18px;
      border: 2px solid rgba(0, 132, 255, 0.2);
      border-top-color: #0084ff;
      border-radius: 50%;
      animation: spin 1s linear infinite;
      flex-shrink: 0;
    }
    
    @keyframes spin {
      to { transform: rotate(360deg); }
    }
    
    .exploration-header-actions {
      display: flex;
      align-items: center;
      gap: 8px;
    }
    
    .exploration-minimize-btn,
    .exploration-cancel-btn {
      width: 24px;
      height: 24px;
      display: flex;
      align-items: center;
      justify-content: center;
      color: #999;
      background: rgba(255, 255, 255, 0.05);
      border: 1px solid rgba(255, 255, 255, 0.1);
      border-radius: 6px;
      cursor: pointer;
      transition: all 0.2s;
      font-size: 12px;
    }
    
    .exploration-minimize-btn:hover,
    .exploration-cancel-btn:hover {
      background: rgba(255, 255, 255, 0.1);
      color: #fff;
      transform: translateY(-1px);
    }
    
    .exploration-cancel-btn {
      color: #ff5555;
      border-color: rgba(255, 85, 85, 0.3);
    }
    
    .exploration-cancel-btn:hover {
      background: rgba(255, 85, 85, 0.2);
      border-color: rgba(255, 85, 85, 0.5);
    }
    
    .exploration-expand-icon {
      width: 24px;
      height: 24px;
      display: flex;
      align-items: center;
      justify-content: center;
      color: #999;
      transition: transform 0.2s;
      cursor: pointer;
      background: rgba(255, 255, 255, 0.05);
      border: 1px solid rgba(255, 255, 255, 0.1);
      border-radius: 6px;
    }
    
    .exploration-expand-icon:hover {
      background: rgba(255, 255, 255, 0.1);
      color: #fff;
    }
    
    .exploration-notification.expanded .exploration-expand-icon {
      transform: rotate(180deg);
    }
    
    .exploration-notification-body {
      max-height: 0;
      overflow: hidden;
      transition: max-height 0.3s cubic-bezier(0.4, 0, 0.2, 1);
    }
    
    .exploration-notification.expanded .exploration-notification-body {
      max-height: 420px;
      overflow-y: auto;
    }
    
    .exploration-notification-content {
      padding: 16px 20px;
      font-size: 13px;
      background: rgba(0, 0, 0, 0.2);
    }
    
    .exploration-progress-item {
      padding: 12px 0;
      border-bottom: 1px solid rgba(255, 255, 255, 0.05);
      animation: slideIn 0.3s ease-out;
    }
    
    @keyframes slideIn {
      from {
        opacity: 0;
        transform: translateX(-10px);
      }
      to {
        opacity: 1;
        transform: translateX(0);
      }
    }
    
    .exploration-progress-item:last-child {
      border-bottom: none;
    }
    
    .exploration-tool-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      margin-bottom: 4px;
    }
    
    .exploration-tool-name {
      font-weight: 500;
      color: #0084ff;
      font-size: 13px;
      display: flex;
      align-items: center;
      gap: 6px;
    }
    
    .exploration-tool-icon {
      font-size: 14px;
    }
    
    .exploration-tool-status {
      display: inline-block;
      padding: 3px 8px;
      border-radius: 4px;
      font-size: 10px;
      font-weight: 600;
      text-transform: uppercase;
      letter-spacing: 0.5px;
    }
    
    .exploration-tool-status.success {
      background: linear-gradient(135deg, #0f4c0f 0%, #1a5a1a 100%);
      color: #4caf50;
      box-shadow: 0 0 10px rgba(76, 175, 80, 0.3);
    }
    
    .exploration-tool-status.failed {
      background: linear-gradient(135deg, #4c0f0f 0%, #5a1a1a 100%);
      color: #f44336;
      box-shadow: 0 0 10px rgba(244, 67, 54, 0.3);
    }
    
    .exploration-tool-status.running {
      background: linear-gradient(135deg, #0f3c4c 0%, #1a4a5a 100%);
      color: #00d4ff;
      box-shadow: 0 0 10px rgba(0, 212, 255, 0.3);
      animation: pulse 1.5s ease-in-out infinite;
    }
    
    @keyframes pulse {
      0%, 100% { opacity: 1; }
      50% { opacity: 0.7; }
    }
    
    .exploration-status-text {
      color: #999;
      font-size: 12px;
      margin-top: 8px;
      padding: 8px 12px;
      background: rgba(255, 255, 255, 0.03);
      border-radius: 6px;
      border-left: 3px solid #0084ff;
    }
    
    .exploration-reasoning-mini {
      color: #aaa;
      font-size: 11px;
      margin-top: 4px;
      font-style: italic;
      opacity: 0.9;
      line-height: 1.4;
      padding-left: 20px;
    }
    
    .exploration-complete-indicator {
      display: inline-block;
      width: 10px;
      height: 10px;
      background: #4caf50;
      border-radius: 50%;
      margin-right: 8px;
      box-shadow: 0 0 0 3px rgba(76, 175, 80, 0.2);
      animation: completeGlow 2s ease-in-out infinite;
    }
    
    @keyframes completeGlow {
      0%, 100% { box-shadow: 0 0 0 3px rgba(76, 175, 80, 0.2); }
      50% { box-shadow: 0 0 0 6px rgba(76, 175, 80, 0.1); }
    }
    
    /* Status text styling */
    .exploration-status-label {
      font-weight: 600;
      background: linear-gradient(90deg, #0084ff 0%, #00d4ff 100%);
      -webkit-background-clip: text;
      -webkit-text-fill-color: transparent;
      background-clip: text;
    }

    /* Full overlay style (when expanded to full view) */
    .exploration-overlay {
      position: fixed;
      top: 0;
      left: 0;
      right: 0;
      bottom: 0;
      background: rgba(0, 0, 0, 0.9);
      backdrop-filter: blur(10px);
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
      max-width: 1000px;
      margin: 40px auto;
      background: linear-gradient(135deg, #1e1e1e 0%, #252525 100%);
      border-radius: 16px;
      box-shadow: 0 20px 60px rgba(0, 0, 0, 0.5), 0 0 0 1px rgba(255, 255, 255, 0.1);
      color: #e0e0e0;
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
      transform: translateY(-20px) scale(0.95);
      transition: transform 0.3s cubic-bezier(0.4, 0, 0.2, 1);
    }

    .exploration-overlay.visible .exploration-container {
      transform: translateY(0) scale(1);
    }

    .exploration-header {
      padding: 24px 30px;
      background: linear-gradient(135deg, #2d2d2d 0%, #333333 100%);
      border-radius: 16px 16px 0 0;
      border-bottom: 1px solid rgba(255, 255, 255, 0.1);
      display: flex;
      justify-content: space-between;
      align-items: center;
    }

    .exploration-title {
      font-size: 20px;
      font-weight: 600;
      color: #fff;
      display: flex;
      align-items: center;
      gap: 12px;
    }

    .exploration-spinner {
      width: 24px;
      height: 24px;
      border: 3px solid rgba(0, 132, 255, 0.2);
      border-top-color: #0084ff;
      border-radius: 50%;
      animation: spin 1s linear infinite;
    }

    .exploration-close {
      background: rgba(255, 255, 255, 0.05);
      border: 1px solid rgba(255, 255, 255, 0.1);
      color: #999;
      font-size: 20px;
      cursor: pointer;
      padding: 0;
      width: 36px;
      height: 36px;
      display: flex;
      align-items: center;
      justify-content: center;
      border-radius: 8px;
      transition: all 0.2s;
    }

    .exploration-close:hover {
      background: rgba(255, 255, 255, 0.1);
      color: #fff;
      transform: scale(1.1);
    }

    .exploration-body {
      padding: 24px 30px;
      max-height: 70vh;
      overflow-y: auto;
    }

    .exploration-body::-webkit-scrollbar {
      width: 8px;
    }

    .exploration-body::-webkit-scrollbar-track {
      background: rgba(0, 0, 0, 0.2);
      border-radius: 4px;
    }

    .exploration-body::-webkit-scrollbar-thumb {
      background: rgba(255, 255, 255, 0.1);
      border-radius: 4px;
    }

    .exploration-body::-webkit-scrollbar-thumb:hover {
      background: rgba(255, 255, 255, 0.2);
    }

    .exploration-query {
      background: linear-gradient(135deg, #2d2d2d 0%, #333333 100%);
      padding: 16px 20px;
      border-radius: 10px;
      margin-bottom: 24px;
      font-size: 14px;
      line-height: 1.6;
      border: 1px solid rgba(255, 255, 255, 0.1);
      position: relative;
      overflow: hidden;
    }

    .exploration-query::before {
      content: '"';
      position: absolute;
      top: -10px;
      left: 10px;
      font-size: 48px;
      color: rgba(0, 132, 255, 0.1);
      font-family: Georgia, serif;
    }

    .exploration-round {
      margin-bottom: 20px;
      border: 1px solid rgba(255, 255, 255, 0.1);
      border-radius: 10px;
      overflow: hidden;
      background: rgba(0, 0, 0, 0.2);
      transition: all 0.2s;
    }

    .exploration-round:hover {
      border-color: rgba(0, 132, 255, 0.3);
      box-shadow: 0 0 20px rgba(0, 132, 255, 0.1);
    }

    .exploration-round-header {
      padding: 14px 20px;
      background: linear-gradient(135deg, #2d2d2d 0%, #333333 100%);
      font-weight: 500;
      cursor: pointer;
      display: flex;
      justify-content: space-between;
      align-items: center;
      transition: background 0.2s;
      user-select: none;
    }

    .exploration-round-header:hover {
      background: linear-gradient(135deg, #353535 0%, #3a3a3a 100%);
    }

    .exploration-round-header::before {
      content: '▼';
      margin-right: 10px;
      font-size: 12px;
      transition: transform 0.2s;
      color: #0084ff;
    }

    .exploration-round.collapsed .exploration-round-header::before {
      transform: rotate(-90deg);
    }

    .exploration-round-count {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      background: rgba(0, 132, 255, 0.1);
      padding: 4px 12px;
      border-radius: 16px;
      font-size: 12px;
      color: #0084ff;
    }

    .exploration-round-content {
      padding: 20px;
      background: rgba(0, 0, 0, 0.1);
      display: block;
      max-height: 500px;
      overflow-y: auto;
    }

    .exploration-round.collapsed .exploration-round-content {
      display: none;
    }

    .tool-execution {
      margin-bottom: 16px;
      padding: 14px 16px;
      background: linear-gradient(135deg, #1e1e1e 0%, #252525 100%);
      border-radius: 8px;
      border: 1px solid rgba(255, 255, 255, 0.05);
      transition: all 0.2s;
    }

    .tool-execution:hover {
      border-color: rgba(0, 132, 255, 0.2);
      transform: translateX(4px);
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
      display: flex;
      align-items: center;
      gap: 8px;
    }

    .tool-params {
      font-size: 12px;
      color: #999;
      margin-top: 10px;
      font-family: "Consolas", "Monaco", monospace;
      background: rgba(0, 0, 0, 0.3);
      padding: 8px 12px;
      border-radius: 6px;
      border: 1px solid rgba(255, 255, 255, 0.05);
    }

    .tool-reasoning {
      font-size: 13px;
      color: #0084ff;
      margin-top: 10px;
      padding: 10px 14px;
      background: linear-gradient(135deg, rgba(0, 132, 255, 0.1) 0%, rgba(0, 132, 255, 0.05) 100%);
      border-radius: 6px;
      font-style: italic;
      border-left: 3px solid #0084ff;
    }

    .tool-deep-reasoning {
      font-size: 13px;
      color: #4caf50;
      margin-top: 10px;
      padding: 10px 14px;
      background: linear-gradient(135deg, rgba(76, 175, 80, 0.1) 0%, rgba(76, 175, 80, 0.05) 100%);
      border-radius: 6px;
      font-style: italic;
      border-left: 3px solid #4caf50;
    }

    .tool-result {
      font-size: 13px;
      line-height: 1.6;
      white-space: pre-wrap;
      max-height: 250px;
      overflow-y: auto;
      padding: 12px;
      background: rgba(0, 0, 0, 0.4);
      border-radius: 6px;
      border: 1px solid rgba(255, 255, 255, 0.05);
      margin-top: 10px;
    }

    .tool-execution.collapsed .tool-params,
    .tool-execution.collapsed .tool-result,
    .tool-execution.collapsed .tool-reasoning,
    .tool-execution.collapsed .tool-deep-reasoning {
      display: none;
    }

    .tool-status {
      display: inline-block;
      padding: 4px 10px;
      border-radius: 6px;
      font-size: 11px;
      font-weight: 600;
      text-transform: uppercase;
      letter-spacing: 0.5px;
    }

    .exploration-summary {
      margin-top: 24px;
      padding: 0;
      background: transparent;
      border-radius: 10px;
      border: 1px solid rgba(255, 255, 255, 0.1);
      overflow: hidden;
      background: rgba(0, 0, 0, 0.2);
    }

    .exploration-summary-header {
      padding: 16px 24px;
      background: linear-gradient(135deg, #2d2d2d 0%, #333333 100%);
      font-weight: 600;
      cursor: pointer;
      display: flex;
      justify-content: space-between;
      align-items: center;
      transition: background 0.2s;
      user-select: none;
      color: #fff;
      font-size: 18px;
    }

    .exploration-summary-header:hover {
      background: linear-gradient(135deg, #353535 0%, #3a3a3a 100%);
    }

    .exploration-summary-header::before {
      content: '▼';
      margin-right: 10px;
      font-size: 14px;
      transition: transform 0.2s;
      color: #4caf50;
    }

    .exploration-summary.collapsed .exploration-summary-header::before {
      transform: rotate(-90deg);
    }

    .summary-content {
      font-size: 14px;
      line-height: 1.8;
      white-space: pre-wrap;
      padding: 24px;
      background: rgba(0, 0, 0, 0.2);
      display: block;
      max-height: 500px;
      overflow-y: auto;
    }

    .exploration-summary.collapsed .summary-content {
      display: none;
    }

    .exploration-complete-badge {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      padding: 6px 14px;
      background: linear-gradient(135deg, #0f4c0f 0%, #1a5a1a 100%);
      color: #4caf50;
      border-radius: 20px;
      font-size: 12px;
      font-weight: 600;
      box-shadow: 0 0 20px rgba(76, 175, 80, 0.3);
      animation: completePulse 2s ease-in-out infinite;
    }

    @keyframes completePulse {
      0%, 100% { transform: scale(1); }
      50% { transform: scale(1.05); }
    }

    .exploration-view-full {
      background: linear-gradient(135deg, #0084ff 0%, #00a8ff 100%);
      color: white;
      border: none;
      padding: 8px 16px;
      border-radius: 6px;
      font-size: 13px;
      font-weight: 500;
      cursor: pointer;
      margin-top: 12px;
      transition: all 0.2s;
      box-shadow: 0 4px 12px rgba(0, 132, 255, 0.3);
    }

    .exploration-view-full:hover {
      background: linear-gradient(135deg, #0070e0 0%, #0094e0 100%);
      transform: translateY(-2px);
      box-shadow: 0 6px 20px rgba(0, 132, 255, 0.4);
    }

    .exploration-error-message {
      background: linear-gradient(135deg, #4c0f0f 0%, #5a1a1a 100%);
      color: #f44336;
      padding: 14px 18px;
      border-radius: 8px;
      margin-bottom: 16px;
      font-size: 13px;
      border: 1px solid rgba(244, 67, 54, 0.3);
      box-shadow: 0 0 20px rgba(244, 67, 54, 0.2);
    }

    .exploration-indexing-message {
      padding: 16px 0;
    }

    .exploration-indexing-spinner {
      display: inline-block;
      width: 16px;
      height: 16px;
      border: 2px solid rgba(0, 132, 255, 0.2);
      border-top-color: #0084ff;
      border-radius: 50%;
      animation: spin 1s linear infinite;
      margin-right: 8px;
      vertical-align: middle;
    }
    
    .exploration-action-buttons {
      display: flex;
      gap: 8px;
      margin-top: 16px;
    }
    
    .exploration-action-btn {
      flex: 1;
      padding: 8px 16px;
      border-radius: 6px;
      font-size: 13px;
      font-weight: 500;
      cursor: pointer;
      transition: all 0.2s;
      border: 1px solid rgba(255, 255, 255, 0.1);
      background: rgba(255, 255, 255, 0.05);
      color: #e0e0e0;
    }
    
    .exploration-action-btn:hover {
      background: rgba(255, 255, 255, 0.1);
      transform: translateY(-1px);
    }
    
    .exploration-action-btn.primary {
      background: linear-gradient(135deg, #0084ff 0%, #00a8ff 100%);
      border: none;
      color: white;
    }
    
    .exploration-action-btn.primary:hover {
      background: linear-gradient(135deg, #0070e0 0%, #0094e0 100%);
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
        <div class="exploration-status-container">
          <div class="exploration-status-main">
            <span class="exploration-status-label">Zest</span>
            <span class="exploration-status-detail">Initializing...</span>
          </div>
          <div class="exploration-status-sub" style="display: none;">
            <span class="exploration-tool-count">
              <span class="exploration-tool-icon">🔧</span>
              <span class="tool-count-value">0</span> tools
            </span>
            <span class="exploration-elapsed-time">
              <span class="exploration-tool-icon">⏱️</span>
              <span class="elapsed-time-value">0:00</span>
            </span>
          </div>
        </div>
      </div>
      <div class="exploration-header-actions">
        <div class="exploration-cancel-btn" title="Cancel exploration" style="display: none;">✕</div>
        <div class="exploration-minimize-btn" title="Minimize">–</div>
        <div class="exploration-expand-icon" title="Toggle details">▼</div>
      </div>
      <div class="exploration-progress-bar">
        <div class="exploration-progress-fill" style="width: 0%"></div>
      </div>
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
        <button class="exploration-close" title="Close">×</button>
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
  let pendingQuery = null; // Store query while indexing
  let currentStatus = null; // Track current status
  let statusUpdateInterval = null; // For periodic status updates
  let lastActivityTime = null; // Track last activity
  let explorationStartTime = null; // Track exploration start time
  let elapsedTimeInterval = null; // For elapsed time updates
  let isMinimized = false; // Track minimized state

  // Helper function to format elapsed time
  function formatElapsedTime(milliseconds) {
    const seconds = Math.floor(milliseconds / 1000);
    const minutes = Math.floor(seconds / 60);
    const remainingSeconds = seconds % 60;
    return `${minutes}:${remainingSeconds.toString().padStart(2, '0')}`;
  }

  // Helper function to start elapsed time updates
  function startElapsedTimeUpdates() {
    if (elapsedTimeInterval) {
      clearInterval(elapsedTimeInterval);
    }
    
    explorationStartTime = Date.now();
    
    elapsedTimeInterval = setInterval(() => {
      if (!currentSessionId) {
        clearInterval(elapsedTimeInterval);
        return;
      }
      
      const elapsed = Date.now() - explorationStartTime;
      const elapsedTimeElement = notification.querySelector('.elapsed-time-value');
      if (elapsedTimeElement) {
        elapsedTimeElement.textContent = formatElapsedTime(elapsed);
      }
    }, 1000);
  }

  // Helper function to stop elapsed time updates
  function stopElapsedTimeUpdates() {
    if (elapsedTimeInterval) {
      clearInterval(elapsedTimeInterval);
      elapsedTimeInterval = null;
    }
  }

  // Helper function to update progress bar
  function updateProgressBar(percentage) {
    const progressFill = notification.querySelector('.exploration-progress-fill');
    if (progressFill) {
      progressFill.style.width = `${Math.min(100, percentage)}%`;
    }
  }

  // Helper function to update tool count
  function updateToolCount(count) {
    const toolCountElement = notification.querySelector('.tool-count-value');
    if (toolCountElement) {
      toolCountElement.textContent = count;
    }
    
    // Update progress based on estimated tool count (assuming ~20 tools average)
    const estimatedProgress = (count / 20) * 100;
    updateProgressBar(estimatedProgress);
  }

  // Helper function to update status text
  function updateStatusText(statusDetail, showSpinner = true) {
    const spinner = notification.querySelector('.exploration-spinner-mini');
    const statusDetailElement = notification.querySelector('.exploration-status-detail');
    const statusSub = notification.querySelector('.exploration-status-sub');
    const cancelBtn = notification.querySelector('.exploration-cancel-btn');

    if (spinner) {
      spinner.style.display = showSpinner ? 'block' : 'none';
    }

    if (statusDetailElement) {
      statusDetailElement.textContent = statusDetail;
    }
    
    if (statusSub) {
      statusSub.style.display = showSpinner ? 'flex' : 'none';
    }
    
    if (cancelBtn) {
      cancelBtn.style.display = showSpinner ? 'block' : 'none';
    }
  }

  // Helper function to minimize notification
  function minimizeNotification() {
    isMinimized = true;
    notification.style.transform = 'translateY(calc(100% - 56px))';
    notification.classList.add('minimized');
  }

  // Helper function to restore notification
  function restoreNotification() {
    isMinimized = false;
    notification.style.transform = 'translateY(0)';
    notification.classList.remove('minimized');
  }

  // Helper function to start periodic status updates
  function startStatusUpdates() {
    // Clear any existing interval
    if (statusUpdateInterval) {
      clearInterval(statusUpdateInterval);
    }

    let dots = 0;
    statusUpdateInterval = setInterval(() => {
      if (!currentSessionId) {
        clearInterval(statusUpdateInterval);
        return;
      }

      // Only update if we haven't had activity recently
      const timeSinceLastActivity = lastActivityTime ? Date.now() - lastActivityTime : 10000;
      if (timeSinceLastActivity > 3000) {
        // Get the last tool's reasoning for context
        const lastTool = explorationData.toolExecutions[explorationData.toolExecutions.length - 1];
        
        if (lastTool) {
          const reasoning = lastTool.reasoning || (lastTool.parameters && lastTool.parameters.reasoning) || '';
          if (reasoning) {
            // Show the reasoning as the status while processing
            const dotCount = (dots % 3) + 1;
            const dotString = '.'.repeat(dotCount);
            const truncatedReasoning = reasoning.substring(0, 100) + (reasoning.length > 100 ? '...' : '');
            updateStatusText(`${truncatedReasoning}${dotString}`, true);
            dots++;
            return;
          }
        }
        
        // Fallback to generic messages if no reasoning
        const messages = [
          'Processing results',
          'Analyzing code structure',
          'Understanding implementation',
          'Connecting findings',
          'Searching for patterns',
          'Examining relationships'
        ];
        
        const messageIndex = Math.floor(dots / 3) % messages.length;
        const dotCount = (dots % 3) + 1;
        const dotString = '.'.repeat(dotCount);
        
        updateStatusText(`${messages[messageIndex]}${dotString}`, true);
        dots++;
      }
    }, 500);
  }

  // Helper function to stop status updates
  function stopStatusUpdates() {
    if (statusUpdateInterval) {
      clearInterval(statusUpdateInterval);
      statusUpdateInterval = null;
    }
  }

  // Helper function to get user-friendly tool display names
  function getToolDisplayName(toolName) {
    const toolDisplayNames = {
      'FindClassTool': '🔍 Finding Classes',
      'FindMethodTool': '🔍 Finding Methods',
      'GetClassInfoTool': '📄 Getting Class Info',
      'GetMethodCodeTool': '📝 Reading Method Code',
      'SearchInFilesTool': '🔎 Searching Files',
      'GetFileContentTool': '📖 Reading File',
      'GetProjectStructureTool': '🏗️ Analyzing Structure',
      'GetClassHierarchyTool': '🌳 Exploring Hierarchy',
      'FindUsagesTool': '🔗 Finding Usages',
      'GetDependenciesTool': '📦 Checking Dependencies',
      'SearchSymbolTool': '🔤 Searching Symbols',
      'GetRecentChangesTool': '📅 Checking Changes',
      'search_code': '🔎 Searching Code',
      'find_by_name': '🔍 Finding by Name',
      'get_class_info': '📄 Getting Class Info',
      'read_file': '📖 Reading File'
    };
    
    return toolDisplayNames[toolName] || `🛠️ ${toolName}`;
  }

  // Helper function to play completion sound
  function playCompletionSound() {
    // Sound removed per user request
    // No-op function kept to avoid breaking existing calls
  }

  // UI functions
  function showNotification() {
    notification.style.display = 'block';
    // Add animation
    setTimeout(() => {
      notification.style.transform = 'translateX(0)';
      notification.style.opacity = '1';
    }, 10);
  }

  function hideNotification() {
    notification.style.transform = 'translateX(100px)';
    notification.style.opacity = '0';
    setTimeout(() => {
      notification.style.display = 'none';
      notificationExpanded = false;
      notification.classList.remove('expanded');
      stopStatusUpdates();
      stopElapsedTimeUpdates();
    }, 300);
  }

  function toggleNotification() {
    notificationExpanded = !notificationExpanded;
    if (notificationExpanded) {
      notification.classList.add('expanded');
      restoreNotification();
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

  function ensureNotificationStructure() {
    const content = notification.querySelector('.exploration-notification-content');
    if (!content) return;

    // Check if progress list exists
    if (!content.querySelector('.exploration-progress-list')) {
      // Recreate the standard structure
      content.innerHTML = `
        <div class="exploration-progress-list"></div>
        <button class="exploration-view-full">View Full Details</button>
      `;

      // Re-attach event listener
      const viewFullBtn = content.querySelector('.exploration-view-full');
      if (viewFullBtn) {
        viewFullBtn.addEventListener('click', showFullView);
      }
    }
  }

  function showIndexingMessage(message) {
    updateStatusText('Building code index...', true);

    const content = notification.querySelector('.exploration-notification-content');
    if (!content) return;

    content.innerHTML = `
      <div class="exploration-indexing-message">
        <div class="exploration-progress-item">
          <div class="exploration-status-text">
            <span class="exploration-indexing-spinner"></span>
            ${message || 'Building comprehensive code index for exploration...'}
          </div>
          <div class="exploration-status-text" style="margin-top: 8px; color: #0084ff;">
            This is a one-time process that enables intelligent code exploration.
          </div>
          <div class="exploration-status-text" style="margin-top: 8px; color: #888;">
            Time required depends on project size. Large projects may take several minutes.
          </div>
        </div>
      </div>
    `;
  }

  function updateNotificationProgress() {
    // Ensure structure exists
    ensureNotificationStructure();

    const progressList = notification.querySelector('.exploration-progress-list');
    if (!progressList) {
      console.warn('Progress list element not found after ensuring structure');
      return;
    }

    progressList.innerHTML = '';

    // Show latest tools
    const recentTools = explorationData.toolExecutions.slice(-4);
    recentTools.forEach((exec, index) => {
      const statusClass = exec.success ? 'success' : 'failed';
      const statusText = exec.success ? 'Done' : 'Failed';
      const isLatest = index === recentTools.length - 1;

      const item = document.createElement('div');
      item.className = 'exploration-progress-item';
      
      // Extract reasoning if available
      const reasoning = exec.reasoning || (exec.parameters && exec.parameters.reasoning) || '';
      const truncatedReasoning = reasoning ? reasoning.substring(0, 60) + (reasoning.length > 60 ? '...' : '') : '';
      
      const toolDisplayName = getToolDisplayName(exec.toolName);
      
      item.innerHTML = `
        <div class="exploration-tool-header">
          <div class="exploration-tool-name">
            <span class="exploration-tool-icon">${toolDisplayName.split(' ')[0]}</span>
            <span>${toolDisplayName.substring(toolDisplayName.indexOf(' ') + 1)}</span>
          </div>
          <span class="exploration-tool-status ${isLatest && !exec.result ? 'running' : statusClass}">
            ${isLatest && !exec.result ? 'Running' : statusText}
          </span>
        </div>
        ${truncatedReasoning ? `<div class="exploration-reasoning-mini">${truncatedReasoning}</div>` : ''}
      `;
      progressList.appendChild(item);
    });

    // Update tool count
    updateToolCount(explorationData.toolExecutions.length);
  }

  function updateFullViewUI() {
    const roundsContainer = overlay.querySelector('.exploration-rounds');
    if (!roundsContainer) return;

    roundsContainer.innerHTML = '';

    explorationData.rounds.forEach((round, index) => {
      const roundEl = document.createElement('div');
      roundEl.className = 'exploration-round collapsed';

      const toolExecutionsHtml = round.toolExecutions.map(exec => {
        const statusClass = exec.success ? 'success' : 'failed';
        const statusText = exec.success ? 'Success' : 'Failed';
        
        // Extract reasoning from tool execution or parameters
        const reasoning = exec.reasoning || (exec.parameters && exec.parameters.reasoning) || '';
        const deepReasoning = exec.deepReasoning || (exec.parameters && exec.parameters.deepreasoning) || '';
        
        const toolDisplayName = getToolDisplayName(exec.toolName);

        return `
          <div class="tool-execution collapsed">
            <div class="tool-execution-header">
              <div class="tool-name">
                <span>${toolDisplayName.split(' ')[0]}</span>
                <span>${exec.toolName}</span>
              </div>
              <span class="tool-status ${statusClass}">${statusText}</span>
            </div>
            ${reasoning ? `<div class="tool-reasoning">💭 ${escapeHtml(reasoning)}</div>` : ''}
            ${deepReasoning ? `<div class="tool-deep-reasoning">🧠 ${escapeHtml(deepReasoning)}</div>` : ''}
            <div class="tool-params">Parameters: ${JSON.stringify(exec.parameters, null, 2)}</div>
            <div class="tool-result">${escapeHtml(exec.result || 'Processing...')}</div>
          </div>
        `;
      }).join('');

      roundEl.innerHTML = `
        <div class="exploration-round-header">
          <span>${round.name}</span>
          <span class="exploration-round-count">
            <span>🔧</span>
            <span>${round.toolExecutions.length} tool calls</span>
          </span>
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
    div.textContent = text || '';
    return div.innerHTML;
  }

  // Event handlers
  notification.querySelector('.exploration-notification-header').addEventListener('click', (e) => {
    // Don't toggle if clicking on action buttons
    if (e.target.closest('.exploration-header-actions')) {
      return;
    }
    toggleNotification();
  });

  // Minimize button handler
  notification.querySelector('.exploration-minimize-btn').addEventListener('click', (e) => {
    e.stopPropagation();
    minimizeNotification();
  });

  // Cancel button handler
  notification.querySelector('.exploration-cancel-btn').addEventListener('click', (e) => {
    e.stopPropagation();
    if (currentSessionId && confirm('Are you sure you want to cancel the exploration?')) {
      // Cancel exploration
      stopStatusUpdates();
      stopElapsedTimeUpdates();
      updateStatusText('Cancelled', false);
      
      // Hide after a moment
      setTimeout(hideNotification, 2000);
    }
  });

  // Expand icon handler
  notification.querySelector('.exploration-expand-icon').addEventListener('click', (e) => {
    e.stopPropagation();
    toggleNotification();
  });

  // Initial setup of view full button
  const initialViewFullBtn = notification.querySelector('.exploration-view-full');
  if (initialViewFullBtn) {
    initialViewFullBtn.addEventListener('click', showFullView);
  }

  overlay.querySelector('.exploration-close').addEventListener('click', hideFullView);
  overlay.addEventListener('click', (e) => {
    if (e.target === overlay) {
      hideFullView();
    }
  });

  // Global functions
  window.startExploration = async function(query, conversationId) {
    console.log('Starting exploration for query:', query, 'conversation:', conversationId);

    try {
      updateStatusText('Starting exploration...', true);
      startElapsedTimeUpdates();

      const response = await window.intellijBridge.callIDE('startExploration', { 
        query: query,
        conversationId: conversationId || ""  // Send empty string instead of null/undefined
      });

      if (!response.success) {
        if (response.indexing) {
          // Project is being indexed
          pendingQuery = query; // Store the query to retry after indexing

          // Show indexing status
          showIndexingMessage(response.message || 'Building code index for the first time...');
          showNotification();

          return 'indexing'; // Special return value to indicate indexing state
        } else if (response.requiresIndexing) {
          // Old error case (shouldn't happen now)
          updateStatusText('Error', false);
          const errorHtml = `
            <div class="exploration-error-message">
              ⚠️ Project not indexed. Please index your project first to use Agent Mode exploration.
            </div>
          `;
          const content = notification.querySelector('.exploration-notification-content');
          if (content) {
            content.innerHTML = errorHtml;
          }
          showNotification();

          // Auto-hide after 5 seconds
          setTimeout(hideNotification, 5000);
        }
        console.error('Failed to start exploration:', response.error);
        return null;
      }

      currentSessionId = response.sessionId;
      explorationData = { rounds: [], toolExecutions: [], summary: null };
      lastActivityTime = Date.now();

      // Update notification with proper structure
      updateStatusText('Understanding your query...', true);

      // Start periodic status updates
      startStatusUpdates();

      // Ensure the content has the right structure
      ensureNotificationStructure();

      // Update full view
      overlay.querySelector('.exploration-query').textContent = `Query: "${query}"`;
      overlay.querySelector('.exploration-title span:last-child').textContent = 'Exploring Codebase...';
      overlay.querySelector('.exploration-spinner').style.display = 'block';
      overlay.querySelector('.exploration-summary').style.display = 'none';

      showNotification();

      return response.sessionId;
    } catch (error) {
      console.error('Error starting exploration:', error);
      updateStatusText('Error', false);
      stopElapsedTimeUpdates();
      return null;
    }
  };

  window.handleExplorationProgress = function(event) {
    if (!currentSessionId || event.sessionId !== currentSessionId) {
      return;
    }

    console.log('Exploration progress:', event);

    // Track last activity time
    lastActivityTime = Date.now();

    // Store current status for state tracking
    currentStatus = event.eventType;

    switch (event.eventType) {
      case 'tool_execution':
        // Shake notification for activity
        notification.classList.add('shake');
        setTimeout(() => notification.classList.remove('shake'), 500);
        
        // Update status to show current tool with more context
        const toolName = event.data.toolName;
        const toolDisplayName = getToolDisplayName(toolName);
        
        // Extract reasoning if available
        const reasoning = event.data.reasoning || (event.data.parameters && event.data.parameters.reasoning) || '';
        
        // Show the full reasoning (up to 120 chars) since we can wrap
        if (reasoning) {
          const truncatedReasoning = reasoning.substring(0, 120) + (reasoning.length > 120 ? '...' : '');
          updateStatusText(truncatedReasoning, true);
        } else {
          // Fallback to tool name if no reasoning
          updateStatusText(`${toolDisplayName}...`, true);
        }

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
        // Update status to show what LLM is doing
        const roundNum = explorationData.rounds.length + 1;
        const toolCount = event.data.toolExecutions ? event.data.toolExecutions.length : 0;
        
        // Extract round reasoning if available in LLM response
        if (event.data.llmResponse) {
          // Try to extract reasoning from LLM response
          const reasoningMatch = event.data.llmResponse.match(/REASONING[:\s]+([^\n]+)/i);
          if (reasoningMatch) {
            const reasoning = reasoningMatch[1].trim().substring(0, 120) + (reasoningMatch[1].trim().length > 120 ? '...' : '');
            updateStatusText(reasoning, true);
          } else {
            // Try to find what the LLM is planning
            const planMatch = event.data.llmResponse.match(/(?:need to|should|will|going to|must)\s+([^\n.]+)/i);
            if (planMatch) {
              const plan = planMatch[1].trim().substring(0, 120) + (planMatch[1].trim().length > 120 ? '...' : '');
              updateStatusText(plan, true);
            } else {
              updateStatusText(`Planning round ${roundNum}...`, true);
            }
          }
        } else {
          updateStatusText(`Analyzing ${toolCount} results...`, true);
        }

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
        // Stop status updates
        stopStatusUpdates();
        stopElapsedTimeUpdates();
        
        // Play completion sound
        playCompletionSound();
        
        // Set progress to 100%
        updateProgressBar(100);

        // Update UI for completion
        const titleElement = notification.querySelector('.exploration-notification-title');
        if (titleElement) {
          const statusContainer = titleElement.querySelector('.exploration-status-container');
          if (statusContainer) {
            statusContainer.innerHTML = `
              <div class="exploration-status-main">
                <span class="exploration-complete-indicator"></span>
                <span class="exploration-status-label">Zest</span>
                <span class="exploration-status-detail">Exploration Complete!</span>
              </div>
              <div class="exploration-status-sub">
                <span class="exploration-tool-count">
                  <span>✓</span>
                  <span>${explorationData.toolExecutions.length} tools executed</span>
                </span>
                <span class="exploration-elapsed-time">
                  <span>⏱️</span>
                  <span>${formatElapsedTime(Date.now() - explorationStartTime)}</span>
                </span>
              </div>
            `;
          }
        }

        overlay.querySelector('.exploration-spinner').style.display = 'none';
        overlay.querySelector('.exploration-title span:last-child').innerHTML =
          'Exploration Complete <span class="exploration-complete-badge">✓ Done</span>';

        if (event.data.summary) {
          const summaryEl = overlay.querySelector('.exploration-summary');
          summaryEl.innerHTML = `
            <div class="exploration-summary-header">
              <span>📋 Summary</span>
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

        // Show completion actions
        const content = notification.querySelector('.exploration-notification-content');
        if (content) {
          ensureNotificationStructure();
          const progressList = content.querySelector('.exploration-progress-list');
          if (progressList) {
            progressList.innerHTML = `
              <div class="exploration-status-text" style="text-align: center; padding: 16px;">
                <strong>✨ Exploration complete!</strong><br>
                <span style="color: #888;">Found ${event.data.rounds ? event.data.rounds.length : 0} key insights about your codebase.</span>
              </div>
            `;
          }
        }

        // Auto-hide notification after 5 seconds
        setTimeout(() => {
          hideNotification();
          hideFullView();
        }, 5000);
        break;
    }
  };

  // Add function to mark exploration as used (for immediate closing)
  window.markExplorationUsed = function() {
    if (currentSessionId) {
      // Update notification to show it's being used
      updateStatusText('Enhancing your query...', true);
      
      // Stop status updates
      stopStatusUpdates();
      stopElapsedTimeUpdates();

      // Close immediately
      setTimeout(() => {
        hideNotification();
        hideFullView();
      }, 500);
    }
  };

  // Add handlers for indexing callbacks
  window.handleIndexingComplete = async function() {
    console.log('Indexing complete, retrying exploration...');

    // Update notification
    const titleElement = notification.querySelector('.exploration-notification-title');
    if (titleElement) {
      const statusContainer = titleElement.querySelector('.exploration-status-container');
      if (statusContainer) {
        statusContainer.innerHTML = `
          <div class="exploration-status-main">
            <span class="exploration-complete-indicator"></span>
            <span class="exploration-status-label">Zest</span>
            <span class="exploration-status-detail">Index Ready!</span>
          </div>
        `;
      }
    }

    const content = notification.querySelector('.exploration-notification-content');
    if (content) {
      content.innerHTML = `
        <div class="exploration-progress-item">
          <div class="exploration-status-text" style="color: #4caf50; text-align: center;">
            <strong>✓ Code index built successfully!</strong>
          </div>
          <div class="exploration-status-text" style="margin-top: 8px; text-align: center;">
            Starting exploration now...
          </div>
        </div>
      `;
    }

    // Play a success sound
    playCompletionSound();

    // Wait a moment for user to see the success message
    await new Promise(resolve => setTimeout(resolve, 2000));

    // Retry the exploration with the stored query
    if (pendingQuery) {
      const query = pendingQuery;
      pendingQuery = null;

      // Hide current notification
      hideNotification();

      // Retry exploration
      window.startExploration(query);
    }
  };

  window.handleIndexingError = function(error) {
    console.error('Indexing failed:', error);

    // Update notification to show error
    updateStatusText('Indexing Failed', false);
    stopElapsedTimeUpdates();

    const content = notification.querySelector('.exploration-notification-content');
    if (content) {
      content.innerHTML = `
        <div class="exploration-error-message">
          <strong>⚠️ Failed to build code index</strong><br>
          <span style="font-size: 12px;">${escapeHtml(error || 'Unknown error occurred')}</span>
        </div>
        <div class="exploration-action-buttons">
          <button class="exploration-action-btn" onclick="window.retryIndexing()">
            🔄 Retry
          </button>
          <button class="exploration-action-btn" onclick="window.hideExplorationNotification()">
            Cancel
          </button>
        </div>
      `;
    }

    // Clear pending query
    pendingQuery = null;
  };

  // Add retry function
  window.retryIndexing = function() {
    if (pendingQuery) {
      hideNotification();
      setTimeout(() => {
        window.startExploration(pendingQuery);
      }, 500);
    }
  };

  // Add hide function
  window.hideExplorationNotification = function() {
    hideNotification();
    pendingQuery = null;
  };

  console.log('Enhanced Exploration UI initialized with improved UX');
})();