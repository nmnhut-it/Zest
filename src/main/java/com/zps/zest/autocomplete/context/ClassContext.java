package com.zps.zest.autocomplete.context;

import java.util.List; /**
 * Class-level context information.
 */
public class ClassContext {
    public final String className;
    public final boolean isInterface;
    public final boolean isAbstract;
    public final String superClass;
    public final List<String> interfaces;
    public final List<FieldInfo> fields;
    public final List<MethodSignature> methods;
    public final List<String> relatedClasses;
    
    public ClassContext(String className, boolean isInterface, boolean isAbstract,
                       String superClass, List<String> interfaces,
                       List<FieldInfo> fields, List<MethodSignature> methods,
                       List<String> relatedClasses) {
        this.className = className;
        this.isInterface = isInterface;
        this.isAbstract = isAbstract;
        this.superClass = superClass;
        this.interfaces = interfaces;
        this.fields = fields;
        this.methods = methods;
        this.relatedClasses = relatedClasses;
    }
}
