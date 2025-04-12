package com.zps.zest;

import com.intellij.ui.components.JBPanel;
import com.intellij.util.ui.JBUI;

import javax.swing.*;
import java.awt.*;

/**
 * Status bar for showing processing state
 */
public class StatusBar {
    private final JPanel panel;
    private final JProgressBar progressBar;
    private final JLabel statusLabel;
    
    /**
     * Creates a new status bar
     */
    public StatusBar() {
        panel = new JBPanel<>(new BorderLayout());
        
        // Set up progress and status
        progressBar = new JProgressBar();
        progressBar.setIndeterminate(true);
        progressBar.setVisible(false);
        
        statusLabel = new JLabel("Ready");
        statusLabel.setBorder(JBUI.Borders.emptyLeft(5));
        
        panel.add(progressBar, BorderLayout.CENTER);
        panel.add(statusLabel, BorderLayout.EAST);
        panel.setBorder(JBUI.Borders.empty(2, 5));
    }
    
    /**
     * Gets the component for this status bar
     */
    public JComponent getComponent() {
        return panel;
    }
    
    /**
     * Sets the processing state
     */
    public void setProcessing(boolean processing) {
        progressBar.setVisible(processing);
        statusLabel.setText(processing ? "Processing..." : "Ready");
    }
    
    /**
     * Sets a custom status message
     */
    public void setStatus(String message) {
        statusLabel.setText(message);
    }
}