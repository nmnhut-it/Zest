// Test file for object property method patterns

// 1. Simple object property assignment
MyApp.init = function() {
    console.log("Initializing app");
    return true;
};

// 2. Nested object property assignment
MyApp.utils.logger = function(message) {
    console.log("[LOG]:", message);
};

// 3. Deep nested property
MyApp.modules.auth.login = function(username, password) {
    // Authentication logic
    return authenticate(username, password);
};

// 4. Object property with async function
MyApp.api.fetchData = async function(url) {
    const response = await fetch(url);
    return response.json();
};

// 5. Object property with arrow function
MyApp.helpers.calculate = (x, y) => {
    return x * y;
};

// 6. Object property with async arrow
MyApp.data.process = async (items) => {
    for (const item of items) {
        await processItem(item);
    }
};

// 7. Prototype method assignment
MyClass.prototype.method = function() {
    return this.value;
};

// 8. Static method assignment
MyClass.static.create = function(options) {
    return new MyClass(options);
};

// 9. Cocos2d-x style with TypeScript return type
interface GameConfig {
    width: number;
    height: number;
}

// TypeScript method with return type
GameLayer.prototype.getConfig = function(): GameConfig {
    return {
        width: 800,
        height: 600
    };
};

// 10. TypeScript arrow with return type
GameLayer.prototype.calculateScore = (points: number[]): number => {
    return points.reduce((sum, p) => sum + p, 0);
};

// 11. Complex multi-line signature with object property
MyApp.complexMethod = function(
    param1: string,
    param2: number,
    param3: boolean
): Promise<void> {
    return new Promise((resolve) => {
        setTimeout(() => {
            console.log(param1, param2, param3);
            resolve();
        }, 1000);
    });
};

// 12. Namespace pattern
var MyNamespace = MyNamespace || {};

MyNamespace.SubModule = {};

MyNamespace.SubModule.doWork = function(task) {
    console.log("Working on:", task);
    return task.completed;
};

// 13. Module pattern with property methods
const MyModule = (function() {
    const private = {};
    
    // Public API
    const api = {};
    
    api.publicMethod = function(data) {
        return processData(data);
    };
    
    api.anotherMethod = async function(id) {
        return await fetchById(id);
    };
    
    return api;
})();

// 14. jQuery style plugin
$.fn.myPlugin = function(options) {
    return this.each(function() {
        // Plugin logic
    });
};

// 15. Node.js module exports
module.exports.handler = function(req, res) {
    res.send("Hello World");
};

exports.middleware = function(req, res, next) {
    console.log("Middleware executed");
    next();
};

// 16. ES6 export with object property
export const api = {
    users: {
        getAll: function() {
            return fetch('/api/users');
        },
        
        getById: async function(id) {
            return await fetch(`/api/users/${id}`);
        }
    }
};

// 17. Window/global assignment
window.MyGlobal = function() {
    console.log("Global function");
};

global.utilities = {
    format: function(str) {
        return str.trim().toLowerCase();
    }
};

// 18. Constructor function property
function MyConstructor() {
    this.value = 0;
}

MyConstructor.prototype.increment = function() {
    this.value++;
    return this.value;
};

// 19. Mixed patterns in one object
const MixedObject = {
    // Regular method
    method1: function() {
        return 1;
    },
    
    // Arrow method
    method2: () => 2,
    
    // Async method
    method3: async function() {
        return await Promise.resolve(3);
    },
    
    // Property that's assigned later
    method4: null
};

// Assign later
MixedObject.method4 = function() {
    return 4;
};

// 20. Dynamic property assignment
const propName = "dynamicMethod";
MyApp[propName] = function() {
    console.log("Dynamic method called");
};
