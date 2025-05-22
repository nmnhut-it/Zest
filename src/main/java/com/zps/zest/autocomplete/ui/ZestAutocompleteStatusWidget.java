package com.zps.zest.autocomplete.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.openapi.wm.impl.status.EditorBasedWidget;
import com.intellij.util.Consumer;
import com.zps.zest.autocomplete.ZestAutocompleteService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.event.MouseEvent;

/**
 * Status bar widget that shows the current state of the autocomplete service.
 * Displays whether autocomplete is enabled/disabled and current activity.
 */
public class ZestAutocompleteStatusWidget extends EditorBasedWidget implements StatusBarWidget.TextPresentation {
    private static final String WIDGET_ID = "ZestAutocomplete";
    
    public ZestAutocompleteStatusWidget(Project project) {
        super(project);
    }
    
    @NotNull
    @Override
    public String ID() {
        return WIDGET_ID;
    }
    
    @Nullable
    @Override
    public WidgetPresentation getPresentation() {
        return this;
    }
    
    @NotNull
    @Override
    public String getText() {
        if (myProject == null) {
            return "";
        }
        
        ZestAutocompleteService service = ZestAutocompleteService.getInstance(myProject);
        
        if (!service.isEnabled()) {
            return "Zest: OFF";
        }
        
        // Show cache stats if available
        return "Zest: ON (" + service.getCacheStats() + ")";
    }
    
    @Override
    public float getAlignment() {
        return 0.0f;
    }
    
    @Nullable
    @Override
    public String getTooltipText() {
        if (myProject == null) {
            return null;
        }
        
        ZestAutocompleteService service = ZestAutocompleteService.getInstance(myProject);
        
        if (!service.isEnabled()) {
            return "Zest Autocomplete is disabled. Click to enable.";
        }
        
        return "Zest Autocomplete is active. Click to disable.\n" + service.getCacheStats();
    }
    
    @Nullable
    @Override
    public Consumer<MouseEvent> getClickConsumer() {
        return mouseEvent -> {
            if (myProject != null) {
                ZestAutocompleteService service = ZestAutocompleteService.getInstance(myProject);
                service.setEnabled(!service.isEnabled());
                
                // Update the status bar
                StatusBar statusBar = getStatusBar();
                if (statusBar != null) {
                    statusBar.updateWidget(WIDGET_ID);
                }
            }
        };
    }
    
    /**
     * Updates the widget display.
     * Should be called when the autocomplete service state changes.
     */
    public void update() {
        StatusBar statusBar = getStatusBar();
        if (statusBar != null) {
            statusBar.updateWidget(WIDGET_ID);
        }
    }

}
