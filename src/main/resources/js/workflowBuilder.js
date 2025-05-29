/**
 * Visual Workflow Builder for Agent Framework
 * Simple but functional drag-and-drop interface for creating workflows
 */

(function() {
    'use strict';

    window.WorkflowBuilder = {
        container: null,
        currentWorkflow: null,
        selectedNode: null,
        selectedConnection: null,
        canvas: null,
        isDragging: false,
        isConnecting: false,
        connectingFrom: null,
        scale: 1,
        offset: { x: 0, y: 0 }
    };

    /**
     * Initialize the workflow builder
     */
    WorkflowBuilder.initialize = function(containerId) {
        this.container = document.getElementById(containerId);
        if (!this.container) {
            this.container = document.createElement('div');
            this.container.id = containerId || 'workflow-builder';
            document.body.appendChild(this.container);
        }

        this.createUI();
        this.currentWorkflow = WorkflowEngine.createWorkflow('New Workflow');
        this.render();
    };

    /**
     * Create the builder UI
     */
    WorkflowBuilder.createUI = function() {
        this.container.innerHTML = `
            <div class="workflow-builder">
                <style>
                    .workflow-builder {
                        position: fixed;
                        top: 0;
                        left: 0;
                        right: 0;
                        bottom: 0;
                        display: flex;
                        background: #1a1a1a;
                        color: #fff;
                        font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                    }

                    .workflow-sidebar {
                        width: 250px;
                        background: #252525;
                        border-right: 1px solid #333;
                        display: flex;
                        flex-direction: column;
                    }

                    .workflow-main {
                        flex: 1;
                        display: flex;
                        flex-direction: column;
                    }

                    .workflow-toolbar {
                        height: 50px;
                        background: #2a2a2a;
                        border-bottom: 1px solid #333;
                        display: flex;
                        align-items: center;
                        padding: 0 20px;
                        gap: 10px;
                    }

                    .workflow-canvas-container {
                        flex: 1;
                        position: relative;
                        overflow: hidden;
                        background: #1a1a1a;
                        background-image: 
                            linear-gradient(rgba(255,255,255,0.05) 1px, transparent 1px),
                            linear-gradient(90deg, rgba(255,255,255,0.05) 1px, transparent 1px);
                        background-size: 20px 20px;
                    }

                    .workflow-canvas {
                        position: absolute;
                        width: 100%;
                        height: 100%;
                    }

                    .workflow-node {
                        position: absolute;
                        background: #2a2a2a;
                        border: 2px solid #444;
                        border-radius: 8px;
                        padding: 10px;
                        min-width: 150px;
                        cursor: move;
                        user-select: none;
                        transition: transform 0.1s, box-shadow 0.1s;
                    }

                    .workflow-node:hover {
                        border-color: #4CAF50;
                        box-shadow: 0 0 10px rgba(76, 175, 80, 0.3);
                    }

                    .workflow-node.selected {
                        border-color: #4CAF50;
                        box-shadow: 0 0 20px rgba(76, 175, 80, 0.5);
                    }

                    .workflow-node.running {
                        border-color: #FF9800;
                        animation: pulse 1s infinite;
                    }

                    .workflow-node.completed {
                        border-color: #4CAF50;
                    }

                    .workflow-node.error {
                        border-color: #f44336;
                    }

                    @keyframes pulse {
                        0% { box-shadow: 0 0 0 0 rgba(255, 152, 0, 0.7); }
                        70% { box-shadow: 0 0 0 10px rgba(255, 152, 0, 0); }
                        100% { box-shadow: 0 0 0 0 rgba(255, 152, 0, 0); }
                    }

                    .node-header {
                        font-weight: bold;
                        margin-bottom: 5px;
                        display: flex;
                        align-items: center;
                        gap: 5px;
                    }

                    .node-type {
                        font-size: 11px;
                        color: #888;
                    }

                    .node-ports {
                        position: relative;
                        margin: 5px -10px;
                    }

                    .node-port {
                        width: 12px;
                        height: 12px;
                        background: #444;
                        border: 2px solid #666;
                        border-radius: 50%;
                        position: absolute;
                        cursor: crosshair;
                        transition: all 0.2s;
                    }

                    .node-port:hover {
                        background: #4CAF50;
                        border-color: #4CAF50;
                        transform: scale(1.2);
                    }

                    .node-port.input {
                        left: -17px;
                    }

                    .node-port.output {
                        right: -17px;
                    }

                    .node-port.connected {
                        background: #4CAF50;
                    }

                    .sidebar-section {
                        padding: 15px;
                        border-bottom: 1px solid #333;
                    }

                    .sidebar-title {
                        font-size: 14px;
                        font-weight: bold;
                        margin-bottom: 10px;
                        color: #4CAF50;
                    }

                    .node-palette {
                        display: flex;
                        flex-direction: column;
                        gap: 8px;
                    }

                    .palette-item {
                        background: #333;
                        border: 1px solid #444;
                        border-radius: 4px;
                        padding: 8px 12px;
                        cursor: grab;
                        transition: all 0.2s;
                        font-size: 13px;
                    }

                    .palette-item:hover {
                        background: #3a3a3a;
                        border-color: #4CAF50;
                    }

                    .palette-item:active {
                        cursor: grabbing;
                    }

                    .properties-panel {
                        flex: 1;
                        overflow-y: auto;
                    }

                    .property-group {
                        margin-bottom: 15px;
                    }

                    .property-label {
                        font-size: 12px;
                        color: #aaa;
                        margin-bottom: 5px;
                    }

                    .property-input {
                        width: 100%;
                        background: #1a1a1a;
                        border: 1px solid #444;
                        color: #fff;
                        padding: 5px 8px;
                        border-radius: 4px;
                        font-size: 12px;
                    }

                    .property-input:focus {
                        outline: none;
                        border-color: #4CAF50;
                    }

                    textarea.property-input {
                        min-height: 60px;
                        resize: vertical;
                        font-family: 'Monaco', 'Consolas', monospace;
                    }

                    .workflow-connection {
                        position: absolute;
                        pointer-events: none;
                        z-index: 1;
                    }

                    .connection-path {
                        stroke: #666;
                        stroke-width: 2;
                        fill: none;
                        pointer-events: stroke;
                        cursor: pointer;
                        transition: stroke 0.2s;
                    }

                    .connection-path:hover {
                        stroke: #4CAF50;
                        stroke-width: 3;
                    }

                    .connection-path.selected {
                        stroke: #4CAF50;
                        stroke-width: 3;
                    }

                    .temp-connection {
                        stroke: #4CAF50;
                        stroke-dasharray: 5,5;
                        animation: dash 0.5s linear infinite;
                    }

                    @keyframes dash {
                        to { stroke-dashoffset: -10; }
                    }

                    .toolbar-btn {
                        background: #333;
                        border: 1px solid #444;
                        color: #fff;
                        padding: 6px 12px;
                        border-radius: 4px;
                        cursor: pointer;
                        font-size: 13px;
                        transition: all 0.2s;
                    }

                    .toolbar-btn:hover {
                        background: #3a3a3a;
                        border-color: #4CAF50;
                    }

                    .toolbar-btn:active {
                        transform: translateY(1px);
                    }

                    .toolbar-separator {
                        width: 1px;
                        height: 20px;
                        background: #444;
                        margin: 0 10px;
                    }

                    .workflow-name {
                        font-weight: bold;
                        margin-right: 20px;
                    }

                    .status-indicator {
                        display: inline-block;
                        width: 8px;
                        height: 8px;
                        border-radius: 50%;
                        margin-right: 5px;
                    }

                    .status-idle { background: #666; }
                    .status-running { background: #FF9800; }
                    .status-completed { background: #4CAF50; }
                    .status-error { background: #f44336; }
                </style>

                <div class="workflow-sidebar">
                    <div class="sidebar-section">
                        <div class="sidebar-title">🎯 Agent Nodes</div>
                        <div class="node-palette" id="agent-palette"></div>
                    </div>
                    
                    <div class="sidebar-section">
                        <div class="sidebar-title">🔧 Utility Nodes</div>
                        <div class="node-palette">
                            <div class="palette-item" data-node-type="input">📥 Input</div>
                            <div class="palette-item" data-node-type="output">📤 Output</div>
                            <div class="palette-item" data-node-type="composer">🔀 Composer</div>
                        </div>
                    </div>
                    
                    <div class="sidebar-section properties-panel">
                        <div class="sidebar-title">⚙️ Properties</div>
                        <div id="properties-content"></div>
                    </div>
                </div>

                <div class="workflow-main">
                    <div class="workflow-toolbar">
                        <span class="workflow-name" id="workflow-name">New Workflow</span>
                        <button class="toolbar-btn" onclick="WorkflowBuilder.runWorkflow()">▶️ Run</button>
                        <button class="toolbar-btn" onclick="WorkflowBuilder.stopWorkflow()">⏹️ Stop</button>
                        <div class="toolbar-separator"></div>
                        <button class="toolbar-btn" onclick="WorkflowBuilder.saveWorkflow()">💾 Save</button>
                        <button class="toolbar-btn" onclick="WorkflowBuilder.loadWorkflowDialog()">📁 Load</button>
                        <div class="toolbar-separator"></div>
                        <button class="toolbar-btn" onclick="WorkflowBuilder.clearWorkflow()">🗑️ Clear</button>
                        <button class="toolbar-btn" onclick="WorkflowBuilder.autoLayout()">📐 Auto Layout</button>
                        <div class="toolbar-separator"></div>
                        <span id="workflow-status"><span class="status-indicator status-idle"></span>Idle</span>
                    </div>
                    
                    <div class="workflow-canvas-container" id="canvas-container">
                        <svg class="workflow-canvas" id="workflow-canvas">
                            <g id="connections-layer"></g>
                        </svg>
                        <div id="nodes-layer"></div>
                    </div>
                </div>
            </div>
        `;

        // Initialize components
        this.canvas = document.getElementById('workflow-canvas');
        this.initializeAgentPalette();
        this.initializeEventHandlers();
    };

    /**
     * Initialize the agent palette
     */
    WorkflowBuilder.initializeAgentPalette = function() {
        const palette = document.getElementById('agent-palette');
        
        // Add agent roles to palette
        for (const [key, role] of Object.entries(window.AgentFramework.AgentRoles)) {
            const item = document.createElement('div');
            item.className = 'palette-item';
            item.dataset.nodeType = 'agent';
            item.dataset.agentRole = key;
            item.textContent = `🤖 ${role.name}`;
            item.draggable = true;
            palette.appendChild(item);
        }
    };

    /**
     * Initialize event handlers
     */
    WorkflowBuilder.initializeEventHandlers = function() {
        const container = document.getElementById('canvas-container');
        const nodesLayer = document.getElementById('nodes-layer');
        
        // Palette drag and drop
        document.querySelectorAll('.palette-item').forEach(item => {
            item.addEventListener('dragstart', (e) => {
                e.dataTransfer.setData('nodeType', item.dataset.nodeType);
                e.dataTransfer.setData('agentRole', item.dataset.agentRole || '');
            });
        });
        
        // Canvas drop
        container.addEventListener('dragover', (e) => {
            e.preventDefault();
        });
        
        container.addEventListener('drop', (e) => {
            e.preventDefault();
            
            const nodeType = e.dataTransfer.getData('nodeType');
            const agentRole = e.dataTransfer.getData('agentRole');
            
            const rect = container.getBoundingClientRect();
            const x = e.clientX - rect.left;
            const y = e.clientY - rect.top;
            
            this.addNode(nodeType, x, y, agentRole);
        });
        
        // Node selection
        nodesLayer.addEventListener('click', (e) => {
            if (e.target.classList.contains('workflow-node')) {
                this.selectNode(e.target.dataset.nodeId);
            }
        });
        
        // Port connections
        nodesLayer.addEventListener('mousedown', (e) => {
            if (e.target.classList.contains('node-port') && e.target.classList.contains('output')) {
                this.startConnection(e.target);
            }
        });
        
        container.addEventListener('mousemove', (e) => {
            if (this.isConnecting && this.connectingFrom) {
                this.updateTempConnection(e);
            }
        });
        
        container.addEventListener('mouseup', (e) => {
            if (this.isConnecting) {
                this.endConnection(e);
            }
        });
        
        // Connection selection
        this.canvas.addEventListener('click', (e) => {
            if (e.target.classList.contains('connection-path')) {
                this.selectConnection(e.target.dataset.connectionId);
            }
        });
        
        // Delete key
        document.addEventListener('keydown', (e) => {
            if (e.key === 'Delete' || e.key === 'Backspace') {
                if (this.selectedNode) {
                    this.deleteNode(this.selectedNode);
                } else if (this.selectedConnection) {
                    this.deleteConnection(this.selectedConnection);
                }
            }
        });
    };

    /**
     * Add a node to the workflow
     */
    WorkflowBuilder.addNode = function(type, x, y, agentRole) {
        let node;
        
        switch (type) {
            case 'agent':
                const role = window.AgentFramework.AgentRoles[agentRole];
                if (!role) return;
                node = WorkflowEngine.createAgentNode(role);
                break;
            case 'composer':
                node = WorkflowEngine.createComposerNode();
                break;
            case 'input':
                node = WorkflowEngine.createInputNode();
                break;
            case 'output':
                node = WorkflowEngine.createOutputNode();
                break;
            default:
                return;
        }
        
        node.setPosition(x, y);
        this.currentWorkflow.addNode(node);
        this.renderNode(node);
        this.selectNode(node.id);
    };

    /**
     * Render a node
     */
    WorkflowBuilder.renderNode = function(node) {
        const nodesLayer = document.getElementById('nodes-layer');
        
        const nodeEl = document.createElement('div');
        nodeEl.className = 'workflow-node';
        nodeEl.dataset.nodeId = node.id;
        nodeEl.style.left = node.position.x + 'px';
        nodeEl.style.top = node.position.y + 'px';
        
        // Node content
        let icon = '📦';
        if (node.type === 'agent') icon = '🤖';
        else if (node.type === 'composer') icon = '🔀';
        else if (node.type === 'input') icon = '📥';
        else if (node.type === 'output') icon = '📤';
        
        nodeEl.innerHTML = `
            <div class="node-header">
                <span>${icon}</span>
                <span>${node.name}</span>
            </div>
            <div class="node-type">${node.type}</div>
            <div class="node-ports" id="ports-${node.id}"></div>
        `;
        
        // Add ports
        this.renderPorts(node, nodeEl.querySelector('.node-ports'));
        
        // Make draggable
        this.makeDraggable(nodeEl);
        
        nodesLayer.appendChild(nodeEl);
    };

    /**
     * Render node ports
     */
    WorkflowBuilder.renderPorts = function(node, container) {
        let topOffset = 30;
        
        // Input ports
        const inputs = node.getRequiredInputs();
        inputs.forEach((input, index) => {
            const port = document.createElement('div');
            port.className = 'node-port input';
            port.dataset.nodeId = node.id;
            port.dataset.portName = input;
            port.dataset.portType = 'input';
            port.style.top = topOffset + (index * 20) + 'px';
            port.title = input;
            container.appendChild(port);
        });
        
        // Output ports
        const outputs = node.getOutputPorts();
        outputs.forEach((output, index) => {
            const port = document.createElement('div');
            port.className = 'node-port output';
            port.dataset.nodeId = node.id;
            port.dataset.portName = output;
            port.dataset.portType = 'output';
            port.style.top = topOffset + (index * 20) + 'px';
            port.title = output;
            container.appendChild(port);
        });
    };

    /**
     * Make node draggable
     */
    WorkflowBuilder.makeDraggable = function(element) {
        let startX, startY, initialX, initialY;
        
        const onMouseDown = (e) => {
            if (e.target.classList.contains('node-port')) return;
            
            e.preventDefault();
            startX = e.clientX;
            startY = e.clientY;
            initialX = element.offsetLeft;
            initialY = element.offsetTop;
            
            document.addEventListener('mousemove', onMouseMove);
            document.addEventListener('mouseup', onMouseUp);
        };
        
        const onMouseMove = (e) => {
            const dx = e.clientX - startX;
            const dy = e.clientY - startY;
            
            element.style.left = (initialX + dx) + 'px';
            element.style.top = (initialY + dy) + 'px';
            
            // Update node position
            const node = this.currentWorkflow.nodes.get(element.dataset.nodeId);
            if (node) {
                node.setPosition(initialX + dx, initialY + dy);
            }
            
            // Update connections
            this.updateConnections();
        };
        
        const onMouseUp = () => {
            document.removeEventListener('mousemove', onMouseMove);
            document.removeEventListener('mouseup', onMouseUp);
        };
        
        element.addEventListener('mousedown', onMouseDown);
    };

    /**
     * Start creating a connection
     */
    WorkflowBuilder.startConnection = function(portElement) {
        this.isConnecting = true;
        this.connectingFrom = portElement;
        
        // Create temp connection line
        const svg = document.getElementById('connections-layer');
        const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
        path.id = 'temp-connection';
        path.classList.add('connection-path', 'temp-connection');
        svg.appendChild(path);
    };

    /**
     * Update temporary connection while dragging
     */
    WorkflowBuilder.updateTempConnection = function(e) {
        const rect = this.canvas.getBoundingClientRect();
        const fromRect = this.connectingFrom.getBoundingClientRect();
        
        const x1 = fromRect.left + fromRect.width / 2 - rect.left;
        const y1 = fromRect.top + fromRect.height / 2 - rect.top;
        const x2 = e.clientX - rect.left;
        const y2 = e.clientY - rect.top;
        
        const path = document.getElementById('temp-connection');
        if (path) {
            path.setAttribute('d', this.createConnectionPath(x1, y1, x2, y2));
        }
    };

    /**
     * End connection creation
     */
    WorkflowBuilder.endConnection = function(e) {
        this.isConnecting = false;
        
        // Remove temp connection
        const tempPath = document.getElementById('temp-connection');
        if (tempPath) tempPath.remove();
        
        // Check if we're over an input port
        const target = document.elementFromPoint(e.clientX, e.clientY);
        if (target && target.classList.contains('node-port') && target.classList.contains('input')) {
            // Create connection
            const fromNodeId = this.connectingFrom.dataset.nodeId;
            const fromPort = this.connectingFrom.dataset.portName;
            const toNodeId = target.dataset.nodeId;
            const toPort = target.dataset.portName;
            
            const connection = this.currentWorkflow.connect(fromNodeId, fromPort, toNodeId, toPort);
            this.renderConnection(connection);
            
            // Mark ports as connected
            this.connectingFrom.classList.add('connected');
            target.classList.add('connected');
        }
        
        this.connectingFrom = null;
    };

    /**
     * Render a connection
     */
    WorkflowBuilder.renderConnection = function(connection) {
        const svg = document.getElementById('connections-layer');
        
        const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
        path.classList.add('connection-path');
        path.dataset.connectionId = connection.id;
        
        svg.appendChild(path);
        this.updateConnection(connection, path);
    };

    /**
     * Update connection path
     */
    WorkflowBuilder.updateConnection = function(connection, pathElement) {
        const fromPort = document.querySelector(
            `.node-port[data-node-id="${connection.from.nodeId}"][data-port-name="${connection.from.port}"]`
        );
        const toPort = document.querySelector(
            `.node-port[data-node-id="${connection.to.nodeId}"][data-port-name="${connection.to.port}"]`
        );
        
        if (!fromPort || !toPort) return;
        
        const rect = this.canvas.getBoundingClientRect();
        const fromRect = fromPort.getBoundingClientRect();
        const toRect = toPort.getBoundingClientRect();
        
        const x1 = fromRect.left + fromRect.width / 2 - rect.left;
        const y1 = fromRect.top + fromRect.height / 2 - rect.top;
        const x2 = toRect.left + toRect.width / 2 - rect.left;
        const y2 = toRect.top + toRect.height / 2 - rect.top;
        
        pathElement.setAttribute('d', this.createConnectionPath(x1, y1, x2, y2));
    };

    /**
     * Create bezier path for connection
     */
    WorkflowBuilder.createConnectionPath = function(x1, y1, x2, y2) {
        const dx = x2 - x1;
        const dy = y2 - y1;
        const offset = Math.min(Math.abs(dx) / 2, 100);
        
        return `M ${x1} ${y1} C ${x1 + offset} ${y1}, ${x2 - offset} ${y2}, ${x2} ${y2}`;
    };

    /**
     * Update all connections
     */
    WorkflowBuilder.updateConnections = function() {
        const paths = document.querySelectorAll('.connection-path:not(.temp-connection)');
        paths.forEach(path => {
            const connectionId = path.dataset.connectionId;
            const connection = this.currentWorkflow.connections.find(c => c.id === connectionId);
            if (connection) {
                this.updateConnection(connection, path);
            }
        });
    };

    /**
     * Select a node
     */
    WorkflowBuilder.selectNode = function(nodeId) {
        // Deselect all
        document.querySelectorAll('.workflow-node').forEach(n => n.classList.remove('selected'));
        document.querySelectorAll('.connection-path').forEach(p => p.classList.remove('selected'));
        
        this.selectedNode = nodeId;
        this.selectedConnection = null;
        
        // Select node
        const nodeEl = document.querySelector(`[data-node-id="${nodeId}"]`);
        if (nodeEl) {
            nodeEl.classList.add('selected');
        }
        
        // Show properties
        this.showNodeProperties(nodeId);
    };

    /**
     * Select a connection
     */
    WorkflowBuilder.selectConnection = function(connectionId) {
        // Deselect all
        document.querySelectorAll('.workflow-node').forEach(n => n.classList.remove('selected'));
        document.querySelectorAll('.connection-path').forEach(p => p.classList.remove('selected'));
        
        this.selectedNode = null;
        this.selectedConnection = connectionId;
        
        // Select connection
        const pathEl = document.querySelector(`[data-connection-id="${connectionId}"]`);
        if (pathEl) {
            pathEl.classList.add('selected');
        }
    };

    /**
     * Show node properties
     */
    WorkflowBuilder.showNodeProperties = function(nodeId) {
        const node = this.currentWorkflow.nodes.get(nodeId);
        if (!node) return;
        
        const container = document.getElementById('properties-content');
        
        let html = `
            <div class="property-group">
                <div class="property-label">Name</div>
                <input type="text" class="property-input" id="prop-name" value="${node.name}" />
            </div>
        `;
        
        // Node-specific properties
        if (node.type === 'agent') {
            html += `
                <div class="property-group">
                    <div class="property-label">Task Type</div>
                    <select class="property-input" id="prop-taskType">
                        <option value="generate">Generate</option>
                        <option value="review">Review</option>
                        <option value="analyze">Analyze</option>
                        <option value="process">Process</option>
                    </select>
                </div>
                <div class="property-group">
                    <div class="property-label">Prompt</div>
                    <textarea class="property-input" id="prop-prompt" placeholder="Enter prompt for the agent...">${node.config.prompt || ''}</textarea>
                </div>
            `;
        } else if (node.type === 'composer') {
            html += `
                <div class="property-group">
                    <div class="property-label">Composer Function</div>
                    <textarea class="property-input" id="prop-composerFunction" placeholder="// JavaScript function to combine inputs
// Available: inputs (object), context (object)
// Return: combined result

return Object.assign({}, ...Object.values(inputs));">${node.config.composerFunction || ''}</textarea>
                </div>
            `;
        } else if (node.type === 'input') {
            html += `
                <div class="property-group">
                    <div class="property-label">Input Source</div>
                    <select class="property-input" id="prop-source">
                        <option value="value">Static Value</option>
                        <option value="context">From Context</option>
                    </select>
                </div>
                <div class="property-group">
                    <div class="property-label">Value/Key</div>
                    <input type="text" class="property-input" id="prop-value" value="${node.config.value || node.config.contextKey || ''}" />
                </div>
            `;
        } else if (node.type === 'output') {
            html += `
                <div class="property-group">
                    <div class="property-label">Output Name</div>
                    <input type="text" class="property-input" id="prop-outputName" value="${node.config.name || ''}" />
                </div>
            `;
        }
        
        container.innerHTML = html;
        
        // Add change listeners
        container.querySelectorAll('.property-input').forEach(input => {
            input.addEventListener('change', () => this.updateNodeProperties(nodeId));
        });
    };

    /**
     * Update node properties from UI
     */
    WorkflowBuilder.updateNodeProperties = function(nodeId) {
        const node = this.currentWorkflow.nodes.get(nodeId);
        if (!node) return;
        
        // Update name
        const nameInput = document.getElementById('prop-name');
        if (nameInput) {
            node.name = nameInput.value;
            document.querySelector(`[data-node-id="${nodeId}"] .node-header span:last-child`).textContent = node.name;
        }
        
        // Update config based on node type
        if (node.type === 'agent') {
            const taskType = document.getElementById('prop-taskType');
            const prompt = document.getElementById('prop-prompt');
            if (taskType) node.config.taskType = taskType.value;
            if (prompt) node.config.prompt = prompt.value;
        } else if (node.type === 'composer') {
            const func = document.getElementById('prop-composerFunction');
            if (func) node.config.composerFunction = func.value;
        } else if (node.type === 'input') {
            const source = document.getElementById('prop-source');
            const value = document.getElementById('prop-value');
            if (source) node.config.source = source.value;
            if (value) {
                if (source.value === 'context') {
                    node.config.contextKey = value.value;
                } else {
                    node.config.value = value.value;
                }
            }
        } else if (node.type === 'output') {
            const outputName = document.getElementById('prop-outputName');
            if (outputName) node.config.name = outputName.value;
        }
    };

    /**
     * Delete a node
     */
    WorkflowBuilder.deleteNode = function(nodeId) {
        this.currentWorkflow.removeNode(nodeId);
        
        // Remove from DOM
        const nodeEl = document.querySelector(`[data-node-id="${nodeId}"]`);
        if (nodeEl) nodeEl.remove();
        
        // Remove connections
        document.querySelectorAll('.connection-path').forEach(path => {
            const connectionId = path.dataset.connectionId;
            const connection = this.currentWorkflow.connections.find(c => c.id === connectionId);
            if (!connection || connection.from.nodeId === nodeId || connection.to.nodeId === nodeId) {
                path.remove();
            }
        });
        
        this.selectedNode = null;
        document.getElementById('properties-content').innerHTML = '';
    };

    /**
     * Delete a connection
     */
    WorkflowBuilder.deleteConnection = function(connectionId) {
        this.currentWorkflow.disconnect(connectionId);
        
        // Remove from DOM
        const pathEl = document.querySelector(`[data-connection-id="${connectionId}"]`);
        if (pathEl) pathEl.remove();
        
        this.selectedConnection = null;
    };

    /**
     * Run the workflow
     */
    WorkflowBuilder.runWorkflow = async function() {
        try {
            // Update status
            this.updateStatus('running');
            
            // Reset node states
            this.currentWorkflow.nodes.forEach(node => {
                node.status = 'idle';
                node.result = null;
                node.error = null;
                this.updateNodeStatus(node.id, 'idle');
            });
            
            // Create custom executor to track progress
            const executor = new WorkflowEngine.WorkflowExecutor(this.currentWorkflow);
            
            // Override executeNode to update UI
            const originalExecuteNode = executor.executeNode.bind(executor);
            executor.executeNode = async (nodeId) => {
                this.updateNodeStatus(nodeId, 'running');
                try {
                    const result = await originalExecuteNode(nodeId);
                    this.updateNodeStatus(nodeId, 'completed');
                    
                    // Show result in properties panel if node is selected
                    if (this.selectedNode === nodeId) {
                        const resultDiv = document.createElement('div');
                        resultDiv.className = 'property-group';
                        resultDiv.innerHTML = `
                            <div class="property-label">Result</div>
                            <pre class="property-input" style="background: #0a0a0a; color: #4CAF50; min-height: 100px;">
${JSON.stringify(result, null, 2)}</pre>
                        `;
                        document.getElementById('properties-content').appendChild(resultDiv);
                    }
                    
                    return result;
                } catch (error) {
                    this.updateNodeStatus(nodeId, 'error');
                    throw error;
                }
            };
            
            // Execute workflow
            const results = await executor.execute();
            
            // Update status
            this.updateStatus('completed');
            
            // Show results
            console.log('Workflow completed:', results);
            
            // Create results summary
            let summary = 'Workflow completed successfully!\n\n';
            for (const [key, value] of Object.entries(results)) {
                summary += `${key}:\n${JSON.stringify(value, null, 2)}\n\n`;
            }
            
            alert(summary);
            
        } catch (error) {
            console.error('Workflow execution failed:', error);
            this.updateStatus('error');
            alert(`Workflow failed: ${error.message}`);
        }
    };

    /**
     * Stop workflow execution
     */
    WorkflowBuilder.stopWorkflow = function() {
        // TODO: Implement workflow cancellation
        this.updateStatus('idle');
    };

    /**
     * Update workflow status
     */
    WorkflowBuilder.updateStatus = function(status) {
        const statusEl = document.getElementById('workflow-status');
        const indicator = statusEl.querySelector('.status-indicator');
        
        indicator.className = `status-indicator status-${status}`;
        statusEl.innerHTML = indicator.outerHTML + status.charAt(0).toUpperCase() + status.slice(1);
    };

    /**
     * Update node status
     */
    WorkflowBuilder.updateNodeStatus = function(nodeId, status) {
        const nodeEl = document.querySelector(`[data-node-id="${nodeId}"]`);
        if (nodeEl) {
            nodeEl.classList.remove('idle', 'running', 'completed', 'error');
            nodeEl.classList.add(status);
        }
    };

    /**
     * Save workflow
     */
    WorkflowBuilder.saveWorkflow = function() {
        const json = this.currentWorkflow.toJSON();
        const blob = new Blob([JSON.stringify(json, null, 2)], { type: 'application/json' });
        const url = URL.createObjectURL(blob);
        
        const a = document.createElement('a');
        a.href = url;
        a.download = `${this.currentWorkflow.name}.workflow.json`;
        a.click();
        
        URL.revokeObjectURL(url);
    };

    /**
     * Load workflow dialog
     */
    WorkflowBuilder.loadWorkflowDialog = function() {
        const input = document.createElement('input');
        input.type = 'file';
        input.accept = '.json,.workflow.json';
        
        input.onchange = (e) => {
            const file = e.target.files[0];
            if (!file) return;
            
            const reader = new FileReader();
            reader.onload = (e) => {
                try {
                    const json = JSON.parse(e.target.result);
                    this.loadWorkflow(json);
                } catch (error) {
                    alert('Failed to load workflow: ' + error.message);
                }
            };
            reader.readAsText(file);
        };
        
        input.click();
    };

    /**
     * Load workflow from JSON
     */
    WorkflowBuilder.loadWorkflow = function(json) {
        this.clearWorkflow();
        
        this.currentWorkflow = WorkflowEngine.loadWorkflow(json);
        document.getElementById('workflow-name').textContent = this.currentWorkflow.name;
        
        // Render nodes
        this.currentWorkflow.nodes.forEach(node => {
            this.renderNode(node);
        });
        
        // Render connections
        this.currentWorkflow.connections.forEach(connection => {
            this.renderConnection(connection);
            
            // Mark ports as connected
            const fromPort = document.querySelector(
                `.node-port[data-node-id="${connection.from.nodeId}"][data-port-name="${connection.from.port}"]`
            );
            const toPort = document.querySelector(
                `.node-port[data-node-id="${connection.to.nodeId}"][data-port-name="${connection.to.port}"]`
            );
            if (fromPort) fromPort.classList.add('connected');
            if (toPort) toPort.classList.add('connected');
        });
    };

    /**
     * Clear workflow
     */
    WorkflowBuilder.clearWorkflow = function() {
        if (!confirm('Clear the current workflow?')) return;
        
        // Clear DOM
        document.getElementById('nodes-layer').innerHTML = '';
        document.getElementById('connections-layer').innerHTML = '';
        document.getElementById('properties-content').innerHTML = '';
        
        // Create new workflow
        this.currentWorkflow = WorkflowEngine.createWorkflow('New Workflow');
        document.getElementById('workflow-name').textContent = 'New Workflow';
        
        this.selectedNode = null;
        this.selectedConnection = null;
    };

    /**
     * Auto layout nodes
     */
    WorkflowBuilder.autoLayout = function() {
        // Simple left-to-right layout based on dependencies
        const layers = [];
        const positioned = new Set();
        
        // Find nodes with no inputs (start nodes)
        const startNodes = [];
        this.currentWorkflow.nodes.forEach((node, nodeId) => {
            if (this.currentWorkflow.getIncomingConnections(nodeId).length === 0) {
                startNodes.push(nodeId);
            }
        });
        
        // BFS to assign layers
        const queue = startNodes.map(id => ({ id, layer: 0 }));
        
        while (queue.length > 0) {
            const { id, layer } = queue.shift();
            
            if (positioned.has(id)) continue;
            positioned.add(id);
            
            if (!layers[layer]) layers[layer] = [];
            layers[layer].push(id);
            
            // Add connected nodes to queue
            const outgoing = this.currentWorkflow.getOutgoingConnections(id);
            outgoing.forEach(conn => {
                queue.push({ id: conn.to.nodeId, layer: layer + 1 });
            });
        }
        
        // Position nodes
        const xSpacing = 200;
        const ySpacing = 100;
        const startX = 50;
        const startY = 50;
        
        layers.forEach((layer, layerIndex) => {
            layer.forEach((nodeId, nodeIndex) => {
                const node = this.currentWorkflow.nodes.get(nodeId);
                if (node) {
                    const x = startX + layerIndex * xSpacing;
                    const y = startY + nodeIndex * ySpacing;
                    
                    node.setPosition(x, y);
                    
                    const nodeEl = document.querySelector(`[data-node-id="${nodeId}"]`);
                    if (nodeEl) {
                        nodeEl.style.left = x + 'px';
                        nodeEl.style.top = y + 'px';
                    }
                }
            });
        });
        
        // Update connections
        this.updateConnections();
    };

    /**
     * Render the entire workflow
     */
    WorkflowBuilder.render = function() {
        // Clear and re-render everything
        document.getElementById('nodes-layer').innerHTML = '';
        document.getElementById('connections-layer').innerHTML = '';
        
        // Render nodes
        this.currentWorkflow.nodes.forEach(node => {
            this.renderNode(node);
        });
        
        // Render connections
        this.currentWorkflow.connections.forEach(connection => {
            this.renderConnection(connection);
        });
    };

    // Initialize on load
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', () => {
            // Auto-initialize if container exists
            if (document.getElementById('workflow-builder')) {
                WorkflowBuilder.initialize('workflow-builder');
            }
        });
    }

    console.log('Workflow Builder loaded');

})();
