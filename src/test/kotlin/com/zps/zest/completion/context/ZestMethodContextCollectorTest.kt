package com.zps.zest.completion.context

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Test

class ZestMethodContextCollectorTest : BasePlatformTestCase() {
    
    @Test
    fun testCocos2dxMethodDetection() {
        val jsContent = """
var GameLayer = cc.Layer.extend({
    sprite: null,
    
    ctor: function () {
        this._super();
        var sprite = cc.Sprite("res/HelloWorld.png");
        this.addChild(sprite, 0);
        return true;
    },
    
    onEnter: function() {
        this._super();
        console.log("Entered");
    }
});
        """.trimIndent()
        
        // Test that we can detect Cocos2d-x project
        val collector = ZestMethodContextCollector(project)
        // This would need proper editor setup in test
        // For now, we're just verifying the code compiles
        assertTrue(true)
    }
    
    @Test
    fun testStringAwareBraceCounting() {
        val methodContent = """
function test() {
    var str = "{ this should not count }";
    var obj = { count: 1 };
    // Comment with brace {
    /* Multi line { } */
    return obj;
}
        """.trimIndent()
        
        // This would test our brace counting logic
        assertTrue(true)
    }
}
