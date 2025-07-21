// Test file for various JavaScript function declaration patterns

// 1. Traditional function declaration
function traditionalFunction(param1, param2) {
    console.log("Traditional function");
    return param1 + param2;
}

// 2. Variable function with const
const constFunction = function(x, y) {
    console.log("Const function");
    return x * y;
};

// 3. Variable function with let
let letFunction = function(a, b) {
    console.log("Let function");
    return a - b;
};

// 4. Variable function with var
var varFunction = function(m, n) {
    console.log("Var function");
    return m / n;
};

// 5. Arrow function with const
const arrowFunction = (x, y) => {
    console.log("Arrow function");
    return x + y;
};

// 6. Arrow function with let
let arrowLet = (a, b) => {
    return a * b;
};

// 7. Arrow function with var
var arrowVar = (p, q) => p - q;

// 8. Arrow function without parentheses
const singleParamArrow = x => x * 2;

// 9. Async function
async function asyncFunction(url) {
    const response = await fetch(url);
    return response.json();
}

// 10. Async arrow function
const asyncArrow = async (data) => {
    await processData(data);
    return "done";
};

// 11. Variable async function
const asyncVarFunction = async function(item) {
    return await processItem(item);
};

// 12. Generator function
function* generatorFunction() {
    yield 1;
    yield 2;
    yield 3;
}

// 13. Variable generator function
const generatorVar = function*(max) {
    for (let i = 0; i < max; i++) {
        yield i;
    }
};

// 14. Object with methods
const myObject = {
    // Object method with function keyword
    method1: function(x) {
        return x * 2;
    },
    
    // Object method with arrow function
    method2: (y) => {
        return y + 10;
    },
    
    // Object method with arrow function (no parens)
    method3: z => z - 5,
    
    // ES6 shorthand method
    method4(a, b) {
        return a + b;
    },
    
    // Async object method
    async method5(data) {
        return await processData(data);
    }
};

// 15. Class with methods
class MyClass {
    // Constructor
    constructor(value) {
        this.value = value;
    }
    
    // Regular method
    getValue() {
        return this.value;
    }
    
    // Static method
    static createDefault() {
        return new MyClass(0);
    }
    
    // Async method
    async fetchData() {
        return await fetch('/api/data');
    }
    
    // Method with arrow function property
    arrowMethod = () => {
        return this.value * 2;
    }
}

// 16. Cocos2d-x style extend pattern
var GameLayer = cc.Layer.extend({
    sprite: null,
    
    // Cocos constructor
    ctor: function() {
        this._super();
        return true;
    },
    
    // Lifecycle method
    onEnter: function() {
        this._super();
        console.log("Entered");
    },
    
    // Custom method with trailing comma
    customMethod: function(param) {
        return param * 2;
    },
});

// 17. Nested functions
function outerFunction() {
    console.log("Outer");
    
    // Nested function declaration
    function innerFunction() {
        console.log("Inner");
    }
    
    // Nested variable function
    const innerVar = function() {
        console.log("Inner var");
    };
    
    // Nested arrow
    const innerArrow = () => {
        console.log("Inner arrow");
    };
    
    return {
        inner: innerFunction,
        innerVar: innerVar,
        innerArrow: innerArrow
    };
}

// 18. IIFE (Immediately Invoked Function Expression)
(function() {
    console.log("IIFE");
})();

// 19. Named IIFE
(function namedIIFE() {
    console.log("Named IIFE");
})();

// 20. Complex multiline function signatures
const complexFunction = function(
    param1,
    param2,
    param3
) {
    return param1 + param2 + param3;
};

// 21. Function with default parameters
function withDefaults(a = 1, b = 2) {
    return a + b;
}

// 22. Function with rest parameters
const withRest = function(...args) {
    return args.reduce((sum, val) => sum + val, 0);
};

// 23. Function with destructuring
const withDestructuring = ({x, y}) => {
    return x + y;
};

// 24. Higher-order function returning function
const higherOrder = (multiplier) => {
    return (value) => value * multiplier;
};

// 25. Async generator
async function* asyncGenerator() {
    yield await Promise.resolve(1);
    yield await Promise.resolve(2);
}
