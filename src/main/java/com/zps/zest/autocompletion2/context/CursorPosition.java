package com.zps.zest.autocompletion2.context;

/**
 * Cursor position information.
 */
public class CursorPosition {
    public final int offset;
    public final int lineNumber;
    public final int columnInLine;
    public final String linePrefix;
    public final String lineSuffix;
    public final String fullLine;
    public final String indentation;
    
    public CursorPosition(int offset, int lineNumber, int columnInLine,
                         String linePrefix, String lineSuffix, String fullLine, String indentation) {
        this.offset = offset;
        this.lineNumber = lineNumber;
        this.columnInLine = columnInLine;
        this.linePrefix = linePrefix;
        this.lineSuffix = lineSuffix;
        this.fullLine = fullLine;
        this.indentation = indentation;
    }
}
