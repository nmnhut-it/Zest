package com.zps.zest.autocomplete.context;

public class LocalContext {
    public final String beforeCursor;
    public final String afterCursor;
    
    public LocalContext(String beforeCursor, String afterCursor) {
        this.beforeCursor = beforeCursor;
        this.afterCursor = afterCursor;
    }
}