package com.zps.zest;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;

import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.text.*;
import java.awt.*;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chat view component that displays messages in a chat-like interface
 */
public class ChatView {
    private static final Logger LOG = Logger.getInstance(ChatView.class);
    
    private final JPanel chatPanel;
    private final JScrollPane scrollPane;
    private final InteractiveAgentPanel parentPanel;
    private final boolean isDarkTheme;
    
    /**
     * Creates a new chat view
     */
    public ChatView(InteractiveAgentPanel parentPanel) {
        this.parentPanel = parentPanel;
        this.isDarkTheme = !JBColor.isBright();
        
        // Set up the chat panel
        chatPanel = new JBPanel<>();
        chatPanel.setLayout(new BoxLayout(chatPanel, BoxLayout.Y_AXIS));
        
        // Customize chat panel background
        Color chatBgColor = JBColor.namedColor("Editor.backgroundColor",
                isDarkTheme ? new Color(43, 43, 43) : new Color(247, 247, 247));
        chatPanel.setBackground(chatBgColor);
        
        // Add scroll pane for chat
        scrollPane = new JBScrollPane(chatPanel);
        scrollPane.setBorder(JBUI.Borders.empty());
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
    }
    
    /**
     * Gets the component for this view
     */
    public JComponent getComponent() {
        return scrollPane;
    }
    
    /**
     * Adds a message to the chat view
     */
    public void addMessage(InteractiveAgentPanel.ChatMessage message) {
        JPanel messageComponent = createMessageComponent(message);
        chatPanel.add(messageComponent);
        chatPanel.add(Box.createVerticalStrut(8));
        
        // Add filler to push everything to the top
        chatPanel.add(Box.createVerticalGlue());
        
        // Refresh the panel
        chatPanel.revalidate();
        chatPanel.repaint();
        
        // Scroll to bottom
        SwingUtilities.invokeLater(() -> {
            JScrollBar vertical = scrollPane.getVerticalScrollBar();
            vertical.setValue(vertical.getMaximum());
        });
    }
    
    /**
     * Creates a UI component for a chat message
     */
    private JPanel createMessageComponent(InteractiveAgentPanel.ChatMessage message) {
        // Get colors for theming
        Color userBgColor = JBColor.namedColor("Plugins.lightSelectionBackground",
                isDarkTheme ? new Color(60, 63, 65) : new Color(230, 247, 255));
        
        Color userBorderColor = JBColor.namedColor("Button.focusedBorderColor",
                isDarkTheme ? new Color(106, 135, 89) : new Color(26, 115, 232));
        
        Color aiBgColor = JBColor.namedColor("EditorPane.inactiveBackground",
                isDarkTheme ? new Color(49, 51, 53) : new Color(240, 240, 240));
        
        Color aiBorderColor = JBColor.namedColor("Component.focusColor",
                isDarkTheme ? new Color(204, 120, 50) : new Color(80, 178, 192));
        
        Color systemColor = JBColor.namedColor("Label.disabledForeground",
                isDarkTheme ? new Color(180, 180, 180) : new Color(120, 120, 120));
        
        JPanel messagePanel = new JBPanel<>();
        messagePanel.setLayout(new BorderLayout());
        messagePanel.setBorder(JBUI.Borders.empty(8, 12));
        
        // Style based on message type
        switch (message.getType()) {
            case USER:
                styleMessagePanel(messagePanel, userBgColor, userBorderColor, "You:");
                break;
            case ASSISTANT:
                styleMessagePanel(messagePanel, aiBgColor, aiBorderColor, "ZPS - AI Assistant:");
                break;
            case SYSTEM:
                // System messages get special styling
                JLabel systemLabel = new JLabel(message.getContent());
                systemLabel.setForeground(systemColor);
                systemLabel.setFont(systemLabel.getFont().deriveFont(Font.ITALIC));
                systemLabel.setHorizontalAlignment(SwingConstants.CENTER);
                systemLabel.setBorder(JBUI.Borders.empty(4));
                
                messagePanel.add(systemLabel, BorderLayout.CENTER);
                messagePanel.setBackground(null); // transparent
                messagePanel.setBorder(JBUI.Borders.empty(4));
                
                return messagePanel;
        }
        
        // Create content panel for the message text and code blocks
        JPanel contentPanel = new JBPanel<>();
        contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));
        contentPanel.setOpaque(false);
        contentPanel.setBorder(JBUI.Borders.emptyTop(5));
        
        // Process message content
        processMessageContent(message.getContent(), contentPanel);
        
        // Add timestamp
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm:ss");
        JLabel timeLabel = new JLabel(message.getTimestamp().format(formatter));
        timeLabel.setForeground(systemColor);
        timeLabel.setFont(timeLabel.getFont().deriveFont(10.0f));
        timeLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        
        // Add panels to message
        messagePanel.add(contentPanel, BorderLayout.CENTER);
        messagePanel.add(timeLabel, BorderLayout.SOUTH);
        
        return messagePanel;
    }
    
    /**
     * Styles a message panel according to the message type.
     */
    private void styleMessagePanel(JPanel panel, Color bgColor, Color borderColor, String senderText) {
        panel.setBackground(bgColor);
        panel.setBorder(new CompoundBorder(
                BorderFactory.createMatteBorder(0, 3, 0, 0, borderColor),
                new EmptyBorder(8, 12, 8, 12)
        ));
        
        // Add sender label at the top
        JLabel senderLabel = new JLabel(senderText);
        senderLabel.setFont(senderLabel.getFont().deriveFont(Font.BOLD));
        panel.add(senderLabel, BorderLayout.NORTH);
    }
    
    /**
     * Process message content to handle code blocks and regular text.
     */
    private void processMessageContent(String content, JPanel contentPanel) {
        // Find code blocks
        Matcher matcher = InteractiveAgentPanel.CODE_BLOCK_PATTERN.matcher(content);
        int lastEnd = 0;
        boolean found = false;
        
        while (matcher.find()) {
            found = true;
            
            // Add text before code block
            String textBefore = content.substring(lastEnd, matcher.start());
            if (!textBefore.isEmpty()) {
                addTextComponent(contentPanel, textBefore);
            }
            
            // Extract and add code block
            String language = matcher.group(1).trim();
            String codeContent = matcher.group(2);
            
            addCodeBlockButton(contentPanel, language, codeContent);
            
            lastEnd = matcher.end();
        }
        
        // Add remaining text after last code block
        if (found && lastEnd < content.length()) {
            addTextComponent(contentPanel, content.substring(lastEnd));
        } else if (!found) {
            // No code blocks found, add the entire content
            addTextComponent(contentPanel, content);
        }
    }
    
    /**
     * Adds a button that opens a code block in a dialog
     */
    private void addCodeBlockButton(JPanel parent, String language, String code) {
        JPanel buttonPanel = new JBPanel<>(new BorderLayout());
        buttonPanel.setOpaque(false);
        
        // Create the button
        JButton codeButton = new JButton("Show Code" + (language.isEmpty() ? "" : " (" + language + ")"));
        codeButton.setIcon(AllIcons.Actions.Edit);
        
        // Add button to panel
        buttonPanel.add(codeButton, BorderLayout.WEST);
        
        // Add action to open dialog
        codeButton.addActionListener(e -> {
            CodeDialog dialog = new CodeDialog(parentPanel.getProject(), language, code);
            dialog.show();
        });
        
        // Configure size constraints
        buttonPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        buttonPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, buttonPanel.getPreferredSize().height));
        
        // Add to parent
        parent.add(buttonPanel);
        parent.add(Box.createVerticalStrut(5));
    }
    
    /**
     * Adds a text component to display regular text.
     */
    private void addTextComponent(JPanel parent, String text) {
        JTextPane textPane = new JTextPane();
        textPane.setContentType("text/plain");
        textPane.setText(text);
        textPane.setEditable(false);
        textPane.setOpaque(false);
        
        // Set text color based on theme
        Color textColor = JBColor.namedColor("Label.foreground",
                isDarkTheme ? new Color(220, 220, 220) : new Color(0, 0, 0));
        textPane.setForeground(textColor);
        
        // Process inline code
        processInlineCode(textPane);
        
        // Configure size constraints
        textPane.setAlignmentX(Component.LEFT_ALIGNMENT);
        Dimension preferredSize = textPane.getPreferredSize();
        textPane.setMaximumSize(new Dimension(Integer.MAX_VALUE, preferredSize.height));
        
        // Add to parent
        parent.add(textPane);
        parent.add(Box.createVerticalStrut(5));
    }
    
    /**
     * Processes inline code in a text component.
     */
    private void processInlineCode(JTextPane textPane) {
        String text = textPane.getText();
        StyledDocument doc = textPane.getStyledDocument();
        
        // Create style for inline code
        Style codeStyle = textPane.addStyle("InlineCode", null);
        
        // Get colors for inline code
        Color codeBgColor = JBColor.namedColor("EditorPane.background",
                isDarkTheme ? new Color(45, 45, 45) : new Color(248, 248, 248));
        Color codeTextColor = JBColor.namedColor("Label.foreground",
                isDarkTheme ? new Color(220, 220, 220) : new Color(0, 0, 0));
        
        StyleConstants.setBackground(codeStyle, codeBgColor);
        StyleConstants.setForeground(codeStyle, codeTextColor);
        StyleConstants.setFontFamily(codeStyle, Font.MONOSPACED);
        
        // Find inline code with regex
        Pattern inlinePattern = Pattern.compile("`([^`]+)`");
        Matcher inlineMatcher = inlinePattern.matcher(text);
        
        // Clear the document first
        try {
            doc.remove(0, doc.getLength());
            
            int lastEnd = 0;
            
            while (inlineMatcher.find()) {
                // Add text before inline code
                doc.insertString(doc.getLength(), text.substring(lastEnd, inlineMatcher.start()), null);
                
                // Add the inline code with style
                doc.insertString(doc.getLength(), inlineMatcher.group(1), codeStyle);
                
                lastEnd = inlineMatcher.end();
            }
            
            // Add remaining text
            if (lastEnd < text.length()) {
                doc.insertString(doc.getLength(), text.substring(lastEnd), null);
            }
            
        } catch (BadLocationException e) {
            LOG.error("Error processing inline code", e);
        }
    }
    
    /**
     * Clears the chat view
     */
    public void clearChat() {
        chatPanel.removeAll();
        chatPanel.revalidate();
        chatPanel.repaint();
    }
}