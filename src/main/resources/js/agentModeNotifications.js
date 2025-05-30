/**
 * Agent Mode UI Notifications for context collection
 */

(function() {
    'use strict';

    // Create notification container if it doesn't exist
    function ensureNotificationContainer() {
        let container = document.getElementById('agent-mode-notifications');
        if (!container) {
            container = document.createElement('div');
            container.id = 'agent-mode-notifications';
            container.style.cssText = `
                position: fixed;
                top: 20px;
                right: 20px;
                z-index: 10000;
                pointer-events: none;
            `;
            document.body.appendChild(container);
        }
        return container;
    }

    // Create a notification element
    function createNotification(message, type = 'info') {
        const notification = document.createElement('div');
        notification.className = 'agent-notification';
        
        const colors = {
            info: '#2196F3',
            success: '#4CAF50',
            error: '#f44336'
        };
        
        notification.style.cssText = `
            background-color: ${colors[type]};
            color: white;
            padding: 12px 20px;
            margin-bottom: 10px;
            border-radius: 4px;
            box-shadow: 0 2px 5px rgba(0,0,0,0.2);
            font-family: Arial, sans-serif;
            font-size: 14px;
            animation: slideIn 0.3s ease-out;
            pointer-events: auto;
            display: flex;
            align-items: center;
            gap: 10px;
        `;
        
        // Add icon based on type
        const icon = document.createElement('span');
        icon.style.cssText = 'font-size: 18px;';
        
        if (type === 'info') {
            icon.innerHTML = '🔍'; // Search icon
            
            // Add spinning animation for loading state
            const spinner = document.createElement('div');
            spinner.style.cssText = `
                width: 16px;
                height: 16px;
                border: 2px solid #ffffff;
                border-top-color: transparent;
                border-radius: 50%;
                animation: spin 1s linear infinite;
                margin-left: 10px;
            `;
            notification.appendChild(spinner);
        } else if (type === 'success') {
            icon.innerHTML = '✓';
        } else if (type === 'error') {
            icon.innerHTML = '⚠';
        }
        
        notification.prepend(icon);
        
        const text = document.createElement('span');
        text.textContent = message;
        notification.appendChild(text);
        
        // Add CSS animations
        if (!document.getElementById('agent-notification-styles')) {
            const style = document.createElement('style');
            style.id = 'agent-notification-styles';
            style.textContent = `
                @keyframes slideIn {
                    from {
                        transform: translateX(100%);
                        opacity: 0;
                    }
                    to {
                        transform: translateX(0);
                        opacity: 1;
                    }
                }
                @keyframes slideOut {
                    from {
                        transform: translateX(0);
                        opacity: 1;
                    }
                    to {
                        transform: translateX(100%);
                        opacity: 0;
                    }
                }
                @keyframes spin {
                    from { transform: rotate(0deg); }
                    to { transform: rotate(360deg); }
                }
            `;
            document.head.appendChild(style);
        }
        
        return notification;
    }

    // Show notification
    function showNotification(message, type = 'info', duration = 3000) {
        const container = ensureNotificationContainer();
        const notification = createNotification(message, type);
        
        container.appendChild(notification);
        
        // Auto-remove after duration (except for loading notifications)
        if (type !== 'info' || duration > 0) {
            setTimeout(() => {
                notification.style.animation = 'slideOut 0.3s ease-out';
                setTimeout(() => notification.remove(), 300);
            }, duration);
        }
        
        return notification;
    }

    // Store current loading notification
    let currentLoadingNotification = null;

    // Context collection started
    window.notifyContextCollection = function() {
        console.log('Agent Mode: Starting context collection...');
        
        // Remove any existing loading notification
        if (currentLoadingNotification) {
            currentLoadingNotification.remove();
        }
        
        // Show loading notification (no auto-remove)
        currentLoadingNotification = showNotification(
            'Collecting project context...', 
            'info', 
            0 // No auto-remove
        );
    };

    // Context collection completed
    window.notifyContextComplete = function() {
        console.log('Agent Mode: Context collection complete');
        
        // Remove loading notification
        if (currentLoadingNotification) {
            currentLoadingNotification.remove();
            currentLoadingNotification = null;
        }
        
        // Show success notification
        showNotification('Context collected successfully!', 'success', 2000);
    };

    // Context collection error
    window.notifyContextError = function() {
        console.log('Agent Mode: Context collection error');
        
        // Remove loading notification
        if (currentLoadingNotification) {
            currentLoadingNotification.remove();
            currentLoadingNotification = null;
        }
        
        // Show error notification
        showNotification('Context collection failed', 'error', 3000);
    };

    // Additional helper for showing custom notifications
    window.showAgentNotification = function(message, type = 'info', duration = 3000) {
        return showNotification(message, type, duration);
    };

    console.log('Agent Mode notifications initialized');
})();
