package com.zps.zest.browser;

import com.intellij.icons.AllIcons;
import com.intellij.ide.DataManager;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionPlaces;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;
import com.zps.zest.ConfigurationManager;
import org.apache.commons.lang.StringEscapeUtils;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefLoadHandlerAdapter;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Panel containing the web browser and navigation controls.
 */
public class WebBrowserPanel implements Disposable {
    private static final Logger LOG = Logger.getInstance(WebBrowserPanel.class);
    private BrowserMode agentMode;

    /**
     * Simple class representing a browser mode.
     */
    static class BrowserMode {
        private final String name;
        private final Icon icon;
        private final String tooltip;
        private final Function<Project, String> promptProvider;

        public BrowserMode(String name, Icon icon, String tooltip, Function<Project, String> promptProvider) {
            this.name = name;
            this.icon = icon;
            this.tooltip = tooltip;
            this.promptProvider = promptProvider;
        }

        public String getName() {
            return name;
        }

        public Icon getIcon() {
            return icon;
        }

        public String getTooltip() {
            return tooltip;
        }

        public String getPrompt(Project project) {
            return promptProvider != null ? promptProvider.apply(project) : null;
        }
    }

    private final Project project;
    private final JPanel mainPanel;
    private final JCEFBrowserManager browserManager;
    private final BrowserPurpose browserPurpose;
    private final JBTextField urlField;
    private final JButton modeButton;
    private final List<BrowserMode> browserModes = new ArrayList<>();
    private BrowserMode currentMode;

    public BrowserMode getCurrentMode() {
        return currentMode;
    }

    /**
     * Creates a new web browser panel with default purpose.
     */
    public WebBrowserPanel(Project project) {
        this(project, true); // Default: include navigation panel
    }

    /**
     * Creates a new web browser panel with optional navigation panel.
     */
    public WebBrowserPanel(Project project, boolean includeNavigation) {
        this(project, includeNavigation, BrowserPurpose.WEB_BROWSER); // Default purpose
    }

    /**
     * Creates a new web browser panel with specified purpose.
     */
    public WebBrowserPanel(Project project, BrowserPurpose purpose) {
        this(project, true, purpose);
    }

    /**
     * Creates a new web browser panel with optional navigation panel and specified purpose.
     * @param project The project context
     * @param includeNavigation Whether to include navigation controls
     * @param purpose The purpose for this browser instance
     */
    public WebBrowserPanel(Project project, boolean includeNavigation, BrowserPurpose purpose) {
        this.project = project;
        this.mainPanel = new JPanel(new BorderLayout());
        this.browserPurpose = purpose;

        // Get browser manager from service for the specified purpose
        this.browserManager = JCEFBrowserService.getInstance(project).getBrowserManager(purpose);

        // Initialize browser modes
        initBrowserModes();

        if (includeNavigation) {
            // Create navigation panel
            JPanel navigationPanel = createNavigationPanel();
            navigationPanel.setName("navigationPanel");
            mainPanel.add(navigationPanel, BorderLayout.NORTH);

            // Get UI references
            this.urlField = (JBTextField) navigationPanel.getComponent(1);
            this.modeButton = (JButton) ((JPanel) navigationPanel.getComponent(0)).getComponent(1);
        } else {
            // No navigation panel - set references to null
            this.urlField = null;
            this.modeButton = null;
        }

        // Add browser component
        mainPanel.add(browserManager.getComponent(), BorderLayout.CENTER);

        // Set default mode to Agent Mode (index 3)
        setMode(browserModes.get(3)); // Agent Mode

        // Add load handler to inject tool server URL when page loads
        browserManager.getBrowser().getJBCefClient().addLoadHandler(new org.cef.handler.CefLoadHandlerAdapter() {
            @Override
            public void onLoadEnd(org.cef.browser.CefBrowser browser, org.cef.browser.CefFrame frame, int httpStatusCode) {
                setMode(currentMode);

                // Get tool server URL from service
                com.zps.zest.mcp.ToolApiServerService toolServerService = project.getService(com.zps.zest.mcp.ToolApiServerService.class);
                if (toolServerService != null && toolServerService.isRunning()) {
                    String toolServerUrl = toolServerService.getBaseUrl();

                    if (toolServerUrl != null) {
                        // Inject tool server URL for JavaScript access
                        String script = "window.__tool_server_url__ = '" + toolServerUrl + "';";
                        browserManager.executeJavaScript(script);
                        LOG.info("Injected tool server URL for project " + project.getName() + ": " + toolServerUrl);
                    }
                }
            }
        }, browserManager.getBrowser().getCefBrowser());

        LOG.info("WebBrowserPanel created for project: " + project.getName() + " with purpose: " + purpose);
    }

    /**
     * Gets the browser purpose for this panel.
     */
    public BrowserPurpose getBrowserPurpose() {
        return browserPurpose;
    }

    public void switchToAgentMode() {
        setMode(agentMode);
    }

    /**
     * Initialize available browser modes.
     */
    private void initBrowserModes() {
        // Add Neutral Mode
        browserModes.add(new BrowserMode(
                "Neutral Mode",
                AllIcons.General.Reset,
                "No system prompt",
                null
        ));

        // Add Dev Mode
        browserModes.add(new BrowserMode(
                "Dev Mode",
                AllIcons.Actions.Run_anything,
                "It's like you have a developer behind your back",
                p -> ConfigurationManager.getInstance(p).getSystemPrompt()
        ));

        // Add Advice Mode
        browserModes.add(new BrowserMode(
                "Advice Mode",
                AllIcons.Actions.IntentionBulb,
                "Provide concise strategic advice",
                p -> "You are a strategic advisor. Provide concise, actionable advice focused on high-level decisions and best practices. Be direct and focus on what matters most."
        ));

        // Add Agent Mode
        this.agentMode = new BrowserMode(
                "Agent Mode",
                AllIcons.Actions.BuildAutoReloadChanges,
                "It's like you have a software assembly line behind your back",
                p -> new OpenWebUIAgentModePromptBuilder(project).buildPrompt()
        );
        BrowserMode agentMode = this.agentMode;
        browserModes.add(agentMode);
    }

    /**
     * Creates the navigation panel with controls.
     */
    private JPanel createNavigationPanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 0));
        panel.setBorder(JBUI.Borders.empty(5));

        // Create navigation buttons
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));

        JButton backToChatButton = new JButton("💬 Chat");
        backToChatButton.setToolTipText("Go to Chat Interface (Reload if already there)");
        backToChatButton.addActionListener(e -> backToChat());

        buttonPanel.add(backToChatButton);

        // Mode selection button
        JButton modeButton = new JButton("Mode");
        modeButton.setToolTipText("Select mode");

        JPopupMenu modeMenu = new JPopupMenu();

        // Add menu items for each browser mode
        for (BrowserMode mode : browserModes) {
            addModePopupItem(modeMenu, mode);
        }

        modeButton.addActionListener(e -> {
            modeMenu.show(modeButton, 0, modeButton.getHeight());
        });

        buttonPanel.add(modeButton);

        // Add Git UI button
        JButton gitUIBtn = new JButton("🌿 Git UI");
        gitUIBtn.setToolTipText("Open Git UI in Browser");
        gitUIBtn.addActionListener(e -> openGitUI());
        buttonPanel.add(gitUIBtn);

        // URL field - hidden but still functional
        JBTextField urlField = new JBTextField();
        urlField.setVisible(false); // Hide the URL field
        urlField.addActionListener(e -> loadUrl(urlField.getText()));
        urlField.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    loadUrl(urlField.getText());
                }
            }
        });

        panel.add(buttonPanel, BorderLayout.WEST);
        panel.add(urlField, BorderLayout.CENTER);

        return panel;
    }
    
    /**
     * Triggers the quick commit and push flow
     */
    private void triggerQuickCommitAndPush() {
        // Use the new web-based quick commit pipeline
        browserManager.executeJavaScript("if (window.QuickCommitPipeline) { window.QuickCommitPipeline.execute(); }");
    }
    
    /**
     * Opens the Git UI in the browser
     */
    public void openGitUI() {
        try {
            // Load the Git UI HTML content and convert to data URL to avoid jar:file:// restrictions
            java.io.InputStream is = getClass().getResourceAsStream("/html/git-ui.html");
            if (is != null) {
                String htmlContent = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                
                // Encode the HTML content as a data URL
                String dataUrl = "data:text/html;charset=UTF-8;base64," + 
                    java.util.Base64.getEncoder().encodeToString(htmlContent.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                
                browserManager.loadURL(dataUrl);
                LOG.info("Loading Git UI from data URL");
            } else {
                LOG.error("Git UI HTML resource not found");
                // Fallback: Load directly from file if in development
                java.io.File htmlFile = new java.io.File(project.getBasePath(), "src/main/resources/html/git-ui.html");
                if (htmlFile.exists()) {
                    String htmlContent = java.nio.file.Files.readString(htmlFile.toPath());
                    String dataUrl = "data:text/html;charset=UTF-8;base64," + 
                        java.util.Base64.getEncoder().encodeToString(htmlContent.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    browserManager.loadURL(dataUrl);
                    LOG.info("Loading Git UI from file as data URL");
                } else {
                    LOG.error("Git UI HTML file not found at: " + htmlFile.getAbsolutePath());
                }
            }
        } catch (Exception e) {
            LOG.error("Error loading Git UI", e);
        }
    }
    
    /**
     * Navigates back to the chat interface or reloads if already there
     */
    private void backToChat() {
        // Get the default chat URL from configuration
        String chatUrl = ConfigurationManager.getInstance(project).getApiUrl().replace("/api/chat/completions", "");
        
        // Get current URL
        String currentUrl = browserManager.getBrowser().getCefBrowser().getURL();
        
        // Check if we're already at the chat URL (handle trailing slashes)
        boolean alreadyAtChat = false;
        if (currentUrl != null) {
            String normalizedCurrent = currentUrl.replaceAll("/$", "");
            String normalizedChat = chatUrl.replaceAll("/$", "");
            alreadyAtChat = normalizedCurrent.equals(normalizedChat) || 
                           normalizedCurrent.equals(normalizedChat + "/") ||
                           (normalizedCurrent + "/").equals(normalizedChat);
        }
        
        if (alreadyAtChat) {
            LOG.info("Already at chat URL, reloading: " + chatUrl);
            browserManager.loadURL(chatUrl);
        } else {
            LOG.info("Navigating to chat URL: " + chatUrl);
            browserManager.loadURL(chatUrl);
        }
    }
    /**
     * Sets the active browser mode.
     */
    private void setMode(BrowserMode mode) {
        if (mode == null)
            return;;
        boolean wasAgentMode = currentMode != null && currentMode.getName().equals("Agent Mode");
        boolean isAgentMode = mode.getName().equals("Agent Mode");
        
        this.currentMode = mode;
        
        // Only update mode button if navigation panel exists
        if (modeButton != null) {
            modeButton.setText(mode.getName());
            modeButton.setIcon(mode.getIcon());
            modeButton.setToolTipText(mode.getTooltip());
        }

        String prompt = mode.getPrompt(project);
        if (prompt != null) {
            browserManager.executeJavaScript("window.__injected_system_prompt__ = '" +
                    StringEscapeUtils.escapeJavaScript(StringUtil.escapeStringCharacters(prompt)) + "';\nwindow.__zest_mode__ = '"  + mode.name + "';");
        } else {
            browserManager.executeJavaScript("window.__injected_system_prompt__ = null;\nwindow.__zest_mode__ = '"  + mode.name + "';");
        }
        
        // If switching to Agent Mode, ensure project is indexed
        if (isAgentMode) {
            ensureProjectIndexed();
        }
        
        // Dispatch a custom event for mode change
        String modeChangeScript = "window.dispatchEvent(new CustomEvent('zestModeChanged', { detail: { mode: '" + 
                                  StringEscapeUtils.escapeJavaScript(mode.getName()) + "' } }));";
        browserManager.executeJavaScript(modeChangeScript);
        
        // Handle tool injection based on mode
        if (isAgentMode) {
            // Enable tool injection for Agent Mode and reload page after a delay (only if not already in Agent Mode)
            String reloadScript = 
                "window.enableZestToolInjection && window.enableZestToolInjection(true);\n";
            
            // Only reload if we're switching TO Agent Mode from another mode
            if (!wasAgentMode) {
                reloadScript += 
                    "// Reload page after tools are injected to ensure they're available\n" +
                    "setTimeout(() => {\n" +
                    "  console.log('[Agent Mode] Reloading page to activate tools...');\n" +
                    "  window.location.reload();\n" +
                    "}, 1000);"; // 1 second delay to allow tool injection to complete
            }
            
            browserManager.executeJavaScript(reloadScript);
            LOG.info("Enabled tool injection for Agent Mode" + (wasAgentMode ? " (no reload needed)" : " with delayed reload"));
        } else {
            // Disable tool injection for other modes
            browserManager.executeJavaScript("window.enableZestToolInjection && window.enableZestToolInjection(false);");
            LOG.info("Disabled tool injection for " + mode.getName());
        }
    }
    
    /**
     * Ensures the project is indexed for Agent Mode.
     */
    private void ensureProjectIndexed() {
        // Check if project is already indexed
//        String knowledgeId = ConfigurationManager.getInstance(project).getKnowledgeId();
//        if (knowledgeId == null || knowledgeId.isEmpty()) {
//            LOG.info("Project not indexed - triggering indexing for Agent Mode");
//
//            // Trigger indexing asynchronously
//            OpenWebUIRagAgent openWebUIRagAgent = OpenWebUIRagAgent.getInstance(project);
//            openWebUIRagAgent.indexProject(false).thenAccept(success -> {
//                if (success) {
//                    LOG.info("Project indexing completed successfully for Agent Mode");
//                } else {
//                    LOG.warn("Project indexing failed or was cancelled");
//                }
//            });
//        } else {
//            LOG.info("Project already indexed with knowledge ID: " + knowledgeId);
//        }
        
        // Index management functionality has been removed
    }

    /**
     * Loads the specified URL.
     */
    public void loadUrl(String url) {
        // Add http:// if no protocol specified
        if (!url.startsWith("http://") && !url.startsWith("https://") && !url.startsWith("file://")) {
            url = "https://" + url;
        }

        browserManager.loadURL(url);
        
        // Only update URL field if navigation panel exists
        if (urlField != null) {
            urlField.setText(url);
        }
    }

    /**
     * Executes JavaScript in the browser.
     */
    public void executeJavaScript(String script) {
        browserManager.executeJavaScript(script);
    }

    /**
     * Sends text to the browser.
     */
    public void sendTextToBrowser(String text) {
        String escapedText = StringUtil.escapeStringCharacters(text);
        String script = "window.receiveFromIDE('" + escapedText + "');";
        executeJavaScript(script);
    }

    /**
     * Gets the current URL loaded in the browser.
     *
     * @return The current URL
     */
    public String getCurrentUrl() {
        return browserManager.getBrowser().getCefBrowser().getURL();
    }

    /**
     * Toggles the visibility of the developer tools.
     *
     * @return true if developer tools are now visible, false otherwise
     */
    public boolean toggleDevTools() {
        browserManager.getBrowser().openDevtools();
        return false;
    }

    /**
     * Gets the component for this panel.
     */
    public JComponent getComponent() {
        return mainPanel;
    }

    public JCEFBrowserManager getBrowserManager() {
        return browserManager;
    }

    /**
     * Adds a new browser mode.
     *
     * @param name           The name of the mode
     * @param icon           The icon for the mode
     * @param tooltip        The tooltip text
     * @param promptProvider Function that provides the system prompt
     */
    public void addBrowserMode(String name, Icon icon, String tooltip, Function<Project, String> promptProvider, JPopupMenu modeMenu) {
        BrowserMode newMode = new BrowserMode(name, icon, tooltip, promptProvider);
        browserModes.add(newMode);

        addModePopupItem(modeMenu, newMode);
    }

    private void addModePopupItem(JPopupMenu modeMenu, BrowserMode newMode) {
        JMenuItem modeItem = new JMenuItem(newMode.getName(), newMode.getIcon());
        modeItem.addActionListener(e -> setMode(newMode));
        modeMenu.add(modeItem);
    }
    
    @Override
    public void dispose() {
        LOG.info("Disposing WebBrowserPanel");
        
        // Browser manager disposal is now handled by JCEFBrowserService
        // We only need to clean up local references
        
        // Clear references
        browserModes.clear();
        currentMode = null;
        agentMode = null;
        
        LOG.info("WebBrowserPanel disposed");
    }
}
