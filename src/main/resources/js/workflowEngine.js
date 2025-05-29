/**
 * Workflow Engine for Agent Framework
 * Minimal but functional implementation for creating and executing agent workflows
 */

(function() {
    'use strict';

    // Workflow Engine namespace
    window.WorkflowEngine = {
        workflows: new Map(),
        activeExecutions: new Map(),
        nodeTypes: {}
    };

    /**
     * Workflow class - represents a complete workflow
     */
    class Workflow {
        constructor(id, name) {
            this.id = id || `workflow_${Date.now()}`;
            this.name = name || 'Untitled Workflow';
            this.nodes = new Map();
            this.connections = [];
            this.variables = {};
            this.status = 'idle';
        }

        addNode(node) {
            this.nodes.set(node.id, node);
            return node;
        }

        removeNode(nodeId) {
            this.nodes.delete(nodeId);
            // Remove all connections to/from this node
            this.connections = this.connections.filter(
                conn => conn.from.nodeId !== nodeId && conn.to.nodeId !== nodeId
            );
        }

        connect(fromNodeId, fromPort, toNodeId, toPort) {
            const connection = {
                id: `conn_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`,
                from: { nodeId: fromNodeId, port: fromPort },
                to: { nodeId: toNodeId, port: toPort }
            };
            this.connections.push(connection);
            return connection;
        }

        disconnect(connectionId) {
            this.connections = this.connections.filter(conn => conn.id !== connectionId);
        }

        getIncomingConnections(nodeId) {
            return this.connections.filter(conn => conn.to.nodeId === nodeId);
        }

        getOutgoingConnections(nodeId) {
            return this.connections.filter(conn => conn.from.nodeId === nodeId);
        }

        validate() {
            const errors = [];
            
            // Check for cycles
            if (this.hasCycles()) {
                errors.push('Workflow contains cycles');
            }
            
            // Check all required inputs are connected
            for (const [nodeId, node] of this.nodes) {
                const incomingConns = this.getIncomingConnections(nodeId);
                const requiredInputs = node.getRequiredInputs();
                
                for (const input of requiredInputs) {
                    const hasConnection = incomingConns.some(conn => conn.to.port === input);
                    if (!hasConnection && !node.config[input]) {
                        errors.push(`Node ${node.name} missing required input: ${input}`);
                    }
                }
            }
            
            return errors;
        }

        hasCycles() {
            // Simple cycle detection using DFS
            const visited = new Set();
            const recursionStack = new Set();
            
            const visit = (nodeId) => {
                if (recursionStack.has(nodeId)) return true;
                if (visited.has(nodeId)) return false;
                
                visited.add(nodeId);
                recursionStack.add(nodeId);
                
                const outgoing = this.getOutgoingConnections(nodeId);
                for (const conn of outgoing) {
                    if (visit(conn.to.nodeId)) return true;
                }
                
                recursionStack.delete(nodeId);
                return false;
            };
            
            for (const nodeId of this.nodes.keys()) {
                if (visit(nodeId)) return true;
            }
            
            return false;
        }

        toJSON() {
            return {
                id: this.id,
                name: this.name,
                nodes: Array.from(this.nodes.values()).map(node => node.toJSON()),
                connections: this.connections,
                variables: this.variables
            };
        }

        static fromJSON(json) {
            const workflow = new Workflow(json.id, json.name);
            
            // Recreate nodes
            for (const nodeData of json.nodes) {
                const NodeClass = WorkflowEngine.nodeTypes[nodeData.type];
                if (NodeClass) {
                    const node = NodeClass.fromJSON(nodeData);
                    workflow.addNode(node);
                }
            }
            
            // Recreate connections
            workflow.connections = json.connections || [];
            workflow.variables = json.variables || {};
            
            return workflow;
        }
    }

    /**
     * Base WorkflowNode class
     */
    class WorkflowNode {
        constructor(id, type, name) {
            this.id = id || `node_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`;
            this.type = type;
            this.name = name || type;
            this.config = {};
            this.position = { x: 0, y: 0 };
            this.status = 'idle';
            this.result = null;
            this.error = null;
        }

        configure(config) {
            this.config = { ...this.config, ...config };
            return this;
        }

        setPosition(x, y) {
            this.position = { x, y };
            return this;
        }

        getRequiredInputs() {
            return [];
        }

        getOutputPorts() {
            return ['output'];
        }

        async execute(inputs, context) {
            throw new Error('Execute method must be implemented by subclass');
        }

        toJSON() {
            return {
                id: this.id,
                type: this.type,
                name: this.name,
                config: this.config,
                position: this.position
            };
        }

        static fromJSON(json) {
            const node = new this();
            node.id = json.id;
            node.name = json.name;
            node.config = json.config || {};
            node.position = json.position || { x: 0, y: 0 };
            return node;
        }
    }

    /**
     * Agent Node - wraps an agent for workflow use
     */
    class AgentNode extends WorkflowNode {
        constructor(agentRole, id) {
            super(id, 'agent', agentRole.name);
            this.agentRole = agentRole;
        }

        getRequiredInputs() {
            // Dynamic based on agent capability
            return ['prompt', 'data'];
        }

        async execute(inputs, context) {
            const { prompt, data, ...otherInputs } = inputs;
            
            // Check if we have LLM provider and should use it
            if (window.LLMProvider && (prompt || this.config.prompt)) {
                try {
                    const taskPrompt = prompt || this.config.prompt;
                    const taskType = this.config.taskType || 'process';
                    
                    // Build context for LLM
                    let llmPrompt = `As a ${this.agentRole.name}, ${taskPrompt}\n\n`;
                    if (data) {
                        llmPrompt += `Input data: ${typeof data === 'string' ? data : JSON.stringify(data, null, 2)}\n\n`;
                    }
                    
                    // Call LLM based on task type
                    let result;
                    switch (taskType) {
                        case 'generate':
                            result = await window.LLMProvider.generateCode(
                                llmPrompt, 
                                this.config.language || 'javascript'
                            );
                            return { code: result, language: this.config.language || 'javascript' };
                            
                        case 'review':
                            const codeToReview = data?.code || data || '';
                            result = await window.LLMProvider.reviewCode(
                                codeToReview,
                                this.config.language || 'javascript'
                            );
                            // Parse review result
                            return {
                                review: result,
                                passed: !result.toLowerCase().includes('error') && !result.toLowerCase().includes('issue'),
                                score: result.toLowerCase().includes('good') || result.toLowerCase().includes('well') ? 80 : 60
                            };
                            
                        case 'analyze':
                            result = await window.LLMProvider.callAPI(llmPrompt, {
                                maxTokens: 500
                            });
                            return { analysis: result };
                            
                        default:
                            result = await window.LLMProvider.callAPI(llmPrompt, {
                                maxTokens: this.config.maxTokens || 300
                            });
                            return { result };
                    }
                } catch (error) {
                    console.error('LLM execution failed, using mock data:', error);
                    // Fall through to mock implementation
                }
            }
            
            // Original mock implementation as fallback
            const agent = await window.AgentFramework.createAgent(this.agentRole.id);
            
            return new Promise((resolve, reject) => {
                agent.queueTask({
                    type: this.config.taskType || 'process',
                    data: {
                        prompt: prompt || this.config.prompt,
                        ...data,
                        ...otherInputs,
                        ...this.config.additionalData
                    },
                    callback: (error, result) => {
                        if (error) {
                            this.error = error;
                            this.status = 'error';
                            reject(error);
                        } else {
                            this.result = result;
                            this.status = 'completed';
                            resolve(result);
                        }
                    }
                });
            });
        }

        toJSON() {
            const json = super.toJSON();
            json.agentRole = this.agentRole;
            return json;
        }

        static fromJSON(json) {
            const node = new AgentNode(json.agentRole, json.id);
            node.name = json.name;
            node.config = json.config || {};
            node.position = json.position || { x: 0, y: 0 };
            return node;
        }
    }

    /**
     * Composer Node - combines outputs from multiple nodes
     */
    class ComposerNode extends WorkflowNode {
        constructor(id) {
            super(id, 'composer', 'Composer');
        }

        getRequiredInputs() {
            // Dynamic - accepts any number of inputs
            return [];
        }

        async execute(inputs, context) {
            const { composerFunction } = this.config;
            
            if (composerFunction) {
                // Execute custom composer function
                try {
                    const func = new Function('inputs', 'context', composerFunction);
                    return func(inputs, context);
                } catch (error) {
                    throw new Error(`Composer function error: ${error.message}`);
                }
            }
            
            // Default: merge all inputs into single object
            if (Array.isArray(inputs)) {
                return inputs;
            }
            
            return { ...inputs };
        }
    }

    /**
     * Input Node - provides initial data to workflow
     */
    class InputNode extends WorkflowNode {
        constructor(id) {
            super(id, 'input', 'Input');
        }

        async execute(inputs, context) {
            // Return configured value or context value
            const { source, value, contextKey } = this.config;
            
            if (source === 'context' && contextKey) {
                return context[contextKey];
            }
            
            return value !== undefined ? value : inputs;
        }
    }

    /**
     * Output Node - captures workflow results
     */
    class OutputNode extends WorkflowNode {
        constructor(id) {
            super(id, 'output', 'Output');
        }

        getRequiredInputs() {
            return ['value'];
        }

        async execute(inputs, context) {
            const { value } = inputs;
            
            // Store in context if configured
            if (this.config.contextKey) {
                context[this.config.contextKey] = value;
            }
            
            return value;
        }
    }

    /**
     * Workflow Executor - handles workflow execution
     */
    class WorkflowExecutor {
        constructor(workflow, context = {}) {
            this.workflow = workflow;
            this.context = { ...workflow.variables, ...context };
            this.nodeResults = new Map();
            this.executionOrder = [];
            this.status = 'idle';
        }

        async execute() {
            this.status = 'running';
            
            try {
                // Validate workflow
                const errors = this.workflow.validate();
                if (errors.length > 0) {
                    throw new Error(`Workflow validation failed: ${errors.join(', ')}`);
                }
                
                // Determine execution order (topological sort)
                this.executionOrder = this.topologicalSort();
                
                // Execute nodes in order
                for (const nodeId of this.executionOrder) {
                    await this.executeNode(nodeId);
                }
                
                this.status = 'completed';
                return this.collectResults();
                
            } catch (error) {
                this.status = 'error';
                throw error;
            }
        }

        async executeNode(nodeId) {
            const node = this.workflow.nodes.get(nodeId);
            if (!node) throw new Error(`Node ${nodeId} not found`);
            
            node.status = 'running';
            
            try {
                // Gather inputs from incoming connections
                const inputs = this.gatherNodeInputs(nodeId);
                
                // Execute node
                const result = await node.execute(inputs, this.context);
                
                // Store result
                this.nodeResults.set(nodeId, result);
                node.status = 'completed';
                
                return result;
                
            } catch (error) {
                node.status = 'error';
                node.error = error;
                throw new Error(`Node ${node.name} failed: ${error.message}`);
            }
        }

        gatherNodeInputs(nodeId) {
            const inputs = {};
            const incomingConns = this.workflow.getIncomingConnections(nodeId);
            
            for (const conn of incomingConns) {
                const sourceResult = this.nodeResults.get(conn.from.nodeId);
                
                if (sourceResult !== undefined) {
                    // Handle different output port types
                    if (conn.from.port === 'output' && typeof sourceResult !== 'object') {
                        inputs[conn.to.port] = sourceResult;
                    } else if (typeof sourceResult === 'object' && conn.from.port in sourceResult) {
                        inputs[conn.to.port] = sourceResult[conn.from.port];
                    } else {
                        inputs[conn.to.port] = sourceResult;
                    }
                }
            }
            
            // Merge with node's configured inputs
            const node = this.workflow.nodes.get(nodeId);
            return { ...node.config, ...inputs };
        }

        topologicalSort() {
            const sorted = [];
            const visited = new Set();
            const visiting = new Set();
            
            const visit = (nodeId) => {
                if (visited.has(nodeId)) return;
                if (visiting.has(nodeId)) {
                    throw new Error('Workflow contains cycles');
                }
                
                visiting.add(nodeId);
                
                // Visit dependencies first
                const incoming = this.workflow.getIncomingConnections(nodeId);
                for (const conn of incoming) {
                    visit(conn.from.nodeId);
                }
                
                visiting.delete(nodeId);
                visited.add(nodeId);
                sorted.push(nodeId);
            };
            
            // Start with nodes that have no dependencies
            for (const nodeId of this.workflow.nodes.keys()) {
                if (this.workflow.getIncomingConnections(nodeId).length === 0) {
                    visit(nodeId);
                }
            }
            
            // Visit remaining nodes
            for (const nodeId of this.workflow.nodes.keys()) {
                visit(nodeId);
            }
            
            return sorted;
        }

        collectResults() {
            const results = {};
            
            // Find output nodes
            for (const [nodeId, node] of this.workflow.nodes) {
                if (node.type === 'output') {
                    const result = this.nodeResults.get(nodeId);
                    if (node.config.name) {
                        results[node.config.name] = result;
                    } else {
                        results[nodeId] = result;
                    }
                }
            }
            
            // If no output nodes, return all results
            if (Object.keys(results).length === 0) {
                for (const [nodeId, result] of this.nodeResults) {
                    results[nodeId] = result;
                }
            }
            
            return results;
        }
    }

    // Register node types
    WorkflowEngine.nodeTypes = {
        agent: AgentNode,
        composer: ComposerNode,
        input: InputNode,
        output: OutputNode
    };

    // Public API
    WorkflowEngine.createWorkflow = function(name) {
        const workflow = new Workflow(null, name);
        this.workflows.set(workflow.id, workflow);
        return workflow;
    };

    WorkflowEngine.loadWorkflow = function(json) {
        const workflow = Workflow.fromJSON(json);
        this.workflows.set(workflow.id, workflow);
        return workflow;
    };

    WorkflowEngine.executeWorkflow = async function(workflowId, context = {}) {
        const workflow = this.workflows.get(workflowId);
        if (!workflow) throw new Error(`Workflow ${workflowId} not found`);
        
        const executor = new WorkflowExecutor(workflow, context);
        const executionId = `exec_${Date.now()}`;
        
        this.activeExecutions.set(executionId, executor);
        
        try {
            const results = await executor.execute();
            this.activeExecutions.delete(executionId);
            return results;
        } catch (error) {
            this.activeExecutions.delete(executionId);
            throw error;
        }
    };

    // Helper functions for creating nodes
    WorkflowEngine.createAgentNode = function(agentRole) {
        return new AgentNode(agentRole);
    };

    WorkflowEngine.createComposerNode = function() {
        return new ComposerNode();
    };

    WorkflowEngine.createInputNode = function() {
        return new InputNode();
    };

    WorkflowEngine.createOutputNode = function() {
        return new OutputNode();
    };

    // Export classes for extension
    WorkflowEngine.Workflow = Workflow;
    WorkflowEngine.WorkflowNode = WorkflowNode;
    WorkflowEngine.AgentNode = AgentNode;
    WorkflowEngine.ComposerNode = ComposerNode;
    WorkflowEngine.WorkflowExecutor = WorkflowExecutor;

    console.log('Workflow Engine initialized');

})();
