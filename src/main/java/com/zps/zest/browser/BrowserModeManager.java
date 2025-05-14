package com.zps.zest.browser;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages browser modes and keeps track of the active mode.
 */
public class BrowserModeManager {
    private final List<BrowserMode> availableModes = new ArrayList<>();
    private final JCEFBrowserManager browserManager;
    private BrowserMode activeMode;
    private final List<ModeChangeListener> listeners = new ArrayList<>();

    public BrowserModeManager(JCEFBrowserManager browserManager) {
        this.browserManager = browserManager;
    }

    /**
     * Registers a new browser mode.
     */
    public void registerMode(BrowserMode mode) {
        availableModes.add(mode);
        // Set the first registered mode as the default
        if (activeMode == null) {
            activeMode = mode;
        }
    }

    /**
     * Gets all available modes.
     */
    public List<BrowserMode> getAvailableModes() {
        return new ArrayList<>(availableModes);
    }

    /**
     * Gets the current active mode.
     */
    public BrowserMode getActiveMode() {
        return activeMode;
    }

    /**
     * Activates the specified mode.
     */
    public void activateMode(BrowserMode mode) {
        if (availableModes.contains(mode)) {
            BrowserMode oldMode = activeMode;
            activeMode = mode;
            mode.onActivate(browserManager);
            notifyModeChanged(oldMode, mode);
        }
    }

    /**
     * Interface for listening to mode changes.
     */
    public interface ModeChangeListener {
        void onModeChanged(BrowserMode oldMode, BrowserMode newMode);
    }

    /**
     * Adds a listener for mode changes.
     */
    public void addModeChangeListener(ModeChangeListener listener) {
        listeners.add(listener);
    }

    /**
     * Removes a mode change listener.
     */
    public void removeModeChangeListener(ModeChangeListener listener) {
        listeners.remove(listener);
    }

    private void notifyModeChanged(BrowserMode oldMode, BrowserMode newMode) {
        for (ModeChangeListener listener : listeners) {
            listener.onModeChanged(oldMode, newMode);
        }
    }
}
