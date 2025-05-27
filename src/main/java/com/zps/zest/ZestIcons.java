package com.zps.zest;

import com.intellij.openapi.util.IconLoader;

import javax.swing.*;

/**
 * Contains icons used by the Zest plugin.
 */
public class ZestIcons {
    /** Main plugin icon */
    public static final Icon ZEST = IconLoader.getIcon("/icons/zest.svg", ZestIcons.class);
    
    /** Test writing icon */
    public static final Icon TEST_WRITING = IconLoader.getIcon("/icons/testWriting.svg", ZestIcons.class);
    
    /** Refactoring icon */
    public static final Icon REFACTORING = IconLoader.getIcon("/icons/refactoring.svg", ZestIcons.class);
    
    /** ZPS Chat icon */
    public static final Icon ZPS_CHAT = IconLoader.getIcon("/icons/zpsChat.svg", ZestIcons.class);
}
