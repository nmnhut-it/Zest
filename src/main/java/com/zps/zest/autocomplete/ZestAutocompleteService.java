package com.zps.zest.autocomplete;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.event.EditorFactoryEvent;
import com.intellij.openapi.editor.event.EditorFactoryListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
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
    private static final int COMPLETION_DELAY_MS = 100; // User's preferred delay
    private static final int MAX_CACHE_SIZE = 100;

    private final Project project;
    private final Map<Editor, ZestCompletionData.PendingCompletion> activeCompletions = new ConcurrentHashMap<>();
    private final Map<String, ZestCompletionData.CompletionItem> completionCache = new ConcurrentHashMap<>();
    private final Map<Editor, ZestAutocompleteDocumentListener> documentListeners = new ConcurrentHashMap<>();
    private final Map<Editor, ZestAutocompleteCaretListener> caretListeners = new ConcurrentHashMap<>();
    private final Map<Editor, ZestInlayRenderer.RenderingContext> renderingContexts = new ConcurrentHashMap<>();
    private final Map<Editor, Integer> tabAcceptCounts = new ConcurrentHashMap<>();
    private final Map<Editor, CompletableFuture<Void>> currentRequests = new ConcurrentHashMap<>();
    private final Map<Editor, Long> lastTypingTimes = new ConcurrentHashMap<>();
    private final Map<Editor, ContextSnapshot> pendingContexts = new ConcurrentHashMap<>();

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
    public void triggerAutocomplete(Editor editor) {
        triggerAutocomplete(editor, false);
    }
    /**
     * Triggers autocomplete with enhanced context gathering.
     */
    public void triggerAutocomplete(Editor editor, boolean forced) {
        if (forced){
        }
        if (!isEnabled || editor.isDisposed()) {
            return;
        }

        // Cancel existing request
        ContextSnapshot snapshot = captureCurrentContext(editor);
        if (snapshot == null) {
            return; // Invalid context, don't trigger
        }
        if (forced) {
            requestEnhancedCompletion(editor, snapshot);
        }

        recordLastTypingTime(editor);
        CompletableFuture<Void> existingRequest = currentRequests.get(editor);
        if (existingRequest != null && !existingRequest.isDone()) {
            existingRequest.cancel(true);
        }

        clearCompletion(editor);
        // Store the context snapshot for when request actually executes
        pendingContexts.put(editor, snapshot);

        // Schedule with optimized delay
        CompletableFuture<Void> newRequest = CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(COMPLETION_DELAY_MS);

                 if (!Thread.currentThread().isInterrupted() && (!isUserStillTyping(editor) || forced)) {
                    requestEnhancedCompletion(editor, snapshot);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                currentRequests.remove(editor);
                pendingContexts.remove(editor);
            }
        });

        currentRequests.put(editor, newRequest);
    }

    public @Nullable Long recordLastTypingTime(Editor editor) {
        return lastTypingTimes.put(editor, System.currentTimeMillis());
    }

    private boolean isUserStillTyping(Editor editor) {
        Long lastTime = lastTypingTimes.get(editor);
        if (lastTime == null) return false;

        long timeSinceLastTyping = System.currentTimeMillis() - lastTime;
        return timeSinceLastTyping < 10; // Small buffer to detect rapid typing
    }
    private ContextSnapshot captureCurrentContext(Editor editor) {
        try {
            return ApplicationManager.getApplication().runReadAction((Computable<ContextSnapshot>) () -> {
                if (editor.isDisposed()) return null;

                PsiFile psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument());
                int currentOffset = editor.getCaretModel().getOffset();

                ContextGatherer.CursorContext cursorContext =
                        ContextGatherer.gatherEnhancedCursorContext(editor, psiFile);
                String fileContext = ContextGatherer.gatherFileContext(editor, psiFile);

                return new ContextSnapshot(cursorContext, fileContext, currentOffset,
                        System.currentTimeMillis(), editor.getDocument().getModificationStamp());
            });
        } catch (Exception e) {
            LOG.warn("Failed to capture context", e);
            return null;
        }
    }
    /**
     * Requests completion using enhanced context gathering and prompting.
     */
    private void requestEnhancedCompletion(Editor editor, ContextSnapshot snapshot) {
        ApplicationManager.getApplication().runReadAction(() -> {
            if (editor.isDisposed() || !isEnabled) {
                return;
            }

            // ✅ FIX 6: Validate context is still valid (user hasn't moved cursor significantly)
            if (!isContextStillValid(editor, snapshot)) {
                LOG.debug("Context invalidated, skipping completion");
                return;
            }
            try {
                ConfigurationManager config = ConfigurationManager.getInstance(project);
                if (!config.isAutocompleteEnabled()) {
                    return;
                }

                // Use captured context instead of re-gathering
                String cacheKey = generateEnhancedCacheKey(snapshot.cursorContext, snapshot.fileContext);
                ZestCompletionData.CompletionItem cachedItem = completionCache.get(cacheKey);

                if (cachedItem != null && cachedItem.isValidAt(snapshot.capturedOffset)) {
                    ApplicationManager.getApplication().invokeLater(() ->
                            displayEnhancedCompletion(editor, cachedItem, snapshot));
                    return;
                }

                // ✅ FIXED: Use AutocompletePromptBuilder's built-in context detection
                // Build enhanced prompt using captured context - this handles context detection internally
                AutocompletePromptBuilder enhancedPrompt = AutocompletePromptBuilder.createContextAwarePrompt(
                        snapshot.fileContext,
                        snapshot.cursorContext.getPrefixContext(),
                        snapshot.cursorContext.getSuffixContext(),
                        detectLanguage(editor, null)
                );

                // Make API request
                CompletableFuture.runAsync(() -> {
                    try {
                        CodeContext context = ReadAction.compute(() ->
                                createCodeContext(editor, enhancedPrompt.build(), config));

                        // ✅ FIXED: Use default AutocompleteApiStage - no duplicate system prompt
                        AutocompleteApiStage apiStage = new AutocompleteApiStage(project, enhancedPrompt.systemPrompt);
                        
                        apiStage.process(context);

                        String completion = context.getApiResponse();
                        if (completion != null && !completion.trim().isEmpty()) {
                            ZestCompletionData.CompletionItem item = createCompletionItem(
                                    completion.trim(), snapshot);

                            if (item == null) return;

                            completionCache.put(cacheKey, item);

                            // ✅ FIX 7: Final validation before display
                            ApplicationManager.getApplication().invokeLater(() -> {
                                if (isContextStillValid(editor, snapshot)) {
                                    displayEnhancedCompletion(editor, item, snapshot);
                                }
                            });
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
    private boolean isContextStillValid(Editor editor, ContextSnapshot snapshot) {
        if (editor.isDisposed()) return false;

        // Check if document was modified since capture
        if (editor.getDocument().getModificationStamp() != snapshot.documentModificationStamp) {
            return false;
        }

        // Check if cursor moved significantly since capture
        int currentOffset = editor.getCaretModel().getOffset();
        int offsetDiff = Math.abs(currentOffset - snapshot.capturedOffset);

        // Allow small movements (up to 2 characters) for natural typing
        return offsetDiff <= 2;
    }

    /**
     * Creates a completion item with intelligent replace range calculation.
     * Note: Redundant prefix removal is now handled at the API response level.
     */
    private ZestCompletionData.CompletionItem createCompletionItem(String completion, ContextSnapshot snapshot) {
        if (completion == null || completion.trim().isEmpty()) {
            LOG.debug("Skipping empty completion");
            return null;
        }

        String trimmedCompletion = completion.trim();

        // Use captured context, not current context
        String currentLineSuffix = snapshot.cursorContext.getCurrentLineSuffix().trim();
        if (!currentLineSuffix.isEmpty() && trimmedCompletion.startsWith(currentLineSuffix)) {
            LOG.debug("Skipping completion - shares content with current line suffix");
            return null;
        }

        LOG.debug("Creating completion item with text: '{}'", trimmedCompletion);

        // ✅ FIX 8: Use captured offset, not current offset
        int startOffset = snapshot.capturedOffset;
        int endOffset = startOffset;

        if (!currentLineSuffix.isEmpty()) {
            if (trimmedCompletion.startsWith(currentLineSuffix)) {
                endOffset = startOffset + currentLineSuffix.length();
                LOG.debug("Will replace suffix: '{}' (length: {})", currentLineSuffix, currentLineSuffix.length());
            }
        }

        ZestCompletionData.Range replaceRange = new ZestCompletionData.Range(startOffset, endOffset);
        return new ZestCompletionData.CompletionItem(trimmedCompletion, replaceRange, null, 1.0);
    }
    /**
     * Displays completion using enhanced renderer.
     * Much simpler now that responses are pre-cleaned for safety.
     */
    private void displayEnhancedCompletion(Editor editor, ZestCompletionData.CompletionItem item,
                                           ContextSnapshot snapshot) {
        if (editor.isDisposed()) {
            return;
        }

        try {
            clearCompletion(editor);

            // ✅ FIX 9: Use captured offset for rendering
            ZestInlayRenderer.RenderingContext renderingContext =
                    ZestInlayRenderer.show(editor, snapshot.capturedOffset+1, item);

            if (!renderingContext.getInlays().isEmpty()) {
                ZestCompletionData.PendingCompletion completion =
                        new ZestCompletionData.PendingCompletion(item, editor,
                                snapshot.cursorContext.getPrefixContext());

                activeCompletions.put(editor, completion);
                renderingContexts.put(editor, renderingContext);
                tabAcceptCounts.put(editor, 0);

                LOG.debug("Displayed enhanced autocomplete: " +
                        item.getInsertText().substring(0, Math.min(50, item.getInsertText().length())));
            } else {
                LOG.debug("Renderer returned empty context - completion skipped");
            }

        } catch (Exception e) {
            LOG.warn("Failed to display enhanced completion", e);
        }
    }

    /**
     * ✅ NEW: Context snapshot to capture state at trigger time
     */
    private static class ContextSnapshot {
        final ContextGatherer.CursorContext cursorContext;
        final String fileContext;
        final int capturedOffset;
        final long captureTime;
        final long documentModificationStamp;

        ContextSnapshot(ContextGatherer.CursorContext cursorContext, String fileContext,
                        int capturedOffset, long captureTime, long documentModificationStamp) {
            this.cursorContext = cursorContext;
            this.fileContext = fileContext;
            this.capturedOffset = capturedOffset;
            this.captureTime = captureTime;
            this.documentModificationStamp = documentModificationStamp;
        }
    }
    /**
     * Accepts completion with proper replace range handling and partial acceptance support.
     */
    public void acceptCompletion(Editor editor) {
        acceptCompletion(editor, AcceptType.FULL_COMPLETION);
    }

    /**
     * Smart tab completion: cycles through word -> line -> full completion
     */
    public void handleTabCompletion(Editor editor) {
        if (!hasActiveCompletion(editor)) {
            return;
        }

        int tabCount = tabAcceptCounts.getOrDefault(editor, 0) + 1;
        tabAcceptCounts.put(editor, tabCount);

        AcceptType acceptType;
        switch (tabCount) {
            case 1:
                acceptType = AcceptType.NEXT_WORD;
                break;
            case 2:
                acceptType = AcceptType.NEXT_LINE;
                break;
            default:
                acceptType = AcceptType.FULL_COMPLETION;
        }

        acceptCompletion(editor, acceptType);
    }

    /**
     * Accepts completion with specified acceptance type (full, word, or line).
     * 
     * @param editor The editor instance
     * @param acceptType The type of acceptance (full, word, or line)
     */
    public void acceptCompletion(Editor editor, AcceptType acceptType) {
        ZestCompletionData.PendingCompletion completion = activeCompletions.get(editor);
        if (completion == null || !completion.isActive()) {
            return;
        }

        try {
            ZestCompletionData.CompletionItem item = completion.getItem();
            String fullCompletionText = item.getInsertText();
            
            // Apply final redundant prefix removal to be absolutely sure
            String cleanedCompletionText = applyFinalRedundantPrefixCheck(editor, fullCompletionText);
            
            // Calculate what text to actually accept based on accept type
            String textToAccept = AcceptType.extractAcceptableText(cleanedCompletionText, acceptType);
            if (textToAccept.isEmpty()) {
                LOG.debug("No text to accept for type: " + acceptType);
                return;
            }

            // Handle IntelliJ auto-insertions and get the final adjusted text
            String finalTextToAccept = handleIntelliJAutoInsertions(editor, textToAccept, item.getReplaceRange());

            Document document = editor.getDocument();

            VirtualFile virtualFile = editor.getVirtualFile();
            if (virtualFile == null)
                return;
                
            WriteCommandAction.runWriteCommandAction(project,"autocomplete","Zest", (Runnable) () -> {
                // Replace the range with the final accepted text
                document.replaceString(item.getReplaceRange().getStart(), item.getReplaceRange().getEnd(), finalTextToAccept);

                // Move cursor to end of inserted text
                editor.getCaretModel().moveToOffset(item.getReplaceRange().getStart() + finalTextToAccept.length());
            }, PsiUtil.getPsiFile(project, virtualFile));

            // Publish acceptance event
            publishCompletionAccepted(editor, item, acceptType, finalTextToAccept);

            // Handle partial acceptance continuation
            if (acceptType != AcceptType.FULL_COMPLETION) {
                String remainingText = AcceptType.calculateRemainingText(cleanedCompletionText, finalTextToAccept);
                if (remainingText != null && !remainingText.trim().isEmpty()) {
                    // Create continuation completion
                    handlePartialAcceptanceContinuation(editor, completion, item, finalTextToAccept, remainingText);
                    return; // Don't clear completion yet
                }
            }

            completion.accept();
            clearCompletion(editor);
            // Reset tab count after completion
            tabAcceptCounts.remove(editor);

            LOG.debug("Accepted enhanced autocomplete suggestion with type: " + acceptType);

        } catch (Exception e) {
            LOG.warn("Failed to accept enhanced completion", e);
        }
    }

    /**
     * Final redundant prefix check at acceptance time to be absolutely sure.
     * ✅ ENHANCED: Use the same smart logic as rendering
     */
    private String applyFinalRedundantPrefixCheck(Editor editor, String completionText) {
        try {
            Document document = editor.getDocument();
            int currentOffset = editor.getCaretModel().getOffset();
            
            // Get current line prefix
            int lineNumber = document.getLineNumber(currentOffset);
            int lineStart = document.getLineStartOffset(lineNumber);
            String currentLinePrefix = document.getText().substring(lineStart, currentOffset);
            
            // ✅ ENHANCED: Use the same smart logic as CompletionItem.getSmartVisibleText()
            String cleaned = applySmartRedundantPrefixRemoval(currentLinePrefix.trim(), completionText.trim());
            
            if (!cleaned.equals(completionText)) {
                LOG.debug("Smart acceptance prefix removal: '{}' -> '{}'", 
                         completionText.substring(0, Math.min(30, completionText.length())), 
                         cleaned.substring(0, Math.min(30, cleaned.length())));
            }
            
            return cleaned;
            
        } catch (Exception e) {
            LOG.warn("Error in smart redundant prefix check", e);
            return completionText;
        }
    }
    
    /**
     * ✅ NEW: Smart redundant prefix removal - same logic as in CompletionItem
     */
    private String applySmartRedundantPrefixRemoval(String currentPrefix, String completionText) {
        if (currentPrefix == null || completionText == null || completionText.isEmpty()) {
            return completionText;
        }
        
        if (currentPrefix.isEmpty()) {
            return completionText;
        }
        
        // Handle comments specially - be very conservative  
        if (currentPrefix.startsWith("//") && completionText.startsWith("//")) {
            return handleCommentRedundancyForAcceptance(currentPrefix, completionText);
        }
        
        // Find longest common suffix of currentPrefix and prefix of completionText
        int commonLength = findCommonPrefixLengthForAcceptance(currentPrefix, completionText);
        
        if (commonLength > 0) {
            String matchedText = completionText.substring(0, commonLength);
            
            // Only remove if it's a meaningful match and won't break words
            if (isValidPrefixToRemoveForAcceptance(currentPrefix, matchedText, completionText)) {
                return completionText.substring(commonLength);
            }
        }
        
        return completionText;
    }
    
    /**
     * Finds common prefix length for acceptance logic
     */
    private int findCommonPrefixLengthForAcceptance(String currentPrefix, String completionText) {
        int commonLength = 0;
        int maxCheck = Math.min(currentPrefix.length(), completionText.length());
        
        for (int i = 1; i <= maxCheck; i++) {
            String prefixSuffix = currentPrefix.substring(currentPrefix.length() - i);
            String completionPrefix = completionText.substring(0, i);
            
            if (prefixSuffix.equals(completionPrefix)) {
                commonLength = i;
            } else {
                break;
            }
        }
        
        return commonLength;
    }
    
    /**
     * Handle comment redundancy for acceptance
     */
    private String handleCommentRedundancyForAcceptance(String currentPrefix, String completionText) {
        if (currentPrefix.equals(completionText.substring(0, Math.min(currentPrefix.length(), completionText.length())))) {
            return completionText.substring(currentPrefix.length());
        }
        return completionText;
    }
    
    /**
     * Validates prefix removal for acceptance
     */
    private boolean isValidPrefixToRemoveForAcceptance(String currentPrefix, String matchedText, String fullCompletion) {
        String remainder = fullCompletion.substring(matchedText.length());
        if (remainder.trim().length() < 2) {
            return false;
        }
        
        // Don't break word boundaries
        if (matchedText.length() < fullCompletion.length()) {
            char nextChar = fullCompletion.charAt(matchedText.length());
            if (Character.isLetterOrDigit(nextChar) && Character.isLetterOrDigit(matchedText.charAt(matchedText.length() - 1))) {
                return false;
            }
        }
        
        if (matchedText.trim().length() < 3) {
            return false;
        }
        
        return true;
    }

    /**
     * Handle IntelliJ's automatic bracket/brace insertions that might interfere.
     * This version is more sophisticated and can adjust the completion accordingly.
     */
    private String handleIntelliJAutoInsertions(Editor editor, String textToAccept, ZestCompletionData.Range replaceRange) {
        try {
            Document document = editor.getDocument();
            int currentOffset = editor.getCaretModel().getOffset();
            
            // Check what's currently after the cursor
            int lineNumber = document.getLineNumber(currentOffset);
            int lineEnd = document.getLineEndOffset(lineNumber);
            String textAfterCursor = "";
            
            if (currentOffset < lineEnd) {
                textAfterCursor = document.getText().substring(currentOffset, Math.min(lineEnd, currentOffset + 20));
            }
            
            // Check if IntelliJ has already inserted matching brackets/braces
            String adjustedCompletion = textToAccept;
            
            // Handle common auto-insertion scenarios
            if (textToAccept.contains("(") && !textAfterCursor.isEmpty()) {
                adjustedCompletion = handleBracketAutoInsertion(textToAccept, textAfterCursor, '(', ')');
            }
            
            if (textToAccept.contains("{") && !textAfterCursor.isEmpty()) {
                adjustedCompletion = handleBracketAutoInsertion(adjustedCompletion, textAfterCursor, '{', '}');
            }
            
            if (textToAccept.contains("[") && !textAfterCursor.isEmpty()) {
                adjustedCompletion = handleBracketAutoInsertion(adjustedCompletion, textAfterCursor, '[', ']');
            }
            
            // Handle semicolon duplication
            if (textToAccept.endsWith(";") && textAfterCursor.startsWith(";")) {
                adjustedCompletion = textToAccept.substring(0, textToAccept.length() - 1);
                LOG.debug("Removed duplicate semicolon from completion");
            }
            
            if (!adjustedCompletion.equals(textToAccept)) {
                LOG.debug("Adjusted completion for IntelliJ auto-insertions: '{}' -> '{}'", textToAccept, adjustedCompletion);
            }
            
            return adjustedCompletion;
            
        } catch (Exception e) {
            LOG.warn("Error handling IntelliJ auto-insertions", e);
            return textToAccept;
        }
    }

    /**
     * Handle specific bracket auto-insertion scenarios.
     */
    private String handleBracketAutoInsertion(String completionText, String textAfterCursor, char openBracket, char closeBracket) {
        // Count opening and closing brackets in completion
        long openCount = completionText.chars().filter(ch -> ch == openBracket).count();
        long closeCount = completionText.chars().filter(ch -> ch == closeBracket).count();
        
        // Count closing brackets already in the editor after cursor
        long existingCloseCount = textAfterCursor.chars().filter(ch -> ch == closeBracket).count();
        
        // If we have unmatched opening brackets in completion and matching closing brackets after cursor
        if (openCount > closeCount && existingCloseCount > 0) {
            long excessCloseNeeded = openCount - closeCount;
            long availableClose = Math.min(existingCloseCount, excessCloseNeeded);
            
            // Remove that many closing brackets from the end of completion
            String result = completionText;
            for (int i = 0; i < availableClose; i++) {
                int lastClose = result.lastIndexOf(closeBracket);
                if (lastClose != -1) {
                    result = result.substring(0, lastClose) + result.substring(lastClose + 1);
                }
            }
            
            if (!result.equals(completionText)) {
                LOG.debug("Removed {} closing '{}' brackets due to auto-insertion", availableClose, closeBracket);
            }
            
            return result;
        }
        
        return completionText;
    }

    /**
     * Same logic as in AutocompleteApiStage but applied at acceptance time.
     */
    private String removeRedundantPrefixAtAcceptance(String currentLinePrefix, String completionText) {
        if (currentLinePrefix == null || completionText == null || completionText.isEmpty()) {
            return completionText;
        }

        String currentPrefix = currentLinePrefix.trim();
        if (currentPrefix.isEmpty()) {
            return completionText;
        }

        // Special handling for comments - be very conservative
        if (currentPrefix.startsWith("//") && completionText.startsWith("//")) {
            if (currentPrefix.equals(completionText.substring(0, Math.min(currentPrefix.length(), completionText.length())))) {
                return completionText.substring(currentPrefix.length());
            }
            return completionText;
        }

        // Check for exact character matches at the beginning
        int commonLength = 0;
        int maxCheck = Math.min(currentPrefix.length(), completionText.length());
        
        for (int i = 1; i <= maxCheck; i++) {
            String prefixSuffix = currentPrefix.substring(currentPrefix.length() - i);
            String completionPrefix = completionText.substring(0, i);
            
            if (prefixSuffix.equals(completionPrefix)) {
                commonLength = i;
            } else {
                break;
            }
        }

        // Only remove if it's a substantial match (5+ characters) and won't break words
        if (commonLength >= 5) {
            String remainder = completionText.substring(commonLength);
            if (remainder.trim().length() >= 3) {
                return remainder;
            }
        }

        return completionText;
    }

    /**
     * Handles continuation after partial acceptance.
     */
    private void handlePartialAcceptanceContinuation(Editor editor, 
                                                   ZestCompletionData.PendingCompletion originalCompletion,
                                                   ZestCompletionData.CompletionItem originalItem,
                                                   String acceptedText, 
                                                   String remainingText) {
        try {
            // Calculate new offset after insertion
            int newOffset = editor.getCaretModel().getOffset();
            
            // Create continuation item with remaining text
            ZestCompletionData.Range newRange = new ZestCompletionData.Range(newOffset, newOffset);
            ZestCompletionData.CompletionItem continuationItem = new ZestCompletionData.CompletionItem(
                remainingText, newRange, null, originalItem.getConfidence()
            );

            // Create new pending completion for continuation
            ZestCompletionData.PendingCompletion continuationCompletion = new ZestCompletionData.PendingCompletion(
                continuationItem, editor, originalCompletion.getOriginalContext() + acceptedText
            );

            // Replace current completion with continuation
            activeCompletions.put(editor, continuationCompletion);

            // Update rendering context
            clearRenderingContext(editor);
            ZestInlayRenderer.RenderingContext newRenderingContext = 
                ZestInlayRenderer.show(editor, newOffset, continuationItem);
            renderingContexts.put(editor, newRenderingContext);

            // Publish continuation event
            publishCompletionContinued(editor, originalItem, continuationItem, acceptedText);

            LOG.debug("Created continuation completion with remaining text: " + 
                     remainingText.substring(0, Math.min(30, remainingText.length())));

        } catch (Exception e) {
            LOG.warn("Failed to create continuation completion", e);
            // Fall back to clearing completion
            clearCompletion(editor);
        }
    }

    /**
     * Clears only the rendering context without affecting the pending completion.
     */
    private void clearRenderingContext(Editor editor) {
        ZestInlayRenderer.RenderingContext renderingContext = renderingContexts.remove(editor);
        if (renderingContext != null) {
            renderingContext.dispose();
        }
    }

    /**
     * Publishes completion accepted event.
     */
    private void publishCompletionAccepted(Editor editor, ZestCompletionData.CompletionItem item, 
                                         AcceptType acceptType, String acceptedText) {
        // TODO: Implement message bus publishing when event system is integrated
        LOG.debug("Completion accepted - type: " + acceptType + ", text: " + 
                 acceptedText.substring(0, Math.min(20, acceptedText.length())));
    }

    /**
     * Publishes completion continued event.
     */
    private void publishCompletionContinued(Editor editor, ZestCompletionData.CompletionItem originalItem,
                                          ZestCompletionData.CompletionItem continuationItem, String acceptedPortion) {
        // TODO: Implement message bus publishing when event system is integrated
        LOG.debug("Completion continued - accepted: " + 
                 acceptedPortion.substring(0, Math.min(20, acceptedPortion.length())) +
                 ", remaining: " + continuationItem.getInsertText().substring(0, Math.min(20, continuationItem.getInsertText().length())));
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
        
        // Reset tab count
        tabAcceptCounts.remove(editor);
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
        // ✅ FIX 10: Cancel all pending requests
        currentRequests.values().forEach(request -> {
            if (!request.isDone()) {
                request.cancel(true);
            }
        });
        
        currentRequests.clear();
        lastTypingTimes.clear();
        pendingContexts.clear();

        activeCompletions.keySet().forEach(this::clearCompletion);
        activeCompletions.clear();
        renderingContexts.clear();
        documentListeners.clear();
        caretListeners.clear();
        completionCache.clear();
        tabAcceptCounts.clear();

        LOG.info("Enhanced ZestAutocompleteService disposed for project: " + project.getName());
    }
}