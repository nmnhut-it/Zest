/**
 * Agent Framework Startup Script
 * This script runs when the framework is loaded to provide user guidance
 */

(function() {
    // Only show the message once per session
    if (window._agentFrameworkStartupShown) return;
    window._agentFrameworkStartupShown = true;
    
    // Wait a bit for everything to load
    setTimeout(() => {
        console.log('%c🤖 Agent Framework Loaded!', 'color: #4CAF50; font-size: 16px; font-weight: bold;');
        console.log('%cThe Agent Framework is now available in this browser session.', 'color: #888;');
        console.log('\n%cQuick Start:', 'color: #4CAF50; font-weight: bold;');
        console.log('1. Use the menu: Right-click → Zest → Show Agent Framework Demo');
        console.log('2. Or navigate to: jcef://resource/html/agentDemo.html');
        console.log('3. Or run in console: window.AgentFramework.createAgent("CODE_GENERATOR")');
        console.log('\n%cAvailable Agent Roles:', 'color: #4CAF50; font-weight: bold;');
        
        if (window.AgentFramework && window.AgentFramework.AgentRoles) {
            Object.entries(window.AgentFramework.AgentRoles).forEach(([key, role]) => {
                console.log(`  • ${key}: ${role.description}`);
            });
        }
        
        console.log('\n%cFor more info, check the Agent UI in the bottom-right corner!', 'color: #888;');
    }, 2000);
})();
