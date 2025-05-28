/**
 * Research Agent for IntelliJ JCEF Browser
 * Facilitates text search and JavaScript syntax analysis across folders recursively
 */

(function() {
    'use strict';

    // Define Research Agent Role
    const ResearchAgentRole = {
        id: 'research_agent',
        name: 'Research Agent',
        description: 'Searches code, analyzes JavaScript syntax, and finds function usage across projects',
        capabilities: ['search_text', 'find_syntax', 'analyze_usage', 'find_references', 'extract_signatures'],
        priority: 8
    };

    /**
     * Research Agent Class
     */
    class ResearchAgent extends window.AgentFramework.Agent {
        constructor(config = {}) {
            super(ResearchAgentRole, config);
            
            // Cache for search results
            this.searchCache = new Map();
            this.syntaxCache = new Map();
            
            // Configuration
            this.searchConfig = {
                maxResults: 100,
                contextLines: 3,
                excludePatterns: ['.git', 'node_modules', 'dist', 'build', '.idea'],
                fileExtensions: ['.js', '.jsx', '.ts', '.tsx', '.mjs', '.cjs'],
                cacheTimeout: 300000 // 5 minutes
            };
            
            // JavaScript syntax patterns
            this.syntaxPatterns = {
                functionDeclaration: /function\s+([a-zA-Z_$][a-zA-Z0-9_$]*)\s*\([^)]*\)\s*{/g,
                functionExpression: /(?:const|let|var)\s+([a-zA-Z_$][a-zA-Z0-9_$]*)\s*=\s*function\s*\([^)]*\)\s*{/g,
                arrowFunction: /(?:const|let|var)\s+([a-zA-Z_$][a-zA-Z0-9_$]*)\s*=\s*\([^)]*\)\s*=>/g,
                arrowFunctionShort: /(?:const|let|var)\s+([a-zA-Z_$][a-zA-Z0-9_$]*)\s*=\s*[a-zA-Z_$][a-zA-Z0-9_$]*\s*=>/g,
                methodDefinition: /([a-zA-Z_$][a-zA-Z0-9_$]*)\s*\([^)]*\)\s*{/g,
                asyncFunction: /async\s+(?:function\s+)?([a-zA-Z_$][a-zA-Z0-9_$]*)\s*\([^)]*\)\s*{/g,
                classMethod: /(?:async\s+)?([a-zA-Z_$][a-zA-Z0-9_$]*)\s*\([^)]*\)\s*{/g,
                exportedFunction: /export\s+(?:default\s+)?(?:async\s+)?function\s+([a-zA-Z_$][a-zA-Z0-9_$]*)/g
            };
        }

        /**
         * Initialize the research agent
         */
        async initialize() {
            await super.initialize();
            console.log('Research Agent initialized with advanced search capabilities');
            return this;
        }

        /**
         * Handle text search across folders
         */
        async handle_search_text(task) {
            const { searchText, folderPath = '/', options = {} } = task.data;
            
            console.log(`Searching for "${searchText}" in ${folderPath}`);
            
            // Check cache first
            const cacheKey = `text_${searchText}_${folderPath}`;
            const cached = this.getCachedResult(cacheKey);
            if (cached) return cached;
            
            try {
                // TODO: Replace with actual file API implementation
                const results = await this.searchTextInFolder(searchText, folderPath, options);
                
                // Process and format results
                const formattedResults = this.formatSearchResults(results);
                
                // Cache results
                this.setCachedResult(cacheKey, formattedResults);
                
                // Send results to IDE if needed
                if (options.showInIDE) {
                    await window.intellijBridge.callIDE('showSearchResults', {
                        query: searchText,
                        results: formattedResults
                    });
                }
                
                return formattedResults;
                
            } catch (error) {
                console.error('Text search failed:', error);
                throw error;
            }
        }

        /**
         * Handle JavaScript syntax search
         */
        async handle_find_syntax(task) {
            const { functionName, folderPath = '/', options = {} } = task.data;
            
            console.log(`Finding syntax for function "${functionName}" in ${folderPath}`);
            
            // Check cache
            const cacheKey = `syntax_${functionName}_${folderPath}`;
            const cached = this.getCachedResult(cacheKey);
            if (cached) return cached;
            
            try {
                // TODO: Replace with actual file API implementation
                const files = await this.getJavaScriptFiles(folderPath, options);
                const syntaxResults = [];
                
                for (const file of files) {
                    const content = await this.readFile(file);
                    const matches = this.findFunctionSyntax(content, functionName);
                    
                    if (matches.length > 0) {
                        syntaxResults.push({
                            file: file,
                            matches: matches,
                            content: content
                        });
                    }
                }
                
                // Analyze and format results
                const analysis = this.analyzeSyntaxResults(syntaxResults, functionName);
                
                // Cache results
                this.setCachedResult(cacheKey, analysis);
                
                return analysis;
                
            } catch (error) {
                console.error('Syntax search failed:', error);
                throw error;
            }
        }

        /**
         * Handle function usage analysis
         */
        async handle_analyze_usage(task) {
            const { functionName, folderPath = '/', options = {} } = task.data;
            
            console.log(`Analyzing usage of "${functionName}" in ${folderPath}`);
            
            try {
                // Find all occurrences of the function
                const usagePattern = new RegExp(`\\b${functionName}\\s*\\(`, 'g');
                const importPattern = new RegExp(`import.*\\b${functionName}\\b.*from`, 'g');
                const requirePattern = new RegExp(`require.*\\b${functionName}\\b`, 'g');
                
                // TODO: Replace with actual file API implementation
                const files = await this.getJavaScriptFiles(folderPath, options);
                const usageResults = [];
                
                for (const file of files) {
                    const content = await this.readFile(file);
                    const usages = this.findUsages(content, functionName, {
                        usagePattern,
                        importPattern,
                        requirePattern
                    });
                    
                    if (usages.length > 0) {
                        usageResults.push({
                            file: file,
                            usages: usages,
                            count: usages.length
                        });
                    }
                }
                
                // Create usage report
                const report = this.createUsageReport(functionName, usageResults);
                
                return report;
                
            } catch (error) {
                console.error('Usage analysis failed:', error);
                throw error;
            }
        }

        /**
         * Handle finding all references
         */
        async handle_find_references(task) {
            const { identifier, folderPath = '/', options = {} } = task.data;
            
            console.log(`Finding references to "${identifier}" in ${folderPath}`);
            
            try {
                // Comprehensive reference search
                const references = await this.findAllReferences(identifier, folderPath, options);
                
                // Group by type
                const groupedReferences = this.groupReferencesByType(references);
                
                // Generate reference map
                const referenceMap = this.createReferenceMap(identifier, groupedReferences);
                
                return referenceMap;
                
            } catch (error) {
                console.error('Reference search failed:', error);
                throw error;
            }
        }

        /**
         * Handle extracting function signatures
         */
        async handle_extract_signatures(task) {
            const { folderPath = '/', options = {} } = task.data;
            
            console.log(`Extracting function signatures from ${folderPath}`);
            
            try {
                // TODO: Replace with actual file API implementation
                const files = await this.getJavaScriptFiles(folderPath, options);
                const signatures = new Map();
                
                for (const file of files) {
                    const content = await this.readFile(file);
                    const extractedSignatures = this.extractFunctionSignatures(content);
                    
                    if (extractedSignatures.length > 0) {
                        signatures.set(file, extractedSignatures);
                    }
                }
                
                // Create signature catalog
                const catalog = this.createSignatureCatalog(signatures);
                
                return catalog;
                
            } catch (error) {
                console.error('Signature extraction failed:', error);
                throw error;
            }
        }

        /**
         * Search text in folder recursively
         * TODO: Implement with actual file API
         */
        async searchTextInFolder(searchText, folderPath, options) {
            // Placeholder for file API implementation
            console.log('TODO: Implement searchTextInFolder with file API');
            
            // Simulated implementation
            const results = [];
            const searchRegex = new RegExp(searchText, options.caseSensitive ? 'g' : 'gi');
            
            // TODO: Replace with actual recursive file search
            // const files = await fileAPI.listFilesRecursive(folderPath, {
            //     exclude: this.searchConfig.excludePatterns,
            //     extensions: options.fileTypes || this.searchConfig.fileExtensions
            // });
            
            // for (const file of files) {
            //     const content = await fileAPI.readFile(file);
            //     const matches = this.findMatches(content, searchRegex, file);
            //     if (matches.length > 0) {
            //         results.push({ file, matches });
            //     }
            // }
            
            return results;
        }

        /**
         * Get JavaScript files from folder
         * TODO: Implement with actual file API
         */
        async getJavaScriptFiles(folderPath, options) {
            // Placeholder for file API implementation
            console.log('TODO: Implement getJavaScriptFiles with file API');
            
            // TODO: Replace with actual implementation
            // return await fileAPI.listFilesRecursive(folderPath, {
            //     exclude: this.searchConfig.excludePatterns,
            //     extensions: this.searchConfig.fileExtensions,
            //     ...options
            // });
            
            return [];
        }

        /**
         * Read file content
         * TODO: Implement with actual file API
         */
        async readFile(filePath) {
            // Placeholder for file API implementation
            console.log('TODO: Implement readFile with file API');
            
            // TODO: Replace with actual implementation
            // return await fileAPI.readFile(filePath, 'utf8');
            
            return '';
        }

        /**
         * Find function syntax in content
         */
        findFunctionSyntax(content, functionName) {
            const matches = [];
            const lines = content.split('\n');
            
            // Search with all patterns
            for (const [patternName, pattern] of Object.entries(this.syntaxPatterns)) {
                pattern.lastIndex = 0; // Reset regex
                let match;
                
                while ((match = pattern.exec(content)) !== null) {
                    if (match[1] === functionName || match[0].includes(functionName)) {
                        const position = match.index;
                        const lineNumber = content.substring(0, position).split('\n').length;
                        const line = lines[lineNumber - 1];
                        
                        // Extract full function definition
                        const functionEnd = this.findFunctionEnd(content, position);
                        const functionBody = content.substring(position, functionEnd);
                        
                        matches.push({
                            type: patternName,
                            line: lineNumber,
                            column: position - content.lastIndexOf('\n', position - 1),
                            text: line.trim(),
                            fullDefinition: functionBody,
                            signature: this.extractSignature(functionBody)
                        });
                    }
                }
            }
            
            return matches;
        }

        /**
         * Find the end of a function definition
         */
        findFunctionEnd(content, startPos) {
            let braceCount = 0;
            let inString = false;
            let stringChar = '';
            let escaped = false;
            
            for (let i = startPos; i < content.length; i++) {
                const char = content[i];
                
                if (escaped) {
                    escaped = false;
                    continue;
                }
                
                if (char === '\\') {
                    escaped = true;
                    continue;
                }
                
                if (inString) {
                    if (char === stringChar) {
                        inString = false;
                    }
                } else {
                    if (char === '"' || char === "'" || char === '`') {
                        inString = true;
                        stringChar = char;
                    } else if (char === '{') {
                        braceCount++;
                    } else if (char === '}') {
                        braceCount--;
                        if (braceCount === 0) {
                            return i + 1;
                        }
                    }
                }
            }
            
            return content.length;
        }

        /**
         * Extract function signature
         */
        extractSignature(functionBody) {
            const signatureMatch = functionBody.match(/^[^{]+/);
            if (signatureMatch) {
                return signatureMatch[0].trim().replace(/\s+/g, ' ');
            }
            return '';
        }

        /**
         * Find usages of a function
         */
        findUsages(content, functionName, patterns) {
            const usages = [];
            const lines = content.split('\n');
            
            // Find direct calls
            let match;
            patterns.usagePattern.lastIndex = 0;
            while ((match = patterns.usagePattern.exec(content)) !== null) {
                const lineNumber = content.substring(0, match.index).split('\n').length;
                usages.push({
                    type: 'call',
                    line: lineNumber,
                    text: lines[lineNumber - 1].trim(),
                    context: this.getContext(lines, lineNumber - 1)
                });
            }
            
            // Find imports
            patterns.importPattern.lastIndex = 0;
            while ((match = patterns.importPattern.exec(content)) !== null) {
                const lineNumber = content.substring(0, match.index).split('\n').length;
                usages.push({
                    type: 'import',
                    line: lineNumber,
                    text: lines[lineNumber - 1].trim()
                });
            }
            
            // Find requires
            patterns.requirePattern.lastIndex = 0;
            while ((match = patterns.requirePattern.exec(content)) !== null) {
                const lineNumber = content.substring(0, match.index).split('\n').length;
                usages.push({
                    type: 'require',
                    line: lineNumber,
                    text: lines[lineNumber - 1].trim()
                });
            }
            
            return usages;
        }

        /**
         * Get context lines around a match
         */
        getContext(lines, lineIndex, contextSize = 3) {
            const start = Math.max(0, lineIndex - contextSize);
            const end = Math.min(lines.length, lineIndex + contextSize + 1);
            
            return {
                before: lines.slice(start, lineIndex),
                current: lines[lineIndex],
                after: lines.slice(lineIndex + 1, end)
            };
        }

        /**
         * Find all references to an identifier
         */
        async findAllReferences(identifier, folderPath, options) {
            const references = [];
            
            // TODO: Implement with file API
            console.log('TODO: Implement findAllReferences with file API');
            
            // Would search for:
            // - Variable declarations
            // - Function calls
            // - Property access
            // - Method calls
            // - Import/export statements
            // - Type references (in TypeScript)
            
            return references;
        }

        /**
         * Extract all function signatures from content
         */
        extractFunctionSignatures(content) {
            const signatures = [];
            const processedFunctions = new Set();
            
            // Extract from all patterns
            for (const [patternName, pattern] of Object.entries(this.syntaxPatterns)) {
                pattern.lastIndex = 0;
                let match;
                
                while ((match = pattern.exec(content)) !== null) {
                    const functionName = match[1];
                    if (functionName && !processedFunctions.has(functionName)) {
                        processedFunctions.add(functionName);
                        
                        const position = match.index;
                        const functionEnd = this.findFunctionEnd(content, position);
                        const functionBody = content.substring(position, functionEnd);
                        const signature = this.extractSignature(functionBody);
                        
                        // Parse parameters
                        const params = this.parseParameters(signature);
                        
                        // Detect return type (basic heuristic)
                        const returnType = this.detectReturnType(functionBody);
                        
                        signatures.push({
                            name: functionName,
                            type: patternName,
                            signature: signature,
                            parameters: params,
                            returnType: returnType,
                            isAsync: signature.includes('async'),
                            isExported: signature.includes('export')
                        });
                    }
                }
            }
            
            return signatures;
        }

        /**
         * Parse function parameters
         */
        parseParameters(signature) {
            const paramMatch = signature.match(/\(([^)]*)\)/);
            if (!paramMatch || !paramMatch[1].trim()) {
                return [];
            }
            
            const paramString = paramMatch[1];
            const params = [];
            let current = '';
            let depth = 0;
            
            for (const char of paramString) {
                if (char === '(' || char === '{' || char === '[') depth++;
                else if (char === ')' || char === '}' || char === ']') depth--;
                
                if (char === ',' && depth === 0) {
                    params.push(this.parseParameter(current.trim()));
                    current = '';
                } else {
                    current += char;
                }
            }
            
            if (current.trim()) {
                params.push(this.parseParameter(current.trim()));
            }
            
            return params;
        }

        /**
         * Parse individual parameter
         */
        parseParameter(paramStr) {
            const defaultMatch = paramStr.match(/^([^=]+)=(.+)$/);
            if (defaultMatch) {
                return {
                    name: defaultMatch[1].trim(),
                    defaultValue: defaultMatch[2].trim(),
                    isOptional: true
                };
            }
            
            const destructuringMatch = paramStr.match(/^{([^}]+)}$/);
            if (destructuringMatch) {
                return {
                    name: paramStr,
                    isDestructured: true,
                    properties: destructuringMatch[1].split(',').map(p => p.trim())
                };
            }
            
            return {
                name: paramStr,
                isOptional: false
            };
        }

        /**
         * Detect return type from function body
         */
        detectReturnType(functionBody) {
            // Simple heuristic - can be enhanced
            if (functionBody.includes('return new Promise')) return 'Promise';
            if (functionBody.includes('return await')) return 'Promise';
            if (/return\s+{/.test(functionBody)) return 'Object';
            if (/return\s+\[/.test(functionBody)) return 'Array';
            if (/return\s+['"`]/.test(functionBody)) return 'string';
            if (/return\s+\d+/.test(functionBody)) return 'number';
            if (/return\s+(true|false)/.test(functionBody)) return 'boolean';
            if (/return\s+null/.test(functionBody)) return 'null';
            if (/return\s+undefined/.test(functionBody)) return 'undefined';
            if (functionBody.includes('return')) return 'unknown';
            return 'void';
        }

        /**
         * Format search results
         */
        formatSearchResults(results) {
            return {
                query: results.query,
                totalMatches: results.reduce((sum, r) => sum + r.matches.length, 0),
                files: results.map(r => ({
                    path: r.file,
                    matchCount: r.matches.length,
                    matches: r.matches.map(m => ({
                        line: m.line,
                        column: m.column,
                        text: m.text,
                        context: m.context
                    }))
                })),
                timestamp: Date.now()
            };
        }

        /**
         * Analyze syntax results
         */
        analyzeSyntaxResults(results, functionName) {
            const analysis = {
                functionName: functionName,
                totalOccurrences: results.length,
                definitions: [],
                variations: new Set(),
                commonPatterns: {}
            };
            
            for (const result of results) {
                for (const match of result.matches) {
                    analysis.definitions.push({
                        file: result.file,
                        type: match.type,
                        line: match.line,
                        signature: match.signature,
                        fullDefinition: match.fullDefinition
                    });
                    
                    analysis.variations.add(match.signature);
                    analysis.commonPatterns[match.type] = (analysis.commonPatterns[match.type] || 0) + 1;
                }
            }
            
            analysis.variations = Array.from(analysis.variations);
            
            return analysis;
        }

        /**
         * Create usage report
         */
        createUsageReport(functionName, usageResults) {
            const report = {
                functionName: functionName,
                summary: {
                    totalFiles: usageResults.length,
                    totalUsages: usageResults.reduce((sum, r) => sum + r.count, 0),
                    usageTypes: {}
                },
                files: usageResults,
                graph: this.createUsageGraph(usageResults)
            };
            
            // Count usage types
            for (const result of usageResults) {
                for (const usage of result.usages) {
                    report.summary.usageTypes[usage.type] = 
                        (report.summary.usageTypes[usage.type] || 0) + 1;
                }
            }
            
            return report;
        }

        /**
         * Create usage graph for visualization
         */
        createUsageGraph(usageResults) {
            const nodes = [];
            const edges = [];
            
            // Add function node
            nodes.push({
                id: 'target',
                type: 'function',
                label: 'Target Function'
            });
            
            // Add file nodes and edges
            for (const result of usageResults) {
                const fileId = `file_${nodes.length}`;
                nodes.push({
                    id: fileId,
                    type: 'file',
                    label: result.file.split('/').pop(),
                    fullPath: result.file,
                    usageCount: result.count
                });
                
                edges.push({
                    source: fileId,
                    target: 'target',
                    weight: result.count
                });
            }
            
            return { nodes, edges };
        }

        /**
         * Group references by type
         */
        groupReferencesByType(references) {
            const groups = {
                declarations: [],
                calls: [],
                imports: [],
                exports: [],
                properties: [],
                other: []
            };
            
            for (const ref of references) {
                if (groups[ref.type]) {
                    groups[ref.type].push(ref);
                } else {
                    groups.other.push(ref);
                }
            }
            
            return groups;
        }

        /**
         * Create reference map
         */
        createReferenceMap(identifier, groupedReferences) {
            return {
                identifier: identifier,
                summary: {
                    totalReferences: Object.values(groupedReferences).reduce((sum, g) => sum + g.length, 0),
                    byType: Object.entries(groupedReferences).map(([type, refs]) => ({
                        type,
                        count: refs.length
                    }))
                },
                references: groupedReferences,
                callGraph: this.createCallGraph(identifier, groupedReferences),
                timestamp: Date.now()
            };
        }

        /**
         * Create call graph
         */
        createCallGraph(identifier, groupedReferences) {
            // Build a graph showing how the identifier is used
            const graph = {
                root: identifier,
                children: []
            };
            
            // Add declaration nodes
            for (const decl of groupedReferences.declarations) {
                graph.children.push({
                    type: 'declaration',
                    file: decl.file,
                    line: decl.line
                });
            }
            
            // Add call nodes
            for (const call of groupedReferences.calls) {
                graph.children.push({
                    type: 'call',
                    file: call.file,
                    line: call.line,
                    context: call.context
                });
            }
            
            return graph;
        }

        /**
         * Create signature catalog
         */
        createSignatureCatalog(signatures) {
            const catalog = {
                totalFiles: signatures.size,
                totalFunctions: 0,
                byType: {},
                byFile: {},
                index: []
            };
            
            for (const [file, sigs] of signatures) {
                catalog.totalFunctions += sigs.length;
                catalog.byFile[file] = sigs;
                
                for (const sig of sigs) {
                    // Group by type
                    if (!catalog.byType[sig.type]) {
                        catalog.byType[sig.type] = [];
                    }
                    catalog.byType[sig.type].push({
                        ...sig,
                        file: file
                    });
                    
                    // Add to index
                    catalog.index.push({
                        name: sig.name,
                        file: file,
                        signature: sig.signature,
                        type: sig.type
                    });
                }
            }
            
            // Sort index alphabetically
            catalog.index.sort((a, b) => a.name.localeCompare(b.name));
            
            return catalog;
        }

        /**
         * Cache management
         */
        getCachedResult(key) {
            const cached = this.searchCache.get(key);
            if (cached && Date.now() - cached.timestamp < this.searchConfig.cacheTimeout) {
                console.log(`Cache hit for ${key}`);
                return cached.data;
            }
            return null;
        }

        setCachedResult(key, data) {
            this.searchCache.set(key, {
                data: data,
                timestamp: Date.now()
            });
            
            // Clean old cache entries
            this.cleanCache();
        }

        cleanCache() {
            const now = Date.now();
            for (const [key, value] of this.searchCache) {
                if (now - value.timestamp > this.searchConfig.cacheTimeout) {
                    this.searchCache.delete(key);
                }
            }
        }

        /**
         * Advanced search with AST parsing (placeholder)
         */
        async performASTSearch(code, searchCriteria) {
            console.log('TODO: Implement AST-based search for more accurate results');
            // This would use a JavaScript parser like acorn or babel to build an AST
            // and search through it for more accurate results
            return [];
        }

        /**
         * Export search results
         */
        async exportResults(results, format = 'json') {
            const exportData = {
                timestamp: Date.now(),
                agent: this.role.name,
                results: results
            };
            
            switch (format) {
                case 'json':
                    return JSON.stringify(exportData, null, 2);
                    
                case 'csv':
                    return this.convertToCSV(exportData);
                    
                case 'markdown':
                    return this.convertToMarkdown(exportData);
                    
                default:
                    throw new Error(`Unsupported export format: ${format}`);
            }
        }

        /**
         * Convert results to CSV
         */
        convertToCSV(data) {
            // Simple CSV conversion
            let csv = 'File,Line,Type,Text\n';
            
            if (data.results.files) {
                for (const file of data.results.files) {
                    for (const match of file.matches) {
                        csv += `"${file.path}",${match.line},"${match.type || 'match'}","${match.text.replace(/"/g, '""')}"\n`;
                    }
                }
            }
            
            return csv;
        }

        /**
         * Convert results to Markdown
         */
        convertToMarkdown(data) {
            let markdown = `# Search Results\n\n`;
            markdown += `**Timestamp:** ${new Date(data.timestamp).toISOString()}\n\n`;
            
            if (data.results.summary) {
                markdown += `## Summary\n\n`;
                markdown += `- Total Files: ${data.results.summary.totalFiles || 0}\n`;
                markdown += `- Total Matches: ${data.results.summary.totalMatches || 0}\n\n`;
            }
            
            if (data.results.files) {
                markdown += `## Results by File\n\n`;
                for (const file of data.results.files) {
                    markdown += `### ${file.path}\n\n`;
                    for (const match of file.matches) {
                        markdown += `- Line ${match.line}: \`${match.text}\`\n`;
                    }
                    markdown += '\n';
                }
            }
            
            return markdown;
        }
    }

    // Register the Research Agent role with the framework
    if (window.AgentFramework && window.AgentFramework.AgentRoles) {
        window.AgentFramework.AgentRoles.RESEARCH = ResearchAgentRole;
        
        // Add to agent manager
        window.AgentFramework.AgentManager.createAgent = (function(originalCreate) {
            return async function(roleId, config) {
                if (roleId === 'RESEARCH') {
                    const agent = new ResearchAgent(config);
                    await agent.initialize();
                    this.agents.set(agent.id, agent);
                    return agent;
                }
                return originalCreate.call(this, roleId, config);
            };
        })(window.AgentFramework.AgentManager.createAgent);
        
        console.log('Research Agent registered with Agent Framework');
    } else {
        console.warn('Agent Framework not found. Research Agent running standalone.');
    }

    // Export for direct use
    window.ResearchAgent = ResearchAgent;
    window.ResearchAgentRole = ResearchAgentRole;

    // Integration with IntelliJ Bridge
    if (window.intellijBridge) {
        window.intellijBridge.researchAgent = {
            create: async (config) => {
                const agent = new ResearchAgent(config);
                await agent.initialize();
                return agent;
            },
            search: async (searchText, folderPath, options) => {
                const agent = new ResearchAgent();
                await agent.initialize();
                return agent.handle_search_text({
                    data: { searchText, folderPath, options }
                });
            },
            findSyntax: async (functionName, folderPath, options) => {
                const agent = new ResearchAgent();
                await agent.initialize();
                return agent.handle_find_syntax({
                    data: { functionName, folderPath, options }
                });
            }
        };
        
        console.log('Research Agent integrated with IntelliJ Bridge');
    }

    // Usage examples in console
    console.log('%c📚 Research Agent Loaded!', 'color: #4CAF50; font-size: 14px; font-weight: bold;');
    console.log('Example usage:');
    console.log('  const agent = await window.AgentFramework.createAgent("RESEARCH");');
    console.log('  agent.queueTask({ type: "search_text", data: { searchText: "function", folderPath: "/src" } });');
    console.log('  agent.queueTask({ type: "find_syntax", data: { functionName: "handleClick", folderPath: "/" } });');

})();
