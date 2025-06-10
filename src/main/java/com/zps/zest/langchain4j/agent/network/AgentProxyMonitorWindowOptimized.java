package com.zps.zest.langchain4j.agent.network;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.ColorUtil;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import com.intellij.util.ui.StartupUiUtil;

import javax.swing.*;
import javax.swing.Timer;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Optimized Window for monitoring Agent Proxy Server activity with better UX on Windows.
 */
public class AgentProxyMonitorWindowOptimized extends JDialog {
    private static final Logger LOG = Logger.getInstance(AgentProxyMonitorWindowOptimized.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    
    // Constants for better UX
    private static final int MIN_WIDTH = 800;
    private static final int MIN_HEIGHT = 500;
    private static final int DEFAULT_WIDTH = 1200;
    private static final int DEFAULT_HEIGHT = 700;
    private static final int MAX_LOG_LINES = 1000;
    private static final int MAX_ACTIVE_REQUESTS = 50;
    private static final int UPDATE_INTERVAL_MS = 500; // Faster updates
    
    private final Project project;
    private final AgentProxyServer proxyServer;
    private final int proxyPort;
    
    // UI Components
    private final JTextArea logArea;
    private final JTextArea currentRequestArea;
    private final JProgressBar overallProgress;
    private final JLabel statusLabel;
    private final JLabel connectionStatusLabel;
    private final DefaultTableModel requestTableModel;
    private final JBTable requestTable;
    private final DefaultListModel<RequestInfo> activeRequestsModel;
    private final JList<RequestInfo> activeRequestsList;
    
    // Enhanced Statistics
    private final AtomicInteger totalRequests = new AtomicInteger(0);
    private final AtomicInteger completedRequests = new AtomicInteger(0);
    private final AtomicInteger failedRequests = new AtomicInteger(0);
    private final AtomicLong totalResponseTime = new AtomicLong(0);
    private final Map<String, RequestInfo> activeRequests = new ConcurrentHashMap<>();
    
    // UI Components for statistics
    private JLabel totalLabel, completedLabel, failedLabel, activeLabel, avgTimeLabel, throughputLabel;
    private JProgressBar cpuUsageBar, memoryUsageBar;
    
    // Threading
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private volatile boolean isDisposed = false;
    
    // Request Info holder
    private static class RequestInfo {
        final String id;
        final String endpoint;
        final String method;
        final long startTime;
        volatile String status = "Active";
        volatile long endTime;
        
        RequestInfo(String id, String endpoint, String method) {
            this.id = id;
            this.endpoint = endpoint;
            this.method = method;
            this.startTime = System.currentTimeMillis();
        }
        
        long getDuration() {
            return (endTime > 0 ? endTime : System.currentTimeMillis()) - startTime;
        }
        
        @Override
        public String toString() {
            return String.format("[%s] %s %s (%dms)", id, method, endpoint, getDuration());
        }
    }
    
    public AgentProxyMonitorWindowOptimized(Project project, AgentProxyServer proxyServer, int proxyPort) {
        super();
        this.project = project;
        this.proxyServer = proxyServer;
        this.proxyPort = proxyPort;
        
        setTitle("Agent Proxy Monitor - Port " + proxyPort);
        setModal(false);
        setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        
        // Initialize components with better theming
        logArea = createThemedTextArea();
        currentRequestArea = createThemedTextArea();
        
        overallProgress = new JProgressBar();
        overallProgress.setStringPainted(true);
        
        statusLabel = new JLabel("Proxy Server Running on Port " + proxyPort);
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.BOLD));
        
        connectionStatusLabel = new JLabel("● Connected");
        connectionStatusLabel.setForeground(JBColor.GREEN);
        
        // Request history table with better model
        String[] columnNames = {"Time", "Endpoint", "Method", "Status", "Duration", "Details"};
        requestTableModel = new DefaultTableModel(columnNames, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
            
            @Override
            public Class<?> getColumnClass(int columnIndex) {
                if (columnIndex == 4) return Long.class; // Duration column for proper sorting
                return String.class;
            }
        };
        
        requestTable = new JBTable(requestTableModel);
        requestTable.setAutoCreateRowSorter(true);
        setupTableRenderers();
        
        // Active requests list with custom renderer
        activeRequestsModel = new DefaultListModel<>();
        activeRequestsList = new JList<>(activeRequestsModel);
        activeRequestsList.setCellRenderer(new ActiveRequestRenderer());
        activeRequestsList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        
        // Add selection listener to show details
        activeRequestsList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                RequestInfo selected = activeRequestsList.getSelectedValue();
                if (selected != null) {
                    showRequestDetails(selected);
                }
            }
        });
        
        // Layout
        setupLayout();
        
        // Start monitoring
        startMonitoring();
        
        // Add window state persistence
        loadWindowState();
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                saveWindowState();
            }
            
            @Override
            public void componentMoved(ComponentEvent e) {
                saveWindowState();
            }
        });
    }
    
    private JTextArea createThemedTextArea() {
        JTextArea area = new JTextArea();
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        
        // Use IDE theme colors
        if (StartupUiUtil.isUnderDarcula()) {
            area.setBackground(UIUtil.getPanelBackground());
            area.setForeground(UIUtil.getLabelForeground());
        } else {
            area.setBackground(UIUtil.getTextFieldBackground());
            area.setForeground(UIUtil.getTextFieldForeground());
        }
        
        return area;
    }
    
    private void setupTableRenderers() {
        // Custom renderer for status column
        requestTable.getColumnModel().getColumn(3).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value,
                    boolean isSelected, boolean hasFocus, int row, int column) {
                Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                
                if (value != null && !isSelected) {
                    String status = value.toString();
                    if (status.contains("Success")) {
                        setForeground(JBColor.GREEN);
                    } else if (status.contains("Failed")) {
                        setForeground(JBColor.RED);
                    }
                }
                
                return c;
            }
        });
        
        // Auto-resize columns based on content
        autoResizeTableColumns();
    }
    
    private void autoResizeTableColumns() {
        requestTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        
        for (int column = 0; column < requestTable.getColumnCount(); column++) {
            TableColumn tableColumn = requestTable.getColumnModel().getColumn(column);
            int preferredWidth = tableColumn.getMinWidth();
            int maxWidth = tableColumn.getMaxWidth();
            
            // Get header width
            TableCellRenderer headerRenderer = tableColumn.getHeaderRenderer();
            if (headerRenderer == null) {
                headerRenderer = requestTable.getTableHeader().getDefaultRenderer();
            }
            Component headerComp = headerRenderer.getTableCellRendererComponent(
                requestTable, tableColumn.getHeaderValue(), false, false, 0, column);
            preferredWidth = Math.max(preferredWidth, headerComp.getPreferredSize().width);
            
            // Get maximum width of first 10 rows
            for (int row = 0; row < Math.min(10, requestTable.getRowCount()); row++) {
                TableCellRenderer cellRenderer = requestTable.getCellRenderer(row, column);
                Component comp = requestTable.prepareRenderer(cellRenderer, row, column);
                preferredWidth = Math.max(comp.getPreferredSize().width + 1, preferredWidth);
            }
            
            tableColumn.setPreferredWidth(Math.min(preferredWidth + 10, maxWidth));
        }
        
        requestTable.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
    }
    
    private void setupLayout() {
        setLayout(new BorderLayout());
        
        // Top panel - Status and controls
        JPanel topPanel = createTopPanel();
        add(topPanel, BorderLayout.NORTH);
        
        // Main content - Tabbed pane
        JTabbedPane tabbedPane = new JTabbedPane();
        tabbedPane.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        
        // Tab 1: Active Monitoring
        tabbedPane.addTab("Active Monitoring", createMonitoringPanel());
        
        // Tab 2: Request History
        tabbedPane.addTab("Request History", createHistoryPanel());
        
        // Tab 3: Server Logs
        tabbedPane.addTab("Server Logs", createLogPanel());
        
        // Tab 4: Performance
        tabbedPane.addTab("Performance", createPerformancePanel());
        
        add(tabbedPane, BorderLayout.CENTER);
        
        // Bottom panel - Actions
        JPanel bottomPanel = createBottomPanel();
        add(bottomPanel, BorderLayout.SOUTH);
        
        // Set size and position
        setMinimumSize(new Dimension(MIN_WIDTH, MIN_HEIGHT));
        setPreferredSize(new Dimension(DEFAULT_WIDTH, DEFAULT_HEIGHT));
        pack();
        setLocationRelativeTo(null);
    }
    
    private JPanel createTopPanel() {
        JPanel topPanel = new JPanel(new BorderLayout());
        topPanel.setBorder(JBUI.Borders.empty(10));
        
        // Status panel
        JPanel statusPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = JBUI.insets(2);
        
        gbc.gridx = 0; gbc.gridy = 0;
        statusPanel.add(statusLabel, gbc);
        
        gbc.gridx = 1; gbc.weightx = 1.0;
        statusPanel.add(Box.createHorizontalStrut(20), gbc);
        
        gbc.gridx = 2; gbc.weightx = 0;
        statusPanel.add(connectionStatusLabel, gbc);
        
        gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 3;
        statusPanel.add(overallProgress, gbc);
        
        // Control panel
        JPanel controlPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        
        JButton refreshButton = createButton("Refresh", this::refreshStatus);
        controlPanel.add(refreshButton);
        
        JButton clearLogsButton = createButton("Clear Logs", this::clearLogs);
        controlPanel.add(clearLogsButton);
        
        JButton configButton = createButton("View Config", this::viewConfiguration);
        controlPanel.add(configButton);
        
        topPanel.add(statusPanel, BorderLayout.CENTER);
        topPanel.add(controlPanel, BorderLayout.EAST);
        
        return topPanel;
    }
    
    private JButton createButton(String text, Runnable action) {
        JButton button = new JButton(text);
        button.addActionListener(e -> action.run());
        return button;
    }
    
    private JPanel createMonitoringPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        
        // Split pane for active requests and details
        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        splitPane.setResizeWeight(0.4);
        
        // Active requests panel
        JPanel activePanel = new JPanel(new BorderLayout());
        activePanel.setBorder(BorderFactory.createTitledBorder("Active Requests"));
        activePanel.add(new JBScrollPane(activeRequestsList), BorderLayout.CENTER);
        
        // Current request details
        JPanel detailsPanel = new JPanel(new BorderLayout());
        detailsPanel.setBorder(BorderFactory.createTitledBorder("Request Details"));
        detailsPanel.add(new JBScrollPane(currentRequestArea), BorderLayout.CENTER);
        
        splitPane.setTopComponent(activePanel);
        splitPane.setBottomComponent(detailsPanel);
        
        panel.add(splitPane, BorderLayout.CENTER);
        panel.add(createStatisticsPanel(), BorderLayout.EAST);
        
        return panel;
    }
    
    private JPanel createHistoryPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(JBUI.Borders.empty(5));
        
        JScrollPane scrollPane = new JBScrollPane(requestTable);
        panel.add(scrollPane, BorderLayout.CENTER);
        
        // Add search/filter panel
        JPanel filterPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JTextField searchField = new JTextField(20);
        searchField.putClientProperty("JTextField.placeholderText", "Search requests...");
        
        searchField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { filterTable(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { filterTable(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { filterTable(); }
            
            private void filterTable() {
                String text = searchField.getText().toLowerCase();
                // Implement table filtering logic here
            }
        });
        
        filterPanel.add(new JLabel("Search:"));
        filterPanel.add(searchField);
        
        panel.add(filterPanel, BorderLayout.NORTH);
        
        return panel;
    }
    
    private JPanel createLogPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(JBUI.Borders.empty(5));
        
        JScrollPane scrollPane = new JBScrollPane(logArea);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        panel.add(scrollPane, BorderLayout.CENTER);
        
        // Add log controls
        JPanel controlPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        
        JCheckBox autoScrollCheckBox = new JCheckBox("Auto-scroll", true);
        autoScrollCheckBox.addActionListener(e -> {
            if (autoScrollCheckBox.isSelected()) {
                logArea.setCaretPosition(logArea.getDocument().getLength());
            }
        });
        controlPanel.add(autoScrollCheckBox);
        
        JComboBox<String> logLevelCombo = new JComboBox<>(new String[]{"ALL", "INFO", "WARN", "ERROR"});
        controlPanel.add(new JLabel("Level:"));
        controlPanel.add(logLevelCombo);
        
        panel.add(controlPanel, BorderLayout.SOUTH);
        
        return panel;
    }
    
    private JPanel createPerformancePanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(JBUI.Borders.empty(10));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = JBUI.insets(5);
        
        // CPU Usage
        gbc.gridx = 0; gbc.gridy = 0;
        panel.add(new JLabel("CPU Usage:"), gbc);
        
        gbc.gridx = 1; gbc.weightx = 1.0;
        cpuUsageBar = new JProgressBar(0, 100);
        cpuUsageBar.setStringPainted(true);
        panel.add(cpuUsageBar, gbc);
        
        // Memory Usage
        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0;
        panel.add(new JLabel("Memory Usage:"), gbc);
        
        gbc.gridx = 1; gbc.weightx = 1.0;
        memoryUsageBar = new JProgressBar(0, 100);
        memoryUsageBar.setStringPainted(true);
        panel.add(memoryUsageBar, gbc);
        
        // Throughput graph placeholder
        gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 2; gbc.weighty = 1.0;
        gbc.fill = GridBagConstraints.BOTH;
        JPanel graphPanel = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                // Simple placeholder for throughput graph
                g.setColor(JBColor.GRAY);
                String text = "Throughput Graph (Coming Soon)";
                FontMetrics fm = g.getFontMetrics();
                int x = (getWidth() - fm.stringWidth(text)) / 2;
                int y = getHeight() / 2;
                g.drawString(text, x, y);
            }
        };
        graphPanel.setBorder(BorderFactory.createLineBorder(JBColor.border()));
        panel.add(graphPanel, gbc);
        
        return panel;
    }
    
    private JPanel createStatisticsPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createTitledBorder("Statistics"));
        panel.setPreferredSize(new Dimension(250, 0));
        
        // Create stats labels
        totalLabel = new JLabel("Total Requests: 0");
        completedLabel = new JLabel("Completed: 0");
        failedLabel = new JLabel("Failed: 0");
        activeLabel = new JLabel("Active: 0");
        avgTimeLabel = new JLabel("Avg Time: 0ms");
        throughputLabel = new JLabel("Throughput: 0/s");
        
        panel.add(Box.createVerticalStrut(10));
        panel.add(totalLabel);
        panel.add(Box.createVerticalStrut(5));
        panel.add(completedLabel);
        panel.add(Box.createVerticalStrut(5));
        panel.add(failedLabel);
        panel.add(Box.createVerticalStrut(5));
        panel.add(activeLabel);
        panel.add(Box.createVerticalStrut(5));
        panel.add(avgTimeLabel);
        panel.add(Box.createVerticalStrut(5));
        panel.add(throughputLabel);
        panel.add(Box.createVerticalGlue());
        
        return panel;
    }
    
    private JPanel createBottomPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        panel.setBorder(JBUI.Borders.empty(5, 10));
        
        JButton exportButton = createButton("Export Logs", this::exportLogs);
        panel.add(exportButton);
        
        JButton stopServerButton = createButton("Stop Server", this::stopServer);
        stopServerButton.setForeground(JBColor.RED);
        panel.add(stopServerButton);
        
        JButton closeButton = createButton("Close Monitor", this::dispose);
        panel.add(closeButton);
        
        return panel;
    }
    
    private void startMonitoring() {
        // Start periodic updates
        scheduler.scheduleAtFixedRate(this::updateUI, 0, UPDATE_INTERVAL_MS, TimeUnit.MILLISECONDS);
        scheduler.scheduleAtFixedRate(this::checkServerStatus, 0, 5, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(this::updatePerformanceMetrics, 0, 1, TimeUnit.SECONDS);
        
        log("Agent Proxy Monitor started");
        log("Monitoring server on port " + proxyPort);
        
        // Register request listener
        proxyServer.addRequestListener(new AgentProxyServer.RequestListener() {
            @Override
            public void onRequestStarted(String requestId, String endpoint, String method) {
                executor.submit(() -> {
                    RequestInfo info = new RequestInfo(requestId, endpoint, method);
                    activeRequests.put(requestId, info);
                    totalRequests.incrementAndGet();
                    
                    SwingUtilities.invokeLater(() -> {
                        // Limit active requests display
                        if (activeRequestsModel.size() >= MAX_ACTIVE_REQUESTS) {
                            activeRequestsModel.remove(0);
                        }
                        activeRequestsModel.addElement(info);
                    });
                    
                    log("Request started: " + info);
                });
            }
            
            @Override
            public void onRequestCompleted(String requestId, int statusCode, String response) {
                executor.submit(() -> {
                    RequestInfo info = activeRequests.remove(requestId);
                    if (info != null) {
                        info.endTime = System.currentTimeMillis();
                        info.status = "Success (" + statusCode + ")";
                        
                        completedRequests.incrementAndGet();
                        totalResponseTime.addAndGet(info.getDuration());
                        
                        SwingUtilities.invokeLater(() -> {
                            activeRequestsModel.removeElement(info);
                            addRequestToHistory(info, response);
                        });
                        
                        log("Request completed: " + requestId + " in " + info.getDuration() + "ms");
                    }
                });
            }
            
            @Override
            public void onRequestFailed(String requestId, String error) {
                executor.submit(() -> {
                    RequestInfo info = activeRequests.remove(requestId);
                    if (info != null) {
                        info.endTime = System.currentTimeMillis();
                        info.status = "Failed";
                        
                        failedRequests.incrementAndGet();
                        
                        SwingUtilities.invokeLater(() -> {
                            activeRequestsModel.removeElement(info);
                            addRequestToHistory(info, error);
                        });
                        
                        log("Request failed: " + requestId + " - " + error);
                    }
                });
            }
            
            @Override
            public void onToolExecuted(String requestId, String toolName, boolean success, String result) {
                SwingUtilities.invokeLater(() -> {
                    String logMsg = String.format("Tool executed in %s: %s (%s)", 
                        requestId, toolName, success ? "Success" : "Failed");
                    log(logMsg);
                    
                    if (currentRequestArea != null && activeRequestsList.getSelectedValue() != null 
                            && activeRequestsList.getSelectedValue().id.equals(requestId)) {
                        currentRequestArea.append("\n" + logMsg + "\n");
                        if (result != null && result.length() > 0) {
                            currentRequestArea.append("Result: " + 
                                result.substring(0, Math.min(result.length(), 200)) + "\n");
                        }
                    }
                });
            }
        });
    }
    
    private void updateUI() {
        if (isDisposed) return;
        
        SwingUtilities.invokeLater(() -> {
            // Update statistics
            int total = totalRequests.get();
            int completed = completedRequests.get() + failedRequests.get();
            int active = activeRequests.size();
            
            totalLabel.setText("Total Requests: " + total);
            completedLabel.setText("Completed: " + completedRequests.get());
            failedLabel.setText("Failed: " + failedRequests.get());
            activeLabel.setText("Active: " + active);
            
            // Calculate average time
            if (completedRequests.get() > 0) {
                long avgTime = totalResponseTime.get() / completedRequests.get();
                avgTimeLabel.setText("Avg Time: " + avgTime + "ms");
            }
            
            // Update progress
            if (total > 0) {
                int percentage = (completed * 100) / total;
                overallProgress.setValue(percentage);
                overallProgress.setString(String.format("%d%% (%d/%d)", percentage, completed, total));
            }
            
            // Calculate throughput (requests per second over last minute)
            // This is simplified - you'd want a rolling window in production
            if (total > 0) {
                long elapsedSeconds = (System.currentTimeMillis() - getStartTime()) / 1000;
                if (elapsedSeconds > 0) {
                    double throughput = (double) completed / elapsedSeconds;
                    throughputLabel.setText(String.format("Throughput: %.2f/s", throughput));
                }
            }
        });
    }
    
    private void updatePerformanceMetrics() {
        if (isDisposed) return;
        
        SwingUtilities.invokeLater(() -> {
            // Get runtime metrics
            Runtime runtime = Runtime.getRuntime();
            long totalMemory = runtime.totalMemory();
            long freeMemory = runtime.freeMemory();
            long usedMemory = totalMemory - freeMemory;
            
            int memoryPercentage = (int) ((usedMemory * 100) / runtime.maxMemory());
            memoryUsageBar.setValue(memoryPercentage);
            memoryUsageBar.setString(String.format("%d%% (%d MB / %d MB)", 
                memoryPercentage, 
                usedMemory / (1024 * 1024), 
                runtime.maxMemory() / (1024 * 1024)));
            
            // CPU usage (simplified - would need JMX for accurate measurement)
            // For now, just show a mock value based on active requests
            int cpuEstimate = Math.min(activeRequests.size() * 10, 100);
            cpuUsageBar.setValue(cpuEstimate);
            cpuUsageBar.setString(cpuEstimate + "%");
        });
    }
    
    private void showRequestDetails(RequestInfo info) {
        currentRequestArea.setText("");
        currentRequestArea.append("Request ID: " + info.id + "\n");
        currentRequestArea.append("Endpoint: " + info.endpoint + "\n");
        currentRequestArea.append("Method: " + info.method + "\n");
        currentRequestArea.append("Status: " + info.status + "\n");
        currentRequestArea.append("Start Time: " + new SimpleDateFormat("HH:mm:ss.SSS").format(new Date(info.startTime)) + "\n");
        currentRequestArea.append("Duration: " + info.getDuration() + "ms\n");
        currentRequestArea.append("\n--- Tool Executions ---\n");
    }
    
    private void addRequestToHistory(RequestInfo info, String details) {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");
        String time = sdf.format(new Date(info.startTime));
        
        Object[] rowData = {
            time,
            info.endpoint,
            info.method,
            info.status,
            info.getDuration(), // Store as Long for proper sorting
            details != null ? details.substring(0, Math.min(details.length(), 100)) : ""
        };
        
        requestTableModel.insertRow(0, rowData);
        
        // Limit history to 100 entries
        if (requestTableModel.getRowCount() > 100) {
            requestTableModel.removeRow(100);
        }
        
        // Auto-resize columns after adding new data
        autoResizeTableColumns();
    }
    
    private void checkServerStatus() {
        if (isDisposed) return;
        
        executor.submit(() -> {
            try {
                URL url = new URL("http://localhost:" + proxyPort + "/health");
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(2000);
                connection.setReadTimeout(2000);
                
                int responseCode = connection.getResponseCode();
                SwingUtilities.invokeLater(() -> {
                    if (responseCode == 200) {
                        connectionStatusLabel.setText("● Connected");
                        connectionStatusLabel.setForeground(JBColor.GREEN);
                        connectionStatusLabel.setIcon(AllIcons.RunConfigurations.TestPassed);
                    } else {
                        connectionStatusLabel.setText("● Warning (" + responseCode + ")");
                        connectionStatusLabel.setForeground(JBColor.ORANGE);
                        connectionStatusLabel.setIcon(AllIcons.General.Warning);
                    }
                });
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    connectionStatusLabel.setText("● Disconnected");
                    connectionStatusLabel.setForeground(JBColor.RED);
                    connectionStatusLabel.setIcon(AllIcons.General.Error);
                });
            }
        });
    }
    
    private void refreshStatus() {
        checkServerStatus();
        updateUI();
        updatePerformanceMetrics();
        log("Status refreshed");
    }
    
    private void clearLogs() {
        logArea.setText("");
        log("Logs cleared");
    }
    
    private void exportLogs() {
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new java.io.File("agent-proxy-logs-" + 
            new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()) + ".txt"));
        
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            try {
                java.nio.file.Files.write(
                    chooser.getSelectedFile().toPath(),
                    logArea.getText().getBytes(java.nio.charset.StandardCharsets.UTF_8)
                );
                JOptionPane.showMessageDialog(this, "Logs exported successfully", 
                    "Export Complete", JOptionPane.INFORMATION_MESSAGE);
            } catch (IOException e) {
                JOptionPane.showMessageDialog(this, "Failed to export logs: " + e.getMessage(), 
                    "Export Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }
    
    private void viewConfiguration() {
        executor.submit(() -> {
            try {
                URL url = new URL("http://localhost:" + proxyPort + "/config");
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                
                if (connection.getResponseCode() == 200) {
                    Scanner scanner = new Scanner(connection.getInputStream());
                    StringBuilder response = new StringBuilder();
                    while (scanner.hasNextLine()) {
                        response.append(scanner.nextLine()).append("\n");
                    }
                    scanner.close();
                    
                    // Pretty print JSON
                    JsonObject json = JsonParser.parseString(response.toString()).getAsJsonObject();
                    String prettyJson = GSON.toJson(json);
                    
                    SwingUtilities.invokeLater(() -> {
                        JTextArea configArea = new JTextArea(20, 60);
                        configArea.setText(prettyJson);
                        configArea.setEditable(false);
                        configArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
                        
                        JScrollPane scrollPane = new JScrollPane(configArea);
                        JOptionPane.showMessageDialog(this, scrollPane, 
                            "Agent Proxy Configuration", JOptionPane.INFORMATION_MESSAGE);
                    });
                }
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> 
                    Messages.showErrorDialog(project, 
                        "Failed to fetch configuration: " + e.getMessage(), 
                        "Error"));
            }
        });
    }
    
    private void stopServer() {
        int result = Messages.showYesNoDialog(
            project,
            "Are you sure you want to stop the Agent Proxy Server?",
            "Stop Server",
            Messages.getQuestionIcon()
        );
        
        if (result == Messages.YES) {
            proxyServer.stop();
            connectionStatusLabel.setText("● Server Stopped");
            connectionStatusLabel.setForeground(JBColor.GRAY);
            connectionStatusLabel.setIcon(null);
            log("Server stopped by user");
            
            // Disable relevant controls
            SwingUtilities.invokeLater(() -> {
                for (Component comp : getContentPane().getComponents()) {
                    if (comp instanceof JButton && !((JButton)comp).getText().equals("Close Monitor")) {
                        comp.setEnabled(false);
                    }
                }
            });
        }
    }
    
    private void log(String message) {
        if (isDisposed) return;
        
        SwingUtilities.invokeLater(() -> {
            SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss.SSS");
            String timestamp = sdf.format(new Date());
            String logEntry = "[" + timestamp + "] " + message + "\n";
            
            logArea.append(logEntry);
            
            // Limit log size
            if (logArea.getLineCount() > MAX_LOG_LINES) {
                try {
                    int endOffset = logArea.getLineEndOffset(logArea.getLineCount() - MAX_LOG_LINES);
                    logArea.replaceRange("", 0, endOffset);
                } catch (Exception e) {
                    // Ignore
                }
            }
            
            // Auto-scroll if at bottom
            if (logArea.getCaretPosition() + 100 >= logArea.getDocument().getLength()) {
                logArea.setCaretPosition(logArea.getDocument().getLength());
            }
        });
    }
    
    private void loadWindowState() {
        // Load saved window position and size from project settings
        // This is a placeholder - implement actual persistence
    }
    
    private void saveWindowState() {
        // Save window position and size to project settings
        // This is a placeholder - implement actual persistence
    }
    
    private long getStartTime() {
        // Track when monitoring started
        return System.currentTimeMillis() - (60 * 1000); // Mock: started 1 minute ago
    }
    
    @Override
    public void dispose() {
        isDisposed = true;
        cleanup();
        super.dispose();
    }
    
    private void cleanup() {
        scheduler.shutdown();
        executor.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            executor.shutdownNow();
        }
    }
    
    /**
     * Custom renderer for active requests list
     */
    private static class ActiveRequestRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value,
                int index, boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            
            if (value instanceof RequestInfo) {
                RequestInfo info = (RequestInfo) value;
                setText(info.toString());
                
                // Color code based on duration
                if (!isSelected) {
                    long duration = info.getDuration();
                    if (duration > 5000) {
                        setForeground(JBColor.RED);
                    } else if (duration > 2000) {
                        setForeground(JBColor.ORANGE);
                    }
                }
            }
            
            return this;
        }
    }
}