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
                        width: 500px;
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
                    
                    /* Research tab styles */
                    .research-section {
                        margin-bottom: 20px;
                    }
                    
                    .research-section h4 {
                        color: #4CAF50;
                        margin: 0 0 10px 0;
                        font-size: 13px;
                    }
                    
                    .search-form {
                        display: flex;
                        flex-direction: column;
                        gap: 8px;
                    }
                    
                    .search-input-group {
                        display: flex;
                        gap: 5px;
                    }
                    
                    .search-input-group input {
                        flex: 1;
                    }
                    
                    .search-options {
                        display: flex;
                        gap: 10px;
                        flex-wrap: wrap;
                        font-size: 11px;
                    }
                    
                    .search-options label {
                        display: flex;
                        align-items: center;
                        gap: 3px;
                        color: #aaa;
                    }
                    
                    .search-options input[type="checkbox"] {
                        margin: 0;
                    }
                    
                    .search-results {
                        background: #0a0a0a;
                        border: 1px solid #333;
                        border-radius: 4px;
                        padding: 10px;
                        max-height: 300px;
                        overflow-y: auto;
                        font-size: 11px;
                    }
                    
                    .search-result-file {
                        margin-bottom: 15px;
                    }
                    
                    .search-result-filename {
                        color: #4CAF50;
                        font-weight: bold;
                        margin-bottom: 5px;
                    }
                    
                    .search-result-match {
                        background: #1a1a1a;
                        border-left: 3px solid #4CAF50;
                        padding: 5px;
                        margin: 3px 0;
                        font-family: monospace;
                    }
                    
                    .search-result-line-number {
                        color: #666;
                        margin-right: 10px;
                    }
                    
                    .search-result-text {
                        color: #ddd;
                    }
                    
                    .search-result-highlight {
                        background: #4CAF50;
                        color: #000;
                        font-weight: bold;
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
                        <div class="agent-ui-tab" data-tab="research">Research</div>
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
                    
                    <div class="agent-ui-tab-content" data-content="research">
                        <div class="research-section">
                            <h4>🔍 Search Project</h4>
                            <div class="search-form">
                                <div class="search-input-group">
                                    <input type="text" id="search-text" placeholder="Search text..." />
                                    <button class="agent-ui-btn" onclick="AgentUI.searchProject()">Search</button>
                                </div>
                                <div class="search-options">
                                    <label><input type="checkbox" id="search-case-sensitive"> Case sensitive</label>
                                    <label><input type="checkbox" id="search-whole-word"> Whole word</label>
                                    <label><input type="checkbox" id="search-regex"> Regex</label>
                                </div>
                            </div>
                            <div id="search-results" class="search-results" style="display: none; margin-top: 10px;"></div>
                        </div>
                        
                        <div class="research-section">
                            <h4>🔧 Find Function</h4>
                            <div class="search-form">
                                <div class="search-input-group">
                                    <input type="text" id="function-name" placeholder="Function name..." />
                                    <button class="agent-ui-btn" onclick="AgentUI.findFunction()">Find</button>
                                </div>
                            </div>
                            <div id="function-results" class="search-results" style="display: none; margin-top: 10px;"></div>
                        </div>
                        
                        <div class="research-section">
                            <h4>📊 Analyze Usage</h4>
                            <div class="search-form">
                                <div class="search-input-group">
                                    <input type="text" id="analyze-function" placeholder="Function to analyze..." />
                                    <button class="agent-ui-btn" onclick="AgentUI.analyzeUsage()">Analyze</button>
                                </div>
                            </div>
                            <div id="usage-results" class="search-results" style="display: none; margin-top: 10px;"></div>
                        </div>
                        
                        <div class="research-section">
                            <h4>🔗 Find References</h4>
                            <div class="search-form">
                                <div class="search-input-group">
                                    <input type="text" id="reference-identifier" placeholder="Identifier to find references..." />
                                    <button class="agent-ui-btn" onclick="AgentUI.findReferences()">Find</button>
                                </div>
                            </div>
                            <div id="reference-results" class="search-results" style="display: none; margin-top: 10px;"></div>
                        </div>
                        
                        <div class="research-section">
                            <h4>📝 Extract Signatures</h4>
                            <div class="search-form">
                                <div class="search-input-group">
                                    <input type="text" id="signatures-path" placeholder="Directory path (optional, default: /)" />
                                    <button class="agent-ui-btn" onclick="AgentUI.extractSignatures()">Extract</button>
                                </div>
                            </div>
                            <div id="signatures-results" class="search-results" style="display: none; margin-top: 10px;"></div>
                        </div>
                        
                        <div class="research-section">
                            <h4>📁 Directory Tree</h4>
                            <div class="search-form">
                                <button class="agent-ui-btn" onclick="AgentUI.getDirectoryTree()" style="width: 100%;">Show Project Structure</button>
                            </div>
                            <div id="tree-results" class="search-results" style="display: none; margin-top: 10px;"></div>
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
                        language: 'javascript',
                        context: {
                            description: 'Review the generated UserService class'
                        }
                    }
                },
                {
                    name: 'Generate Tests',
                    requiredCapability: 'create_tests',
                    type: 'create_tests',
                    data: {
                        language: 'javascript',
                        framework: 'jest',
                        targetClass: 'UserService'
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
                this.log('Created coordinator agent');
            }
            
            // Execute workflow
            const taskId = coordinator.queueTask({
                type: 'orchestrate',
                data: {
                    workflow: window.AgentFramework.AgentManager.workflows.get('demo-workflow'),
                    data: {}
                },
                callback: (error, result) => {
                    if (error) {
                        this.log(`Workflow failed: ${error.message}`, 'error');
                    } else {
                        this.log('Workflow completed successfully!', 'success');
                        this.log(`Completed steps: ${result.results.length}`);
                        this.log(`Summary: ${JSON.stringify(result.summary)}`);
                    }
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
        
        // Color code based on type
        let color = '#0f0'; // default green
        let prefix = '';
        if (type === 'error') {
            color = '#f44';
            prefix = '[ERROR] ';
        } else if (type === 'warning') {
            color = '#fa0';
            prefix = '[WARN] ';
        } else if (type === 'success') {
            color = '#4f4';
            prefix = '[SUCCESS] ';
        }
        
        line.innerHTML = `<span class="console-timestamp">[${timestamp}]</span><span style="color: ${color}">${prefix}${message}</span>`;
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

    /**
     * Research Agent Functions
     */
    
    // Get or create research agent
    window.AgentUI.getResearchAgent = async function() {
        // Check if research agent already exists
        for (const [id, agent] of window.AgentFramework.agents) {
            if (agent.role.id === 'research_agent') {
                return agent;
            }
        }
        
        // Create new research agent
        try {
            const agent = await window.AgentFramework.createAgent('RESEARCH');
            this.log('Created Research Agent', 'success');
            return agent;
        } catch (error) {
            this.log(`Failed to create Research Agent: ${error.message}`, 'error');
            throw error;
        }
    };
    
    // Get project directory from IntelliJ
    window.AgentUI.getProjectDirectory = async function() {
        try {
            const result = await window.intellijBridge.callIDE('getProjectPath', {});
            return result.path || '/';
        } catch (error) {
            console.log('Failed to get project path, using root:', error);
            return '/';
        }
    };
    
    // Search project
    window.AgentUI.searchProject = async function() {
        const searchText = this.container.querySelector('#search-text').value;
        if (!searchText) {
            alert('Please enter search text');
            return;
        }
        
        const caseSensitive = this.container.querySelector('#search-case-sensitive').checked;
        const wholeWord = this.container.querySelector('#search-whole-word').checked;
        const regex = this.container.querySelector('#search-regex').checked;
        
        const resultsDiv = this.container.querySelector('#search-results');
        resultsDiv.style.display = 'block';
        resultsDiv.innerHTML = '<div style="color: #fa0;">Searching...</div>';
        
        try {
            const agent = await this.getResearchAgent();
            // Always use "/" for project root - the Java code expects relative paths
            const projectPath = "/";
            
            this.log(`Searching for "${searchText}" in project...`);
            
            const taskId = agent.queueTask({
                type: 'search_text',
                data: {
                    searchText: searchText,
                    folderPath: projectPath,
                    options: {
                        caseSensitive: caseSensitive,
                        wholeWord: wholeWord,
                        regex: regex,
                        contextLines: 2,
                        excludePatterns: ['**/node_modules/**', '**/.git/**', '**/dist/**', '**/build/**']
                    }
                },
                callback: (error, result) => {
                    if (error) {
                        resultsDiv.innerHTML = `<div style="color: #f44;">Error: ${error.message}</div>`;
                        this.log(`Search failed: ${error.message}`, 'error');
                    } else {
                        this.displaySearchResults(result, resultsDiv);
                        this.log(`Search completed: ${result.totalMatches} matches in ${result.files.length} files`, 'success');
                    }
                }
            });
            
        } catch (error) {
            resultsDiv.innerHTML = `<div style="color: #f44;">Error: ${error.message}</div>`;
            this.log(`Search error: ${error.message}`, 'error');
        }
    };
    
    // Display search results
    window.AgentUI.displaySearchResults = function(results, container) {
        if (results.files.length === 0) {
            container.innerHTML = '<div style="color: #666;">No matches found</div>';
            return;
        }
        
        let html = `<div style="color: #4CAF50; margin-bottom: 10px;">Found ${results.totalMatches} matches in ${results.files.length} files:</div>`;
        
        results.files.forEach(file => {
            html += `<div class="search-result-file">`;
            html += `<div class="search-result-filename">${file.path} (${file.matchCount} matches)</div>`;
            
            file.matches.forEach(match => {
                html += `<div class="search-result-match">`;
                html += `<span class="search-result-line-number">Line ${match.line}:</span>`;
                html += `<span class="search-result-text">${this.escapeHtml(match.text)}</span>`;
                html += `</div>`;
            });
            
            html += `</div>`;
        });
        
        container.innerHTML = html;
    };
    
    // Find function
    window.AgentUI.findFunction = async function() {
        const functionName = this.container.querySelector('#function-name').value;
        if (!functionName) {
            alert('Please enter a function name');
            return;
        }
        
        const resultsDiv = this.container.querySelector('#function-results');
        resultsDiv.style.display = 'block';
        resultsDiv.innerHTML = '<div style="color: #fa0;">Searching for function...</div>';
        
        try {
            const agent = await this.getResearchAgent();
            // Always use "/" for project root - the Java code expects relative paths
            const projectPath = "/";
            
            this.log(`Finding function "${functionName}"...`);
            
            agent.queueTask({
                type: 'find_syntax',
                data: {
                    functionName: functionName,
                    folderPath: projectPath,
                    options: {
                        excludePatterns: ['**/node_modules/**', '**/.git/**']
                    }
                },
                callback: (error, result) => {
                    if (error) {
                        resultsDiv.innerHTML = `<div style="color: #f44;">Error: ${error.message}</div>`;
                        this.log(`Function search failed: ${error.message}`, 'error');
                    } else {
                        this.displayFunctionResults(result, resultsDiv);
                        this.log(`Found ${result.totalOccurrences} definitions of "${functionName}"`, 'success');
                    }
                }
            });
            
        } catch (error) {
            resultsDiv.innerHTML = `<div style="color: #f44;">Error: ${error.message}</div>`;
            this.log(`Function search error: ${error.message}`, 'error');
        }
    };
    
    // Display function results
    window.AgentUI.displayFunctionResults = function(results, container) {
        if (results.definitions.length === 0) {
            container.innerHTML = '<div style="color: #666;">Function not found</div>';
            return;
        }
        
        let html = `<div style="color: #4CAF50; margin-bottom: 10px;">Found ${results.totalOccurrences} definitions:</div>`;
        
        results.definitions.forEach(def => {
            html += `<div class="search-result-file">`;
            html += `<div class="search-result-filename">${def.file}</div>`;
            html += `<div class="search-result-match">`;
            html += `<span class="search-result-line-number">Line ${def.line}:</span><br>`;
            html += `<span class="search-result-text" style="font-family: monospace;">${this.escapeHtml(def.signature)}</span>`;
            html += `</div>`;
            html += `</div>`;
        });
        
        if (results.variations.length > 1) {
            html += `<div style="color: #fa0; margin-top: 10px;">Note: Found ${results.variations.length} different signatures</div>`;
        }
        
        container.innerHTML = html;
    };
    
    // Analyze usage
    window.AgentUI.analyzeUsage = async function() {
        const functionName = this.container.querySelector('#analyze-function').value;
        if (!functionName) {
            alert('Please enter a function name');
            return;
        }
        
        const resultsDiv = this.container.querySelector('#usage-results');
        resultsDiv.style.display = 'block';
        resultsDiv.innerHTML = '<div style="color: #fa0;">Analyzing usage...</div>';
        
        try {
            const agent = await this.getResearchAgent();
            // Always use "/" for project root - the Java code expects relative paths
            const projectPath = "/";
            
            this.log(`Analyzing usage of "${functionName}"...`);
            
            agent.queueTask({
                type: 'analyze_usage',
                data: {
                    functionName: functionName,
                    folderPath: projectPath,
                    options: {
                        excludePatterns: ['**/node_modules/**', '**/.git/**']
                    }
                },
                callback: (error, result) => {
                    if (error) {
                        resultsDiv.innerHTML = `<div style="color: #f44;">Error: ${error.message}</div>`;
                        this.log(`Usage analysis failed: ${error.message}`, 'error');
                    } else {
                        this.displayUsageResults(result, resultsDiv);
                        this.log(`Analysis complete: ${result.summary.totalCalls} calls in ${result.summary.totalFiles} files`, 'success');
                    }
                }
            });
            
        } catch (error) {
            resultsDiv.innerHTML = `<div style="color: #f44;">Error: ${error.message}</div>`;
            this.log(`Usage analysis error: ${error.message}`, 'error');
        }
    };
    
    // Display usage results
    window.AgentUI.displayUsageResults = function(results, container) {
        let html = `<div style="color: #4CAF50; margin-bottom: 10px;">Usage Summary:</div>`;
        html += `<div style="margin-bottom: 10px;">`;
        html += `<div>Total Files: ${results.summary.totalFiles}</div>`;
        html += `<div>Total Calls: ${results.summary.totalCalls}</div>`;
        html += `<div>Total Imports: ${results.summary.totalImports}</div>`;
        html += `</div>`;
        
        if (results.usage.calls.length > 0) {
            html += `<div style="color: #4CAF50; margin: 10px 0;">Function Calls:</div>`;
            results.usage.calls.forEach(file => {
                html += `<div class="search-result-file">`;
                html += `<div class="search-result-filename">${file.file} (${file.matches.length} calls)</div>`;
                file.matches.slice(0, 3).forEach(match => {
                    html += `<div class="search-result-match">`;
                    html += `<span class="search-result-line-number">Line ${match.line}:</span>`;
                    html += `<span class="search-result-text">${this.escapeHtml(match.text)}</span>`;
                    html += `</div>`;
                });
                if (file.matches.length > 3) {
                    html += `<div style="color: #666; font-size: 10px; margin-left: 10px;">...and ${file.matches.length - 3} more</div>`;
                }
                html += `</div>`;
            });
        }
        
        container.innerHTML = html;
    };
    
    // Get directory tree
    window.AgentUI.getDirectoryTree = async function() {
        const resultsDiv = this.container.querySelector('#tree-results');
        resultsDiv.style.display = 'block';
        resultsDiv.innerHTML = '<div style="color: #fa0;">Loading project structure...</div>';
        
        try {
            const agent = await this.getResearchAgent();
            // Always use "/" for project root - the Java code expects relative paths
            const projectPath = "/";
            
            this.log('Getting project structure...');
            
            agent.queueTask({
                type: 'get_directory_tree',
                data: {
                    folderPath: projectPath,
                    options: {
                        maxDepth: 3,
                        excludePatterns: ['**/node_modules/**', '**/.git/**', '**/dist/**', '**/build/**'],
                        fileTypes: ['.js', '.jsx', '.ts', '.tsx', '.java', '.py', '.json', '.xml', '.html', '.css']
                    }
                },
                callback: (error, result) => {
                    if (error) {
                        resultsDiv.innerHTML = `<div style="color: #f44;">Error: ${error.message}</div>`;
                        this.log(`Failed to get directory tree: ${error.message}`, 'error');
                    } else {
                        this.displayDirectoryTree(result.tree, resultsDiv);
                        this.log('Project structure loaded', 'success');
                    }
                }
            });
            
        } catch (error) {
            resultsDiv.innerHTML = `<div style="color: #f44;">Error: ${error.message}</div>`;
            this.log(`Directory tree error: ${error.message}`, 'error');
        }
    };
    
    // Display directory tree
    window.AgentUI.displayDirectoryTree = function(tree, container) {
        if (!tree) {
            container.innerHTML = '<div style="color: #666;">No structure available</div>';
            return;
        }
        
        const renderTree = (node, level = 0) => {
            const indent = '  '.repeat(level);
            let html = '';
            
            if (node.type === 'directory') {
                html += `<div style="color: #4CAF50;">${indent}📁 ${node.name}/</div>`;
                if (node.children && node.children.length > 0) {
                    node.children.forEach(child => {
                        html += renderTree(child, level + 1);
                    });
                }
            } else {
                const icon = this.getFileIcon(node.name);
                html += `<div style="color: #ddd;">${indent}${icon} ${node.name}</div>`;
            }
            
            return html;
        };
        
        container.innerHTML = `<div style="font-family: monospace; white-space: pre;">${renderTree(tree)}</div>`;
    };
    
    // Find references
    window.AgentUI.findReferences = async function() {
        const identifier = this.container.querySelector('#reference-identifier').value;
        if (!identifier) {
            alert('Please enter an identifier');
            return;
        }
        
        const resultsDiv = this.container.querySelector('#reference-results');
        resultsDiv.style.display = 'block';
        resultsDiv.innerHTML = '<div style="color: #fa0;">Finding references...</div>';
        
        try {
            const agent = await this.getResearchAgent();
            // Always use "/" for project root - the Java code expects relative paths
            const projectPath = "/";
            
            this.log(`Finding references to "${identifier}"...`);
            
            agent.queueTask({
                type: 'find_references',
                data: {
                    identifier: identifier,
                    folderPath: projectPath,
                    options: {
                        excludePatterns: ['**/node_modules/**', '**/.git/**']
                    }
                },
                callback: (error, result) => {
                    if (error) {
                        resultsDiv.innerHTML = `<div style="color: #f44;">Error: ${error.message}</div>`;
                        this.log(`Reference search failed: ${error.message}`, 'error');
                    } else {
                        this.displayReferenceResults(result, resultsDiv);
                        this.log(`Found ${result.summary.totalReferences} references to "${identifier}"`, 'success');
                    }
                }
            });
            
        } catch (error) {
            resultsDiv.innerHTML = `<div style="color: #f44;">Error: ${error.message}</div>`;
            this.log(`Reference search error: ${error.message}`, 'error');
        }
    };
    
    // Display reference results
    window.AgentUI.displayReferenceResults = function(results, container) {
        let html = `<div style="color: #4CAF50; margin-bottom: 10px;">Reference Summary:</div>`;
        html += `<div style="margin-bottom: 10px;">`;
        html += `<div>Total References: ${results.summary.totalReferences}</div>`;
        html += `</div>`;
        
        // Display by type
        html += `<div style="color: #4CAF50; margin: 10px 0;">References by Type:</div>`;
        results.summary.byType.forEach(typeInfo => {
            if (typeInfo.count > 0) {
                html += `<div style="margin-left: 10px;">${typeInfo.type}: ${typeInfo.count}</div>`;
            }
        });
        
        // Display actual references
        for (const [type, refs] of Object.entries(results.references)) {
            if (refs.length > 0) {
                html += `<div style="color: #4CAF50; margin: 15px 0;">${type.charAt(0).toUpperCase() + type.slice(1)}:</div>`;
                refs.slice(0, 5).forEach(ref => {
                    html += `<div class="search-result-file">`;
                    html += `<div class="search-result-filename">${ref.file}</div>`;
                    html += `<div class="search-result-match">`;
                    html += `<span class="search-result-line-number">Line ${ref.line}:</span>`;
                    html += `<span class="search-result-text">${this.escapeHtml(ref.text)}</span>`;
                    html += `</div>`;
                    html += `</div>`;
                });
                if (refs.length > 5) {
                    html += `<div style="color: #666; font-size: 10px; margin-left: 10px;">...and ${refs.length - 5} more</div>`;
                }
            }
        }
        
        container.innerHTML = html;
    };
    
    // Extract signatures
    window.AgentUI.extractSignatures = async function() {
        const pathInput = this.container.querySelector('#signatures-path').value;
        // Use "/" for project root if no path specified
        const folderPath = pathInput || '/';
        
        const resultsDiv = this.container.querySelector('#signatures-results');
        resultsDiv.style.display = 'block';
        resultsDiv.innerHTML = '<div style="color: #fa0;">Extracting function signatures...</div>';
        
        try {
            const agent = await this.getResearchAgent();
            
            this.log(`Extracting signatures from ${folderPath}...`);
            
            agent.queueTask({
                type: 'extract_signatures',
                data: {
                    folderPath: folderPath,
                    options: {
                        excludePatterns: ['**/node_modules/**', '**/.git/**']
                    }
                },
                callback: (error, result) => {
                    if (error) {
                        resultsDiv.innerHTML = `<div style="color: #f44;">Error: ${error.message}</div>`;
                        this.log(`Signature extraction failed: ${error.message}`, 'error');
                    } else {
                        this.displaySignatureResults(result, resultsDiv);
                        this.log(`Extracted ${result.totalFunctions} functions from ${result.totalFiles} files`, 'success');
                    }
                }
            });
            
        } catch (error) {
            resultsDiv.innerHTML = `<div style="color: #f44;">Error: ${error.message}</div>`;
            this.log(`Signature extraction error: ${error.message}`, 'error');
        }
    };
    
    // Display signature results
    window.AgentUI.displaySignatureResults = function(results, container) {
        let html = `<div style="color: #4CAF50; margin-bottom: 10px;">Signature Extraction Summary:</div>`;
        html += `<div style="margin-bottom: 10px;">`;
        html += `<div>Total Files: ${results.totalFiles}</div>`;
        html += `<div>Total Functions: ${results.totalFunctions}</div>`;
        html += `</div>`;
        
        // Display by type
        html += `<div style="color: #4CAF50; margin: 10px 0;">Functions by Type:</div>`;
        for (const [type, funcs] of Object.entries(results.byType)) {
            if (funcs.length > 0) {
                html += `<div style="margin: 10px 0;">`;
                html += `<div style="color: #fa0;">${type} (${funcs.length}):</div>`;
                funcs.slice(0, 5).forEach(func => {
                    html += `<div class="search-result-match" style="margin-left: 10px;">`;
                    html += `<span style="color: #4CAF50;">${func.name}</span> - `;
                    html += `<span style="color: #666;">${func.file}:${func.line}</span><br>`;
                    html += `<span style="font-family: monospace; font-size: 10px;">${this.escapeHtml(func.signature)}</span>`;
                    html += `</div>`;
                });
                if (funcs.length > 5) {
                    html += `<div style="color: #666; font-size: 10px; margin-left: 10px;">...and ${funcs.length - 5} more</div>`;
                }
                html += `</div>`;
            }
        }
        
        // Show function index
        if (results.index && results.index.length > 0) {
            html += `<div style="color: #4CAF50; margin: 15px 0;">Function Index (alphabetical):</div>`;
            html += `<div style="max-height: 200px; overflow-y: auto; border: 1px solid #333; padding: 10px; margin-top: 5px;">`;
            results.index.slice(0, 50).forEach(item => {
                html += `<div style="margin: 2px 0;">`;
                html += `<span style="color: #4CAF50;">${item.name}</span> `;
                html += `<span style="color: #666; font-size: 10px;">(${item.type}) - ${item.file}:${item.line}</span>`;
                html += `</div>`;
            });
            if (results.index.length > 50) {
                html += `<div style="color: #666; font-size: 10px; margin-top: 10px;">...and ${results.index.length - 50} more functions</div>`;
            }
            html += `</div>`;
        }
        
        container.innerHTML = html;
    };
    
    // Get file icon based on extension
    window.AgentUI.getFileIcon = function(filename) {
        const ext = filename.split('.').pop().toLowerCase();
        const icons = {
            js: '📜', jsx: '⚛️', ts: '📘', tsx: '⚛️',
            java: '☕', py: '🐍', json: '📋', xml: '📄',
            html: '🌐', css: '🎨', md: '📝', txt: '📄'
        };
        return icons[ext] || '📄';
    };
    
    // Escape HTML
    window.AgentUI.escapeHtml = function(text) {
        const div = document.createElement('div');
        div.textContent = text;
        return div.innerHTML;
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
