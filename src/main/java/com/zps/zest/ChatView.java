package com.zps.zest;

import com.intellij.icons.AllIcons;
import com.intellij.markdown.utils.MarkdownToHtmlConverter;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.HTMLEditorKitBuilder;
import com.petebevin.markdown.MarkdownProcessor;
import org.intellij.markdown.flavours.commonmark.CommonMarkFlavourDescriptor;

import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.text.*;
import java.awt.*;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.text.*;
import javax.swing.text.html.HTMLEditorKit;
import java.awt.*;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chat view component that displays messages in a chat-like interface with Markdown support
 */
public class ChatView {
    private static final Logger LOG = Logger.getInstance(ChatView.class);

    private final JPanel chatPanel;
    private final JScrollPane scrollPane;
    private final InteractiveAgentPanel parentPanel;
    private final boolean isDarkTheme;
    private final Project project;

    /**
     * Creates a new chat view with Markdown support
     */
    public ChatView(InteractiveAgentPanel parentPanel) {
        this.parentPanel = parentPanel;
        this.project = parentPanel.getProject();
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
     * Process message content to handle code blocks and regular text with Markdown support.
     */
    private void processMessageContent(String content, JPanel contentPanel) {
        // First, check if content has code blocks
        Matcher matcher = InteractiveAgentPanel.CODE_BLOCK_PATTERN.matcher(content);

        if (matcher.find()) {
            // If there are code blocks, process them separately
            processContentWithCodeBlocks(content, contentPanel);
        } else {
            // If no code blocks, render the whole content as Markdown
            addMarkdownComponent(contentPanel, content);
        }
    }

    /**
     * Process content that contains code blocks
     */
    private void processContentWithCodeBlocks(String content, JPanel contentPanel) {
        Matcher matcher = InteractiveAgentPanel.CODE_BLOCK_PATTERN.matcher(content);
        int lastEnd = 0;

        while (matcher.find()) {
            // Add text before code block as Markdown
            String textBefore = content.substring(lastEnd, matcher.start());
            if (!textBefore.isEmpty()) {
                addMarkdownComponent(contentPanel, textBefore+"\n");
            }

            // Extract and add code block
            String language = matcher.group(1).trim();
            String codeContent = matcher.group(2);

            addCodeBlockButton(contentPanel, language, codeContent);

            lastEnd = matcher.end();
        }

        // Add remaining text after last code block as Markdown
        if (lastEnd < content.length()) {
            addMarkdownComponent(contentPanel, "\n" + content.substring(lastEnd));
        }
    }
    /**
     * Adds a Markdown component to display text with formatting
     */
    private void addMarkdownComponent(JPanel parent, String text) {
        try {
            // Convert markdown to HTML
            String html = markdownToHtml(text);

            // Create HTML display component
            JTextPane htmlPane = new JTextPane();
            htmlPane.setContentType("text/html");
            HTMLEditorKit build = new HTMLEditorKitBuilder().build();
            htmlPane.setEditorKit(build);
            htmlPane.setText(html);
            htmlPane.setEditable(false);
            htmlPane.setOpaque(false);
            htmlPane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
            htmlPane.setBorder(JBUI.Borders.empty(5));

            // Set initial width constraint but flexible height
            htmlPane.setAlignmentX(Component.LEFT_ALIGNMENT);
            int width = parent.getWidth() > 0 ? parent.getWidth() - 20 : 500;
            htmlPane.setPreferredSize(new Dimension(width, 10)); // Initial small height

            // Add to parent
            parent.add(htmlPane);
            parent.add(Box.createVerticalStrut(5));

            // Calculate actual height after rendering
            SwingUtilities.invokeLater(() -> {
                // Use getPreferredSize to get the actual height needed
                View view = htmlPane.getUI().getRootView(htmlPane);
                view.setSize(width, 0);  // Set width but unconstrained height
                float preferredHeight = view.getPreferredSpan(View.Y_AXIS);

                // Add some padding to ensure all content is visible
                int actualHeight = (int)Math.ceil(preferredHeight) + 10;

                // Update the component size
                htmlPane.setPreferredSize(new Dimension(width, actualHeight));
                htmlPane.setMaximumSize(new Dimension(Integer.MAX_VALUE, actualHeight));

                // Revalidate the parent to apply the new size
                parent.revalidate();
                parent.repaint();
            });
        } catch (Exception e) {
            LOG.error("Error creating Markdown component", e);

            // Fallback to plain text if Markdown fails
            addTextComponent(parent, text);
        }
    }
    /**
     * Converts Markdown to HTML using regex patterns
     */
    private String markdownToHtml(String markdown) {
        String html = new MarkdownToHtmlConverter(new CommonMarkFlavourDescriptor()).convertMarkdownToHtml(markdown,null);

        // Wrap in style
        html = "<html><head><style>" +
                "body { font-family: sans-serif; font-size: 10px; }" +
                "h1 { font-size: 24px; margin: 5px 0; }" +
                "h2 { font-size: 18px; margin: 5px 0; }" +
                "h3 { font-size: 14px; margin: 4px 0; }" +
                "h4, h5, h6 { font-size: 12px; margin: 3px 0; }" +
                "p { margin: 4px 0; }" +
                "blockquote { margin: 4px 0 4px 10px; padding-left: 5px; " +
                "border-left: 3px solid #808080; color: #606060; }" +
                "ul, ol { margin: 4px 0 4px 20px; padding: 0; }" +
                "li { margin: 2px 0; }" +
                "</style></head><body>" + html + "</body></html>";

        return html;
    }

    /**
     * Adds a button that opens a code block in a dialog
     */
    private void addCodeBlockButton(JPanel parent, String language, String code) {
        JPanel buttonPanel = new JBPanel<>(new BorderLayout());
        buttonPanel.setOpaque(false);

        // Create the button
        JButton codeButton = new JButton("Show Code" + (language.isEmpty() ? "" : " (" + language + ")"));
        codeButton.setIcon(AllIcons.Actions.Preview);

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
        parent.add(Box.createVerticalStrut(2));
        parent.add(buttonPanel);
        parent.add(Box.createVerticalStrut(2));
    }

    /**
     * Adds a text component to display regular text.
     */
    private void addTextComponent(JPanel parent, String text) {
        JTextPane textPane = new JTextPane();
        textPane.setContentType("text/plain");
        textPane.setText(text+"\n");
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