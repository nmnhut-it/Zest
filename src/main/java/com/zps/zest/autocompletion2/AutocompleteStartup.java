package com.zps.zest.autocompletion2;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import com.zps.zest.autocompletion2.core.AutocompleteService;
import com.zps.zest.autocompletion2.core.TabHandler;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Startup component for Zest Autocomplete v2.
 * Initializes the service and installs the Tab handler.
 */
public class AutocompleteStartup implements ProjectActivity {
    private static final Logger LOG = Logger.getInstance(AutocompleteStartup.class);
    
    @Nullable
    @Override
    public Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {
        LOG.info("Initializing Zest Autocomplete v2 for project: " + project.getName());
        
        try {
            // Install the Tab handler (global, only needs to be done once)
            TabHandler.install();
            
            // Initialize the autocomplete service for this project
            AutocompleteService service = AutocompleteService.getInstance(project);
            LOG.info("Autocomplete service initialized: " + service);
            
            LOG.info("Zest Autocomplete v2 initialization complete");
            
        } catch (Exception e) {
            LOG.error("Failed to initialize Zest Autocomplete v2", e);
        }
        
        return Unit.INSTANCE;
    }
}
