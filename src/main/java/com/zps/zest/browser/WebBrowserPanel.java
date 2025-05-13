package com.zps.zest.browser;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;
import com.zps.zest.ConfigurationManager;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Panel containing the web browser and navigation controls.
 */
public class WebBrowserPanel {
    private static final Logger LOG = Logger.getInstance(WebBrowserPanel.class);

    private final Project project;
    private final JPanel mainPanel;
    private final JCEFBrowserManager browserManager;
    private final JBTextField urlField;
    AtomicBoolean _lightBulbState = new AtomicBoolean();

    /**
     * Creates a new web browser panel.
     */
    public WebBrowserPanel(Project project) {
        this.project = project;
        this.mainPanel = new JPanel(new BorderLayout());

        // Create browser manager
        this.browserManager = new JCEFBrowserManager(project);

        // Create navigation panel
        JPanel navigationPanel = createNavigationPanel();
        navigationPanel.setName("navigationPanel");
        mainPanel.add(navigationPanel, BorderLayout.NORTH);

        // Add browser component
        mainPanel.add(browserManager.getComponent(), BorderLayout.CENTER);

        // Get URL field reference
        this.urlField = (JBTextField) navigationPanel.getComponent(1);

        // Initialize JavaScript bridge
//        initJavaScriptBridge();
    }

    /**
     * Creates the navigation panel with controls.
     */
    private JPanel createNavigationPanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 0));
        panel.setBorder(JBUI.Borders.empty(5));

        // Create navigation buttons
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));

//        JButton backButton = new JButton(AllIcons.Actions.Back);
//        backButton.setToolTipText("Back");
//        backButton.addActionListener(e -> browserManager.goBack());
//
//        JButton forwardButton = new JButton(AllIcons.Actions.Forward);
//        forwardButton.setToolTipText("Forward");
//        forwardButton.addActionListener(e -> browserManager.goForward());

        JButton refreshButton = new JButton(AllIcons.Actions.Refresh);
        refreshButton.setToolTipText("Refresh");
        refreshButton.addActionListener(e -> browserManager.refresh());

//        buttonPanel.add(backButton);
//        buttonPanel.add(forwardButton);

        buttonPanel.add(refreshButton);
        JButton modeButton = new JButton("Dev Mode");
        modeButton.setIcon(AllIcons.Actions.Run_anything);
        modeButton.setToolTipText("Select mode");

        JPopupMenu modeMenu = new JPopupMenu();
        JMenuItem devModeItem = new JMenuItem("Dev Mode", AllIcons.Actions.Run_anything);
        JMenuItem adviceModeItem = new JMenuItem("Advice Mode", AllIcons.Actions.IntentionBulb);

        devModeItem.addActionListener(e -> {
            String prompt = ConfigurationManager.getInstance(project).getOpenWebUISystemPrompt();
            _lightBulbState.set(false);
            modeButton.setText("Dev Mode");
            modeButton.setIcon(AllIcons.Actions.Run_anything);
            browserManager.executeJavaScript("window.__injected_system_prompt__ = '" +
                    StringUtil.escapeStringCharacters(prompt) + "';");
        });

        adviceModeItem.addActionListener(e -> {
            String prompt = ConfigurationManager.getInstance(project).getBossPrompt();
            _lightBulbState.set(true);
            modeButton.setText("Advice Mode");
            modeButton.setIcon(AllIcons.Actions.IntentionBulb);
            browserManager.executeJavaScript("window.__injected_system_prompt__ = '" +
                    StringUtil.escapeStringCharacters(prompt) + "';");
        });

        modeMenu.add(devModeItem);
        modeMenu.add(adviceModeItem);

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
     * Checks if developer tools are currently visible.
     *
     * @return true if developer tools are visible, false otherwise
     */
//    public boolean isDevToolsVisible() {
//        return browserManager.isDevToolsVisible();
//    }

    /**
     * Gets the component for this panel.
     */
    public JComponent getComponent() {
        return mainPanel;
    }

    public JCEFBrowserManager getBrowserManager() {
        return browserManager;
    }
}
