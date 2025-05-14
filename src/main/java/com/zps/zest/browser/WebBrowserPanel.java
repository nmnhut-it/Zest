package com.zps.zest.browser;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;
import com.zps.zest.ClassAnalyzer;
import com.zps.zest.ConfigurationManager;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefLoadHandlerAdapter;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Panel containing the web browser and navigation controls.
 */
public class WebBrowserPanel {
    private static final Logger LOG = Logger.getInstance(WebBrowserPanel.class);

    /**
     * Simple class representing a browser mode.
     */
    private static class BrowserMode {
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
    private final JBTextField urlField;
    private final JButton modeButton;
    private final List<BrowserMode> browserModes = new ArrayList<>();
    private BrowserMode currentMode;

    /**
     * Creates a new web browser panel.
     */
    public WebBrowserPanel(Project project) {
        this.project = project;
        this.mainPanel = new JPanel(new BorderLayout());

        // Create browser manager
        this.browserManager = new JCEFBrowserManager(project);

        // Initialize browser modes
        initBrowserModes();

        // Create navigation panel
        JPanel navigationPanel = createNavigationPanel();
        navigationPanel.setName("navigationPanel");
        mainPanel.add(navigationPanel, BorderLayout.NORTH);

        // Add browser component
        mainPanel.add(browserManager.getComponent(), BorderLayout.CENTER);

        // Get UI references
        this.urlField = (JBTextField) navigationPanel.getComponent(1);
        this.modeButton = (JButton) ((JPanel) navigationPanel.getComponent(0)).getComponent(1);

        // Set default mode
        setMode(browserModes.get(0));

        browserManager.getBrowser().getJBCefClient().addLoadHandler(new CefLoadHandlerAdapter() {
            @Override
            public void onLoadEnd(CefBrowser browser, CefFrame frame, int httpStatusCode) {
                setMode(currentMode);
            }
        }, browserManager.getBrowser().getCefBrowser());
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
                p -> ConfigurationManager.getInstance(p).getOpenWebUISystemPrompt()
        ));

        // Add Advice Mode
        browserModes.add(new BrowserMode(
                "Advice Mode",
                AllIcons.Actions.IntentionBulb,
                "It's like you have boss behind your back",
                p -> ConfigurationManager.getInstance(p).getBossPrompt()
        ));

        // Add Advice Mode
        browserModes.add(new BrowserMode(
                "Agent Mode",
                AllIcons.Actions.BuildAutoReloadChanges,
                "It's like you have a software assembly line behind your back",
                p -> new OpenWebUIPromptBuilder(project).buildPrompt()
        ));
    }

    /**
     * Creates the navigation panel with controls.
     */
    private JPanel createNavigationPanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 0));
        panel.setBorder(JBUI.Borders.empty(5));

        // Create navigation buttons
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));

        JButton refreshButton = new JButton(AllIcons.Actions.Refresh);
        refreshButton.setToolTipText("Refresh");
        refreshButton.addActionListener(e -> browserManager.refresh());

        buttonPanel.add(refreshButton);

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

        // Create URL field
        JBTextField urlField = new JBTextField();
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
     * Sets the active browser mode.
     */
    private void setMode(BrowserMode mode) {
        this.currentMode = mode;
        modeButton.setText(mode.getName());
        modeButton.setIcon(mode.getIcon());
        modeButton.setToolTipText(mode.getTooltip());

        String prompt = mode.getPrompt(project);
        if (prompt != null) {
            browserManager.executeJavaScript("window.__injected_system_prompt__ = '" +
                    StringUtil.escapeStringCharacters(prompt) + "';\nwindow.__zest_mode__ = '"  + mode.name + "';");
        } else {
            browserManager.executeJavaScript("window.__injected_system_prompt__ = null;\nwindow.__zest_mode__ = '"  + mode.name + "';");
        }
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
        urlField.setText(url);
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
        return browserManager.toggleDevTools();
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
}
