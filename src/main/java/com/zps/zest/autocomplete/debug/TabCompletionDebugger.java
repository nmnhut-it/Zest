package com.zps.zest.autocomplete.debug;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.actionSystem.EditorActionHandler;
import com.intellij.openapi.editor.actionSystem.EditorActionManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.zps.zest.autocomplete.ZestAutocompleteService;
import com.zps.zest.autocomplete.ZestCompletionData;
import com.zps.zest.autocomplete.handlers.ZestSmartTabHandler;
import com.zps.zest.autocomplete.utils.TabCompletionContext;
import org.jetbrains.annotations.NotNull;

/**
 * Comprehensive debugger for Tab completion issues.
 * This will help identify why Tab is canceling instead of accepting completion.
 */
public class TabCompletionDebugger {
    private static final Logger LOG = Logger.getInstance(TabCompletionDebugger.class);
    
    /**
     * Performs comprehensive diagnostic of Tab completion system
     */
    public static String performDiagnostic(@NotNull Editor editor) {
        Project project = editor.getProject();
        if (project == null) {
            return "No project available";
        }
        
        StringBuilder report = new StringBuilder();
        report.append("=== ZEST TAB COMPLETION DIAGNOSTIC ===\n\n");
        
        try {
            // 1. Check Tab Handler Installation
            report.append("1. TAB HANDLER CHECK:\n");
            EditorActionManager actionManager = EditorActionManager.getInstance();
            EditorActionHandler tabHandler = actionManager.getActionHandler("EditorTab");
            
            report.append("   Current handler: ").append(tabHandler.getClass().getName()).append("\n");
            report.append("   Is ZestSmartTabHandler: ").append(tabHandler instanceof ZestSmartTabHandler).append("\n");
            
            if (tabHandler instanceof ZestSmartTabHandler) {
                ZestSmartTabHandler smartHandler = (ZestSmartTabHandler) tabHandler;
                report.append("   ✅ ZestSmartTabHandler is installed\n");
            } else {
                report.append("   ❌ ZestSmartTabHandler is NOT installed\n");
                report.append("   ⚠️  This is likely the main issue!\n");
            }
            report.append("\n");
            
            // 2. Check Zest Service
            report.append("2. ZEST SERVICE CHECK:\n");
            ZestAutocompleteService service = ZestAutocompleteService.getInstance(project);
            boolean serviceEnabled = service.isEnabled();
            boolean hasActiveCompletion = service.hasActiveCompletion(editor);
            
            report.append("   Service enabled: ").append(serviceEnabled).append("\n");
            report.append("   Has active completion: ").append(hasActiveCompletion).append("\n");
            
            if (hasActiveCompletion) {
                ZestCompletionData.PendingCompletion completion = service.getActiveCompletion(editor);
                if (completion != null) {
                    report.append("   Completion text: ").append(completion.getDisplayText().substring(0, Math.min(50, completion.getDisplayText().length()))).append("...\n");
                    report.append("   Completion active: ").append(completion.isActive()).append("\n");
                }
                
                // Show tab count for debugging continuation issues
                int tabCount = service.getStateManager().getTabAcceptCount(editor);
                report.append("   Current tab count: ").append(tabCount).append("\n");
                report.append("   Next Tab will: ").append(getTabActionDescription(tabCount + 1)).append("\n");
            }
            report.append("\n");
            
            // 3. Check Context Detection
            report.append("3. CONTEXT DETECTION CHECK:\n");
            TabCompletionContext.ContextType context = TabCompletionContext.detectContext(editor, null);
            report.append("   Detected context: ").append(context).append("\n");
            
            // Analyze context appropriateness
            if (hasActiveCompletion) {
                if (context == TabCompletionContext.ContextType.ZEST_COMPLETION_ACTIVE) {
                    report.append("   ✅ Context correctly identifies active Zest completion\n");
                } else {
                    report.append("   ❌ Context detection FAILURE: Has completion but context is ").append(context).append("\n");
                    report.append("   ⚠️  This will cause Tab to delegate to IntelliJ instead of accepting!\n");
                }
            } else {
                report.append("   ℹ️  No active completion to test context against\n");
            }
            report.append("\n");
            
            // 4. Check State Manager
            report.append("4. STATE MANAGER CHECK:\n");
            String stateInfo = service.getStateManagerDiagnostic();
            report.append(stateInfo).append("\n\n");
            
            // 5. Recommendations
            report.append("5. RECOMMENDATIONS:\n");
            if (!(tabHandler instanceof ZestSmartTabHandler)) {
                report.append("   🔧 CRITICAL: Re-install ZestSmartTabHandler\n");
                report.append("   → Run: ZestSmartTabHandler.install()\n");
            }
            
            if (hasActiveCompletion && context != TabCompletionContext.ContextType.ZEST_COMPLETION_ACTIVE) {
                report.append("   🔧 Fix context detection logic in TabCompletionContext.hasActiveZestCompletion()\n");
            }
            
            if (!serviceEnabled) {
                report.append("   🔧 Enable Zest autocomplete service\n");
            }
            
        } catch (Exception e) {
            report.append("ERROR during diagnostic: ").append(e.getMessage()).append("\n");
            LOG.error("Diagnostic error", e);
        }
        
        report.append("\n=== END DIAGNOSTIC ===");
        return report.toString();
    }
    
    /**
     * Attempts to fix common Tab completion issues automatically
     */
    public static boolean attemptAutoFix(@NotNull Editor editor) {
        Project project = editor.getProject();
        if (project == null) {
            return false;
        }
        
        LOG.info("Attempting auto-fix for Tab completion issues");
        boolean fixed = false;
        
        try {
            // Fix 1: Re-install Tab handler if missing
            EditorActionManager actionManager = EditorActionManager.getInstance();
            EditorActionHandler tabHandler = actionManager.getActionHandler("EditorTab");
            
            if (!(tabHandler instanceof ZestSmartTabHandler)) {
                LOG.info("Re-installing ZestSmartTabHandler");
                ZestSmartTabHandler.install();
                fixed = true;
            }
            
            // Fix 2: Clear potentially corrupted completion state
            ZestAutocompleteService service = ZestAutocompleteService.getInstance(project);
            if (service.hasActiveCompletion(editor)) {
                LOG.info("Clearing potentially corrupted completion state");
                service.clearCompletion(editor);
                
                // Trigger fresh completion after a short delay
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (!editor.isDisposed()) {
                        service.triggerCompletion(editor, true);
                    }
                });
                fixed = true;
            }
            
            return fixed;
            
        } catch (Exception e) {
            LOG.error("Error during auto-fix", e);
            return false;
        }
    }
    
    /**
     * Shows diagnostic dialog to user
     */
    public static void showDiagnosticDialog(@NotNull Editor editor) {
        String diagnostic = performDiagnostic(editor);
        
        ApplicationManager.getApplication().invokeLater(() -> {
            int result = Messages.showYesNoDialog(
                editor.getProject(),
                diagnostic + "\n\nWould you like to attempt automatic fixes?",
                "Zest Tab Completion Diagnostic",
                "Auto-Fix",
                "Close",
                Messages.getQuestionIcon()
            );
            
            if (result == Messages.YES) {
                boolean fixed = attemptAutoFix(editor);
                String message = fixed ? 
                    "Auto-fix completed! Try using Tab completion again." :
                    "No fixes were needed or possible at this time.";
                Messages.showInfoMessage(editor.getProject(), message, "Auto-Fix Result");
            }
        });
    }
    
    /**
     * Enhanced logging for Tab key presses
     */
    public static void logTabPress(@NotNull Editor editor, @NotNull String phase) {
        if (!LOG.isDebugEnabled()) {
            return;
        }
        
        try {
            Project project = editor.getProject();
            if (project == null) return;
            
            ZestAutocompleteService service = ZestAutocompleteService.getInstance(project);
            TabCompletionContext.ContextType context = TabCompletionContext.detectContext(editor, null);
            int tabCount = service.getStateManager().getTabAcceptCount(editor);
            
            LOG.debug("TAB PRESS [{}]: hasCompletion={}, context={}, tabCount={}, offset={}", 
                phase,
                service.hasActiveCompletion(editor),
                context,
                tabCount,
                editor.getCaretModel().getOffset()
            );
            
        } catch (Exception e) {
            LOG.warn("Error logging tab press", e);
        }
    }
    
    /**
     * Helper method to describe what the next Tab action will do
     */
    private static String getTabActionDescription(int nextTabCount) {
        switch (nextTabCount) {
            case 1:
                return "Accept WORD";
            case 2:
                return "Accept LINE";
            case 3:
            default:
                return "Accept FULL";
        }
    }
}
