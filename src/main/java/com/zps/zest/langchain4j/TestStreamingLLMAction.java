package com.zps.zest.langchain4j;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.zps.zest.langchain4j.util.StreamingLLMService;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.util.concurrent.CompletableFuture;

/**
 * Test action for the Streaming LLM Service.
 */
public class TestStreamingLLMAction extends AnAction {
    
    public TestStreamingLLMAction() {
        super("Test Streaming LLM", "Tests the streaming LLM service", null);
    }
    
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;
        
        // Get query from user
        String query = Messages.showInputDialog(
            project,
            "Enter a query to test streaming:",
            "Test Streaming LLM",
            Messages.getQuestionIcon()
        );
        
        if (query == null || query.trim().isEmpty()) {
            return;
        }
        
        // Create a dialog to show streaming response
        JDialog dialog = new JDialog();
        dialog.setTitle("Streaming LLM Response");
        dialog.setModal(false);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        
        JTextArea textArea = new JTextArea();
        textArea.setEditable(false);
        textArea.setWrapStyleWord(true);
        textArea.setLineWrap(true);
        textArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        
        JScrollPane scrollPane = new JScrollPane(textArea);
        scrollPane.setPreferredSize(new Dimension(600, 400));
        
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton cancelButton = new JButton("Cancel");
        JButton closeButton = new JButton("Close");
        closeButton.setEnabled(false);
        
        buttonPanel.add(cancelButton);
        buttonPanel.add(closeButton);
        
        dialog.setLayout(new BorderLayout());
        dialog.add(scrollPane, BorderLayout.CENTER);
        dialog.add(buttonPanel, BorderLayout.SOUTH);
        
        dialog.pack();
        dialog.setLocationRelativeTo(null);
        dialog.setVisible(true);
        
        // Get streaming service
        StreamingLLMService streamingService = project.getService(StreamingLLMService.class);
        
        // Create streaming session
        StreamingLLMService.StreamingSession session = streamingService.createStreamingSession(query);
        
        // Cancel button action
        cancelButton.addActionListener(ev -> {
            session.cancel();
            cancelButton.setEnabled(false);
        });
        
        // Close button action
        closeButton.addActionListener(ev -> dialog.dispose());
        
        // Start streaming
        CompletableFuture<String> future = session.start(chunk -> {
            // Update UI with each chunk
            SwingUtilities.invokeLater(() -> {
                textArea.append(chunk);
                textArea.setCaretPosition(textArea.getDocument().getLength());
            });
        });
        
        // Handle completion
        future.whenComplete((result, throwable) -> {
            SwingUtilities.invokeLater(() -> {
                cancelButton.setEnabled(false);
                closeButton.setEnabled(true);
                
                if (throwable != null) {
                    textArea.append("\n\n[Error: " + throwable.getMessage() + "]");
                } else if (session.isCancelled()) {
                    textArea.append("\n\n[Streaming cancelled]");
                } else {
                    textArea.append("\n\n[Streaming complete]");
                }
            });
        });
    }
    
    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        e.getPresentation().setEnabledAndVisible(project != null);
    }
}
