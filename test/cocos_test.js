// Test file for Cocos2d-x method extraction

var GameLayer = cc.Layer.extend({
    sprite: null,
    
    ctor: function () {
        this._super();
        
        var size = cc.winSize;
        
        // Test various node creation patterns
        var sprite = cc.Sprite("res/HelloWorld.png");
        sprite.x = size.width / 2;
        sprite.y = size.height / 2;
        this.addChild(sprite, 0);
        
        return true;
    },
    
    onEnter: function() {
        this._super();
        
        // Test event listener setup
        var listener = cc.EventListener.create({
            event: cc.EventListener.TOUCH_ONE_BY_ONE,
            onTouchBegan: function(touch, event) {
                return true;
            }
        });
        
        cc.eventManager.addListener(listener, this);
    },
    
    // Test method with string containing braces
    testStringMethod: function() {
        var jsonString = '{"name": "test", "value": 123}';
        var obj = JSON.parse(jsonString);
        
        // Comment with unmatched brace {
        var result = this.processData(obj);
        
        /* Multi-line comment
           with some braces { } 
           that should be ignored */
        
        return result;
    },
    
    // Arrow function method (modern syntax)
    modernMethod: (param) => {
        console.log("Modern arrow function", param);
        return param * 2;
    },
    
    // Complex nested method
    complexMethod: function(data) {
        var self = this;
        
        var processItem = function(item) {
            if (item.type === "sprite") {
                var sprite = cc.Sprite(item.texture);
                sprite.setPosition(item.x, item.y);
                
                // Nested object literal
                var config = {
                    actions: {
                        move: function() {
                            return cc.MoveTo(1, cc.p(100, 100));
                        },
                        scale: function() {
                            return cc.ScaleTo(1, 2);
                        }
                    }
                };
                
                sprite.runAction(config.actions.move());
                return sprite;
            }
        };
        
        data.forEach(function(item) {
            self.addChild(processItem(item));
        });
    },
    
    update: function(dt) {
        // Game update logic
        this.sprite.rotation += dt * 60;
    }
});

var GameScene = cc.Scene.extend({
    onEnter: function () {
        this._super();
        var layer = new GameLayer();
        this.addChild(layer);
    }
});
