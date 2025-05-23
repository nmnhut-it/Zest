package com.zps.zest.autocomplete.context;

import java.util.List;

public class MethodSignature {
    public final String name;
    public final String returnType;
    public final List<String> parameterTypes;
    public final boolean isStatic;
    
    public MethodSignature(String name, String returnType, List<String> parameterTypes, boolean isStatic) {
        this.name = name;
        this.returnType = returnType;
        this.parameterTypes = parameterTypes;
        this.isStatic = isStatic;
    }
}
