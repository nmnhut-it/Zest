package com.zps.zest.ui;

import com.intellij.icons.AllIcons;
import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import com.intellij.util.ui.StartupUiUtil;

import javax.swing.*;
import javax.swing.text.JTextComponent;
import java.awt.*;

/**
 * Helper class for consistent theming across the Zest plugin.
 * Provides theme-aware colors and styling for Windows optimization.
 */
public class ThemeHelper {
    
    // Status Colors
    public static final Color SUCCESS_COLOR = new JBColor(new Color(0, 128, 0), new Color(80, 200, 80));
    public static final Color WARNING_COLOR = new JBColor(new Color(255, 140, 0), new Color(255, 180, 0));
    public static final Color ERROR_COLOR = new JBColor(new Color(220, 0, 0), new Color(255, 80, 80));
    public static final Color INFO_COLOR = new JBColor(new Color(0, 100, 200), new Color(100, 150, 255));
    
    // Performance Colors
    public static final Color FAST_COLOR = SUCCESS_COLOR;
    public static final Color MODERATE_COLOR = WARNING_COLOR;
    public static final Color SLOW_COLOR = ERROR_COLOR;
    
    /**
     * Creates a themed text area that matches the IDE theme.
     */
    public static JTextArea createThemedTextArea() {
        JTextArea area = new JTextArea();
        area.setEditable(false);
        area.setFont(getMonospacedFont());
        applyTheme(area);
        return area;
    }
    
    /**
     * Creates a themed scroll pane with consistent styling.
     */
    public static JScrollPane createThemedScrollPane(Component view) {
        JScrollPane scrollPane = new JScrollPane(view);
        scrollPane.setBorder(JBUI.Borders.empty());
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        return scrollPane;
    }
    
    /**
     * Applies theme colors to a text component.
     */
    public static void applyTheme(JTextComponent component) {
        if (StartupUiUtil.isUnderDarcula()) {
            component.setBackground(UIUtil.getPanelBackground());
            component.setForeground(UIUtil.getLabelForeground());
            component.setCaretColor(UIUtil.getLabelForeground());
        } else {
            component.setBackground(UIUtil.getTextFieldBackground());
            component.setForeground(UIUtil.getTextFieldForeground());
            component.setCaretColor(UIUtil.getTextFieldForeground());
        }
    }
    
    /**
     * Gets the appropriate monospaced font for code display.
     */
    public static Font getMonospacedFont() {
        return new Font(Font.MONOSPACED, Font.PLAIN, getFontSize());
    }
    
    /**
     * Gets the appropriate font size based on system settings.
     */
    public static int getFontSize() {
        // Adjust font size for Windows DPI settings
        int baseSize = 12;
        if (isHighDPI()) {
            baseSize = 14;
        }
        return JBUI.scale(baseSize);
    }
    
    /**
     * Checks if the system is using high DPI.
     */
    public static boolean isHighDPI() {
        return JBUI.isUsrHiDPI() || Toolkit.getDefaultToolkit().getScreenResolution() > 120;
    }
    
    /**
     * Creates a consistent button with proper styling.
     */
    public static JButton createButton(String text, Runnable action) {
        JButton button = new JButton(text);
        button.addActionListener(e -> action.run());
        
        // Add consistent padding
        button.setMargin(JBUI.insets(4, 12));
        
        return button;
    }
    
    /**
     * Creates a danger/warning button (e.g., Stop Server).
     */
    public static JButton createDangerButton(String text, Runnable action) {
        JButton button = createButton(text, action);
        button.setForeground(ERROR_COLOR);
        return button;
    }
    
    /**
     * Creates a primary action button.
     */
    public static JButton createPrimaryButton(String text, Runnable action) {
        JButton button = createButton(text, action);
        button.setBackground(INFO_COLOR);
        button.setForeground(JBColor.WHITE);
        button.setOpaque(true);
        button.setBorderPainted(false);
        return button;
    }

    /**
     * Gets color for request duration.
     */
    public static Color getColorForDuration(long durationMs) {
        if (durationMs > 5000) return SLOW_COLOR;
        if (durationMs > 2000) return MODERATE_COLOR;
        return FAST_COLOR;
    }
    
    /**
     * Gets color for status code.
     */
    public static Color getColorForStatusCode(int statusCode) {
        if (statusCode >= 200 && statusCode < 300) return SUCCESS_COLOR;
        if (statusCode >= 400 && statusCode < 500) return WARNING_COLOR;
        if (statusCode >= 500) return ERROR_COLOR;
        return INFO_COLOR;
    }
    
    /**
     * Creates a progress bar with consistent styling.
     */
    public static JProgressBar createProgressBar(int min, int max) {
        JProgressBar bar = new JProgressBar(min, max);
        bar.setStringPainted(true);
        
        if (StartupUiUtil.isUnderDarcula()) {
            bar.setForeground(INFO_COLOR);
        }
        
        return bar;
    }
    
    /**
     * Gets the appropriate icon for a status.
     */
    public static Icon getStatusIcon(String status) {
        if (status.toLowerCase().contains("success") || status.toLowerCase().contains("connected")) {
            return AllIcons.RunConfigurations.TestPassed;
        } else if (status.toLowerCase().contains("warning")) {
            return AllIcons.General.Warning;
        } else if (status.toLowerCase().contains("error") || status.toLowerCase().contains("failed")) {
            return AllIcons.General.Error;
        }
        return AllIcons.General.Information;
    }
    
    /**
     * Applies Windows-specific optimizations to a component.
     */
    public static void optimizeForWindows(JComponent component) {
        // Enable double buffering for smoother rendering
        component.setDoubleBuffered(true);
        
        // Set appropriate borders and padding
        if (component instanceof JPanel) {
            component.setBorder(JBUI.Borders.empty(5));
        }
        
        // Optimize font rendering
        if (isWindows()) {
            System.setProperty("awt.useSystemAAFontSettings", "on");
            System.setProperty("swing.aatext", "true");
        }
    }
    
    /**
     * Checks if running on Windows.
     */
    public static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("windows");
    }
    
    /**
     * Creates a separator with proper theming.
     */
    public static JSeparator createSeparator() {
        JSeparator separator = new JSeparator();
        separator.setForeground(JBColor.border());
        return separator;
    }
}