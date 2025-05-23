package com.zps.zest.autocomplete.context;

// Info classes
public class FieldInfo {
    public final String name;
    public final String type;
    public final boolean isStatic;
    public final boolean isFinal;
    
    public FieldInfo(String name, String type, boolean isStatic, boolean isFinal) {
        this.name = name;
        this.type = type;
        this.isStatic = isStatic;
        this.isFinal = isFinal;
    }
}
