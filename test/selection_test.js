// Test file for selected text feature
// Users can select any portion of code and it will be treated as the method to rewrite

function normalFunction() {
    console.log("This is a normal function");
}

// Try selecting just this block of code:
const selectedCode = {
    // This could be any code block
    x: 10,
    y: 20,
    calculate: function() {
        return this.x + this.y;
    }
};

// Or select just a portion of a method:
function partialMethod() {
    const a = 1;
    const b = 2;
    
    // Select from here...
    if (a > b) {
        console.log("a is greater");
    } else {
        console.log("b is greater or equal");
    }
    // ...to here
    
    return a + b;
}

// Select an anonymous function
setTimeout(function() {
    console.log("Timer fired");
}, 1000);

// Select just the implementation
class Example {
    method() {
        // Select just the body
        const result = [];
        for (let i = 0; i < 10; i++) {
            result.push(i * 2);
        }
        return result;
    }
}

// Instructions:
// 1. Select any portion of code in this file
// 2. Run the "Trigger Block Rewrite" action
// 3. The selected text will be used as the method content
// 4. No need to detect method boundaries - the selection IS the method
