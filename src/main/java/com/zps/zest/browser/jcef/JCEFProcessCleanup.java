package com.zps.zest.browser.jcef;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.SystemInfo;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.util.*;

/**
 * Utility class to handle JCEF process cleanup and prevent orphaned helper processes
 */
public class JCEFProcessCleanup {
    private static final Logger LOG = Logger.getInstance(JCEFProcessCleanup.class);
    private static final Set<Integer> trackedProcessIds = Collections.synchronizedSet(new HashSet<>());
    private static boolean shutdownHookRegistered = false;
    private static final String PARENT_PID = getParentProcessId();
    
    /**
     * Register a shutdown hook to clean up JCEF processes
     */
    public static synchronized void registerShutdownHook() {
        if (!shutdownHookRegistered) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                LOG.info("Shutting down JCEF processes...");
                cleanupAllJCEFProcesses();
            }, "JCEF-Cleanup-Thread"));
            shutdownHookRegistered = true;
            LOG.info("JCEF shutdown hook registered");
        }
    }
    
    /**
     * Clean up all JCEF helper processes
     */
    public static void cleanupAllJCEFProcesses() {
        try {
            if (SystemInfo.isWindows) {
                cleanupWindowsProcesses();
            } else if (SystemInfo.isMac) {
                cleanupMacProcesses();
            } else if (SystemInfo.isLinux) {
                cleanupLinuxProcesses();
            }
        } catch (Exception e) {
            LOG.error("Error cleaning up JCEF processes", e);
        }
    }
    
    /**
     * Clean up JCEF processes on Windows
     */
    private static void cleanupWindowsProcesses() {
        try {
            // First, try to find all jcef_helper.exe processes that are children of our process
            String currentPid = getCurrentProcessId();
            
            // Use WMIC to find child processes
            ProcessBuilder pb = new ProcessBuilder(
                "wmic", "process", "where", 
                "(Name='jcef_helper.exe' OR Name='jcef_helper.exe *32')",
                "get", "ProcessId,ParentProcessId"
            );
            
            Process process = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.contains("ProcessId")) continue;
                    
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 2) {
                        String parentPid = parts[0];
                        String pid = parts[1];
                        
                        // Kill if it's our child process
                        if (parentPid.equals(currentPid) || trackedProcessIds.contains(Integer.parseInt(pid))) {
                            killWindowsProcess(pid);
                        }
                    }
                }
            }
            
            // Also kill any orphaned jcef_helper processes (those without a parent)
            killOrphanedJCEFProcesses();
            
        } catch (Exception e) {
            LOG.error("Error cleaning up Windows JCEF processes", e);
        }
    }
    
    /**
     * Kill a Windows process by PID
     */
    private static void killWindowsProcess(String pid) {
        try {
            ProcessBuilder pb = new ProcessBuilder("taskkill", "/F", "/PID", pid);
            Process killProcess = pb.start();
            killProcess.waitFor();
            LOG.info("Killed JCEF helper process: " + pid);
        } catch (Exception e) {
            LOG.warn("Failed to kill process " + pid + ": " + e.getMessage());
        }
    }
    
    /**
     * Clean up JCEF processes on macOS
     */
    private static void cleanupMacProcesses() {
        try {
            String currentPid = getCurrentProcessId();
            
            // Find all jcef_helper processes
            ProcessBuilder pb = new ProcessBuilder(
                "ps", "-ax", "-o", "pid,ppid,comm"
            );
            
            Process process = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("jcef_helper")) {
                        String[] parts = line.trim().split("\\s+", 3);
                        if (parts.length >= 2) {
                            String pid = parts[0];
                            String ppid = parts[1];
                            
                            // Kill if it's our child process
                            if (ppid.equals(currentPid) || trackedProcessIds.contains(Integer.parseInt(pid))) {
                                killUnixProcess(pid);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOG.error("Error cleaning up macOS JCEF processes", e);
        }
    }
    
    /**
     * Clean up JCEF processes on Linux
     */
    private static void cleanupLinuxProcesses() {
        try {
            String currentPid = getCurrentProcessId();
            
            // Find all jcef_helper processes
            ProcessBuilder pb = new ProcessBuilder(
                "ps", "-eo", "pid,ppid,comm"
            );
            
            Process process = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("jcef_helper")) {
                        String[] parts = line.trim().split("\\s+", 3);
                        if (parts.length >= 2) {
                            String pid = parts[0];
                            String ppid = parts[1];
                            
                            // Kill if it's our child process
                            if (ppid.equals(currentPid) || trackedProcessIds.contains(Integer.parseInt(pid))) {
                                killUnixProcess(pid);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOG.error("Error cleaning up Linux JCEF processes", e);
        }
    }
    
    /**
     * Kill a Unix process by PID
     */
    private static void killUnixProcess(String pid) {
        try {
            ProcessBuilder pb = new ProcessBuilder("kill", "-9", pid);
            Process killProcess = pb.start();
            killProcess.waitFor();
            LOG.info("Killed JCEF helper process: " + pid);
        } catch (Exception e) {
            LOG.warn("Failed to kill process " + pid + ": " + e.getMessage());
        }
    }
    
    /**
     * Get the current process ID
     */
    private static String getCurrentProcessId() {
        String name = ManagementFactory.getRuntimeMXBean().getName();
        return name.split("@")[0];
    }
    
    /**
     * Get parent process ID (for tracking purposes)
     */
    private static String getParentProcessId() {
        try {
            return getCurrentProcessId(); // Simplified for now
        } catch (Exception e) {
            return "unknown";
        }
    }
    
    /**
     * Track a JCEF process ID for cleanup
     */
    public static void trackProcess(int pid) {
        trackedProcessIds.add(pid);
        LOG.debug("Tracking JCEF process: " + pid);
    }
    
    /**
     * Kill orphaned JCEF helper processes on Windows
     */
    private static void killOrphanedJCEFProcesses() {
        if (!SystemInfo.isWindows) return;
        
        try {
            // Find jcef_helper processes with non-existent parent
            ProcessBuilder pb = new ProcessBuilder(
                "wmic", "process", "where",
                "Name='jcef_helper.exe'",
                "get", "ProcessId,ParentProcessId"
            );
            
            Process process = pb.start();
            List<String[]> processInfo = new ArrayList<>();
            
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.contains("ProcessId")) continue;
                    
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 2) {
                        processInfo.add(new String[]{parts[0], parts[1]}); // parentPid, pid
                    }
                }
            }
            
            // Check which parent processes exist
            for (String[] info : processInfo) {
                String parentPid = info[0];
                String pid = info[1];
                
                if (!isProcessRunning(parentPid)) {
                    LOG.info("Found orphaned JCEF helper process " + pid + " (parent " + parentPid + " not running)");
                    killWindowsProcess(pid);
                }
            }
            
        } catch (Exception e) {
            LOG.error("Error killing orphaned JCEF processes", e);
        }
    }
    
    /**
     * Check if a process is running on Windows
     */
    private static boolean isProcessRunning(String pid) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                "tasklist", "/FI", "PID eq " + pid
            );
            Process process = pb.start();
            
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains(pid)) {
                        return true;
                    }
                }
            }
            
            return false;
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Periodic cleanup task for long-running IDE sessions
     */
    public static void performPeriodicCleanup() {
        try {
            if (SystemInfo.isWindows) {
                killOrphanedJCEFProcesses();
            }
            // Can add similar cleanup for other OS if needed
        } catch (Exception e) {
            LOG.warn("Error during periodic JCEF cleanup", e);
        }
    }
}
