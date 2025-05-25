package com.zps.zest.autocompletion2.settings;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Settings for Zest Autocomplete v2.
 * Persists user preferences across IDE restarts.
 */
@Service(Service.Level.PROJECT)
@State(name = "ZestAutocompleteV2Settings", storages = @Storage("zest-autocomplete-v2.xml"))
public class AutocompleteSettings implements PersistentStateComponent<AutocompleteSettings.State> {
    
    public static class State {
        public boolean autoTriggerEnabled = true;
        public int triggerDelayMs = 800;
        public boolean triggerOnDot = true;
        public boolean triggerOnAssignment = true;
        public boolean triggerOnMethodCall = true;
        public boolean triggerOnKeywords = true;
        public boolean enableDebugLogging = false;
    }
    
    private State state = new State();
    
    public static AutocompleteSettings getInstance(@NotNull Project project) {
        return project.getService(AutocompleteSettings.class);
    }
    
    @Override
    public @Nullable State getState() {
        return state;
    }
    
    @Override
    public void loadState(@NotNull State state) {
        this.state = state;
    }
    
    // Getters
    public boolean isAutoTriggerEnabled() { return state.autoTriggerEnabled; }
    public int getTriggerDelayMs() { return state.triggerDelayMs; }
    public boolean isTriggerOnDot() { return state.triggerOnDot; }
    public boolean isTriggerOnAssignment() { return state.triggerOnAssignment; }
    public boolean isTriggerOnMethodCall() { return state.triggerOnMethodCall; }
    public boolean isTriggerOnKeywords() { return state.triggerOnKeywords; }
    public boolean isDebugLoggingEnabled() { return state.enableDebugLogging; }
    
    // Setters
    public void setAutoTriggerEnabled(boolean enabled) { state.autoTriggerEnabled = enabled; }
    public void setTriggerDelayMs(int delayMs) { state.triggerDelayMs = Math.max(100, Math.min(5000, delayMs)); }
    public void setTriggerOnDot(boolean enabled) { state.triggerOnDot = enabled; }
    public void setTriggerOnAssignment(boolean enabled) { state.triggerOnAssignment = enabled; }
    public void setTriggerOnMethodCall(boolean enabled) { state.triggerOnMethodCall = enabled; }
    public void setTriggerOnKeywords(boolean enabled) { state.triggerOnKeywords = enabled; }
    public void setDebugLoggingEnabled(boolean enabled) { state.enableDebugLogging = enabled; }
}
