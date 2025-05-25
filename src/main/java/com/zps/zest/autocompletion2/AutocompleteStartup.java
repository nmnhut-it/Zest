package com.zps.zest.autocompletion2;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.event.EditorFactoryEvent;
import com.intellij.openapi.editor.event.EditorFactoryListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import com.zps.zest.autocompletion2.core.AutocompleteService;
import com.zps.zest.autocompletion2.core.TabHandler;
import com.zps.zest.autocompletion2.integration.AutoTriggerSetup;
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
            
            // Enable auto-completion for all editors by default
            enableAutoCompletionForAllEditors(project);
            
            LOG.info("Zest Autocomplete v2 initialization complete");
            
        } catch (Exception e) {
            LOG.error("Failed to initialize Zest Autocomplete v2", e);
        }
        
        return Unit.INSTANCE;
    }
    
    /**
     * Enables auto-completion for all editors in the project.
     * Sets up listeners for new editors as they are created.
     */
    private void enableAutoCompletionForAllEditors(@NotNull Project project) {
        // Enable for existing editors
        for (Editor editor : EditorFactory.getInstance().getAllEditors()) {
            if (editor.getProject() == project) {
                AutoTriggerSetup.enableAutoCompletion(editor);
                LOG.debug("Auto-completion enabled for existing editor");
            }
        }
        
        // Listen for new editors and enable auto-completion
        EditorFactoryListener editorListener = new EditorFactoryListener() {
            @Override
            public void editorCreated(@NotNull EditorFactoryEvent event) {
                Editor editor = event.getEditor();
                if (editor.getProject() == project) {
                    AutoTriggerSetup.enableAutoCompletion(editor);
                    LOG.debug("Auto-completion enabled for new editor");
                }
            }
        };
        
        // Register the listener with the project as disposable parent
        EditorFactory.getInstance().addEditorFactoryListener(editorListener, project);
        
        LOG.info("Auto-completion enabled by default for all editors in project: " + project.getName());
    }
}
