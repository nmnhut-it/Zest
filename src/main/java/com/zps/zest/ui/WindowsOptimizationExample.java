package com.zps.zest.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

/**
 * Example dialog showing how to use ThemeHelper for consistent Windows-optimized UI.
 * This can be used as a template for other dialogs in the Zest plugin.
 */
public class WindowsOptimizationExample extends DialogWrapper {
    
    private JTextArea codeArea;
    private JProgressBar progressBar;
    private JLabel statusLabel;
    
    public WindowsOptimizationExample(@Nullable Project project) {
        super(project);
        setTitle("Windows Optimized Dialog Example");
        init();
    }
    
    @Nullable
    @Override
    protected JComponent createCenterPanel() {
        JPanel mainPanel = new JPanel(new BorderLayout());
        ThemeHelper.optimizeForWindows(mainPanel);
        
        // Create tabbed pane with consistent styling
        JTabbedPane tabbedPane = new JTabbedPane();
        
        // Tab 1: Code display
        JPanel codePanel = createCodePanel();
        tabbedPane.addTab("Code View", codePanel);
        
        // Tab 2: Status panel
        JPanel statusPanel = createStatusPanel();
        tabbedPane.addTab("Status", statusPanel);
        
        mainPanel.add(tabbedPane, BorderLayout.CENTER);
        
        // Set preferred size based on DPI
        int width = ThemeHelper.isHighDPI() ? 800 : 600;
        int height = ThemeHelper.isHighDPI() ? 600 : 400;
        mainPanel.setPreferredSize(new Dimension(width, height));
        
        return mainPanel;
    }
    
    private JPanel createCodePanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(JBUI.Borders.empty(10));
        
        // Create themed text area
        codeArea = ThemeHelper.createThemedTextArea();
        codeArea.setText("""
            // Example code with theme-aware styling
            public class Example {
                public void method() {
                    System.out.println("Windows optimized UI");
                }
            }
            """);
        
        // Use themed scroll pane
        JScrollPane scrollPane = ThemeHelper.createThemedScrollPane(codeArea);
        panel.add(scrollPane, BorderLayout.CENTER);
        
        // Add control buttons
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        
        JButton refreshButton = ThemeHelper.createButton("Refresh", this::refresh);
        buttonPanel.add(refreshButton);
        
        JButton clearButton = ThemeHelper.createButton("Clear", () -> codeArea.setText(""));
        buttonPanel.add(clearButton);
        
        panel.add(buttonPanel, BorderLayout.SOUTH);
        
        return panel;
    }
    
    private JPanel createStatusPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(JBUI.Borders.empty(10));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = JBUI.insets(5);
        
        // Status label with dynamic coloring
        gbc.gridx = 0; gbc.gridy = 0;
        panel.add(new JLabel("Status:"), gbc);
        
        gbc.gridx = 1; gbc.weightx = 1.0;
        statusLabel = new JLabel("Ready");
        statusLabel.setForeground(ThemeHelper.SUCCESS_COLOR);
        statusLabel.setIcon(ThemeHelper.getStatusIcon("success"));
        panel.add(statusLabel, gbc);
        
        // Progress bar
        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0;
        panel.add(new JLabel("Progress:"), gbc);
        
        gbc.gridx = 1; gbc.weightx = 1.0;
        progressBar = ThemeHelper.createProgressBar(0, 100);
        progressBar.setValue(75);
        panel.add(progressBar, gbc);
        
        // Separator
        gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 2;
        panel.add(ThemeHelper.createSeparator(), gbc);
        
        // Example of duration-based coloring
        gbc.gridy = 3; gbc.gridwidth = 1;
        panel.add(new JLabel("Response Times:"), gbc);
        
        gbc.gridy = 4;
        JLabel fastLabel = new JLabel("Fast: 500ms");
        fastLabel.setForeground(ThemeHelper.getColorForDuration(500));
        panel.add(fastLabel, gbc);
        
        gbc.gridy = 5;
        JLabel moderateLabel = new JLabel("Moderate: 3000ms");
        moderateLabel.setForeground(ThemeHelper.getColorForDuration(3000));
        panel.add(moderateLabel, gbc);
        
        gbc.gridy = 6;
        JLabel slowLabel = new JLabel("Slow: 6000ms");
        slowLabel.setForeground(ThemeHelper.getColorForDuration(6000));
        panel.add(slowLabel, gbc);
        
        // Add vertical glue to push content to top
        gbc.gridy = 7; gbc.weighty = 1.0;
        panel.add(Box.createVerticalGlue(), gbc);
        
        return panel;
    }
    
    @Override
    protected JComponent createSouthPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        
        // Example of different button styles
        JButton primaryButton = ThemeHelper.createPrimaryButton("Apply", () -> {
            // Apply action
            close(OK_EXIT_CODE);
        });
        panel.add(primaryButton);
        
        JButton dangerButton = ThemeHelper.createDangerButton("Reset", () -> {
            // Reset action
            statusLabel.setText("Reset!");
            statusLabel.setForeground(ThemeHelper.WARNING_COLOR);
        });
        panel.add(dangerButton);
        
        JButton cancelButton = ThemeHelper.createButton("Cancel", () -> close(CANCEL_EXIT_CODE));
        panel.add(cancelButton);
        
        return panel;
    }
    
    private void refresh() {
        // Simulate refresh with progress
        SwingUtilities.invokeLater(() -> {
            statusLabel.setText("Refreshing...");
            statusLabel.setForeground(ThemeHelper.INFO_COLOR);
            statusLabel.setIcon(ThemeHelper.getStatusIcon("info"));
            
            // Simulate progress
            new Timer(50, e -> {
                int value = progressBar.getValue();
                if (value < 100) {
                    progressBar.setValue(value + 2);
                } else {
                    ((Timer)e.getSource()).stop();
                    statusLabel.setText("Refresh Complete");
                    statusLabel.setForeground(ThemeHelper.SUCCESS_COLOR);
                    statusLabel.setIcon(ThemeHelper.getStatusIcon("success"));
                    progressBar.setValue(0);
                }
            }).start();
        });
    }
}