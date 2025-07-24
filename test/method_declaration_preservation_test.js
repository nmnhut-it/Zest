// Test file to verify method declaration preservation

// Test 1: Simple function
function simpleFunction(param1, param2) {
    // TODO: implement this
    return null;
}

// Test 2: Variable function
const myFunction = function(x, y) {
    // TODO: add implementation
    console.log("Not implemented");
};

// Test 3: Arrow function
const arrowFunc = (a, b) => {
    // TODO: implement logic
    return a + b;
};

// Test 4: Object method
const myObject = {
    calculate: function(value) {
        // TODO: implement calculation
        return 0;
    },
    
    process: (data) => {
        // TODO: process data
        console.log(data);
    }
};

// Test 5: Class method
class MyClass {
    constructor(value) {
        this.value = value;
    }
    
    compute(factor) {
        // TODO: implement computation
        return this.value;
    }
    
    async fetchData(url) {
        // TODO: fetch and process data
        return null;
    }
}

// Test 6: Object property method
MyApp.utils.formatData = function(rawData) {
    // TODO: format the data properly
    return rawData;
};

// Test 7: TypeScript with return type (in .ts file this would work)
// function getConfig(): ConfigType {
//     // TODO: return proper config
//     return {};
// }

// Test 8: Cocos2d-x style
var GameLayer = cc.Layer.extend({
    sprites: [],
    
    ctor: function() {
        this._super();
        // TODO: initialize game layer
        return true;
    },
    
    update: function(dt) {
        // TODO: implement game update logic
    }
});

// Instructions:
// 1. Place cursor inside any method body (after the opening brace)
// 2. Run "Trigger Block Rewrite"
// 3. The method DECLARATION should be preserved
// 4. Only the method BODY should be replaced
// 5. Check that the signature (function name, parameters, return type) remains unchanged
