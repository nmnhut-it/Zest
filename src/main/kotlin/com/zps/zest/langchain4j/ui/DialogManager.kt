package com.zps.zest.langchain4j.ui

import com.intellij.openapi.ui.DialogWrapper
import javax.swing.SwingUtilities

/**
 * Manages dialogs to ensure only one ChatMemoryDialog or MessageDetailDialog is shown at a time.
 * When a new dialog is requested, any existing dialog is closed first.
 */
object DialogManager {
    private var currentDialog: DialogWrapper? = null

    /**
     * Shows a dialog, closing any existing dialog first.
     * @param dialog The dialog to show
     */
    fun showDialog(dialog: DialogWrapper) {
        SwingUtilities.invokeLater {
            // Close existing dialog if present
            currentDialog?.let {
                if (it.isShowing) {
                    if (it is MessageDetailDialog) {
                        it.close(DialogWrapper.CANCEL_EXIT_CODE)
                    }
                }
            }

            // Set new dialog as current and show it
            currentDialog = dialog
            dialog.show()

            // Clear reference when dialog is closed
            if (!dialog.isShowing) {
                if (currentDialog == dialog) {
                    currentDialog = null
                }
            }
        }
    }

    /**
     * Closes the current dialog if one exists
     */
    fun closeCurrentDialog() {
        SwingUtilities.invokeLater {
            currentDialog?.let {
                if (it.isShowing) {
                    it.close(DialogWrapper.CANCEL_EXIT_CODE)
                }
                currentDialog = null
            }
        }
    }

    /**
     * Checks if a dialog is currently showing
     */
    fun hasActiveDialog(): Boolean {
        return currentDialog?.isShowing == true
    }
}