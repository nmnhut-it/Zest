/**
 * File API for Research Agent
 * Provides file system operations for the research agent through IntelliJ Bridge
 */

(function() {
    'use strict';
    
    class FileAPI {
        constructor(config = {}) {
            this.config = {
                maxFileSize: 10 * 1024 * 1024, // 10MB default
                encoding: 'utf8',
                excludePatterns: [
                    '.git/**',
                    '**/node_modules/**',
                    '**/dist/**',
                    '**/build/**',
                    '**/.idea/**',
                    '**/coverage/**',
                    '**/.next/**',
                    '**/.nuxt/**',
                    '**/vendor/**',
                    '**/__pycache__/**',
                    '**/*.pyc',
                    '**/.DS_Store',
                    '**/Thumbs.db'
                ],
                fileExtensions: ['.js', '.jsx', '.ts', '.tsx', '.mjs', '.cjs', '.json'],
                ...config
            };
            
            // Cache for file listings to improve performance
            this.directoryCache = new Map();
            this.cacheTimeout = 60000; // 1 minute
        }

        /**
         * Normalize path for consistent handling
         */
        normalizePath(filePath) {
            return filePath.replace(/\\/g, '/');
        }

        /**
         * List files recursively with filtering
         */
        async listFilesRecursive(dirPath, options = {}) {
            const {
                exclude = [],
                extensions = null,
                maxDepth = 5,
                includeDirectories = false
            } = options;

            try {
                // Check cache first
                const cacheKey = `${dirPath}_${JSON.stringify(options)}`;
                const cached = this.getFromCache(cacheKey);
                if (cached) return cached;

                // Call IntelliJ Bridge
                const response = await window.intellijBridge.callIDE('listFiles', {
                    path: dirPath,
                    excludePatterns: [...this.config.excludePatterns, ...exclude],
                    extensions: extensions || this.config.fileExtensions,
                    maxDepth: maxDepth,
                    includeDirectories: includeDirectories
                });

                if (response.success) {
                    const files = response.files || [];
                    // Cache results
                    this.setCache(cacheKey, files);
                    return files;
                } else {
                    throw new Error(response.error || 'Failed to list files');
                }
                
            } catch (error) {
                console.error(`Error listing files in ${dirPath}:`, error);
                throw error;
            }
        }

        /**
         * Read file with size check
         */
        async readFile(filePath, encoding = null) {
            try {
                const response = await window.intellijBridge.callIDE('readFile', {
                    path: filePath,
                    encoding: encoding || this.config.encoding
                });

                if (response.success) {
                    return response.content;
                } else {
                    throw new Error(response.error || 'Failed to read file');
                }
                
            } catch (error) {
                console.error(`Error reading file ${filePath}:`, error);
                throw error;
            }
        }

        /**
         * Read multiple files in parallel
         */
        async readMultipleFiles(filePaths, encoding = null) {
            const results = await Promise.allSettled(
                filePaths.map(filePath => this.readFile(filePath, encoding))
            );
            
            return results.map((result, index) => ({
                path: filePaths[index],
                status: result.status,
                content: result.status === 'fulfilled' ? result.value : null,
                error: result.status === 'rejected' ? result.reason.message : null
            }));
        }

        /**
         * Search for text in files
         */
        async searchInFiles(dirPath, searchText, options = {}) {
            const {
                caseSensitive = false,
                wholeWord = false,
                regex = false,
                exclude = [],
                extensions = null,
                maxResults = 1000,
                contextLines = 3
            } = options;

            try {
                const response = await window.intellijBridge.callIDE('searchInFiles', {
                    path: dirPath,
                    searchText: searchText,
                    caseSensitive: caseSensitive,
                    wholeWord: wholeWord,
                    regex: regex,
                    excludePatterns: [...this.config.excludePatterns, ...exclude],
                    extensions: extensions || this.config.fileExtensions,
                    maxResults: maxResults,
                    contextLines: contextLines
                });

                if (response.success) {
                    return {
                        query: searchText,
                        options,
                        totalMatches: response.totalMatches || 0,
                        results: response.results || []
                    };
                } else {
                    throw new Error(response.error || 'Search failed');
                }
                
            } catch (error) {
                console.error('Search failed:', error);
                throw error;
            }
        }

        /**
         * Find JavaScript functions
         */
        async findFunctions(dirPath, functionName = null, options = {}) {
            const {
                exclude = [],
                includeArrow = true,
                includeClass = true,
                includeExports = true
            } = options;

            try {
                const response = await window.intellijBridge.callIDE('findFunctions', {
                    path: dirPath,
                    functionName: functionName,
                    excludePatterns: [...this.config.excludePatterns, ...exclude],
                    includeArrow: includeArrow,
                    includeClass: includeClass,
                    includeExports: includeExports
                });

                if (response.success) {
                    return response.results || [];
                } else {
                    throw new Error(response.error || 'Failed to find functions');
                }
                
            } catch (error) {
                console.error('Error finding functions:', error);
                throw error;
            }
        }

        /**
         * Get directory tree structure
         */
        async getDirectoryTree(dirPath, options = {}) {
            const {
                maxDepth = 5,
                exclude = [],
                extensions = null
            } = options;

            try {
                const response = await window.intellijBridge.callIDE('getDirectoryTree', {
                    path: dirPath,
                    maxDepth: maxDepth,
                    excludePatterns: [...this.config.excludePatterns, ...exclude],
                    extensions: extensions
                });

                if (response.success) {
                    return response.tree;
                } else {
                    throw new Error(response.error || 'Failed to get directory tree');
                }
                
            } catch (error) {
                console.error(`Error getting directory tree for ${dirPath}:`, error);
                throw error;
            }
        }

        /**
         * Cache management
         */
        getFromCache(key) {
            const cached = this.directoryCache.get(key);
            if (cached && Date.now() - cached.timestamp < this.cacheTimeout) {
                return cached.data;
            }
            this.directoryCache.delete(key);
            return null;
        }

        setCache(key, data) {
            this.directoryCache.set(key, {
                data,
                timestamp: Date.now()
            });
            
            // Limit cache size
            if (this.directoryCache.size > 100) {
                const firstKey = this.directoryCache.keys().next().value;
                this.directoryCache.delete(firstKey);
            }
        }

        clearCache() {
            this.directoryCache.clear();
        }

        /**
         * Get file stats
         */
        async getFileStats(filePath) {
            try {
                // For now, we'll return basic info from readFile
                // Could be extended with a dedicated stats endpoint
                await this.readFile(filePath);
                return {
                    exists: true,
                    isFile: true,
                    isDirectory: false
                };
            } catch (error) {
                return {
                    exists: false,
                    error: error.message
                };
            }
        }
    }

    // Export for browser/ES modules
    if (typeof window !== 'undefined') {
        window.FileAPI = FileAPI;
    }

    // Export for Node.js (if needed for testing)
    if (typeof module !== 'undefined' && module.exports) {
        module.exports = FileAPI;
    }
})();
