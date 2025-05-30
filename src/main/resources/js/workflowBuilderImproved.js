/**
 * Improved Visual Workflow Builder for Agent Framework
 * Enhanced drag & drop UX with simplified agent list (Code & Research only)
 * User-ready with better visual feedback and error handling
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
        offset: { x: 0, y: 0 },
        initialized: false,
        draggedElement: null,
        dropZone: null,
        autoSaveTimer: null
    };

    // Simplified agent list - only Code & Research agents
    const SIMPLIFIED_AGENTS = {
        CODE_GENERATOR: {
            id: 'code_generator',
            name: 'Code Agent',
            icon: '💻',
            description: 'Generates, refactors, and analyzes code',
            capabilities: ['generate', 'refactor', 'optimize', 'analyze_code', 'review', 'debug']
        },
        RESEARCH: {
            id: 'research',
            name: 'Research Agent',
            icon: '🔍',
            description: 'Searches code, finds references, and analyzes usage',
            capabilities: ['search_text', 'find_syntax', 'analyze_usage', 'find_references']
        }
    };

    /**
     * Initialize the workflow builder
     */
    WorkflowBuilder.initialize = function(containerId) {
        if (this.initialized) {
            console.log('WorkflowBuilder already initialized');
            return;
        }
        
        this.container = document.getElementById(containerId);
        if (!this.container) {
            this.container = document.createElement('div');
            this.container.id = containerId || 'workflow-builder';
            document.body.appendChild(this.container);
        }

        this.createUI();
        this.currentWorkflow = WorkflowEngine.createWorkflow('New Workflow');
        this.render();
        this.initialized = true;
        
        // Enable auto-save
        this.enableAutoSave();
        
        // Show welcome message
        this.showWelcomeMessage();
    };

    /**
     * Create the builder UI with improved styling
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
                        background: #0d1117;
                        color: #c9d1d9;
                        font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                    }

                    .workflow-sidebar {
                        width: 280px;
                        background: #161b22;
                        border-right: 1px solid #30363d;
                        display: flex;
                        flex-direction: column;
                        transition: width 0.3s ease;
                    }

                    .workflow-main {
                        flex: 1;
                        display: flex;
                        flex-direction: column;
                        position: relative;
                    }

                    .workflow-toolbar {
                        height: 56px;
                        background: #161b22;
                        border-bottom: 1px solid #30363d;
                        display: flex;
                        align-items: center;
                        padding: 0 20px;
                        gap: 12px;
                    }

                    .workflow-canvas-container {
                        flex: 1;
                        position: relative;
                        overflow: hidden;
                        background: #0d1117;
                        background-image: 
                            radial-gradient(circle at 1px 1px, #30363d 1px, transparent 1px);
                        background-size: 20px 20px;
                    }

                    .workflow-canvas {
                        position: absolute;
                        width: 100%;
                        height: 100%;
                        cursor: grab;
                    }

                    .workflow-canvas.dragging {
                        cursor: grabbing;
                    }

                    .workflow-node {
                        position: absolute;
                        background: #161b22;
                        border: 2px solid #30363d;
                        border-radius: 12px;
                        padding: 16px;
                        min-width: 180px;
                        cursor: move;
                        user-select: none;
                        transition: all 0.2s ease;
                        box-shadow: 0 2px 8px rgba(0, 0, 0, 0.3);
                    }

                    .workflow-node:hover {
                        border-color: #58a6ff;
                        box-shadow: 0 4px 16px rgba(88, 166, 255, 0.2);
                        transform: translateY(-2px);
                    }

                    .workflow-node.selected {
                        border-color: #58a6ff;
                        box-shadow: 0 0 0 3px rgba(88, 166, 255, 0.3);
                    }

                    .workflow-node.dragging {
                        opacity: 0.8;
                        cursor: grabbing;
                        z-index: 1000;
                    }

                    .workflow-node.running {
                        border-color: #f0883e;
                        animation: pulse 1.5s infinite;
                    }

                    .workflow-node.completed {
                        border-color: #3fb950;
                    }

                    .workflow-node.error {
                        border-color: #f85149;
                        animation: shake 0.5s;
                    }

                    @keyframes pulse {
                        0% { box-shadow: 0 0 0 0 rgba(240, 136, 62, 0.7); }
                        70% { box-shadow: 0 0 0 10px rgba(240, 136, 62, 0); }
                        100% { box-shadow: 0 0 0 0 rgba(240, 136, 62, 0); }
                    }

                    @keyframes shake {
                        0%, 100% { transform: translateX(0); }
                        25% { transform: translateX(-5px); }
                        75% { transform: translateX(5px); }
                    }

                    .node-header {
                        font-weight: 600;
                        margin-bottom: 8px;
                        display: flex;
                        align-items: center;
                        gap: 8px;
                        font-size: 16px;
                    }

                    .node-icon {
                        font-size: 20px;
                    }

                    .node-type {
                        font-size: 12px;
                        color: #8b949e;
                        margin-bottom: 8px;
                    }

                    .node-ports {
                        position: relative;
                        margin: 8px -16px;
                    }

                    .node-port {
                        width: 16px;
                        height: 16px;
                        background: #30363d;
                        border: 2px solid #58a6ff;
                        border-radius: 50%;
                        position: absolute;
                        cursor: crosshair;
                        transition: all 0.2s;
                    }

                    .node-port:hover {
                        background: #58a6ff;
                        transform: scale(1.3);
                        box-shadow: 0 0 8px rgba(88, 166, 255, 0.6);
                    }

                    .node-port.input {
                        left: -25px;
                    }

                    .node-port.output {
                        right: -25px;
                    }

                    .node-port.connected {
                        background: #3fb950;
                        border-color: #3fb950;
                    }

                    .node-port.connecting {
                        background: #f0883e;
                        border-color: #f0883e;
                        animation: pulse-ring 1s infinite;
                    }

                    @keyframes pulse-ring {
                        0% { box-shadow: 0 0 0 0 rgba(240, 136, 62, 0.7); }
                        70% { box-shadow: 0 0 0 6px rgba(240, 136, 62, 0); }
                        100% { box-shadow: 0 0 0 0 rgba(240, 136, 62, 0); }
                    }

                    .sidebar-section {
                        padding: 20px;
                        border-bottom: 1px solid #30363d;
                    }

                    .sidebar-title {
                        font-size: 14px;
                        font-weight: 600;
                        margin-bottom: 16px;
                        color: #58a6ff;
                        text-transform: uppercase;
                        letter-spacing: 0.5px;
                    }

                    .node-palette {
                        display: flex;
                        flex-direction: column;
                        gap: 12px;
                    }

                    .palette-item {
                        background: #0d1117;
                        border: 2px solid #30363d;
                        border-radius: 8px;
                        padding: 12px 16px;
                        cursor: grab;
                        transition: all 0.2s;
                        font-size: 14px;
                        display: flex;
                        align-items: center;
                        gap: 12px;
                    }

                    .palette-item:hover {
                        background: #161b22;
                        border-color: #58a6ff;
                        transform: translateX(4px);
                    }

                    .palette-item.dragging {
                        opacity: 0.5;
                        cursor: grabbing;
                    }

                    .palette-item-icon {
                        font-size: 24px;
                    }

                    .palette-item-info {
                        flex: 1;
                    }

                    .palette-item-name {
                        font-weight: 600;
                        margin-bottom: 2px;
                    }

                    .palette-item-desc {
                        font-size: 12px;
                        color: #8b949e;
                    }

                    .properties-panel {
                        flex: 1;
                        overflow-y: auto;
                    }

                    .property-group {
                        margin-bottom: 20px;
                    }

                    .property-label {
                        font-size: 12px;
                        color: #8b949e;
                        margin-bottom: 6px;
                        font-weight: 500;
                    }

                    .property-input {
                        width: 100%;
                        background: #0d1117;
                        border: 1px solid #30363d;
                        color: #c9d1d9;
                        padding: 8px 12px;
                        border-radius: 6px;
                        font-size: 13px;
                        transition: all 0.2s;
                    }

                    .property-input:focus {
                        outline: none;
                        border-color: #58a6ff;
                        box-shadow: 0 0 0 3px rgba(88, 166, 255, 0.1);
                    }

                    textarea.property-input {
                        min-height: 80px;
                        resize: vertical;
                        font-family: 'Monaco', 'Consolas', 'Courier New', monospace;
                    }

                    .workflow-connection {
                        position: absolute;
                        pointer-events: none;
                        z-index: 1;
                    }

                    .connection-path {
                        stroke: #30363d;
                        stroke-width: 3;
                        fill: none;
                        pointer-events: stroke;
                        cursor: pointer;
                        transition: stroke 0.2s;
                    }

                    .connection-path:hover {
                        stroke: #58a6ff;
                        stroke-width: 4;
                    }

                    .connection-path.selected {
                        stroke: #58a6ff;
                        stroke-width: 4;
                    }

                    .temp-connection {
                        stroke: #f0883e;
                        stroke-dasharray: 5,5;
                        animation: dash 0.5s linear infinite;
                    }

                    @keyframes dash {
                        to { stroke-dashoffset: -10; }
                    }

                    .toolbar-btn {
                        background: #21262d;
                        border: 1px solid #30363d;
                        color: #c9d1d9;
                        padding: 8px 16px;
                        border-radius: 6px;
                        cursor: pointer;
                        font-size: 13px;
                        font-weight: 500;
                        transition: all 0.2s;
                        display: flex;
                        align-items: center;
                        gap: 6px;
                    }

                    .toolbar-btn:hover {
                        background: #30363d;
                        border-color: #58a6ff;
                    }

                    .toolbar-btn:active {
                        transform: translateY(1px);
                    }

                    .toolbar-btn.primary {
                        background: #238636;
                        border-color: #238636;
                    }

                    .toolbar-btn.primary:hover {
                        background: #2ea043;
                        border-color: #2ea043;
                    }

                    .toolbar-separator {
                        width: 1px;
                        height: 24px;
                        background: #30363d;
                        margin: 0 12px;
                    }

                    .workflow-name {
                        font-weight: 600;
                        font-size: 16px;
                        margin-right: 20px;
                    }

                    .status-indicator {
                        display: inline-block;
                        width: 8px;
                        height: 8px;
                        border-radius: 50%;
                        margin-right: 6px;
                    }

                    .status-idle { background: #8b949e; }
                    .status-running { background: #f0883e; }
                    .status-completed { background: #3fb950; }
                    .status-error { background: #f85149; }

                    .drop-zone {
                        position: absolute;
                        background: rgba(88, 166, 255, 0.1);
                        border: 2px dashed #58a6ff;
                        border-radius: 8px;
                        pointer-events: none;
                        opacity: 0;
                        transition: opacity 0.2s;
                    }

                    .drop-zone.active {
                        opacity: 1;
                    }

                    .welcome-overlay {
                        position: absolute;
                        top: 50%;
                        left: 50%;
                        transform: translate(-50%, -50%);
                        text-align: center;
                        padding: 40px;
                        background: #161b22;
                        border-radius: 12px;
                        border: 1px solid #30363d;
                        max-width: 500px;
                        z-index: 100;
                    }

                    .welcome-title {
                        font-size: 24px;
                        font-weight: 600;
                        margin-bottom: 16px;
                        color: #58a6ff;
                    }

                    .welcome-text {
                        font-size: 14px;
                        color: #8b949e;
                        margin-bottom: 24px;
                        line-height: 1.6;
                    }

                    .welcome-actions {
                        display: flex;
                        gap: 12px;
                        justify-content: center;
                    }

                    .toast {
                        position: fixed;
                        bottom: 20px;
                        right: 20px;
                        background: #161b22;
                        border: 1px solid #30363d;
                        border-radius: 8px;
                        padding: 16px 20px;
                        display: flex;
                        align-items: center;
                        gap: 12px;
                        z-index: 1000;
                        animation: slide-in 0.3s ease;
                        box-shadow: 0 4px 12px rgba(0, 0, 0, 0.3);
                    }

                    @keyframes slide-in {
                        from {
                            transform: translateX(100%);
                            opacity: 0;
                        }
                        to {
                            transform: translateX(0);
                            opacity: 1;
                        }
                    }

                    .toast.success {
                        border-color: #3fb950;
                    }

                    .toast.error {
                        border-color: #f85149;
                    }

                    .toast.info {
                        border-color: #58a6ff;
                    }

                    /* Improved scrollbar */
                    ::-webkit-scrollbar {
                        width: 8px;
                        height: 8px;
                    }

                    ::-webkit-scrollbar-track {
                        background: #0d1117;
                    }

                    ::-webkit-scrollbar-thumb {
                        background: #30363d;
                        border-radius: 4px;
                    }

                    ::-webkit-scrollbar-thumb:hover {
                        background: #484f58;
                    }
                </style>

                <div class="workflow-sidebar">
                    <div class="sidebar-section">
                        <div class="sidebar-title">🚀 Agent Nodes</div>
                        <div class="node-palette" id="agent-palette"></div>
                    </div>
                    
                    <div class="sidebar-section">
                        <div class="sidebar-title">🔧 Utility Nodes</div>
                        <div class="node-palette">
                            <div class="palette-item" data-node-type="input" draggable="true">
                                <span class="palette-item-icon">📥</span>
                                <div class="palette-item-info">
                                    <div class="palette-item-name">Input</div>
                                    <div class="palette-item-desc">Data entry point</div>
                                </div>
                            </div>
                            <div class="palette-item" data-node-type="output" draggable="true">
                                <span class="palette-item-icon">📤</span>
                                <div class="palette-item-info">
                                    <div class="palette-item-name">Output</div>
                                    <div class="palette-item-desc">Result collector</div>
                                </div>
                            </div>
                            <div class="palette-item" data-node-type="composer" draggable="true">
                                <span class="palette-item-icon">🔀</span>
                                <div class="palette-item-info">
                                    <div class="palette-item-name">Composer</div>
                                    <div class="palette-item-desc">Combine data</div>
                                </div>
                            </div>
                        </div>
                    </div>
                    
                    <div class="sidebar-section properties-panel">
                        <div class="sidebar-title">⚙️ Properties</div>
                        <div id="properties-content">
                            <div style="text-align: center; color: #8b949e; padding: 20px;">
                                Select a node to view properties
                            </div>
                        </div>
                    </div>
                </div>

                <div class="workflow-main">
                    <div class="workflow-toolbar">
                        <span class="workflow-name" id="workflow-name">New Workflow</span>
                        <button class="toolbar-btn primary" onclick="WorkflowBuilder.runWorkflow()">
                            <span>▶️</span> Run
                        </button>
                        <button class="toolbar-btn" onclick="WorkflowBuilder.stopWorkflow()">
                            <span>⏹️</span> Stop
                        </button>
                        <div class="toolbar-separator"></div>
                        <button class="toolbar-btn" onclick="WorkflowBuilder.saveWorkflow()">
                            <span>💾</span> Save
                        </button>
                        <button class="toolbar-btn" onclick="WorkflowBuilder.loadWorkflowDialog()">
                            <span>📁</span> Load
                        </button>
                        <div class="toolbar-separator"></div>
                        <button class="toolbar-btn" onclick="WorkflowBuilder.clearWorkflow()">
                            <span>🗑️</span> Clear
                        </button>
                        <button class="toolbar-btn" onclick="WorkflowBuilder.autoLayout()">
                            <span>📐</span> Auto Layout
                        </button>
                        <div class="toolbar-separator"></div>
                        <span id="workflow-status"><span class="status-indicator status-idle"></span>Idle</span>
                    </div>
                    
                    <div class="workflow-canvas-container" id="canvas-container">
                        <svg class="workflow-canvas" id="workflow-canvas">
                            <defs>
                                <marker id="arrowhead" markerWidth="10" markerHeight="7" 
                                 refX="9" refY="3.5" orient="auto" fill="#30363d">
                                    <polygon points="0 0, 10 3.5, 0 7" />
                                </marker>
                            </defs>
                            <g id="connections-layer"></g>
                        </svg>
                        <div id="nodes-layer"></div>
                        <div class="drop-zone" id="drop-zone"></div>
                    </div>
                </div>
            </div>
        `;

        // Initialize components
        this.canvas = document.getElementById('workflow-canvas');
        this.dropZone = document.getElementById('drop-zone');
        this.initializeAgentPalette();
        this.initializeEventHandlers();
    };

    /**
     * Initialize the simplified agent palette
     */
    WorkflowBuilder.initializeAgentPalette = function() {
        const palette = document.getElementById('agent-palette');
        
        // Add simplified agent roles to palette
        for (const [key, agent] of Object.entries(SIMPLIFIED_AGENTS)) {
            const item = document.createElement('div');
            item.className = 'palette-item';
            item.dataset.nodeType = 'agent';
            item.dataset.agentRole = key;
            item.draggable = true;
            
            item.innerHTML = `
                <span class="palette-item-icon">${agent.icon}</span>
                <div class="palette-item-info">
                    <div class="palette-item-name">${agent.name}</div>
                    <div class="palette-item-desc">${agent.description}</div>
                </div>
            `;
            
            palette.appendChild(item);
        }
    };

    /**
     * Initialize enhanced event handlers
     */
    WorkflowBuilder.initializeEventHandlers = function() {
        const container = document.getElementById('canvas-container');
        const nodesLayer = document.getElementById('nodes-layer');
        
        // Enhanced palette drag and drop
        document.querySelectorAll('.palette-item').forEach(item => {
            item.addEventListener('dragstart', (e) => {
                e.dataTransfer.effectAllowed = 'copy';
                e.dataTransfer.setData('nodeType', item.dataset.nodeType);
                e.dataTransfer.setData('agentRole', item.dataset.agentRole || '');
                
                // Visual feedback
                item.classList.add('dragging');
                this.draggedElement = item;
                
                // Create custom drag image
                const dragImage = item.cloneNode(true);
                dragImage.style.position = 'absolute';
                dragImage.style.top = '-1000px';
                document.body.appendChild(dragImage);
                e.dataTransfer.setDragImage(dragImage, e.offsetX, e.offsetY);
                setTimeout(() => document.body.removeChild(dragImage), 0);
            });
            
            item.addEventListener('dragend', (e) => {
                item.classList.remove('dragging');
                this.draggedElement = null;
            });
        });
        
        // Canvas drag over with drop zone
        container.addEventListener('dragover', (e) => {
            e.preventDefault();
            e.dataTransfer.dropEffect = 'copy';
            
            // Show drop zone
            const rect = container.getBoundingClientRect();
            const x = e.clientX - rect.left - 90;
            const y = e.clientY - rect.top - 40;
            
            this.dropZone.style.left = x + 'px';
            this.dropZone.style.top = y + 'px';
            this.dropZone.style.width = '180px';
            this.dropZone.style.height = '80px';
            this.dropZone.classList.add('active');
        });
        
        container.addEventListener('dragleave', (e) => {
            if (e.target === container) {
                this.dropZone.classList.remove('active');
            }
        });
        
        // Canvas drop with animation
        container.addEventListener('drop', (e) => {
            e.preventDefault();
            
            this.dropZone.classList.remove('active');
            
            const nodeType = e.dataTransfer.getData('nodeType');
            const agentRole = e.dataTransfer.getData('agentRole');
            
            const rect = container.getBoundingClientRect();
            const x = e.clientX - rect.left - 90;
            const y = e.clientY - rect.top - 40;
            
            this.addNode(nodeType, x, y, agentRole);
            
            // Show success feedback
            this.showToast('Node added successfully', 'success');
        });
        
        // Node selection with multi-select support
        nodesLayer.addEventListener('click', (e) => {
            const node = e.target.closest('.workflow-node');
            if (node) {
                if (e.ctrlKey || e.metaKey) {
                    // Multi-select
                    node.classList.toggle('selected');
                } else {
                    // Single select
                    document.querySelectorAll('.workflow-node').forEach(n => n.classList.remove('selected'));
                    this.selectNode(node.dataset.nodeId);
                }
            }
        });
        
        // Enhanced port connections
        nodesLayer.addEventListener('mousedown', (e) => {
            if (e.target.classList.contains('node-port') && e.target.classList.contains('output')) {
                e.preventDefault();
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
        
        // Keyboard shortcuts
        document.addEventListener('keydown', (e) => {
            // Delete selected items
            if (e.key === 'Delete' || e.key === 'Backspace') {
                if (this.selectedNode) {
                    this.deleteNode(this.selectedNode);
                } else if (this.selectedConnection) {
                    this.deleteConnection(this.selectedConnection);
                }
            }
            
            // Ctrl+S to save
            if ((e.ctrlKey || e.metaKey) && e.key === 's') {
                e.preventDefault();
                this.saveWorkflow();
            }
            
            // Ctrl+O to open
            if ((e.ctrlKey || e.metaKey) && e.key === 'o') {
                e.preventDefault();
                this.loadWorkflowDialog();
            }
            
            // Ctrl+A to select all
            if ((e.ctrlKey || e.metaKey) && e.key === 'a') {
                e.preventDefault();
                document.querySelectorAll('.workflow-node').forEach(n => n.classList.add('selected'));
            }
        });
        
        // Canvas panning
        let isPanning = false;
        let panStart = { x: 0, y: 0 };
        
        this.canvas.addEventListener('mousedown', (e) => {
            if (e.target === this.canvas || e.target.parentElement === this.canvas) {
                isPanning = true;
                panStart = { x: e.clientX, y: e.clientY };
                this.canvas.classList.add('dragging');
            }
        });
        
        document.addEventListener('mousemove', (e) => {
            if (isPanning) {
                const dx = e.clientX - panStart.x;
                const dy = e.clientY - panStart.y;
                
                // Pan the canvas
                const transform = this.canvas.style.transform || '';
                const match = transform.match(/translate\(([-\d.]+)px,\s*([-\d.]+)px\)/);
                const currentX = match ? parseFloat(match[1]) : 0;
                const currentY = match ? parseFloat(match[2]) : 0;
                
                this.canvas.style.transform = `translate(${currentX + dx}px, ${currentY + dy}px)`;
                document.getElementById('nodes-layer').style.transform = `translate(${currentX + dx}px, ${currentY + dy}px)`;
                
                panStart = { x: e.clientX, y: e.clientY };
            }
        });
        
        document.addEventListener('mouseup', () => {
            if (isPanning) {
                isPanning = false;
                this.canvas.classList.remove('dragging');
            }
        });
    };

    /**
     * Add a node with animation
     */
    WorkflowBuilder.addNode = function(type, x, y, agentRole) {
        let node;
        
        switch (type) {
            case 'agent':
                const agentInfo = SIMPLIFIED_AGENTS[agentRole];
                if (!agentInfo) return;
                
                // Map to actual agent role from framework
                let frameworkRole;
                if (agentRole === 'CODE_GENERATOR') {
                    frameworkRole = window.AgentFramework.AgentRoles.CODE_GENERATOR;
                } else if (agentRole === 'RESEARCH') {
                    frameworkRole = window.AgentFramework.AgentRoles.RESEARCH || {
                        id: 'research',
                        name: 'Research Agent',
                        capabilities: ['search_text', 'find_syntax', 'analyze_usage', 'find_references']
                    };
                }
                
                node = WorkflowEngine.createAgentNode(frameworkRole);
                node.icon = agentInfo.icon;
                break;
            case 'composer':
                node = WorkflowEngine.createComposerNode();
                node.icon = '🔀';
                break;
            case 'input':
                node = WorkflowEngine.createInputNode();
                node.icon = '📥';
                break;
            case 'output':
                node = WorkflowEngine.createOutputNode();
                node.icon = '📤';
                break;
            default:
                return;
        }
        
        node.setPosition(x, y);
        this.currentWorkflow.addNode(node);
        this.renderNode(node);
        this.selectNode(node.id);
        
        // Animate node appearance
        const nodeEl = document.querySelector(`[data-node-id="${node.id}"]`);
        if (nodeEl) {
            nodeEl.style.transform = 'scale(0)';
            nodeEl.style.opacity = '0';
            setTimeout(() => {
                nodeEl.style.transition = 'all 0.3s ease';
                nodeEl.style.transform = 'scale(1)';
                nodeEl.style.opacity = '1';
            }, 10);
        }
    };

    /**
     * Render a node with improved styling
     */
    WorkflowBuilder.renderNode = function(node) {
        const nodesLayer = document.getElementById('nodes-layer');
        
        const nodeEl = document.createElement('div');
        nodeEl.className = 'workflow-node';
        nodeEl.dataset.nodeId = node.id;
        nodeEl.style.left = node.position.x + 'px';
        nodeEl.style.top = node.position.y + 'px';
        
        // Node content
        const icon = node.icon || '📦';
        
        nodeEl.innerHTML = `
            <div class="node-header">
                <span class="node-icon">${icon}</span>
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
     * Enhanced port rendering
     */
    WorkflowBuilder.renderPorts = function(node, container) {
        let topOffset = 40;
        
        // Input ports
        const inputs = node.getRequiredInputs();
        inputs.forEach((input, index) => {
            const port = document.createElement('div');
            port.className = 'node-port input';
            port.dataset.nodeId = node.id;
            port.dataset.portName = input;
            port.dataset.portType = 'input';
            port.style.top = topOffset + (index * 25) + 'px';
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
            port.style.top = topOffset + (index * 25) + 'px';
            port.title = output;
            container.appendChild(port);
        });
    };

    /**
     * Enhanced node dragging
     */
    WorkflowBuilder.makeDraggable = function(element) {
        let startX, startY, initialX, initialY;
        let isDragging = false;
        
        const onMouseDown = (e) => {
            if (e.target.classList.contains('node-port')) return;
            
            e.preventDefault();
            isDragging = true;
            startX = e.clientX;
            startY = e.clientY;
            initialX = element.offsetLeft;
            initialY = element.offsetTop;
            
            element.classList.add('dragging');
            
            document.addEventListener('mousemove', onMouseMove);
            document.addEventListener('mouseup', onMouseUp);
        };
        
        const onMouseMove = (e) => {
            if (!isDragging) return;
            
            const dx = e.clientX - startX;
            const dy = e.clientY - startY;
            
            element.style.left = (initialX + dx) + 'px';
            element.style.top = (initialY + dy) + 'px';
            
            // Update node position
            const node = this.currentWorkflow.nodes.get(element.dataset.nodeId);
            if (node) {
                node.setPosition(initialX + dx, initialY + dy);
            }
            
            // Update connections smoothly
            this.updateConnections();
        };
        
        const onMouseUp = () => {
            isDragging = false;
            element.classList.remove('dragging');
            document.removeEventListener('mousemove', onMouseMove);
            document.removeEventListener('mouseup', onMouseUp);
        };
        
        element.addEventListener('mousedown', onMouseDown);
    };

    /**
     * Enhanced connection creation
     */
    WorkflowBuilder.startConnection = function(portElement) {
        this.isConnecting = true;
        this.connectingFrom = portElement;
        
        // Visual feedback
        portElement.classList.add('connecting');
        
        // Create temp connection line
        const svg = document.getElementById('connections-layer');
        const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
        path.id = 'temp-connection';
        path.classList.add('connection-path', 'temp-connection');
        svg.appendChild(path);
    };

    /**
     * End connection with validation
     */
    WorkflowBuilder.endConnection = function(e) {
        this.isConnecting = false;
        
        // Remove visual feedback
        if (this.connectingFrom) {
            this.connectingFrom.classList.remove('connecting');
        }
        
        // Remove temp connection
        const tempPath = document.getElementById('temp-connection');
        if (tempPath) tempPath.remove();
        
        // Check if we're over an input port
        const target = document.elementFromPoint(e.clientX, e.clientY);
        if (target && target.classList.contains('node-port') && target.classList.contains('input')) {
            // Validate connection
            const fromNodeId = this.connectingFrom.dataset.nodeId;
            const toNodeId = target.dataset.nodeId;
            
            if (fromNodeId === toNodeId) {
                this.showToast('Cannot connect node to itself', 'error');
                return;
            }
            
            // Check if connection already exists
            const existingConnection = this.currentWorkflow.connections.find(c => 
                c.from.nodeId === fromNodeId && 
                c.to.nodeId === toNodeId
            );
            
            if (existingConnection) {
                this.showToast('Connection already exists', 'error');
                return;
            }
            
            // Create connection
            const fromPort = this.connectingFrom.dataset.portName;
            const toPort = target.dataset.portName;
            
            try {
                const connection = this.currentWorkflow.connect(fromNodeId, fromPort, toNodeId, toPort);
                this.renderConnection(connection);
                
                // Mark ports as connected
                this.connectingFrom.classList.add('connected');
                target.classList.add('connected');
                
                this.showToast('Connection created', 'success');
            } catch (error) {
                this.showToast('Failed to create connection: ' + error.message, 'error');
            }
        }
        
        this.connectingFrom = null;
    };

    /**
     * Show node properties with better UI
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
            const agentInfo = Object.values(SIMPLIFIED_AGENTS).find(a => a.id === node.config.role.id);
            
            html += `
                <div class="property-group">
                    <div class="property-label">Agent Type</div>
                    <input type="text" class="property-input" value="${agentInfo ? agentInfo.name : node.config.role.name}" disabled />
                </div>
                <div class="property-group">
                    <div class="property-label">Task Type</div>
                    <select class="property-input" id="prop-taskType">
                        ${agentInfo && agentInfo.id === 'code_generator' ? `
                            <option value="generate">Generate Code</option>
                            <option value="refactor">Refactor Code</option>
                            <option value="review">Review Code</option>
                            <option value="debug">Debug Code</option>
                        ` : `
                            <option value="search_text">Search Text</option>
                            <option value="find_syntax">Find Syntax</option>
                            <option value="analyze_usage">Analyze Usage</option>
                            <option value="find_references">Find References</option>
                        `}
                    </select>
                </div>
                <div class="property-group">
                    <div class="property-label">Instructions</div>
                    <textarea class="property-input" id="prop-prompt" placeholder="Enter specific instructions for the agent...">${node.config.prompt || ''}</textarea>
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
                        <option value="prompt">User Prompt</option>
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
                <div class="property-group">
                    <div class="property-label">Output Format</div>
                    <select class="property-input" id="prop-outputFormat">
                        <option value="json">JSON</option>
                        <option value="text">Plain Text</option>
                        <option value="markdown">Markdown</option>
                    </select>
                </div>
            `;
        }
        
        container.innerHTML = html;
        
        // Add change listeners with auto-save
        container.querySelectorAll('.property-input').forEach(input => {
            input.addEventListener('change', () => {
                this.updateNodeProperties(nodeId);
                this.triggerAutoSave();
            });
            
            // Live update for text inputs
            if (input.tagName === 'INPUT' || input.tagName === 'TEXTAREA') {
                input.addEventListener('input', debounce(() => {
                    this.updateNodeProperties(nodeId);
                    this.triggerAutoSave();
                }, 500));
            }
        });
    };

    /**
     * Show toast notification
     */
    WorkflowBuilder.showToast = function(message, type = 'info') {
        const toast = document.createElement('div');
        toast.className = `toast ${type}`;
        toast.innerHTML = `
            <span>${type === 'success' ? '✓' : type === 'error' ? '✗' : 'ℹ'}</span>
            <span>${message}</span>
        `;
        
        document.body.appendChild(toast);
        
        setTimeout(() => {
            toast.style.opacity = '0';
            setTimeout(() => toast.remove(), 300);
        }, 3000);
    };

    /**
     * Show welcome message
     */
    WorkflowBuilder.showWelcomeMessage = function() {
        const overlay = document.createElement('div');
        overlay.className = 'welcome-overlay';
        overlay.innerHTML = `
            <h2 class="welcome-title">Welcome to Workflow Builder</h2>
            <p class="welcome-text">
                Create powerful workflows by dragging agents from the sidebar onto the canvas.
                Connect them together to build complex automation pipelines.
            </p>
            <div class="welcome-actions">
                <button class="toolbar-btn primary" onclick="WorkflowBuilder.hideWelcome()">Get Started</button>
                <button class="toolbar-btn" onclick="WorkflowBuilder.loadExample()">Load Example</button>
            </div>
        `;
        
        document.getElementById('canvas-container').appendChild(overlay);
    };

    WorkflowBuilder.hideWelcome = function() {
        const overlay = document.querySelector('.welcome-overlay');
        if (overlay) {
            overlay.style.opacity = '0';
            setTimeout(() => overlay.remove(), 300);
        }
    };

    /**
     * Load example workflow
     */
    WorkflowBuilder.loadExample = function() {
        this.hideWelcome();
        
        // Create example workflow
        const input = this.addNode('input', 100, 200);
        const codeAgent = this.addNode('agent', 350, 100, 'CODE_GENERATOR');
        const researchAgent = this.addNode('agent', 350, 300, 'RESEARCH');
        const composer = this.addNode('composer', 600, 200);
        const output = this.addNode('output', 850, 200);
        
        // Create connections
        setTimeout(() => {
            this.currentWorkflow.connect(input.id, 'output', codeAgent.id, 'input');
            this.currentWorkflow.connect(input.id, 'output', researchAgent.id, 'input');
            this.currentWorkflow.connect(codeAgent.id, 'result', composer.id, 'input1');
            this.currentWorkflow.connect(researchAgent.id, 'result', composer.id, 'input2');
            this.currentWorkflow.connect(composer.id, 'output', output.id, 'input');
            
            this.render();
            this.showToast('Example workflow loaded', 'success');
        }, 500);
    };

    /**
     * Enable auto-save
     */
    WorkflowBuilder.enableAutoSave = function() {
        // Load auto-saved workflow if exists
        const saved = localStorage.getItem('workflow_autosave');
        if (saved) {
            try {
                const data = JSON.parse(saved);
                // Only load if it's recent (less than 24 hours old)
                if (Date.now() - data.timestamp < 24 * 60 * 60 * 1000) {
                    // Ask user if they want to restore
                    if (confirm('Found auto-saved workflow. Would you like to restore it?')) {
                        this.loadWorkflow(data.workflow);
                        this.showToast('Auto-saved workflow restored', 'success');
                    }
                }
            } catch (e) {
                console.error('Failed to load auto-save:', e);
            }
        }
    };

    /**
     * Trigger auto-save
     */
    WorkflowBuilder.triggerAutoSave = function() {
        if (this.autoSaveTimer) {
            clearTimeout(this.autoSaveTimer);
        }
        
        this.autoSaveTimer = setTimeout(() => {
            const data = {
                workflow: this.currentWorkflow.toJSON(),
                timestamp: Date.now()
            };
            localStorage.setItem('workflow_autosave', JSON.stringify(data));
            console.log('Workflow auto-saved');
        }, 2000);
    };

    /**
     * Improved run workflow with better error handling
     */
    WorkflowBuilder.runWorkflow = async function() {
        try {
            // Validate workflow
            if (this.currentWorkflow.nodes.size === 0) {
                this.showToast('Workflow is empty. Add some nodes first!', 'error');
                return;
            }
            
            // Check for unconnected nodes
            const unconnected = [];
            this.currentWorkflow.nodes.forEach((node, id) => {
                if (node.type !== 'input' && this.currentWorkflow.getIncomingConnections(id).length === 0) {
                    unconnected.push(node.name);
                }
            });
            
            if (unconnected.length > 0) {
                this.showToast(`Warning: Unconnected nodes: ${unconnected.join(', ')}`, 'error');
                return;
            }
            
            // Update status
            this.updateStatus('running');
            this.showToast('Workflow started', 'info');
            
            // Reset node states
            this.currentWorkflow.nodes.forEach(node => {
                node.status = 'idle';
                node.result = null;
                node.error = null;
                this.updateNodeStatus(node.id, 'idle');
            });
            
            // Create custom executor
            const executor = new WorkflowEngine.WorkflowExecutor(this.currentWorkflow);
            
            // Override executeNode to update UI
            const originalExecuteNode = executor.executeNode.bind(executor);
            executor.executeNode = async (nodeId) => {
                this.updateNodeStatus(nodeId, 'running');
                try {
                    const result = await originalExecuteNode(nodeId);
                    this.updateNodeStatus(nodeId, 'completed');
                    
                    // Update properties panel if selected
                    if (this.selectedNode === nodeId) {
                        this.showNodeResult(nodeId, result);
                    }
                    
                    return result;
                } catch (error) {
                    this.updateNodeStatus(nodeId, 'error');
                    this.showToast(`Node ${this.currentWorkflow.nodes.get(nodeId).name} failed: ${error.message}`, 'error');
                    throw error;
                }
            };
            
            // Execute workflow
            const results = await executor.execute();
            
            // Update status
            this.updateStatus('completed');
            this.showToast('Workflow completed successfully!', 'success');
            
            // Show results
            this.showWorkflowResults(results);
            
        } catch (error) {
            console.error('Workflow execution failed:', error);
            this.updateStatus('error');
            this.showToast(`Workflow failed: ${error.message}`, 'error');
        }
    };

    /**
     * Show node execution result
     */
    WorkflowBuilder.showNodeResult = function(nodeId, result) {
        const existingResult = document.querySelector('.node-result');
        if (existingResult) existingResult.remove();
        
        const resultDiv = document.createElement('div');
        resultDiv.className = 'property-group node-result';
        resultDiv.innerHTML = `
            <div class="property-label">Execution Result</div>
            <pre class="property-input" style="background: #0a0c10; color: #3fb950; min-height: 100px; max-height: 300px; overflow-y: auto;">
${JSON.stringify(result, null, 2)}</pre>
        `;
        document.getElementById('properties-content').appendChild(resultDiv);
    };

    /**
     * Show workflow results
     */
    WorkflowBuilder.showWorkflowResults = function(results) {
        const modal = document.createElement('div');
        modal.className = 'welcome-overlay';
        modal.style.maxWidth = '600px';
        modal.innerHTML = `
            <h2 class="welcome-title">Workflow Results</h2>
            <div style="max-height: 400px; overflow-y: auto;">
                <pre class="property-input" style="background: #0a0c10; color: #3fb950; padding: 16px; text-align: left;">
${JSON.stringify(results, null, 2)}</pre>
            </div>
            <div class="welcome-actions" style="margin-top: 20px;">
                <button class="toolbar-btn primary" onclick="this.parentElement.parentElement.remove()">Close</button>
                <button class="toolbar-btn" onclick="WorkflowBuilder.exportResults()">Export Results</button>
            </div>
        `;
        
        document.getElementById('canvas-container').appendChild(modal);
    };

    /**
     * Export workflow results
     */
    WorkflowBuilder.exportResults = function() {
        // Implementation for exporting results
        this.showToast('Export feature coming soon!', 'info');
    };

    // Utility functions
    function debounce(func, wait) {
        let timeout;
        return function executedFunction(...args) {
            const later = () => {
                clearTimeout(timeout);
                func(...args);
            };
            clearTimeout(timeout);
            timeout = setTimeout(later, wait);
        };
    }

    // Initialize on load
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', () => {
            if (document.getElementById('workflow-builder')) {
                WorkflowBuilder.initialize('workflow-builder');
            }
        });
    }

    console.log('Improved Workflow Builder loaded');

})();
