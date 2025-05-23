package com.zps.zest.autocomplete.context;

import java.util.List; /**
 * Local scope information.
 */
public class LocalScope {
    public final List<VariableInfo> availableVariables;
    public final List<String> availableTypes;
    
    public LocalScope(List<VariableInfo> availableVariables, List<String> availableTypes) {
        this.availableVariables = availableVariables;
        this.availableTypes = availableTypes;
    }
}
