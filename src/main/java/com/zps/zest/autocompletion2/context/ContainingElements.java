package com.zps.zest.autocompletion2.context;

import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiCodeBlock;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiStatement; /**
 * Information about containing PSI elements.
 */
public class ContainingElements {
    public final PsiMethod method;
    public final PsiClass containingClass;
    public final PsiCodeBlock codeBlock;
    public final PsiStatement statement;
    
    public ContainingElements(PsiMethod method, PsiClass containingClass, 
                            PsiCodeBlock codeBlock, PsiStatement statement) {
        this.method = method;
        this.containingClass = containingClass;
        this.codeBlock = codeBlock;
        this.statement = statement;
    }
}
