package com.zps.zest.autocomplete;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.event.EditorFactoryEvent;
import com.intellij.openapi.editor.event.EditorFactoryListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiUtil;
import com.zps.zest.CodeContext;
import com.zps.zest.ConfigurationManager;
import com.zps.zest.PipelineExecutionException;
import com.zps.zest.autocomplete.listeners.ZestAutocompleteCaretListener;
import com.zps.zest.autocomplete.listeners.ZestAutocompleteDocumentListener;
import com.zps.zest.autocomplete.utils.AutocompletePromptBuilder;
import com.zps.zest.autocomplete.utils.ContextGatherer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;

/**
 * Enhanced autocomplete service following Tabby ML patterns.
 * Provides sophisticated context-aware completions with proper replace range support.
 */
@Service(Service.Level.PROJECT)
public final class ZestAutocompleteService implements Disposable {
    private static final Logger LOG = Logger.getInstance(ZestAutocompleteService.class);
    private static final int COMPLETION_DELAY_MS = 250; // Slightly faster trigger
    private static final int MAX_CACHE_SIZE = 100;

    private final Project project;
    private final Map<Editor, ZestCompletionData.PendingCompletion> activeCompletions = new ConcurrentHashMap<>();
    private final Map<String, ZestCompletionData.CompletionItem> completionCache = new ConcurrentHashMap<>();
    private final Map<Editor, ZestAutocompleteDocumentListener> documentListeners = new ConcurrentHashMap<>();
    private final Map<Editor, ZestAutocompleteCaretListener> caretListeners = new ConcurrentHashMap<>();
    private final Map<Editor, ZestInlayRenderer.RenderingContext> renderingContexts = new ConcurrentHashMap<>();

    private CompletableFuture<Void> currentRequest;
    private boolean isEnabled = true;
    private EditorFactoryListener editorFactoryListener;

    public ZestAutocompleteService(Project project) {
        this.project = project;
        LOG.info("Enhanced ZestAutocompleteService constructor called for project: " + project.getName());

        ApplicationManager.getApplication().invokeLater(() -> {
            if (!project.isDisposed()) {
                initializeService();
            }
        });
    }

    public static ZestAutocompleteService getInstance(Project project) {
        return project.getService(ZestAutocompleteService.class);
    }

    private void initializeService() {
        LOG.info("Initializing enhanced ZestAutocompleteService for project: " + project.getName());

        editorFactoryListener = new EditorFactoryListener() {
            @Override
            public void editorCreated(@NotNull EditorFactoryEvent event) {
                Editor editor = event.getEditor();
                if (editor.getProject() == project) {
                    registerEditor(editor);
                }
            }

            @Override
            public void editorReleased(@NotNull EditorFactoryEvent event) {
                unregisterEditor(event.getEditor());
            }
        };

        EditorFactory.getInstance().addEditorFactoryListener(editorFactoryListener, this);

        // Register existing editors
        for (Editor editor : EditorFactory.getInstance().getAllEditors()) {
            if (editor.getProject() == project) {
                registerEditor(editor);
            }
        }

        LOG.info("Enhanced ZestAutocompleteService initialized");
    }

    private void registerEditor(Editor editor) {
        if (editor.isDisposed() || documentListeners.containsKey(editor)) {
            return;
        }

        LOG.debug("Registering enhanced autocomplete for editor: " + editor);

        // Add listeners
        ZestAutocompleteDocumentListener docListener = new ZestAutocompleteDocumentListener(this, editor);
        editor.getDocument().addDocumentListener(docListener, this);
        documentListeners.put(editor, docListener);

        ZestAutocompleteCaretListener caretListener = new ZestAutocompleteCaretListener(this, editor);
        editor.getCaretModel().addCaretListener(caretListener, this);
        caretListeners.put(editor, caretListener);
    }

    private void unregisterEditor(Editor editor) {
        clearCompletion(editor);
        documentListeners.remove(editor);
        caretListeners.remove(editor);
    }

    /**
     * Triggers autocomplete with enhanced context gathering.
     */
    public void triggerAutocomplete(Editor editor) {
        if (!isEnabled || editor.isDisposed()) {
            return;
        }

        // Cancel existing request
        if (currentRequest != null && !currentRequest.isDone()) {
            currentRequest.cancel(true);
        }

        clearCompletion(editor);

        // Schedule with optimized delay
        currentRequest = CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(COMPLETION_DELAY_MS);
                if (!Thread.currentThread().isInterrupted()) {
                    requestEnhancedCompletion(editor);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /**
     * Requests completion using enhanced context gathering and prompting.
     */
    private void requestEnhancedCompletion(Editor editor) {
        if (editor.isDisposed() || !isEnabled) {
            return;
        }

        ApplicationManager.getApplication().runReadAction(() -> {
            try {
                ConfigurationManager config = ConfigurationManager.getInstance(project);
                if (!config.isAutocompleteEnabled()) {
                    return;
                }

                // Enhanced context gathering
                PsiFile psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument());
                ContextGatherer.CursorContext cursorContext = ContextGatherer.gatherEnhancedCursorContext(editor, psiFile);
                String fileContext = ContextGatherer.gatherFileContext(editor, psiFile);

                // Generate cache key based on context
                String cacheKey = generateEnhancedCacheKey(cursorContext, fileContext);
                ZestCompletionData.CompletionItem cachedItem = completionCache.get(cacheKey);

                if (cachedItem != null && cachedItem.isValidAt(cursorContext.getOffset())) {
                    ApplicationManager.getApplication().invokeLater(() ->
                            displayEnhancedCompletion(editor, cachedItem, cursorContext));
                    return;
                }

                // Build enhanced prompt
                String enhancedPrompt = AutocompletePromptBuilder.createContextAwarePrompt(
                        fileContext,
                        cursorContext.getPrefixContext(),
                        cursorContext.getSuffixContext(),
                        detectLanguage(editor, psiFile)
                );

                // Make API request
                CompletableFuture.runAsync(() -> {
                    try {
                        CodeContext context = createCodeContext(editor, enhancedPrompt, config);
                        AutocompleteApiStage apiStage = new AutocompleteApiStage();
                        apiStage.process(context);

                        String completion = context.getApiResponse();
                        if (completion != null && !completion.trim().isEmpty()) {
                            // Create completion item with proper range
                            ZestCompletionData.CompletionItem item = createCompletionItem(
                                    completion.trim(), cursorContext);

                            // Cache the result
                            completionCache.put(cacheKey, item);

                            // Display on UI thread
                            ApplicationManager.getApplication().invokeLater(() ->
                                    displayEnhancedCompletion(editor, item, cursorContext));
                        }
                    } catch (PipelineExecutionException e) {
                        LOG.warn("Failed to get enhanced autocomplete suggestion", e);
                    }
                });

            } catch (Exception e) {
                LOG.warn("Error requesting enhanced autocomplete", e);
            }
        });
    }

    /**
     * Creates a completion item with intelligent replace range calculation.
     */
    private ZestCompletionData.CompletionItem createCompletionItem(String completion,
                                                                   ContextGatherer.CursorContext context) {
        int startOffset = context.getOffset();
        int endOffset = startOffset;

        // Calculate replace range based on current line suffix
        String currentLineSuffix = context.getCurrentLineSuffix();
        if (!currentLineSuffix.trim().isEmpty()) {
            // If there's meaningful content after cursor, we might want to replace it
            // This is a simplified version - Tabby ML has more sophisticated logic
            if (completion.startsWith(currentLineSuffix.trim())) {
                endOffset = startOffset + currentLineSuffix.trim().length();
            }
        }

        ZestCompletionData.Range replaceRange = new ZestCompletionData.Range(startOffset, endOffset);
        return new ZestCompletionData.CompletionItem(completion, replaceRange, null, 1.0);
    }

    /**
     * Displays completion using enhanced renderer.
     */
    private void displayEnhancedCompletion(Editor editor, ZestCompletionData.CompletionItem item,
                                           ContextGatherer.CursorContext context) {
        if (editor.isDisposed()) {
            return;
        }

        try {
            clearCompletion(editor);

            // Create rendering context
            ZestInlayRenderer.RenderingContext renderingContext =
                    ZestInlayRenderer.show(editor, context.getOffset(), item);

            if (!renderingContext.getInlays().isEmpty()) {
                // Create pending completion
                ZestCompletionData.PendingCompletion completion =
                        new ZestCompletionData.PendingCompletion(item, editor, context.getPrefixContext());

                activeCompletions.put(editor, completion);
                renderingContexts.put(editor, renderingContext);

                LOG.debug("Displayed enhanced autocomplete: " +
                        item.getInsertText().substring(0, Math.min(50, item.getInsertText().length())));
            }

        } catch (Exception e) {
            LOG.warn("Failed to display enhanced completion", e);
        }
    }

    /**
     * Accepts completion with proper replace range handling.
     */
    public void acceptCompletion(Editor editor) {
        ZestCompletionData.PendingCompletion completion = activeCompletions.get(editor);
        if (completion == null || !completion.isActive()) {
            return;
        }

        try {
            ZestCompletionData.CompletionItem item = completion.getItem();
            Document document = editor.getDocument();

            VirtualFile virtualFile = editor.getVirtualFile();
            if (virtualFile == null)
                return;
            WriteCommandAction.runWriteCommandAction(project,"autocomplete","Zest", (Runnable) () -> {
                // Replace the range with the completion text
                ZestCompletionData.Range replaceRange = item.getReplaceRange();
                document.replaceString(replaceRange.getStart(), replaceRange.getEnd(),
                        item.getInsertText());

                // Move cursor to end of inserted text
                editor.getCaretModel().moveToOffset(replaceRange.getStart() + item.getInsertText().length());
            }, PsiUtil.getPsiFile(project, virtualFile));

            completion.accept();
            clearCompletion(editor);

            LOG.debug("Accepted enhanced autocomplete suggestion");

        } catch (Exception e) {
            LOG.warn("Failed to accept enhanced completion", e);
        }
    }

    public void rejectCompletion(Editor editor) {
        ZestCompletionData.PendingCompletion completion = activeCompletions.get(editor);
        if (completion == null || !completion.isActive()) {
            return;
        }

        completion.reject();
        clearCompletion(editor);
        LOG.debug("Rejected enhanced autocomplete suggestion");
    }

    public void clearCompletion(Editor editor) {
        // Clear rendering context
        ZestInlayRenderer.RenderingContext renderingContext = renderingContexts.remove(editor);
        if (renderingContext != null) {
            renderingContext.dispose();
        }

        // Clear pending completion
        ZestCompletionData.PendingCompletion completion = activeCompletions.remove(editor);
        if (completion != null) {
            completion.dispose();
        }
    }

    public boolean hasActiveCompletion(Editor editor) {
        ZestCompletionData.PendingCompletion completion = activeCompletions.get(editor);
        return completion != null && completion.isActive();
    }

    @Nullable
    public ZestCompletionData.PendingCompletion getActiveCompletion(Editor editor) {
        return activeCompletions.get(editor);
    }

    public void setEnabled(boolean enabled) {
        this.isEnabled = enabled;
        if (!enabled) {
            activeCompletions.keySet().forEach(this::clearCompletion);
        }
        LOG.info("Enhanced autocomplete service " + (enabled ? "enabled" : "disabled"));
    }

    public boolean isEnabled() {
        return isEnabled;
    }

    /**
     * Generates enhanced cache key based on rich context.
     */
    private String generateEnhancedCacheKey(ContextGatherer.CursorContext cursorContext, String fileContext) {
        return String.valueOf((cursorContext.getPrefixContext() +
                cursorContext.getSuffixContext() +
                cursorContext.getOffset()).hashCode());
    }

    /**
     * Detects programming language from editor context.
     */
    private String detectLanguage(Editor editor, @Nullable PsiFile psiFile) {
        if (psiFile != null) {
            String fileName = psiFile.getName();
            if (fileName.endsWith(".java")) return "java";
            if (fileName.endsWith(".kt")) return "kotlin";
            if (fileName.endsWith(".js")) return "javascript";
            if (fileName.endsWith(".ts")) return "typescript";
            if (fileName.endsWith(".py")) return "python";
        }
        return "java"; // Default
    }

    /**
     * Creates code context for API request.
     */
    private CodeContext createCodeContext(Editor editor, String prompt, ConfigurationManager config) {
        CodeContext context = new CodeContext();
        context.setProject(project);
        context.setEditor(editor);
        context.setConfig(config);
        context.setPrompt(prompt);
        context.useTestWrightModel(false);
        return context;
    }

    public void clearCache() {
        completionCache.clear();
        LOG.debug("Cleared enhanced autocomplete cache");
    }

    public String getCacheStats() {
        return String.format("Enhanced cache size: %d/%d", completionCache.size(), MAX_CACHE_SIZE);
    }

    @Override
    public void dispose() {
        if (currentRequest != null && !currentRequest.isDone()) {
            currentRequest.cancel(true);
        }

        activeCompletions.keySet().forEach(this::clearCompletion);
        activeCompletions.clear();
        renderingContexts.clear();
        documentListeners.clear();
        caretListeners.clear();
        completionCache.clear();

        LOG.info("Enhanced ZestAutocompleteService disposed for project: " + project.getName());
    }
}