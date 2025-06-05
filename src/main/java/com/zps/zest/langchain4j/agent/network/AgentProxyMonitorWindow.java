package com.zps.zest.langchain4j.agent.network;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;

import javax.swing.*;
import javax.swing.Timer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellRenderer;
import java.awt.*;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Window for monitoring Agent Proxy Server activity and requests.
 */
public class AgentProxyMonitorWindow extends JDialog {
    private static final Logger LOG = Logger.getInstance(AgentProxyMonitorWindow.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    
    private final Project project;
    private final AgentProxyServer proxyServer;
    private final int proxyPort;
    
    // UI Components
    private final JTextArea logArea;
    private final JTextArea currentRequestArea;
    private final JProgressBar overallProgress;
    private final JLabel statusLabel;
    private final DefaultTableModel requestTableModel;
    private final JBTable requestTable;
    private final DefaultListModel<String> activeRequestsModel;
    private final JList<String> activeRequestsList;
    
    // Statistics
    private final AtomicInteger totalRequests = new AtomicInteger(0);
    private final AtomicInteger completedRequests = new AtomicInteger(0);
    private final AtomicInteger failedRequests = new AtomicInteger(0);
    private final Map<String, Long> requestStartTimes = new ConcurrentHashMap<>();
    
    // Threading
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private final ExecutorService executor = Executors.newCachedThreadPool();
    
    public AgentProxyMonitorWindow(Project project, AgentProxyServer proxyServer, int proxyPort) {
        super();
        this.project = project;
        this.proxyServer = proxyServer;
        this.proxyPort = proxyPort;
        
        setTitle("Agent Proxy Monitor - Port " + proxyPort);
        setModal(false);
        setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        
        // Initialize components
        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        logArea.setBackground(new Color(39, 40, 34));
        logArea.setForeground(new Color(248, 248, 242));
        
        currentRequestArea = new JTextArea();
        currentRequestArea.setEditable(false);
        currentRequestArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        currentRequestArea.setLineWrap(true);
        currentRequestArea.setWrapStyleWord(true);
        
        overallProgress = new JProgressBar();
        overallProgress.setStringPainted(true);
        
        statusLabel = new JLabel("Proxy Server Running on Port " + proxyPort);
        statusLabel.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        
        // Request history table
        String[] columnNames = {"Time", "Endpoint", "Method", "Status", "Duration", "Details"};
        requestTableModel = new DefaultTableModel(columnNames, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        requestTable = new JBTable(requestTableModel);
        requestTable.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        requestTable.getColumnModel().getColumn(0).setPreferredWidth(80);
        requestTable.getColumnModel().getColumn(1).setPreferredWidth(150);
        requestTable.getColumnModel().getColumn(2).setPreferredWidth(60);
        requestTable.getColumnModel().getColumn(3).setPreferredWidth(60);
        requestTable.getColumnModel().getColumn(4).setPreferredWidth(80);
        requestTable.getColumnModel().getColumn(5).setPreferredWidth(200);
        
        // Active requests list
        activeRequestsModel = new DefaultListModel<>();
        activeRequestsList = new JList<>(activeRequestsModel);
        activeRequestsList.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        activeRequestsList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        
        // Layout
        setupLayout();
        
        // Start monitoring
        startMonitoring();
        
        // Add shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(this::cleanup));
    }
    
    private void setupLayout() {
        setLayout(new BorderLayout());
        
        // Top panel - Status and controls
        JPanel topPanel = new JPanel(new BorderLayout());
        topPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        
        JPanel statusPanel = new JPanel(new GridLayout(2, 1, 5, 5));
        statusPanel.add(statusLabel);
        statusPanel.add(overallProgress);
        
        JPanel controlPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        
        JButton refreshButton = new JButton("Refresh");
        refreshButton.addActionListener(e -> refreshStatus());
        controlPanel.add(refreshButton);
        
        JButton clearLogsButton = new JButton("Clear Logs");
        clearLogsButton.addActionListener(e -> clearLogs());
        controlPanel.add(clearLogsButton);
        
        JButton configButton = new JButton("View Config");
        configButton.addActionListener(e -> viewConfiguration());
        controlPanel.add(configButton);
        
        topPanel.add(statusPanel, BorderLayout.CENTER);
        topPanel.add(controlPanel, BorderLayout.EAST);
        
        add(topPanel, BorderLayout.NORTH);
        
        // Main content - Tabbed pane
        JTabbedPane tabbedPane = new JTabbedPane();
        
        // Tab 1: Active Monitoring
        JPanel monitoringPanel = new JPanel(new BorderLayout());
        
        JSplitPane monitoringSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        
        // Active requests panel
        JPanel activePanel = new JPanel(new BorderLayout());
        activePanel.setBorder(BorderFactory.createTitledBorder("Active Requests"));
        activePanel.add(new JBScrollPane(activeRequestsList), BorderLayout.CENTER);
        
        // Current request details
        JPanel detailsPanel = new JPanel(new BorderLayout());
        detailsPanel.setBorder(BorderFactory.createTitledBorder("Current Request Details"));
        detailsPanel.add(new JBScrollPane(currentRequestArea), BorderLayout.CENTER);
        
        monitoringSplit.setTopComponent(activePanel);
        monitoringSplit.setBottomComponent(detailsPanel);
        monitoringSplit.setDividerLocation(200);
        
        monitoringPanel.add(monitoringSplit, BorderLayout.CENTER);
        
        // Statistics panel on the right
        JPanel statsPanel = createStatisticsPanel();
        monitoringPanel.add(statsPanel, BorderLayout.EAST);
        
        tabbedPane.addTab("Active Monitoring", monitoringPanel);
        
        // Tab 2: Request History
        JPanel historyPanel = new JPanel(new BorderLayout());
        historyPanel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        historyPanel.add(new JBScrollPane(requestTable), BorderLayout.CENTER);
        
        tabbedPane.addTab("Request History", historyPanel);
        
        // Tab 3: Server Logs
        JPanel logPanel = new JPanel(new BorderLayout());
        logPanel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        
        JScrollPane logScrollPane = new JBScrollPane(logArea);
        logScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        logPanel.add(logScrollPane, BorderLayout.CENTER);
        
        tabbedPane.addTab("Server Logs", logPanel);
        
        add(tabbedPane, BorderLayout.CENTER);
        
        // Bottom panel - Actions
        JPanel bottomPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        bottomPanel.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));
        
        JButton stopServerButton = new JButton("Stop Server");
        stopServerButton.addActionListener(e -> stopServer());
        bottomPanel.add(stopServerButton);
        
        JButton closeButton = new JButton("Close Monitor");
        closeButton.addActionListener(e -> dispose());
        bottomPanel.add(closeButton);
        
        add(bottomPanel, BorderLayout.SOUTH);
        
        // Set size and position
        setSize(1000, 700);
        setLocationRelativeTo(null);
    }
    
    private JPanel createStatisticsPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createTitledBorder("Statistics"));
        panel.setPreferredSize(new Dimension(250, 0));
        
        // Create stats labels
        JLabel totalLabel = new JLabel("Total Requests: 0");
        JLabel completedLabel = new JLabel("Completed: 0");
        JLabel failedLabel = new JLabel("Failed: 0");
        JLabel activeLabel = new JLabel("Active: 0");
        JLabel avgTimeLabel = new JLabel("Avg Time: 0s");
        
        // Update stats periodically
        Timer statsTimer = new Timer(1000, e -> {
            totalLabel.setText("Total Requests: " + totalRequests.get());
            completedLabel.setText("Completed: " + completedRequests.get());
            failedLabel.setText("Failed: " + failedRequests.get());
            activeLabel.setText("Active: " + activeRequestsModel.size());
            
            // Calculate average time
            if (completedRequests.get() > 0) {
                // This is simplified - you'd want to track actual durations
                avgTimeLabel.setText("Avg Time: N/A");
            }
        });
        statsTimer.start();
        
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
        panel.add(Box.createVerticalGlue());
        
        return panel;
    }
    
    private void startMonitoring() {
        // Start periodic status check
        scheduler.scheduleAtFixedRate(this::checkServerStatus, 0, 5, TimeUnit.SECONDS);
        
        log("Agent Proxy Monitor started");
        log("Monitoring server on port " + proxyPort);
        
        // Simulate request monitoring (in real implementation, this would hook into the server)
        proxyServer.addRequestListener(new AgentProxyServer.RequestListener() {
            @Override
            public void onRequestStarted(String requestId, String endpoint, String method) {
                SwingUtilities.invokeLater(() -> {
                    totalRequests.incrementAndGet();
                    String requestInfo = String.format("[%s] %s %s", requestId, method, endpoint);
                    activeRequestsModel.addElement(requestInfo);
                    requestStartTimes.put(requestId, System.currentTimeMillis());
                    
                    log("Request started: " + requestInfo);
                    updateProgress();
                });
            }
            
            @Override
            public void onRequestCompleted(String requestId, int statusCode, String response) {
                SwingUtilities.invokeLater(() -> {
                    completedRequests.incrementAndGet();
                    removeActiveRequest(requestId);
                    
                    Long startTime = requestStartTimes.remove(requestId);
                    long duration = startTime != null ? System.currentTimeMillis() - startTime : 0;
                    
                    addRequestToHistory(requestId, "Success", statusCode, duration, 
                        response != null ? response.substring(0, Math.min(response.length(), 100)) : "");
                    
                    log("Request completed: " + requestId + " (Status: " + statusCode + ")");
                    updateProgress();
                });
            }
            
            @Override
            public void onRequestFailed(String requestId, String error) {
                SwingUtilities.invokeLater(() -> {
                    failedRequests.incrementAndGet();
                    removeActiveRequest(requestId);
                    
                    Long startTime = requestStartTimes.remove(requestId);
                    long duration = startTime != null ? System.currentTimeMillis() - startTime : 0;
                    
                    addRequestToHistory(requestId, "Failed", -1, duration, error);
                    
                    log("Request failed: " + requestId + " - " + error);
                    updateProgress();
                });
            }
            
            @Override
            public void onToolExecuted(String requestId, String toolName, boolean success, String result) {
                SwingUtilities.invokeLater(() -> {
                    String logMsg = String.format("Tool executed in %s: %s (%s)", 
                        requestId, toolName, success ? "Success" : "Failed");
                    log(logMsg);
                    
                    if (currentRequestArea != null) {
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
    
    private void removeActiveRequest(String requestId) {
        for (int i = 0; i < activeRequestsModel.size(); i++) {
            String element = activeRequestsModel.get(i);
            if (element.startsWith("[" + requestId + "]")) {
                activeRequestsModel.remove(i);
                break;
            }
        }
    }
    
    private void addRequestToHistory(String requestId, String status, int statusCode, 
                                      long duration, String details) {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");
        String time = sdf.format(new Date());
        
        String endpoint = "N/A";
        String method = "N/A";
        
        // Extract from active request info if available
        for (int i = 0; i < activeRequestsModel.size(); i++) {
            String element = activeRequestsModel.get(i);
            if (element.startsWith("[" + requestId + "]")) {
                String[] parts = element.split(" ");
                if (parts.length >= 3) {
                    method = parts[1];
                    endpoint = parts[2];
                }
                break;
            }
        }
        
        Object[] rowData = {
            time,
            endpoint,
            method,
            status + (statusCode > 0 ? " (" + statusCode + ")" : ""),
            duration + "ms",
            details
        };
        
        requestTableModel.insertRow(0, rowData);
        
        // Limit history to 100 entries
        if (requestTableModel.getRowCount() > 100) {
            requestTableModel.removeRow(100);
        }
    }
    
    private void updateProgress() {
        int total = totalRequests.get();
        int completed = completedRequests.get() + failedRequests.get();
        
        if (total > 0) {
            int percentage = (completed * 100) / total;
            overallProgress.setValue(percentage);
            overallProgress.setString(String.format("%d%% (%d/%d)", percentage, completed, total));
        }
    }
    
    private void checkServerStatus() {
        executor.submit(() -> {
            try {
                URL url = new URL("http://localhost:" + proxyPort + "/health");
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(2000);
                connection.setReadTimeout(2000);
                
                int responseCode = connection.getResponseCode();
                if (responseCode == 200) {
                    SwingUtilities.invokeLater(() -> 
                        statusLabel.setText("✓ Proxy Server Running on Port " + proxyPort));
                } else {
                    SwingUtilities.invokeLater(() -> 
                        statusLabel.setText("⚠ Server Responding with Code: " + responseCode));
                }
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> 
                    statusLabel.setText("✗ Server Not Responding on Port " + proxyPort));
            }
        });
    }
    
    private void refreshStatus() {
        checkServerStatus();
        log("Status refreshed");
    }
    
    private void clearLogs() {
        logArea.setText("");
        log("Logs cleared");
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
                    
                    SwingUtilities.invokeLater(() -> {
                        JTextArea configArea = new JTextArea(20, 60);
                        configArea.setText(response.toString());
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
            statusLabel.setText("✗ Server Stopped");
            log("Server stopped by user");
            
            // Disable controls
            SwingUtilities.invokeLater(() -> {
                Component[] components = getContentPane().getComponents();
                for (Component component : components) {
                    if (component instanceof JButton && !"Close Monitor".equals(((JButton) component).getText())) {
                        component.setEnabled(false);
                    }
                }
            });
        }
    }
    
    private void log(String message) {
        SwingUtilities.invokeLater(() -> {
            SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss.SSS");
            String timestamp = sdf.format(new Date());
            logArea.append("[" + timestamp + "] " + message + "\n");
            
            // Auto-scroll to bottom
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
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
    
    @Override
    public void dispose() {
        cleanup();
        super.dispose();
    }
}
