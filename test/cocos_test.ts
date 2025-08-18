// TypeScript Cocos2d-x test file

interface GameConfig {
    width: number;
    height: number;
}

class GameLayer extends cc.Layer {
    private sprite: cc.Sprite;
    private config: GameConfig;
    
    constructor() {
        super();
        this.config = { width: 800, height: 600 };
    }
    
    // Object literal style method (Cocos2d-x pattern)
    ctor: function() {
        this._super();
        const size = cc.winSize;
        
        // Using direct constructor
        this.sprite = cc.Sprite("res/HelloWorld.png");
        this.sprite.x = size.width / 2;
        this.sprite.y = size.height / 2;
        this.addChild(this.sprite, 0);
        
        return true;
    },
    
    // Modern TypeScript method
    public initializeGame(): void {
        const listener = cc.EventListener.create({
            event: cc.EventListener.TOUCH_ONE_BY_ONE,
            onTouchBegan: (touch: cc.Touch, event: cc.Event): boolean => {
                console.log("Touch began at:", touch.getLocation());
                return true;
            }
        });
        
        cc.eventManager.addListener(listener, this);
    }
    
    // Arrow function property
    processData = (data: any[]): void => {
        data.forEach(item => {
            if (item.type === "sprite") {
                const sprite = cc.Sprite(item.texture);
                sprite.setPosition(item.x, item.y);
                this.addChild(sprite);
            }
        });
    }
    
    // Async method
    async loadResources(): Promise<void> {
        return new Promise((resolve, reject) => {
            cc.loader.loadRes("config.json", (err, data) => {
                if (err) {
                    reject(err);
                } else {
                    this.config = data;
                    resolve();
                }
            });
        });
    }
}

// Extend pattern with TypeScript
const GameScene = cc.Scene.extend({
    onEnter: function(): void {
        this._super();
        const layer = new GameLayer();
        this.addChild(layer);
    }
});
