package com.zps.zest.langchain4j.ui;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.EditorSettings;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.Splitter;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.AnimatedIcon;
import com.intellij.ui.TitledSeparator;
import com.intellij.ui.components.*;
import com.intellij.ui.tabs.JBTabs;
import com.intellij.ui.tabs.TabInfo;
import com.intellij.ui.tabs.impl.JBTabsImpl;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import com.zps.zest.langchain4j.agent.CodeExplorationReport;
import com.zps.zest.langchain4j.agent.CodeExplorationReportGenerator;
import com.zps.zest.langchain4j.agent.CodingTaskAgent;
import com.zps.zest.langchain4j.agent.ImprovedToolCallingAutonomousAgent;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.CompletableFuture;

/**
 * Enhanced UI panel for code exploration and coding tasks with improved UX.
 */
public class CodeExplorationAndCodingPanel extends JPanel {
    private final Project project;
    
    // UI Components
    private final JBTextField queryField;
    private final JButton exploreButton;
    private final JButton stopButton;
    private final JLabel statusLabel;
    private final JProgressBar progressBar;
    
    // Tabs
    private final JBTabs tabs;
    private final ExplorationProgressPanel progressPanel;
    private final ReportViewerPanel reportPanel;
    private final CodingTaskPanel codingPanel;
    private final HistoryPanel historyPanel;
    
    // State
    private CompletableFuture<?> currentExploration;
    private CodeExplorationReport currentReport;
    
    public CodeExplorationAndCodingPanel(@NotNull Project project) {
        this.project = project;
        
        setLayout(new BorderLayout());
        
        // Initialize components
        queryField = new JBTextField();
        queryField.getEmptyText().setText("Enter your exploration query (e.g., 'How does the authentication system work?')");
        
        exploreButton = new JButton("Explore");
        exploreButton.setIcon(AllIcons.Actions.Execute);
        
        stopButton = new JButton("Stop");
        stopButton.setIcon(AllIcons.Actions.Suspend);
        stopButton.setEnabled(false);
        
        statusLabel = new JLabel("Ready");
        progressBar = new JProgressBar();
        progressBar.setIndeterminate(false);
        
        // Create panels
        progressPanel = new ExplorationProgressPanel();
        reportPanel = new ReportViewerPanel();
        codingPanel = new CodingTaskPanel();
        historyPanel = new HistoryPanel();
        
        // Create tabs
        tabs = new JBTabsImpl(project);
        tabs.addTab(new TabInfo(progressPanel).setText("Exploration Progress").setIcon(AllIcons.RunConfigurations.TestState.Run));
        tabs.addTab(new TabInfo(reportPanel).setText("Exploration Report").setIcon(AllIcons.Actions.Preview));
        tabs.addTab(new TabInfo(codingPanel).setText("Coding Assistant").setIcon(AllIcons.Actions.IntentionBulb));
        tabs.addTab(new TabInfo(historyPanel).setText("History").setIcon(AllIcons.Vcs.History));
        
        // Layout
        add(createTopPanel(), BorderLayout.NORTH);
        add(tabs.getComponent(), BorderLayout.CENTER);
        add(createBottomPanel(), BorderLayout.SOUTH);
        
        // Setup listeners
        setupListeners();
    }
    
    private JPanel createTopPanel() {
        JPanel panel = new JPanel(new BorderLayout(JBUI.scale(5), 0));
        panel.setBorder(JBUI.Borders.empty(10));
        
        // Query panel
        JPanel queryPanel = new JPanel(new BorderLayout(JBUI.scale(5), 0));
        
        // Add presets dropdown
        ComboBox<String> presetsCombo = new ComboBox<>(new String[]{
            "Custom Query...",
            "How does the authentication system work?",
            "Explain the payment processing flow",
            "Show me the caching implementation",
            "How is error handling implemented?",
            "What design patterns are used in this project?"
        });
        presetsCombo.setPreferredSize(new Dimension(300, presetsCombo.getPreferredSize().height));
        presetsCombo.addActionListener(e -> {
            if (presetsCombo.getSelectedIndex() > 0) {
                queryField.setText((String) presetsCombo.getSelectedItem());
            }
        });
        
        queryPanel.add(new JLabel("Query:"), BorderLayout.WEST);
        queryPanel.add(queryField, BorderLayout.CENTER);
        queryPanel.add(presetsCombo, BorderLayout.EAST);
        
        // Buttons panel
        JPanel buttonsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(5), 0));
        buttonsPanel.add(exploreButton);
        buttonsPanel.add(stopButton);
        
        // Quick actions
        JButton quickReportButton = new JButton("Quick Report");
        quickReportButton.setIcon(AllIcons.Actions.Download);
        quickReportButton.setToolTipText("Generate a quick report from the last exploration");
        quickReportButton.addActionListener(e -> generateQuickReport());
        buttonsPanel.add(quickReportButton);
        
        panel.add(queryPanel, BorderLayout.CENTER);
        panel.add(buttonsPanel, BorderLayout.EAST);
        
        return panel;
    }
    
    private JPanel createBottomPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(JBUI.Borders.empty(5, 10));
        
        // Status panel
        JPanel statusPanel = new JPanel(new BorderLayout(JBUI.scale(10), 0));
        statusPanel.add(statusLabel, BorderLayout.WEST);
        statusPanel.add(progressBar, BorderLayout.CENTER);
        
        // Tips
        JLabel tipLabel = new JLabel();
        tipLabel.setForeground(UIUtil.getLabelDisabledForeground());
        tipLabel.setFont(tipLabel.getFont().deriveFont(Font.ITALIC));
        updateTip(tipLabel);
        
        panel.add(statusPanel, BorderLayout.CENTER);
        panel.add(tipLabel, BorderLayout.EAST);
        
        // Update tips periodically
        Timer tipTimer = new Timer(10000, e -> updateTip(tipLabel));
        tipTimer.start();
        
        return panel;
    }
    
    private void setupListeners() {
        // Enable/disable explore button based on query
        queryField.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { updateExploreButton(); }
            public void removeUpdate(DocumentEvent e) { updateExploreButton(); }
            public void changedUpdate(DocumentEvent e) { updateExploreButton(); }
        });
        
        // Explore button action
        exploreButton.addActionListener(e -> startExploration());
        
        // Stop button action
        stopButton.addActionListener(e -> stopExploration());
        
        // Enter key in query field
        queryField.addActionListener(e -> {
            if (exploreButton.isEnabled()) {
                startExploration();
            }
        });
    }
    
    private void updateExploreButton() {
        exploreButton.setEnabled(!queryField.getText().trim().isEmpty() && currentExploration == null);
    }
    
    private void startExploration() {
        String query = queryField.getText().trim();
        if (query.isEmpty()) return;
        
        // Update UI state
        exploreButton.setEnabled(false);
        stopButton.setEnabled(true);
        progressBar.setIndeterminate(true);
        statusLabel.setText("Starting exploration...");
        
        // Clear previous results
        progressPanel.clear();
        reportPanel.clear();
        codingPanel.setReport(null);
        
        // Switch to progress tab
        tabs.select(tabs.getTabAt(0), true);
        
        // Start exploration
        ImprovedToolCallingAutonomousAgent agent = project.getService(ImprovedToolCallingAutonomousAgent.class);
        
        currentExploration = agent.exploreWithToolsAsync(query, new ImprovedToolCallingAutonomousAgent.ProgressCallback() {
            @Override
            public void onToolExecution(ImprovedToolCallingAutonomousAgent.ToolExecution execution) {
                SwingUtilities.invokeLater(() -> {
                    progressPanel.addToolExecution(execution);
                    statusLabel.setText("Executing: " + execution.getToolName());
                });
            }
            
            @Override
            public void onRoundComplete(ImprovedToolCallingAutonomousAgent.ExplorationRound round) {
                SwingUtilities.invokeLater(() -> {
                    progressPanel.completeRound(round.getName());
                });
            }
            
            @Override
            public void onExplorationComplete(ImprovedToolCallingAutonomousAgent.ExplorationResult result) {
                // Will be handled in the future completion
            }
        }).thenAccept(result -> {
            SwingUtilities.invokeLater(() -> {
                // Generate report
                CodeExplorationReportGenerator reportGenerator = new CodeExplorationReportGenerator(project);
                currentReport = reportGenerator.generateReport(query, result);
                
                // Update UI
                progressBar.setIndeterminate(false);
                progressBar.setValue(100);
                statusLabel.setText("Exploration complete!");
                
                // Display report
                reportPanel.displayReport(currentReport);
                codingPanel.setReport(currentReport);
                
                // Add to history
                historyPanel.addExploration(query, currentReport);
                
                // Switch to report tab
                tabs.select(tabs.getTabAt(1), true);
                
                // Show notification
                showCompletionNotification();
            });
        }).exceptionally(throwable -> {
            SwingUtilities.invokeLater(() -> {
                progressBar.setIndeterminate(false);
                statusLabel.setText("Exploration failed");
                Messages.showErrorDialog(project, 
                    "Exploration failed: " + throwable.getMessage(), 
                    "Error");
            });
            return null;
        }).whenComplete((result, throwable) -> {
            SwingUtilities.invokeLater(() -> {
                exploreButton.setEnabled(true);
                stopButton.setEnabled(false);
                currentExploration = null;
            });
        });
    }
    
    private void stopExploration() {
        if (currentExploration != null && !currentExploration.isDone()) {
            currentExploration.cancel(true);
            statusLabel.setText("Exploration stopped");
            progressBar.setIndeterminate(false);
        }
    }
    
    private void generateQuickReport() {
        if (currentReport == null) {
            Messages.showInfoMessage(project, 
                "No exploration results available. Please run an exploration first.", 
                "No Results");
            return;
        }
        
        // Generate and save report
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        String fileName = "exploration_report_" + timestamp + ".md";
        
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File(fileName));
        
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            try {
                File file = chooser.getSelectedFile();
                try (FileWriter writer = new FileWriter(file)) {
                    writer.write(currentReport.getCodingContext());
                }
                Messages.showInfoMessage(project, 
                    "Report saved to: " + file.getAbsolutePath(), 
                    "Report Saved");
            } catch (IOException e) {
                Messages.showErrorDialog(project, 
                    "Failed to save report: " + e.getMessage(), 
                    "Error");
            }
        }
    }
    
    private void showCompletionNotification() {
        // Simple notification - in a real implementation, use IDE's notification system
        Timer timer = new Timer(3000, e -> statusLabel.setText("Ready"));
        timer.setRepeats(false);
        timer.start();
    }
    
    private void updateTip(JLabel tipLabel) {
        String[] tips = {
            "Tip: Use specific queries for better results",
            "Tip: Explore one concept at a time for clarity",
            "Tip: Check the report tab for comprehensive context",
            "Tip: Use the coding assistant to implement features",
            "Tip: Export reports for documentation"
        };
        
        int index = (int) (Math.random() * tips.length);
        tipLabel.setText(tips[index]);
    }
    
    /**
     * Panel showing exploration progress in real-time.
     */
    private class ExplorationProgressPanel extends JPanel {
        private final DefaultListModel<ToolExecutionItem> model;
        private final JBList<ToolExecutionItem> list;
        private final JTextArea detailsArea;
        
        public ExplorationProgressPanel() {
            setLayout(new BorderLayout());
            
            model = new DefaultListModel<>();
            list = new JBList<>(model);
            list.setCellRenderer(new ToolExecutionRenderer());
            list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            
            detailsArea = new JTextArea();
            detailsArea.setEditable(false);
            detailsArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            
            // Split pane
            Splitter splitter = new Splitter(false, 0.5f);
            splitter.setFirstComponent(new JBScrollPane(list));
            splitter.setSecondComponent(new JBScrollPane(detailsArea));
            
            add(splitter, BorderLayout.CENTER);
            
            // Selection listener
            list.addListSelectionListener(e -> {
                if (!e.getValueIsAdjusting()) {
                    ToolExecutionItem item = list.getSelectedValue();
                    if (item != null) {
                        detailsArea.setText(item.execution.getResult());
                        detailsArea.setCaretPosition(0);
                    }
                }
            });
        }
        
        public void clear() {
            model.clear();
            detailsArea.setText("");
        }
        
        public void addToolExecution(ImprovedToolCallingAutonomousAgent.ToolExecution execution) {
            model.addElement(new ToolExecutionItem(execution, new Date()));
            
            // Auto-scroll to latest
            int lastIndex = model.size() - 1;
            list.ensureIndexIsVisible(lastIndex);
            list.setSelectedIndex(lastIndex);
        }
        
        public void completeRound(String roundName) {
            // Could add round separators or summaries
        }
    }
    
    /**
     * Panel for viewing the exploration report.
     */
    private class ReportViewerPanel extends JPanel {
        private final Editor editor;
        private final JButton exportButton;
        private final JButton copyButton;
        
        public ReportViewerPanel() {
            setLayout(new BorderLayout());
            
            // Create editor
            editor = EditorFactory.getInstance().createEditor(
                EditorFactory.getInstance().createDocument(""),
                project,
                FileTypeManager.getInstance().getFileTypeByExtension("md"),
                false
            );
            
            EditorSettings settings = editor.getSettings();
            settings.setLineNumbersShown(true);
            settings.setFoldingOutlineShown(true);
            settings.setIndentGuidesShown(true);
            settings.setLineMarkerAreaShown(false);
            
            // Toolbar
            JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT));
            
            exportButton = new JButton("Export", AllIcons.Actions.Download);
            exportButton.addActionListener(e -> exportReport());
            
            copyButton = new JButton("Copy All", AllIcons.Actions.Copy);
            copyButton.addActionListener(e -> copyReport());
            
            JButton navigateButton = new JButton("Navigate to Code", AllIcons.Actions.EditSource);
            navigateButton.addActionListener(e -> navigateToCode());
            
            toolbar.add(exportButton);
            toolbar.add(copyButton);
            toolbar.add(navigateButton);
            
            add(toolbar, BorderLayout.NORTH);
            add(editor.getComponent(), BorderLayout.CENTER);
            
            Disposer.register(project, () -> EditorFactory.getInstance().releaseEditor(editor));
        }
        
        public void clear() {
            ApplicationManager.getApplication().runWriteAction(() -> {
                editor.getDocument().setText("");
            });
        }
        
        public void displayReport(CodeExplorationReport report) {
            ApplicationManager.getApplication().runWriteAction(() -> {
                editor.getDocument().setText(report.getCodingContext());
            });
        }
        
        private void exportReport() {
            generateQuickReport();
        }
        
        private void copyReport() {
            String text = editor.getDocument().getText();
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
                new java.awt.datatransfer.StringSelection(text), null);
            statusLabel.setText("Report copied to clipboard");
        }
        
        private void navigateToCode() {
            // TODO: Implement navigation to selected code element
            Messages.showInfoMessage(project, 
                "Navigation feature coming soon!", 
                "Navigate to Code");
        }
    }
    
    /**
     * Panel for coding tasks using the exploration report.
     */
    private class CodingTaskPanel extends JPanel {
        private final JTextArea taskArea;
        private final JButton executeButton;
        private final Editor resultEditor;
        private CodeExplorationReport report;
        
        public CodingTaskPanel() {
            setLayout(new BorderLayout());
            
            // Task input
            JPanel taskPanel = new JPanel(new BorderLayout());
            taskPanel.setBorder(JBUI.Borders.empty(10));
            
            taskPanel.add(new TitledSeparator("Coding Task"), BorderLayout.NORTH);
            
            taskArea = new JTextArea(3, 0);
            taskArea.setLineWrap(true);
            taskArea.setWrapStyleWord(true);
            taskArea.setBorder(JBUI.Borders.empty(5));
            
            JBScrollPane taskScroll = new JBScrollPane(taskArea);
            taskPanel.add(taskScroll, BorderLayout.CENTER);
            
            executeButton = new JButton("Generate Code", AllIcons.Actions.Execute);
            executeButton.setEnabled(false);
            executeButton.addActionListener(e -> executeCodingTask());
            
            JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            buttonPanel.add(executeButton);
            taskPanel.add(buttonPanel, BorderLayout.SOUTH);
            
            // Result editor
            resultEditor = EditorFactory.getInstance().createEditor(
                EditorFactory.getInstance().createDocument(""),
                project,
                FileTypeManager.getInstance().getFileTypeByExtension("java"),
                false
            );
            
            // Layout
            Splitter splitter = new Splitter(false, 0.3f);
            splitter.setFirstComponent(taskPanel);
            splitter.setSecondComponent(resultEditor.getComponent());
            
            add(splitter, BorderLayout.CENTER);
            
            Disposer.register(project, () -> EditorFactory.getInstance().releaseEditor(resultEditor));
        }
        
        public void setReport(CodeExplorationReport report) {
            this.report = report;
            executeButton.setEnabled(report != null);
            
            if (report != null) {
                // Suggest some tasks based on the exploration
                suggestTasks();
            }
        }
        
        private void suggestTasks() {
            taskArea.setText("Based on the exploration, you can:\n" +
                "- Implement a new feature using the discovered patterns\n" +
                "- Extend existing functionality\n" +
                "- Fix or improve the code\n\n" +
                "Enter your specific task here...");
        }
        
        private void executeCodingTask() {
            if (report == null) return;
            
            String task = taskArea.getText().trim();
            if (task.isEmpty()) {
                Messages.showWarningDialog(project, 
                    "Please enter a coding task", 
                    "No Task");
                return;
            }
            
            executeButton.setEnabled(false);
            statusLabel.setText("Generating code...");
            
            CompletableFuture.supplyAsync(() -> {
                CodingTaskAgent agent = project.getService(CodingTaskAgent.class);
                return agent.executeCodingTask(task, report);
            }).thenAccept(result -> {
                SwingUtilities.invokeLater(() -> {
                    if (result.isSuccess() && result.getGeneratedCode() != null) {
                        ApplicationManager.getApplication().runWriteAction(() -> {
                            resultEditor.getDocument().setText(result.getGeneratedCode());
                        });
                        statusLabel.setText("Code generated successfully");
                    } else {
                        Messages.showErrorDialog(project, 
                            "Failed to generate code: " + result.getError(), 
                            "Error");
                    }
                    executeButton.setEnabled(true);
                });
            });
        }
    }
    
    /**
     * Panel showing exploration history.
     */
    private class HistoryPanel extends JPanel {
        private final DefaultListModel<ExplorationHistoryItem> model;
        private final JBList<ExplorationHistoryItem> list;
        
        public HistoryPanel() {
            setLayout(new BorderLayout());
            
            model = new DefaultListModel<>();
            list = new JBList<>(model);
            list.setCellRenderer(new HistoryItemRenderer());
            
            add(new JBScrollPane(list), BorderLayout.CENTER);
            
            // Context menu
            list.setComponentPopupMenu(createHistoryPopupMenu());
            
            // Double-click to load
            list.addMouseListener(new java.awt.event.MouseAdapter() {
                public void mouseClicked(java.awt.event.MouseEvent evt) {
                    if (evt.getClickCount() == 2) {
                        ExplorationHistoryItem item = list.getSelectedValue();
                        if (item != null) {
                            loadFromHistory(item);
                        }
                    }
                }
            });
        }
        
        public void addExploration(String query, CodeExplorationReport report) {
            model.add(0, new ExplorationHistoryItem(query, report, new Date()));
            
            // Keep only last 50 items
            while (model.size() > 50) {
                model.remove(model.size() - 1);
            }
        }
        
        private JPopupMenu createHistoryPopupMenu() {
            JPopupMenu menu = new JPopupMenu();
            
            JMenuItem loadItem = new JMenuItem("Load Report", AllIcons.Actions.Download);
            loadItem.addActionListener(e -> {
                ExplorationHistoryItem item = list.getSelectedValue();
                if (item != null) {
                    loadFromHistory(item);
                }
            });
            
            JMenuItem deleteItem = new JMenuItem("Delete", AllIcons.Actions.Cancel);
            deleteItem.addActionListener(e -> {
                int index = list.getSelectedIndex();
                if (index >= 0) {
                    model.remove(index);
                }
            });
            
            menu.add(loadItem);
            menu.addSeparator();
            menu.add(deleteItem);
            
            return menu;
        }
        
        private void loadFromHistory(ExplorationHistoryItem item) {
            currentReport = item.report;
            reportPanel.displayReport(currentReport);
            codingPanel.setReport(currentReport);
            queryField.setText(item.query);
            tabs.select(tabs.getTabAt(1), true);
        }
    }
    
    // Helper classes
    
    private static class ToolExecutionItem {
        final ImprovedToolCallingAutonomousAgent.ToolExecution execution;
        final Date timestamp;
        
        ToolExecutionItem(ImprovedToolCallingAutonomousAgent.ToolExecution execution, Date timestamp) {
            this.execution = execution;
            this.timestamp = timestamp;
        }
    }
    
    private static class ToolExecutionRenderer extends DefaultListCellRenderer {
        private static final SimpleDateFormat TIME_FORMAT = new SimpleDateFormat("HH:mm:ss");
        
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, 
                                                    int index, boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            
            if (value instanceof ToolExecutionItem) {
                ToolExecutionItem item = (ToolExecutionItem) value;
                setText(String.format("[%s] %s - %s",
                    TIME_FORMAT.format(item.timestamp),
                    item.execution.getToolName(),
                    item.execution.isSuccess() ? "✓ Success" : "✗ Failed"
                ));
                
                setIcon(item.execution.isSuccess() ? 
                    AllIcons.RunConfigurations.TestPassed : 
                    AllIcons.RunConfigurations.TestFailed);
            }
            
            return this;
        }
    }
    
    private static class ExplorationHistoryItem {
        final String query;
        final CodeExplorationReport report;
        final Date timestamp;
        
        ExplorationHistoryItem(String query, CodeExplorationReport report, Date timestamp) {
            this.query = query;
            this.report = report;
            this.timestamp = timestamp;
        }
    }
    
    private static class HistoryItemRenderer extends DefaultListCellRenderer {
        private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("MMM dd, HH:mm");
        
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, 
                                                    int index, boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            
            if (value instanceof ExplorationHistoryItem) {
                ExplorationHistoryItem item = (ExplorationHistoryItem) value;
                setText(String.format("<html><b>%s</b><br><small>%s - %d elements</small></html>",
                    item.query,
                    DATE_FORMAT.format(item.timestamp),
                    item.report.getDiscoveredElements().size()
                ));
                
                setIcon(AllIcons.Nodes.Package);
            }
            
            return this;
        }
    }
}
