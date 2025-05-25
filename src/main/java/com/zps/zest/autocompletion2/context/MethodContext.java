package com.zps.zest.autocompletion2.context;

import java.util.List; /**
 * Method-level context information.
 */
public class MethodContext {
    public final String methodName;
    public final boolean isStatic;
    public final boolean isPrivate;
    public final String returnType;
    public final List<ParameterInfo> parameters;
    public final List<VariableInfo> localVariables;
    
    public MethodContext(String methodName, boolean isStatic, boolean isPrivate,
                        String returnType, List<ParameterInfo> parameters,
                        List<VariableInfo> localVariables) {
        this.methodName = methodName;
        this.isStatic = isStatic;
        this.isPrivate = isPrivate;
        this.returnType = returnType;
        this.parameters = parameters;
        this.localVariables = localVariables;
    }
}
