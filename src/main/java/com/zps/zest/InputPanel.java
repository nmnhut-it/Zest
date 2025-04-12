package com.zps.zest;

import com.intellij.icons.AllIcons;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;

import javax.swing.*;
import javax.swing.border.Border;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;

/**
 * Input panel for entering messages
 */
public class InputPanel {
    private final JPanel panel;
    private final JTextArea inputArea;
    private final JButton sendButton;
    private final InteractiveAgentPanel parentPanel;
    
    /**
     * Creates a new input panel
     */
    public InputPanel(InteractiveAgentPanel parentPanel) {
        this.parentPanel = parentPanel;
        
        // Create panel
        panel = new JBPanel<>(new BorderLayout());
        
        // Set up input area
        inputArea = new JTextArea();
        inputArea.setRows(4);
        inputArea.setLineWrap(true);
        inputArea.setWrapStyleWord(true);
        inputArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        inputArea.setBorder(JBUI.Borders.empty(8));
        inputArea.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER && e.isControlDown()) {
                    parentPanel.sendMessage();
                    e.consume();
                }
            }
        });
        
        // For input area
        Color inputBgColor = JBColor.namedColor("TextArea.background", JBColor.background());
        inputArea.setBackground(inputBgColor);
        
        JScrollPane inputScrollPane = new JBScrollPane(inputArea);
        inputScrollPane.setBorder(JBUI.Borders.empty());
        
        // Set up send button
        sendButton = new JButton("Send", AllIcons.Actions.Execute);
        sendButton.addActionListener(e -> parentPanel.sendMessage());
        
        // Action panel
        JPanel actionPanel = new JBPanel<>(new BorderLayout());
        
        // Code snippet button
        JButton codeButton = new JButton(AllIcons.Actions.EditSource);
        codeButton.setToolTipText("Insert code snippet");
        codeButton.setBorderPainted(false);
        codeButton.setContentAreaFilled(false);
        codeButton.addActionListener(e -> inputArea.insert("```\n// Your code here\n```", inputArea.getCaretPosition()));
        
        // Buttons on right
        JPanel rightButtons = new JBPanel<>(new FlowLayout(FlowLayout.RIGHT));
        JButton clearButton = new JButton("Clear", AllIcons.Actions.Cancel);
        clearButton.addActionListener(e -> inputArea.setText(""));
        
        rightButtons.add(clearButton);
        rightButtons.add(sendButton);
        
        // Add buttons
        JPanel leftButtons = new JBPanel<>(new FlowLayout(FlowLayout.LEFT));
        leftButtons.add(codeButton);
        
        actionPanel.add(leftButtons, BorderLayout.WEST);
        actionPanel.add(rightButtons, BorderLayout.EAST);
        actionPanel.setBorder(JBUI.Borders.empty(5));
        
        // Assemble input panel
        panel.add(inputScrollPane, BorderLayout.CENTER);
        panel.add(actionPanel, BorderLayout.SOUTH);
        
        Border titledBorder = BorderFactory.createTitledBorder("Your Message (Ctrl+Enter to send)");
        panel.setBorder(titledBorder);
    }
    
    /**
     * Gets the component for this panel
     */
    public JComponent getComponent() {
        return panel;
    }
    
    /**
     * Gets the text from the input area
     */
    public String getInputText() {
        return inputArea.getText();
    }
    
    /**
     * Clears the input area
     */
    public void clearInput() {
        inputArea.setText("");
    }
    
    /**
     * Appends text to the input area
     */
    public void appendText(String text) {
        inputArea.append(text);
    }
    
    /**
     * Enables or disables the input panel
     */
    public void setEnabled(boolean enabled) {
        inputArea.setEnabled(enabled);
        sendButton.setEnabled(enabled);
    }
}