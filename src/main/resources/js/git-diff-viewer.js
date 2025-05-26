/**
 * Git Diff Viewer Module
 * 
 * This module provides an interface between the HTML diff viewer and IntelliJ's
 * git services via the JavaScript bridge.
 */

const GitDiffViewer = {
    /**
     * Initialize the Git Diff Viewer
     * @param {boolean} isDark - Whether to use dark theme
     * @returns {string} HTML content for the diff viewer
     */
    initialize: function(isDark = false) {
        // Load the HTML content from the resource
        return this.loadHTMLContent();
    },

    /**
     * Load the HTML content for the diff viewer
     * @returns {string} HTML content
     */
    loadHTMLContent: function() {
        // In a real implementation, this would load the HTML file dynamically
        // For now, we'll return a placeholder that redirects to the HTML file
        return `
            <div style="height: 100%; width: 100%; display: flex; flex-direction: column;">
                <iframe 
                    src="js/git-diff-viewer.html" 
                    style="flex: 1; border: none; width: 100%; height: 100%;"
                    id="git-diff-viewer-frame">
                </iframe>
            </div>
        `;
    },

    /**
     * Get changed files from git
     * @returns {Promise} Promise that resolves with the list of changed files
     */
    getChangedFiles: function() {
        return new Promise((resolve, reject) => {
            if (window.intellijBridge && window.intellijBridge.callIDE) {
                // Call IDE to get VCS status
                window.intellijBridge.callIDE('getVCSStatus', {})
                    .then(function(response) {
                        if (response && response.success) {
                            resolve(response.files || []);
                        } else {
                            reject(new Error('Failed to get changed files: ' + (response.error || 'Unknown error')));
                        }
                    })
                    .catch(function(error) {
                        reject(error);
                    });
            } else {
                // Fallback for testing
                console.warn('IntelliJ Bridge not available, using mock data');
                
                // Mock data similar to what we saw in your project
                const mockFiles = [
                    { path: 'src/main/java/com/zps/zest/browser/GitService.java', status: 'MODIFICATION' },
                    { path: 'src/main/resources/js/git-ui.js', status: 'MODIFICATION' },
                    { path: 'src/main/java/com/zps/zest/browser/JavaScriptBridgeActions.java', status: 'MODIFICATION' },
                    { path: 'java_pid15848.hprof', status: 'ADDITION' }
                ];
                
                setTimeout(() => resolve(mockFiles), 300);
            }
        });
    },

    /**
     * Get diff for a specific file
     * @param {string} filePath - Path to the file
     * @param {string} status - Status of the file (e.g., "MODIFICATION")
     * @returns {Promise} Promise that resolves with the diff text
     */
    getFileDiff: function(filePath, status) {
        return new Promise((resolve, reject) => {
            if (window.intellijBridge && window.intellijBridge.callIDE) {
                window.intellijBridge.callIDE('getFileDiff', {
                    filePath: filePath,
                    status: status
                })
                .then(function(response) {
                    if (response && response.success) {
                        resolve(response.diff);
                    } else {
                        reject(new Error('Failed to get file diff: ' + (response.error || 'Unknown error')));
                    }
                })
                .catch(function(error) {
                    reject(error);
                });
            } else {
                // Fallback for testing
                console.warn('IntelliJ Bridge not available, using mock diff');
                
                // Generate a simple mock diff
                setTimeout(() => {
                    const mockDiff = `diff --git a/${filePath} b/${filePath}
index 123456..789abc 100644
--- a/${filePath}
+++ b/${filePath}
@@ -10,7 +10,7 @@ public class GitService {
     private final Project project;
     private final Gson gson = new Gson();
     
-    // Map to store contexts
+    // Static map to store contexts across all instances
     private static final ConcurrentHashMap<String, GitCommitContext> GLOBAL_CONTEXTS = new ConcurrentHashMap<>();
     
     public GitService(@NotNull Project project) {
@@ -25,6 +25,7 @@ public class GitService {
     public String handleCommitWithMessage(JsonObject data) {
         LOG.info("Processing commit with message: " + data.toString());

+        // Added new implementation
         try {
             // Parse commit message
             String commitMessage = data.get("message").getAsString();`;
                    
                    resolve(mockDiff);
                }, 300);
            }
        });
    },

    /**
     * Confirm changes to a file
     * @param {string} filePath - Path to the file
     * @param {string} status - Status of the file
     * @returns {Promise} Promise that resolves when the changes are confirmed
     */
    confirmChanges: function(filePath, status) {
        return new Promise((resolve, reject) => {
            if (window.intellijBridge && window.intellijBridge.callIDE) {
                // Open the file diff in IntelliJ's native diff viewer
                window.intellijBridge.callIDE('openFileDiffInIDE', {
                    filePath: filePath,
                    status: status
                })
                .then(function(response) {
                    if (response && response.success) {
                