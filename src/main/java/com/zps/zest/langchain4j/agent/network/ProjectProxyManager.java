package com.zps.zest.langchain4j.agent.network;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages agent proxy servers for each project, ensuring each project has its own
 * proxy server running on a unique port.
 */
@Service
public final class ProjectProxyManager implements Disposable {
    private static final Logger LOG = Logger.getInstance(ProjectProxyManager.class);
    
    // Base port for allocation - each project gets a unique port starting from this
    private static final int BASE_PORT = 8765;
    private static final int MAX_PORT = 9765; // Allow up to 1000 projects
    
    // Track running proxies by project
    private final Map<Project, ProxyInfo> projectProxies = new ConcurrentHashMap<>();
    
    // Port allocation counter
    private final AtomicInteger nextPort = new AtomicInteger(BASE_PORT);
    
    // Track allocated ports to avoid conflicts
    private final Map<Integer, Project> allocatedPorts = new ConcurrentHashMap<>();
    
    private static class ProxyInfo {
        final JavalinProxyServer server;
        final int port;
        final long startTime;
        
        ProxyInfo(JavalinProxyServer server, int port) {
            this.server = server;
            this.port = port;
            this.startTime = System.currentTimeMillis();
        }
    }
    
    public static ProjectProxyManager getInstance() {
        return com.intellij.openapi.application.ApplicationManager.getApplication()
                .getService(ProjectProxyManager.class);
    }
    
    public ProjectProxyManager() {
        // The proxy startup is now handled by ProxyStartupActivity for project opening
        // and we use Disposer.register in startProxyForProject for cleanup on project closing
    }
    
    /**
     * Starts a proxy server for the given project if not already running.
     * @return the port number of the proxy server
     */
    public synchronized int startProxyForProject(@NotNull Project project) {
        // Check if proxy already exists for this project
        ProxyInfo existing = projectProxies.get(project);
        if (existing != null) {
            LOG.info("Proxy already running for project " + project.getName() + " on port " + existing.port);
            return existing.port;
        }
        
        // Allocate a port for this project
        int port = allocatePort(project);
        if (port == -1) {
            LOG.error("Failed to allocate port for project " + project.getName());
            return -1;
        }
        
        try {
            // Create and start the proxy server
            AgentProxyConfiguration config = AgentProxyConfiguration.getDefault();
            JavalinProxyServer server = new JavalinProxyServer(project, port, config);
            
            // Start the server
            server.start();
            
            // Store the proxy info
            ProxyInfo proxyInfo = new ProxyInfo(server, port);
            projectProxies.put(project, proxyInfo);
            
            // Set system property for this project's proxy URL
            String proxyUrl = "http://localhost:" + port;
            System.setProperty("zest.agent.proxy.url", proxyUrl);
            
            // Also store project-specific property
            System.setProperty("zest.agent.proxy.url." + project.getName().replaceAll("[^a-zA-Z0-9]", "_"), proxyUrl);
            
            LOG.info("Started proxy server for project " + project.getName() + " on port " + port);
            
            // Register for disposal
            Disposer.register(project, () -> stopProxyForProject(project));
            
            return port;
            
        } catch (Exception e) {
            LOG.error("Failed to start proxy server for project " + project.getName(), e);
            // Clean up on failure
            releasePort(port);
            return -1;
        }
    }
    
    /**
     * Stops the proxy server for the given project.
     */
    public synchronized void stopProxyForProject(@NotNull Project project) {
        ProxyInfo proxyInfo = projectProxies.remove(project);
        if (proxyInfo != null) {
            try {
                proxyInfo.server.stop();
                releasePort(proxyInfo.port);
                LOG.info("Stopped proxy server for project " + project.getName() + " on port " + proxyInfo.port);
                
                // Clear system property if this was the last active proxy
                if (projectProxies.isEmpty()) {
                    System.clearProperty("zest.agent.proxy.url");
                }
                
                // Clear project-specific property
                System.clearProperty("zest.agent.proxy.url." + project.getName().replaceAll("[^a-zA-Z0-9]", "_"));
                
            } catch (Exception e) {
                LOG.error("Error stopping proxy server for project " + project.getName(), e);
            }
        }
    }
    
    /**
     * Gets the proxy URL for the given project.
     * @return the proxy URL or null if no proxy is running
     */
    public String getProxyUrlForProject(@NotNull Project project) {
        ProxyInfo proxyInfo = projectProxies.get(project);
        if (proxyInfo != null) {
            return "http://localhost:" + proxyInfo.port;
        }
        return null;
    }
    
    /**
     * Gets the proxy port for the given project.
     * @return the port number or -1 if no proxy is running
     */
    public int getProxyPortForProject(@NotNull Project project) {
        ProxyInfo proxyInfo = projectProxies.get(project);
        return proxyInfo != null ? proxyInfo.port : -1;
    }
    
    /**
     * Checks if a proxy is running for the given project.
     */
    public boolean isProxyRunningForProject(@NotNull Project project) {
        return projectProxies.containsKey(project);
    }
    
    /**
     * Restarts the proxy for the given project.
     */
    public void restartProxyForProject(@NotNull Project project) {
        stopProxyForProject(project);
        startProxyForProject(project);
    }
    
    /**
     * Allocates a unique port for the project.
     */
    private int allocatePort(Project project) {
        // Try to find an available port
        for (int attempts = 0; attempts < 100; attempts++) {
            int port = nextPort.getAndIncrement();
            
            // Wrap around if we exceed max port
            if (port > MAX_PORT) {
                nextPort.set(BASE_PORT);
                port = nextPort.getAndIncrement();
            }
            
            // Check if port is already allocated
            if (allocatedPorts.containsKey(port)) {
                continue;
            }
            
            // Check if port is actually available
            if (isPortAvailable(port)) {
                allocatedPorts.put(port, project);
                return port;
            }
        }
        
        // If we couldn't find a port in the range, try random ports
        return findRandomAvailablePort(project);
    }
    
    /**
     * Releases an allocated port.
     */
    private void releasePort(int port) {
        allocatedPorts.remove(port);
    }
    
    /**
     * Checks if a port is available for binding.
     */
    private boolean isPortAvailable(int port) {
        try (ServerSocket socket = new ServerSocket(port)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }
    
    /**
     * Finds a random available port.
     */
    private int findRandomAvailablePort(Project project) {
        try (ServerSocket socket = new ServerSocket(0)) {
            int port = socket.getLocalPort();
            allocatedPorts.put(port, project);
            return port;
        } catch (IOException e) {
            return -1;
        }
    }
    
    /**
     * Gets statistics about running proxies.
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new ConcurrentHashMap<>();
        stats.put("totalProxies", projectProxies.size());
        stats.put("allocatedPorts", allocatedPorts.size());
        
        Map<String, Map<String, Object>> projectStats = new ConcurrentHashMap<>();
        for (Map.Entry<Project, ProxyInfo> entry : projectProxies.entrySet()) {
            Map<String, Object> projectInfo = new ConcurrentHashMap<>();
            projectInfo.put("port", entry.getValue().port);
            projectInfo.put("uptime", System.currentTimeMillis() - entry.getValue().startTime);
            projectStats.put(entry.getKey().getName(), projectInfo);
        }
        stats.put("projects", projectStats);
        
        return stats;
    }
    
    @Override
    public void dispose() {
        // Stop all running proxies
        for (Project project : projectProxies.keySet()) {
            stopProxyForProject(project);
        }
        projectProxies.clear();
        allocatedPorts.clear();
    }
}