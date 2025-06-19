package com.zps.zest;

import com.intellij.openapi.util.IconLoader;

import javax.swing.*;

/**
 * Contains icons used by the Zest plugin.
 * Following IntelliJ Platform guidelines for icon management.
 */
public class ZestIcons {
    /** Main plugin icon */
    public static final Icon ZEST = IconLoader.getIcon("/icons/zest.svg", ZestIcons.class);
    
    /** Test writing icon */
    public static final Icon TEST_WRITING = IconLoader.getIcon("/icons/testWriting.svg", ZestIcons.class);
    /** Test writing icon (16px) for menu items */
    public static final Icon TEST_WRITING_16 = IconLoader.getIcon("/icons/testWriting16.svg", ZestIcons.class);
    
    /** Refactoring icon */
    public static final Icon REFACTORING = IconLoader.getIcon("/icons/refactoring.svg", ZestIcons.class);
    /** Refactoring icon (16px) for menu items */
    public static final Icon REFACTORING_16 = IconLoader.getIcon("/icons/refactoring16.svg", ZestIcons.class);
    
    /** ZPS Chat icon */
    public static final Icon ZPS_CHAT = IconLoader.getIcon("/icons/zpsChat.svg", ZestIcons.class);
    /** ZPS Chat icon (16px) for menu items */
    public static final Icon ZPS_CHAT_16 = IconLoader.getIcon("/icons/zpsChat16.svg", ZestIcons.class);
}
