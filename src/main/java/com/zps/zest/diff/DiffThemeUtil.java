package com.zps.zest.diff;

import com.intellij.ui.JBColor;

import java.awt.*;

/**
 * Utility class for managing theme colors in the diff viewer
 */
public class DiffThemeUtil {

    /**
     * GitHub light theme colors - with stronger contrast for add/delete
     */
    private static final class GithubLight {
        static final Color BACKGROUND = new Color(0xFFFFFF);
        static final Color TEXT = new Color(0x24292E);
        static final Color LINE_NUMBER_BG = new Color(0xF6F8FA);
        static final Color LINE_NUMBER_TEXT = new Color(0x6E7781);
        static final Color BORDER = new Color(0xD0D7DE);
        static final Color HEADER_BG = new Color(0xF1F8FF);
        static final Color ADDITION_BG = new Color(0xDCFFE4);  // Brighter green
        static final Color ADDITION_BORDER = new Color(0x34D058);  // More saturated
        static final Color DELETION_BG = new Color(0xFFDCE0);  // Brighter red
        static final Color DELETION_BORDER = new Color(0xFF5555);  // More saturated
        static final Color HUNK_HEADER_BG = new Color(0xF6F8FA);
        static final Color HUNK_HEADER_TEXT = new Color(0x6E7781);
    }

    /**
     * GitHub dark theme colors - with stronger contrast for add/delete
     */
    private static final class GithubDark {
        static final Color BACKGROUND = new Color(0x0D1117);
        static final Color TEXT = new Color(0xC9D1D9);
        static final Color LINE_NUMBER_BG = new Color(0x161B22);
        static final Color LINE_NUMBER_TEXT = new Color(0x8B949E);
        static final Color BORDER = new Color(0x30363D);
        static final Color HEADER_BG = new Color(0x161B22);
        static final Color ADDITION_BG = new Color(0x0F5323);  // More saturated green
        static final Color ADDITION_BORDER = new Color(0x2EA043);
        static final Color DELETION_BG = new Color(0x5C1624);  // More saturated red
        static final Color DELETION_BORDER = new Color(0xF85149);
        static final Color HUNK_HEADER_BG = new Color(0x161B22);
        static final Color HUNK_HEADER_TEXT = new Color(0x8B949E);
    }

    /**
     * Returns the appropriate background color based on the current theme
     */
    public static Color getBackground() {
        return JBColor.lazy(() -> JBColor.isBright() ? GithubLight.BACKGROUND : GithubDark.BACKGROUND);
    }

    /**
     * Returns the appropriate text color based on the current theme
     */
    public static Color getText() {
        return JBColor.lazy(() -> JBColor.isBright() ? GithubLight.TEXT : GithubDark.TEXT);
    }

    /**
     * Returns the appropriate line number background color based on the current theme
     */
    public static Color getLineNumberBackground() {
        return JBColor.lazy(() -> JBColor.isBright() ? GithubLight.LINE_NUMBER_BG : GithubDark.LINE_NUMBER_BG);
    }

    /**
     * Returns the appropriate line number text color based on the current theme
     */
    public static Color getLineNumberText() {
        return JBColor.lazy(() -> JBColor.isBright() ? GithubLight.LINE_NUMBER_TEXT : GithubDark.LINE_NUMBER_TEXT);
    }

    /**
     * Returns the appropriate border color based on the current theme
     */
    public static Color getBorder() {
        return JBColor.lazy(() -> JBColor.isBright() ? GithubLight.BORDER : GithubDark.BORDER);
    }

    /**
     * Returns the appropriate header background color based on the current theme
     */
    public static Color getHeaderBackground() {
        return JBColor.lazy(() -> JBColor.isBright() ? GithubLight.HEADER_BG : GithubDark.HEADER_BG);
    }

    /**
     * Returns the appropriate addition background color based on the current theme
     */
    public static Color getAdditionBackground() {
        return JBColor.lazy(() -> JBColor.isBright() ? GithubLight.ADDITION_BG : GithubDark.ADDITION_BG);
    }

    /**
     * Returns the appropriate addition border color based on the current theme
     */
    public static Color getAdditionBorder() {
        return JBColor.lazy(() -> JBColor.isBright() ? GithubLight.ADDITION_BORDER : GithubDark.ADDITION_BORDER);
    }

    /**
     * Returns the appropriate deletion background color based on the current theme
     */
    public static Color getDeletionBackground() {
        return JBColor.lazy(() -> JBColor.isBright() ? GithubLight.DELETION_BG : GithubDark.DELETION_BG);
    }

    /**
     * Returns the appropriate deletion border color based on the current theme
     */
    public static Color getDeletionBorder() {
        return JBColor.lazy(() -> JBColor.isBright() ? GithubLight.DELETION_BORDER : GithubDark.DELETION_BORDER);
    }

    /**
     * Returns the appropriate hunk header background color based on the current theme
     */
    public static Color getHunkHeaderBackground() {
        return JBColor.lazy(() -> JBColor.isBright() ? GithubLight.HUNK_HEADER_BG : GithubDark.HUNK_HEADER_BG);
    }

    /**
     * Returns the appropriate hunk header text color based on the current theme
     */
    public static Color getHunkHeaderText() {
        return JBColor.lazy(() -> JBColor.isBright() ? GithubLight.HUNK_HEADER_TEXT : GithubDark.HUNK_HEADER_TEXT);
    }

    /**
     * Get CSS for GitHub-style diff based on current theme
     */
    public static String getGithubStyleCss() {
        boolean isLight = JBColor.isBright();

        return "body { " +
                "  font-family: SFMono-Regular, Consolas, 'Liberation Mono', Menlo, monospace; " +
                "  font-size: 12px; " +
                "  line-height: 1.5; " +
                "  margin: 0; " +
                "  padding: 0; " +
                "  background-color: " + colorToHex(isLight ? GithubLight.BACKGROUND : GithubDark.BACKGROUND) + "; " +
                "  color: " + colorToHex(isLight ? GithubLight.TEXT : GithubDark.TEXT) + "; " +
                "} " +
                ".diff-table { border-collapse: collapse; width: 100%; table-layout: fixed; } " +
                ".diff-table td { padding: 0; } " +
                ".line-number { " +
                "  width: 50px; " +
                "  padding: 0 10px; " +
                "  text-align: right; " +
                "  user-select: none; " +
                "  color: " + colorToHex(isLight ? GithubLight.LINE_NUMBER_TEXT : GithubDark.LINE_NUMBER_TEXT) + "; " +
                "  background-color: " + colorToHex(isLight ? GithubLight.LINE_NUMBER_BG : GithubDark.LINE_NUMBER_BG) + "; " +
                "  border-right: 1px solid " + colorToHex(isLight ? GithubLight.BORDER : GithubDark.BORDER) + "; " +
                "} " +
                ".line-content { padding: 0 10px; white-space: pre; overflow: visible; } " +
                ".diff-line.addition { background-color: " + colorToHex(isLight ? GithubLight.ADDITION_BG : GithubDark.ADDITION_BG) + "; } " +
                ".addition .line-content, .line-content.addition-content { " +
                "  background-color: " + colorToHex(isLight ? GithubLight.ADDITION_BG : GithubDark.ADDITION_BG) + "; " +
                "  border-left: 1px solid " + colorToHex(isLight ? GithubLight.ADDITION_BORDER : GithubDark.ADDITION_BORDER) + "; " +
                "} " +
                ".diff-line.deletion { background-color: " + colorToHex(isLight ? GithubLight.DELETION_BG : GithubDark.DELETION_BG) + "; } " +
                ".deletion .line-content, .line-content.deletion-content { " +
                "  background-color: " + colorToHex(isLight ? GithubLight.DELETION_BG : GithubDark.DELETION_BG) + "; " +
                "  border-left: 1px solid " + colorToHex(isLight ? GithubLight.DELETION_BORDER : GithubDark.DELETION_BORDER) + "; " +
                "} " +
                ".diff-hunk-header { " +
                "  background-color: " + colorToHex(isLight ? GithubLight.HUNK_HEADER_BG : GithubDark.HUNK_HEADER_BG) + "; " +
                "  color: " + colorToHex(isLight ? GithubLight.HUNK_HEADER_TEXT : GithubDark.HUNK_HEADER_TEXT) + "; " +
                "  padding: 2px 10px; " +
                "  border-top: 1px solid " + colorToHex(isLight ? GithubLight.BORDER : GithubDark.BORDER) + "; " +
                "  border-bottom: 1px solid " + colorToHex(isLight ? GithubLight.BORDER : GithubDark.BORDER) + "; " +
                "}";
    }

    /**
     * Convert a Color to hex string
     */
    private static String colorToHex(Color color) {
        return String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
    }
}