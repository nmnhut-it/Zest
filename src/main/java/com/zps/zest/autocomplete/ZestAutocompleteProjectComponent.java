package com.zps.zest.autocomplete;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Project component that ensures the ZestAutocompleteService is initialized
 * when a project opens.
 */
public class ZestAutocompleteProjectComponent implements ProjectActivity {
    private static final Logger LOG = Logger.getInstance(ZestAutocompleteProjectComponent.class);
    
    @Nullable
    @Override
    public Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {
        LOG.info("ZestAutocompleteProjectComponent: Initializing for project " + project.getName());
        
        try {
            // Force initialization of the autocomplete service
            ZestAutocompleteService service = ZestAutocompleteService.getInstance(project);
            LOG.info("ZestAutocompleteProjectComponent: Service initialized successfully - " + service);
            
        } catch (Exception e) {
            LOG.error("ZestAutocompleteProjectComponent: Failed to initialize service", e);
        }
        
        return Unit.INSTANCE;
    }
}
