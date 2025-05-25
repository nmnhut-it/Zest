package com.zps.zest.autocompletion2.context;

public class VariableInfo {
    public enum Scope { LOCAL, PARAMETER, FIELD }

    public final String name;
    public final String type;
    public final Scope scope;

    public VariableInfo(String name, String type, Scope scope) {
        this.name = name;
        this.type = type;
        this.scope = scope;
    }
}
