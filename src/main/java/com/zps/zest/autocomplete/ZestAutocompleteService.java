package com.zps.zest.autocomplete;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.Inlay;
import com.intellij.openapi.editor.event.EditorFactoryEvent;
import com.intellij.openapi.editor.event.EditorFactoryListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.zps.zest.CodeContext;
import com.zps.zest.ConfigurationManager;
import com.zps.zest.PipelineExecutionException;
import com.zps.zest.ZestNotifications;
import com.zps.zest.autocomplete.listeners.ZestAutocompleteCaretListener;
import com.zps.zest.autocomplete.listeners.ZestAutocompleteDocumentListener;
import com.zps.zest.autocomplete.utils.AutocompletePromptBuilder;
import com.zps.zest.autocomplete.utils.ContextGatherer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Central service for managing AI-powered autocomplete functionality.
 * Handles completion requests, caching, and lifecycle management.
 */
@Service(Service.Level.PROJECT)
public final class ZestAutocompleteService implements Disposable  {
    private static final Logger LOG = Logger.getInstance(ZestAutocompleteService.class);
    private static final int COMPLETION_DELAY_MS = 300;
    private static final int MAX_CACHE_SIZE = 50;
    
    private final Project project;
    private final Map<Editor, ZestPendingCompletion> activeCompletions = new ConcurrentHashMap<>();
    private final Map<String, String> completionCache = new ConcurrentHashMap<>();
    private final Map<Editor, ZestAutocompleteDocumentListener> documentListeners = new ConcurrentHashMap<>();
    private final Map<Editor, ZestAutocompleteCaretListener> caretListeners = new ConcurrentHashMap<>();
    
    private CompletableFuture<Void> currentRequest;
    private boolean isEnabled = true;
    private EditorFactoryListener editorFactoryListener;
    
    public ZestAutocompleteService(Project project) {
        this.project = project;
        LOG.info("ZestAutocompleteService constructor called for project: " + project.getName());
        
        // Initialize service after project is fully loaded
        ApplicationManager.getApplication().invokeLater(() -> {
            if (!project.isDisposed()) {
                initializeService();
            }
        });
    }
    
    /**
     * Gets the service instance for a project.
     */
    public static ZestAutocompleteService getInstance(Project project) {
        return project.getService(ZestAutocompleteService.class);
    }
    
    /**
     * Initializes the service and sets up editor listeners.
     */
    private void initializeService() {
        LOG.info("Initializing ZestAutocompleteService for project: " + project.getName());
        
        // Register editor factory listener to handle new editors
        editorFactoryListener = new EditorFactoryListener() {
            @Override
            public void editorCreated(@NotNull EditorFactoryEvent event) {
                Editor editor = event.getEditor();
                LOG.debug("Editor created: " + editor);
                if (editor.getProject() == project) {
                    LOG.info("Registering autocomplete for editor in project: " + project.getName());
                    registerEditor(editor);
                }
            }
            
            @Override
            public void editorReleased(@NotNull EditorFactoryEvent event) {
                Editor editor = event.getEditor();
                LOG.debug("Editor released: " + editor);
                unregisterEditor(editor);
            }
        };
        
        EditorFactory.getInstance().addEditorFactoryListener(editorFactoryListener, this);
        LOG.info("Added EditorFactoryListener");
        
        // Register existing editors
        Editor[] allEditors = EditorFactory.getInstance().getAllEditors();
        LOG.info("Found " + allEditors.length + " existing editors");
        
        for (Editor editor : allEditors) {
            if (editor.getProject() == project) {
                LOG.info("Registering existing editor for project: " + project.getName());
                registerEditor(editor);
            }
        }
        
        LOG.info("ZestAutocompleteService initialized for project: " + project.getName());
    }
    
    /**
     * Registers an editor for autocomplete functionality.
     */
    private void registerEditor(Editor editor) {
        if (editor.isDisposed() || documentListeners.containsKey(editor)) {
            LOG.debug("Editor already registered or disposed");
            return;
        }
        
        LOG.info("Registering editor for autocomplete: " + editor);
        
        // Add document change listener
        ZestAutocompleteDocumentListener docListener = new ZestAutocompleteDocumentListener(this, editor);
        editor.getDocument().addDocumentListener(docListener, this);
        documentListeners.put(editor, docListener);
        LOG.debug("Added document listener");
        
        // Add caret listener
        ZestAutocompleteCaretListener caretListener = new ZestAutocompleteCaretListener(this, editor);
        editor.getCaretModel().addCaretListener(caretListener, this);
        caretListeners.put(editor, caretListener);
        LOG.debug("Added caret listener");
        
        LOG.info("Successfully registered autocomplete listeners for editor");
    }
    
    /**
     * Unregisters an editor from autocomplete functionality.
     */
    private void unregisterEditor(Editor editor) {
        // Clear any active completion
        clearCompletion(editor);
        
        // Remove listeners
        documentListeners.remove(editor);
        caretListeners.remove(editor);
        
        LOG.debug("Unregistered autocomplete listeners for editor");
    }
    
    /**
     * Triggers autocomplete for the given editor with a delay.
     */
    public void triggerAutocomplete(Editor editor) {
        if (!isEnabled || editor.isDisposed()) {
            return;
        }
        
        // Cancel any existing request
        if (currentRequest != null && !currentRequest.isDone()) {
            currentRequest.cancel(true);
        }
        
        // Clear any existing completion
        clearCompletion(editor);
        
        // Schedule new completion request with delay
        currentRequest = CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(COMPLETION_DELAY_MS);
                if (!Thread.currentThread().isInterrupted()) {
                    requestCompletion(editor);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }
    
    /**
     * Requests a completion from the LLM API.
     */
    private void requestCompletion(Editor editor) {
        if (editor.isDisposed() || !isEnabled) {
            return;
        }

        ApplicationManager.getApplication().runReadAction(() -> {
            try {
                // Check configuration
                ConfigurationManager config = ConfigurationManager.getInstance(project);
                if (!config.isAutocompleteEnabled()) {
                    return;
                }

                // Build context for the completion request (this needs read access)
                CodeContext context = buildCompletionContext(editor);
                if (context == null) {
                    return;
                }

                // Check cache first (this needs read access for cursor position)
                String cacheKey = generateCacheKey(context);
                String cachedCompletion = completionCache.get(cacheKey);
                if (cachedCompletion != null) {
                    ApplicationManager.getApplication().invokeLater(() -> 
                        displayCompletion(editor, cachedCompletion, context));
                    return;
                }

                // Make API request in background thread
                AutocompleteApiStage apiStage = new AutocompleteApiStage();
                CompletableFuture.runAsync(() -> {
                    try {
                        // Create a copy of context for background processing
                        CodeContext bgContext = copyContextForBackground(context);
                        apiStage.process(bgContext);
                        String completion = bgContext.getApiResponse();

                        if (completion != null && !completion.trim().isEmpty()) {
                            // Cache the result
                            cacheCompletion(cacheKey, completion);

                            // Display completion on UI thread
                            ApplicationManager.getApplication().invokeLater(() -> 
                                displayCompletion(editor, completion, context));
                        }
                    } catch (PipelineExecutionException e) {
                        LOG.warn("Failed to get autocomplete suggestion", e);
                        // Don't show error notifications for autocomplete failures to avoid noise
                    }
                });

            } catch (Exception e) {
                LOG.warn("Error requesting autocomplete", e);
            }
        });
    }
    
    /**
     * Builds the context for a completion request.
     */
    @Nullable
    private CodeContext buildCompletionContext(Editor editor) {
        try {
            CodeContext context = new CodeContext();
            context.setProject(project);
            context.setEditor(editor);
            context.setConfig(ConfigurationManager.getInstance(project));
            context.useTestWrightModel(false); // Use code model for autocomplete
            
            // Gather cursor context here (while we're in read action)
            String cursorContext = ContextGatherer.gatherCursorContext(editor, null);
            
            // Set file context if available
            if (editor.getVirtualFile() != null) {
                String documentText = editor.getDocument().getText();
                context.setClassContext(documentText);
            }
            
            // Build the prompt now (while we have editor access)
            String autocompletePrompt = new AutocompletePromptBuilder()
                .withSystemPrompt(getAutocompleteSystemPrompt())
                .withFileContext(context.getClassContext())
                .withCursorPosition(cursorContext)
                .withLanguage("Java")
                .build();
            
            context.setPrompt(autocompletePrompt);
            
            return context;
            
        } catch (Exception e) {
            LOG.warn("Failed to build completion context", e);
            return null;
        }
    }
    
    /**
     * Gets the system prompt for autocomplete.
     */
    private String getAutocompleteSystemPrompt() {
        return "You are an AI code completion assistant. Complete the code naturally and concisely.\n" +
               "Rules:\n" +
               "1. Only provide the completion text, no explanations or markdown\n" +
               "2. Complete the current line or logical block of code\n" +
               "3. Maintain consistent indentation and style\n" +
               "4. Don't repeat existing code\n" +
               "5. Keep completions focused and relevant to the context\n" +
               "6. For single-line completions, complete to the end of the statement\n" +
               "7. For multi-line completions, complete the logical block (method, if statement, etc.)";
    }
    
    /**
     * Displays a completion suggestion in the editor.
     */
    private void displayCompletion(Editor editor, String completionText, CodeContext context) {
        if (editor.isDisposed() || completionText.trim().isEmpty()) {
            return;
        }
        
        try {
            // Clear any existing completion
            clearCompletion(editor);
            
            // Create and display the completion
            int offset = editor.getCaretModel().getOffset();
            ZestPendingCompletion completion = new ZestPendingCompletion(
                completionText.trim(), 
                "", // original text - could be enhanced
                offset, 
                editor
            );
            
            // Create inlay renderer
            ZestInlayRenderer renderer = new ZestInlayRenderer(completionText.trim(), editor);
            
            // Add inline inlay
            Inlay<?> inlay = editor.getInlayModel().addInlineElement(
                offset, 
                true, 
                renderer
            );
            
            if (inlay != null) {
                completion.setInlay(inlay);
                activeCompletions.put(editor, completion);
                
                LOG.debug("Displayed autocomplete suggestion: " + completionText.substring(0, 
                    Math.min(50, completionText.length())));
            }
            
        } catch (Exception e) {
            LOG.warn("Failed to display completion", e);
        }
    }
    
    /**
     * Accepts the current completion suggestion.
     */
    public void acceptCompletion(Editor editor) {
        ZestPendingCompletion completion = activeCompletions.get(editor);
        if (completion == null || !completion.isActive()) {
            return;
        }
        
        try {
            // Insert the completion text
            Document document = editor.getDocument();
            int offset = completion.getOffset();
            
            ApplicationManager.getApplication().runWriteAction(() -> {
                document.insertString(offset, completion.getCompletionText());
            });
            
            // Mark as accepted and cleanup
            completion.accept();
            clearCompletion(editor);
            
            LOG.debug("Accepted autocomplete suggestion");
            
        } catch (Exception e) {
            LOG.warn("Failed to accept completion", e);
        }
    }
    
    /**
     * Rejects the current completion suggestion.
     */
    public void rejectCompletion(Editor editor) {
        ZestPendingCompletion completion = activeCompletions.get(editor);
        if (completion == null || !completion.isActive()) {
            return;
        }
        
        completion.reject();
        clearCompletion(editor);
        
        LOG.debug("Rejected autocomplete suggestion");
    }
    
    /**
     * Clears any active completion for the editor.
     */
    public void clearCompletion(Editor editor) {
        ZestPendingCompletion completion = activeCompletions.remove(editor);
        if (completion != null) {
            completion.dispose();
        }
    }
    
    /**
     * Checks if there's an active completion for the editor.
     */
    public boolean hasActiveCompletion(Editor editor) {
        ZestPendingCompletion completion = activeCompletions.get(editor);
        return completion != null && completion.isActive();
    }
    
    /**
     * Gets the active completion for an editor.
     */
    @Nullable
    public ZestPendingCompletion getActiveCompletion(Editor editor) {
        return activeCompletions.get(editor);
    }
    
    /**
     * Enables or disables the autocomplete service.
     */
    public void setEnabled(boolean enabled) {
        this.isEnabled = enabled;
        if (!enabled) {
            // Clear all active completions
            activeCompletions.keySet().forEach(this::clearCompletion);
        }
        LOG.info("Autocomplete service " + (enabled ? "enabled" : "disabled"));
    }
    
    /**
     * Checks if the service is enabled.
     */
    public boolean isEnabled() {
        return isEnabled;
    }
    
    /**
     * Creates a copy of the context that can be safely used in background threads.
     */
    private CodeContext copyContextForBackground(CodeContext original) {
        CodeContext copy = new CodeContext();
        copy.setProject(original.getProject());
        copy.setConfig(original.getConfig());
        copy.setPrompt(original.getPrompt());
        copy.setClassContext(original.getClassContext());
        copy.useTestWrightModel(false); // Use code model for autocomplete
        
        // Override the autocomplete model to use the main code model
        ConfigurationManager config = original.getConfig();
        if (config != null) {
            // Use the main code model instead of the specific autocomplete model
            String modelToUse = config.getCodeModel();
            LOG.debug("Using model for autocomplete: " + modelToUse);
        }
        
        return copy;
    }

    /**
     * Generates a cache key for the completion request.
     */
    private String generateCacheKey(CodeContext context) {
        // Simple cache key based on context - could be enhanced
        return String.valueOf((context.getClassContext() + 
                               context.getEditor().getCaretModel().getOffset()).hashCode());
    }
    
    /**
     * Caches a completion result.
     */
    private void cacheCompletion(String key, String completion) {
        // Simple LRU-like behavior
        if (completionCache.size() >= MAX_CACHE_SIZE) {
            // Remove oldest entries (simplified)
            completionCache.clear();
        }
        completionCache.put(key, completion);
    }
    
    /**
     * Clears the completion cache.
     */
    public void clearCache() {
        completionCache.clear();
        LOG.debug("Cleared autocomplete cache");
    }
    
    /**
     * Gets cache statistics.
     */
    public String getCacheStats() {
        return String.format("Cache size: %d/%d", completionCache.size(), MAX_CACHE_SIZE);
    }
    
    @Override
    public void dispose() {
        // Cancel any running requests
        if (currentRequest != null && !currentRequest.isDone()) {
            currentRequest.cancel(true);
        }
        
        // Clear all active completions
        activeCompletions.keySet().forEach(this::clearCompletion);
        activeCompletions.clear();
        
        // Clear listeners maps
        documentListeners.clear();
        caretListeners.clear();
        
        // Clear cache
        completionCache.clear();
        
        LOG.info("ZestAutocompleteService disposed for project: " + project.getName());
    }
}
