package com.zps.zest.autocompletion2.context;

import java.util.List; /**
 * Rich semantic information about the code context.
 */
public class SemanticInfo {
    public final String packageName;
    public final List<String> imports;
    public final ClassContext classContext;
    public final MethodContext methodContext;
    public final LocalScope localScope;
    
    public SemanticInfo(String packageName, List<String> imports, ClassContext classContext,
                        MethodContext methodContext, LocalScope localScope) {
        this.packageName = packageName;
        this.imports = imports;
        this.classContext = classContext;
        this.methodContext = methodContext;
        this.localScope = localScope;
    }
}
