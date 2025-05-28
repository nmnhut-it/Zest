/**
 * Agent UI Components for visualizing and controlling agents
 */

(function() {
    'use strict';

    window.AgentUI = {
        container: null,
        initialized: false,
        updateInterval: null
    };

    /**
     * Initialize the Agent UI
     */
    window.AgentUI.initialize = function(containerId) {
        if (this.initialized) return;
        
        this.container = document.getElementById(containerId);
        if (!this.container) {
            // Create container if it doesn't exist
            this.container = document.createElement('div');
            this.container.id = containerId || 'agent-ui-container';
            document.body.appendChild(this.container);
        }
        
        this.createUI();
        this.initialized = true;
        
        // Start monitoring
        this.startMonitoring();
    };

    /**
     * Create the main UI structure
     */
    window.AgentUI.createUI = function() {
        this.container.innerHTML = `
            <div class="agent-ui-wrapper">
                <style>
                    .agent-ui-wrapper {
                        position: fixed;
                        bottom: 20px;
                        right: 20px;
                        width: 400px;
                        max-height: 600px;
                        background: rgba(0, 0, 0, 0.9);
                        border: 1px solid #333;
                        border-radius: 8px;
                        color: #fff;
                        font-family: 'Monaco', 'Consolas', monospace;
                        font-size: 12px;
                        z-index: 10000;
                        overflow: hidden;
                        transition: all 0.3s ease;
                    }
                    
                    .agent-ui-wrapper.minimized {
                        height: 40px;
                        width: 200px;
                    }
                    
                    .agent-ui-header {
                        background: #1a1a1a;
                        padding: 10px;
                        border-bottom: 1px solid #333;
                        display: flex;
                        justify-content: space-between;
                        align-items: center;
                        cursor: move;
                    }
                    
                    .agent-ui-title {
                        font-weight: bold;
                        color: #4CAF50;
                    }
                    
                    .agent-ui-controls {
                        display: flex;
                        gap: 10px;
                    }
                    
                    .agent-ui-btn {
                        background: #333;
                        border: 1px solid #555;
                        color: #fff;
                        padding: 4px 8px;
                        border-radius: 4px;
                        cursor: pointer;
                        font-size: 11px;
                    }
                    
                    .agent-ui-btn:hover {
                        background: #444;
                    }
                    
                    .agent-ui-content {
                        padding: 10px;
                        max-height: 500px;
                        overflow-y: auto;
                    }
                    
                    .agent-ui-tabs {
                        display: flex;
                        border-bottom: 1px solid #333;
                        margin-bottom: 10px;
                    }
                    
                    .agent-ui-tab {
                        padding: 8px 16px;
                        cursor: pointer;
                        border-bottom: 2px solid transparent;
                        transition: all 0.3s;
                    }
                    
                    .agent-ui-tab.active {
                        color: #4CAF50;
                        border-bottom-color: #4CAF50;
                    }
                    
                    .agent-ui-tab-content {
                        display: none;
                    }
                    
                    .agent-ui-tab-content.active {
                        display: block;
                    }
                    
                    .agent-card {
                        background: #1a1a1a;
                        border: 1px solid #333;
                        border-radius: 4px;
                        padding: 10px;
                        margin-bottom: 10px;
                    }
                    
                    .agent-card-header {
                        display: flex;
                        justify-content: space-between;
                        align-items: center;
                        margin-bottom: 8px;
                    }
                    
                    .agent-name {
                        font-weight: bold;
                        color: #4CAF50;
                    }
                    
                    .agent-state {
                        padding: 2px 6px;
                        border-radius: 3px;
                        font-size: 10px;
                        text-transform: uppercase;
                    }
                    
                    .agent-state.idle {
                        background: #555;
                        color: #aaa;
                    }
                    
                    .agent-state.ready {
                        background: #2196F3;
                        color: #fff;
                    }
                    
                    .agent-state.working {
                        background: #FF9800;
                        color: #fff;
                    }
                    
                    .agent-stats {
                        display: grid;
                        grid-template-columns: 1fr 1fr;
                        gap: 8px;
                        font-size: 11px;
                        color: #aaa;
                    }
                    
                    .workflow-item {
                        background: #1a1a1a;
                        border: 1px solid #333;
                        border-radius: 4px;
                        padding: 8px;
                        margin-bottom: 8px;
                    }
                    
                    .create-agent-form {
                        display: flex;
                        flex-direction: column;
                        gap: 10px;
                    }
                    
                    .form-group {
                        display: flex;
                        flex-direction: column;
                        gap: 4px;
                    }
                    
                    .form-group label {
                        color: #aaa;
                        font-size: 11px;
                    }
                    
                    .form-group select,
                    .form-group input {
                        background: #1a1a1a;
                        border: 1px solid #333;
                        color: #fff;
                        padding: 6px;
                        border-radius: 4px;
                    }
                    
                    .console-output {
                        background: #0a0a0a;
                        border: 1px solid #333;
                        border-radius: 4px;
                        padding: 10px;
                        max-height: 200px;
                        overflow-y: auto;
                        font-family: monospace;
                        font-size: 11px;
                        color: #0f0;
                    }
                    
                    .console-line {
                        margin-bottom: 4px;
                    }
                    
                    .console-timestamp {
                        color: #666;
                        margin-right: 8px;
                    }
                </style>
                
                <div class="agent-ui-header">
                    <div class="agent-ui-title">🤖 Agent Framework</div>
                    <div class="agent-ui-controls">
                        <button class="agent-ui-btn" onclick="AgentUI.toggleMinimize()">_</button>
                        <button class="agent-ui-btn" onclick="AgentUI.close()">✕</button>
                    </div>
                </div>
                
                <div class="agent-ui-content">
                    <div class="agent-ui-tabs">
                        <div class="agent-ui-tab active" data-tab="agents">Agents</div>
                        <div class="agent-ui-tab" data-tab="workflows">Workflows</div>
                        <div class="agent-ui-tab" data-tab="create">Create</div>
                        <div class="agent-ui-tab" data-tab="console">Console</div>
                    </div>
                    
                    <div class="agent-ui-tab-content active" data-content="agents">
                        <div id="agents-list"></div>
                    </div>
                    
                    <div class="agent-ui-tab-content" data-content="workflows">
                        <div id="workflows-list"></div>
                    </div>
                    
                    <div class="agent-ui-tab-content" data-content="create">
                        <div class="create-agent-form">
                            <div class="form-group">
                                <label>Agent Role</label>
                                <select id="agent-role-select">
                                    <option value="">Select Role...</option>
                                </select>
                            </div>
                            <div class="form-group">
                                <label>Configuration (JSON)</label>
                                <input type="text" id="agent-config" placeholder='{"priority": 5}' />
                            </div>
                            <button class="agent-ui-btn" onclick="AgentUI.createAgent()">Create Agent</button>
                            
                            <hr style="border-color: #333; margin: 20px 0;">
                            
                            <h4 style="color: #4CAF50;">Quick Actions</h4>
                            <button class="agent-ui-btn" onclick="AgentUI.createDefaultTeam()">Create Default Team</button>
                            <button class="agent-ui-btn" onclick="AgentUI.runDemoWorkflow()">Run Demo Workflow</button>
                        </div>
                    </div>
                    
                    <div class="agent-ui-tab-content" data-content="console">
                        <div class="console-output" id="agent-console"></div>
                    </div>
                </div>
            </div>
        `;
        
        // Initialize tabs
        this.initializeTabs();
        
        // Populate role selector
        this.populateRoleSelector();
        
        // Make draggable
        this.makeDraggable();
    };

    /**
     * Initialize tab functionality
     */
    window.AgentUI.initializeTabs = function() {
        const tabs = this.container.querySelectorAll('.agent-ui-tab');
        tabs.forEach(tab => {
            tab.addEventListener('click', () => {
                // Remove active from all tabs and contents
                tabs.forEach(t => t.classList.remove('active'));
                this.container.querySelectorAll('.agent-ui-tab-content').forEach(c => {
                    c.classList.remove('active');
                });
                
                // Add active to clicked tab and corresponding content
                tab.classList.add('active');
                const content = this.container.querySelector(`[data-content="${tab.dataset.tab}"]`);
                if (content) content.classList.add('active');
            });
        });
    };

    /**
     * Populate role selector
     */
    window.AgentUI.populateRoleSelector = function() {
        const select = this.container.querySelector('#agent-role-select');
        for (const [key, role] of Object.entries(window.AgentFramework.AgentRoles)) {
            const option = document.createElement('option');
            option.value = key;
            option.textContent = role.name;
            select.appendChild(option);
        }
    };

    /**
     * Make the UI draggable
     */
    window.AgentUI.makeDraggable = function() {
        const header = this.container.querySelector('.agent-ui-header');
        let isDragging = false;
        let currentX;
        let currentY;
        let initialX;
        let initialY;
        let xOffset = 0;
        let yOffset = 0;

        header.addEventListener('mousedown', dragStart);
        document.addEventListener('mousemove', drag);
        document.addEventListener('mouseup', dragEnd);

        function dragStart(e) {
            initialX = e.clientX - xOffset;
            initialY = e.clientY - yOffset;
            if (e.target === header) {
                isDragging = true;
            }
        }

        function drag(e) {
            if (isDragging) {
                e.preventDefault();
                currentX = e.clientX - initialX;
                currentY = e.clientY - initialY;
                xOffset = currentX;
                yOffset = currentY;
                setTranslate(currentX, currentY, header.parentElement);
            }
        }

        function setTranslate(xPos, yPos, el) {
            el.style.transform = `translate(${xPos}px, ${yPos}px)`;
        }

        function dragEnd(e) {
            initialX = currentX;
            initialY = currentY;
            isDragging = false;
        }
    };

    /**
     * Start monitoring agents
     */
    window.AgentUI.startMonitoring = function() {
        this.updateAgentsList();
        this.updateInterval = setInterval(() => {
            this.updateAgentsList();
        }, 1000);
    };

    /**
     * Update agents list
     */
    window.AgentUI.updateAgentsList = function() {
        const agentsList = this.container.querySelector('#agents-list');
        const status = window.AgentFramework.AgentManager.getStatus();
        
        let html = '';
        for (const agent of status.agents) {
            html += `
                <div class="agent-card">
                    <div class="agent-card-header">
                        <div class="agent-name">${agent.role}</div>
                        <div class="agent-state ${agent.state}">${agent.state}</div>
                    </div>
                    <div class="agent-stats">
                        <div>Tasks: ${agent.stats.tasksCompleted}</div>
                        <div>Failed: ${agent.stats.tasksFailed}</div>
                        <div>Queue: ${agent.queueLength}</div>
                        <div>Avg Time: ${agent.stats.totalExecutionTime ? 
                            (agent.stats.totalExecutionTime / agent.stats.tasksCompleted).toFixed(0) : 0}ms</div>
                    </div>
                </div>
            `;
        }
        
        agentsList.innerHTML = html || '<div style="color: #666; text-align: center;">No agents running</div>';
    };

    /**
     * Create a new agent
     */
    window.AgentUI.createAgent = async function() {
        const role = this.container.querySelector('#agent-role-select').value;
        const configInput = this.container.querySelector('#agent-config').value;
        
        if (!role) {
            alert('Please select a role');
            return;
        }
        
        let config = {};
        if (configInput) {
            try {
                config = JSON.parse(configInput);
            } catch (e) {
                alert('Invalid JSON configuration');
                return;
            }
        }
        
        try {
            const agent = await window.AgentFramework.createAgent(role, config);
            this.log(`Created ${role} agent with ID: ${agent.id}`);
            this.updateAgentsList();
        } catch (error) {
            this.log(`Error creating agent: ${error.message}`, 'error');
        }
    };

    /**
     * Create default team
     */
    window.AgentUI.createDefaultTeam = async function() {
        try {
            const team = await window.AgentFramework.createTeam({
                name: 'Default Development Team',
                agents: [
                    { role: 'CODE_GENERATOR' },
                    { role: 'CODE_REVIEWER' },
                    { role: 'TESTING' },
                    { role: 'DOCUMENTATION' }
                ]
            });
            
            this.log(`Created team with ${team.agents.length} agents`);
            this.updateAgentsList();
        } catch (error) {
            this.log(`Error creating team: ${error.message}`, 'error');
        }
    };

    /**
     * Run demo workflow
     */
    window.AgentUI.runDemoWorkflow = async function() {
        // Register a demo workflow
        window.AgentFramework.AgentManager.registerWorkflow('demo-workflow', {
            name: 'Demo Development Workflow',
            steps: [
                {
                    name: 'Generate Code',
                    requiredCapability: 'generate',
                    type: 'generate',
                    data: {
                        type: 'class',
                        language: 'javascript',
                        specification: {
                            name: 'UserService',
                            methods: ['getUser', 'updateUser', 'deleteUser']
                        }
                    }
                },
                {
                    name: 'Review Code',
                    requiredCapability: 'review',
                    type: 'review',
                    data: {
                        language: 'javascript'
                    }
                },
                {
                    name: 'Generate Tests',
                    requiredCapability: 'create_tests',
                    type: 'create_tests',
                    data: {
                        language: 'javascript',
                        framework: 'jest'
                    }
                }
            ]
        });
        
        try {
            // Find or create a coordinator
            let coordinator = null;
            for (const [id, agent] of window.AgentFramework.agents) {
                if (agent.role.id === 'coordinator') {
                    coordinator = agent;
                    break;
                }
            }
            
            if (!coordinator) {
                coordinator = await window.AgentFramework.createAgent('COORDINATOR');
            }
            
            // Execute workflow
            const taskId = coordinator.queueTask({
                type: 'orchestrate',
                data: {
                    workflow: window.AgentFramework.AgentManager.workflows.get('demo-workflow'),
                    data: {}
                }
            });
            
            this.log(`Started demo workflow with task ID: ${taskId}`);
        } catch (error) {
            this.log(`Error running demo workflow: ${error.message}`, 'error');
        }
    };

    /**
     * Log to console
     */
    window.AgentUI.log = function(message, type = 'info') {
        const consoleElement = this.container.querySelector('#agent-console');
        const timestamp = new Date().toLocaleTimeString();
        const line = document.createElement('div');
        line.className = 'console-line';
        line.innerHTML = `<span class="console-timestamp">[${timestamp}]</span>${message}`;
        consoleElement.appendChild(line);
        consoleElement.scrollTop = consoleElement.scrollHeight;
        
        // Also log to browser console
        console.log(`[AgentUI] ${message}`);
    };

    /**
     * Toggle minimize
     */
    window.AgentUI.toggleMinimize = function() {
        this.container.querySelector('.agent-ui-wrapper').classList.toggle('minimized');
    };

    /**
     * Close UI
     */
    window.AgentUI.close = function() {
        if (this.updateInterval) {
            clearInterval(this.updateInterval);
        }
        this.container.remove();
        this.initialized = false;
    };

    // Auto-initialize when DOM is ready
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', () => {
            window.AgentUI.initialize('agent-ui-container');
        });
    } else {
        window.AgentUI.initialize('agent-ui-container');
    }

})();
