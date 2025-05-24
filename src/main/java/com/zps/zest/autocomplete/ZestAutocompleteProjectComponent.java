package com.zps.zest.autocomplete;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import com.zps.zest.autocomplete.handlers.ZestSmartTabHandler;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Project component that initializes Zest autocomplete functionality.
 * Installs the smart TAB handler that integrates with IntelliJ's built-in functionality.
 */
public class ZestAutocompleteProjectComponent implements ProjectActivity {
    private static final Logger LOG = Logger.getInstance(ZestAutocompleteProjectComponent.class);
    
    @Nullable
    @Override
    public Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {
        LOG.info("Initializing Zest Autocomplete for project: " + project.getName());
        
        try {
            // Install the smart TAB handler (only once globally)
            ZestSmartTabHandler.install();
            
            // Initialize the autocomplete service (this will register editor listeners)
            ZestAutocompleteService service = ZestAutocompleteService.getInstance(project);
            LOG.info("ZestAutocompleteProjectComponent: Service initialized successfully - " + service);
            
            LOG.info("Zest Autocomplete initialization complete");
            
        } catch (Exception e) {
            LOG.error("Failed to initialize Zest Autocomplete", e);
        }
        
        return Unit.INSTANCE;
    }
}
