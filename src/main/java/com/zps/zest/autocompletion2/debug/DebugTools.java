package com.zps.zest.autocompletion2.debug;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.zps.zest.autocompletion2.core.AutocompleteService;
import com.zps.zest.autocompletion2.core.CompletionState;
import com.zps.zest.autocompletion2.core.TabHandler;
import org.jetbrains.annotations.NotNull;

/**
 * Debug utilities for Zest Autocomplete v2.
 * Provides testing and diagnostic capabilities.
 */
public class DebugTools {
    private static final Logger LOG = Logger.getInstance(DebugTools.class);
    
    /**
     * Shows comprehensive diagnostic information.
     */
    public static void showDiagnostic(@NotNull Editor editor) {
        Project project = editor.getProject();
        if (project == null) {
            Messages.showErrorDialog("No project available", "Diagnostic");
            return;
        }
        
        StringBuilder diagnostic = new StringBuilder();
        diagnostic.append("=== ZEST AUTOCOMPLETE V2 DIAGNOSTIC ===\n\n");
        
        // Tab Handler Check
        diagnostic.append("1. TAB HANDLER:\n");
        boolean handlerInstalled = TabHandler.isInstalled();
        diagnostic.append("   Installed: ").append(handlerInstalled ? "✅ YES" : "❌ NO").append("\n");
        if (!handlerInstalled) {
            diagnostic.append("   ⚠️ Tab handler not installed - Tab key won't work!\n");
        }
        diagnostic.append("\n");
        
        // Service Check
        diagnostic.append("2. AUTOCOMPLETE SERVICE:\n");
        AutocompleteService service = AutocompleteService.getInstance(project);
        boolean serviceEnabled = service.isEnabled();
        boolean hasCompletion = service.hasCompletion(editor);
        
        diagnostic.append("   Enabled: ").append(serviceEnabled ? "✅ YES" : "❌ NO").append("\n");
        diagnostic.append("   Has active completion: ").append(hasCompletion ? "✅ YES" : "❌ NO").append("\n");
        
        if (hasCompletion) {
            CompletionState state = service.getCompletionState(editor);
            if (state != null) {
                diagnostic.append("   Tab count: ").append(state.getTabCount()).append("\n");
                diagnostic.append("   Next Tab will: ").append(state.getNextAcceptanceType().getDescription()).append("\n");
                diagnostic.append("   Inlays: ").append(state.getInlays().size()).append("\n");
            }
        }
        diagnostic.append("\n");
        
        // Recommendations
        diagnostic.append("3. RECOMMENDATIONS:\n");
        if (!handlerInstalled) {
            diagnostic.append("   🔧 Install Tab handler: TabHandler.install()\n");
        }
        if (!serviceEnabled) {
            diagnostic.append("   🔧 Enable service: service.setEnabled(true)\n");
        }
        if (!hasCompletion) {
            diagnostic.append("   ℹ️ No active completion - try 'Create Test Completion'\n");
        }
        
        // Show dialog with option to auto-fix
        String fullMessage = diagnostic + "\nWould you like to attempt automatic fixes?";
        
        int result = Messages.showYesNoDialog(
            project,
            fullMessage,
            "Zest Autocomplete V2 Diagnostic",
            "Auto-Fix",
            "Close",
            Messages.getQuestionIcon()
        );
        
        if (result == Messages.YES) {
            autoFix(project, service);
        }
    }
    
    /**
     * Attempts to fix common issues automatically.
     */
    private static void autoFix(@NotNull Project project, @NotNull AutocompleteService service) {
        boolean fixed = false;
        StringBuilder fixes = new StringBuilder("Auto-fix results:\n\n");
        
        // Fix 1: Install Tab handler
        if (!TabHandler.isInstalled()) {
            TabHandler.install();
            fixes.append("✅ Installed Tab handler\n");
            fixed = true;
        }
        
        // Fix 2: Enable service
        if (!service.isEnabled()) {
            service.setEnabled(true);
            fixes.append("✅ Enabled autocomplete service\n");
            fixed = true;
        }
        
        if (!fixed) {
            fixes.append("ℹ️ No fixes were needed");
        }
        
        Messages.showInfoMessage(project, fixes.toString(), "Auto-Fix Complete");
    }
    
    /**
     * Creates a test completion for the current editor.
     */
    public static void createTestCompletion(@NotNull Editor editor) {
        Project project = editor.getProject();
        if (project == null) {
            Messages.showErrorDialog("No project available", "Test Completion");
            return;
        }
        
        AutocompleteService service = AutocompleteService.getInstance(project);
        
        // Create a multi-line test completion
        String testCompletion = """
            writeInt(42);
                buffer.flip();
                channel.write(buffer);
                buffer.clear();""";
        
        boolean success = service.showCompletion(editor, testCompletion);
        
        if (success) {
            String message = """
                ✅ Test completion created!
                
                You should see gray completion text.
                
                TEST PROGRESSIVE TAB:
                1️⃣ Tab → Accept word
                2️⃣ Tab → Accept next word
                3️⃣ Tab → Accept line
                4️⃣ Tab → Accept all remaining
                
                Each Tab should show remaining completion!""";
                
            Messages.showInfoMessage(project, message, "Test Completion Ready");
        } else {
            String message = """
                ❌ Failed to create test completion.
                
                Check that:
                • Service is enabled
                • Editor is valid
                • Cursor is in valid position""";
                
            Messages.showWarningDialog(project, message, "Test Failed");
        }
    }
    
    /**
     * Demonstrates the progressive Tab acceptance system.
     */
    public static void demonstrateProgressive(@NotNull Editor editor) {
        createTestCompletion(editor);
        
        // Show instructions
        String instructions = """
            PROGRESSIVE TAB DEMONSTRATION
            
            The test completion has been created.
            
            HOW IT WORKS:
            • Each Tab press accepts a portion
            • Creates a new completion with remaining text
            • Tab count resets for each new completion
            • Progression: WORD → LINE → FULL
            
            TRY IT NOW:
            Press Tab repeatedly and watch the progression!""";
            
        Messages.showInfoMessage(editor.getProject(), instructions, "Progressive Tab Demo");
    }
    
    /**
     * Logs detailed information about the current state.
     */
    public static void logDebugInfo(@NotNull Editor editor) {
        Project project = editor.getProject();
        if (project == null) return;
        
        AutocompleteService service = AutocompleteService.getInstance(project);
        
        LOG.info("=== DEBUG INFO ===");
        LOG.info("Tab handler installed: " + TabHandler.isInstalled());
        LOG.info("Service enabled: " + service.isEnabled());
        LOG.info("Has completion: " + service.hasCompletion(editor));
        LOG.info("Editor offset: " + editor.getCaretModel().getOffset());
        
        if (service.hasCompletion(editor)) {
            CompletionState state = service.getCompletionState(editor);
            if (state != null) {
                LOG.info("Completion state: " + state);
            }
        }
        
        LOG.info("Service diagnostic:\n" + service.getDiagnosticInfo());
        LOG.info("=== END DEBUG INFO ===");
    }
}
