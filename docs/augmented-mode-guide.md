# Augmented Mode Guide

## Overview

Augmented Mode features an intelligent agent that not only discovers relevant code context but can also **autonomously ask and answer its own questions** to better understand your codebase. The agent uses the LLM service to think through problems, generate clarifying questions, and explore code relationships on its own.

**Key Innovation**: The agent can call the LLM service itself to generate smarter questions and perform autonomous exploration, building a deeper understanding before responding to you.

## How the Autonomous Agent Works

### 1. Intelligent Question Generation
When your query is ambiguous, the agent uses the LLM to generate targeted clarifying questions:

```
User: "Fix the payment issue"

Agent calls LLM with:
- Detected patterns: payment
- Detected actions: fix
- Ambiguity: No specific error mentioned

LLM generates smart questions:
- What specific error or symptom are you experiencing with payments?
- Is this related to payment processing, validation, or UI display?
- Do you have error logs or a specific transaction ID?
```

### 2. Autonomous Exploration
For complex queries, the agent can explore on its own:

```
User: "How does authentication work?"

Agent autonomously:
1. Searches for auth-related components
2. Asks itself: "What authentication method is used?"
3. Finds SecurityConfig and explores it
4. Asks itself: "How are tokens validated?"
5. Discovers TokenValidator and analyzes flow
6. Builds comprehensive understanding
```

### 3. Self-Reflective Analysis
The agent analyzes your query for ambiguity and generates clarifying questions to better understand your needs:

- **Ambiguous Patterns**: "Are you looking for a specific type of component (controller, service), or all of them?"
- **Missing Context**: "What specific feature would you like to implement?"
- **Scope Clarification**: "Should I include test files in the search?"
- **Relationship Direction**: "Do you want to see incoming or outgoing relationships?"

### 2. Pattern Detection
When you ask a question in Augmented Mode, the system analyzes your query for:

1. **Code Patterns**:
   - Controllers (`*Controller`, `@RestController`, endpoints)
   - Services (`*Service`, `*ServiceImpl`, business logic)
   - Handlers (`*Handler`, `EventHandler`, event processing)
   - Commands (`*Command`, `*Cmd`, operations)
   - Repositories (`*Repository`, `*Dao`, data access)
   - DTOs (`*DTO`, `*Request`, `*Response`)
   - Configurations (`*Config`, settings)
   - Utilities (`*Util`, `*Helper`)
   - Tests (`*Test`, test cases)

2. **Action Intent**:
   - Implement (create, add, build)
   - Fix (repair, debug, resolve)
   - Refactor (improve, enhance, optimize)
   - Test (verify, validate)
   - Understand (explain, show, describe)

3. **Identifier Detection**:
   - Automatically detects CamelCase and PascalCase identifiers in your query

### 3. Context Augmentation
Based on the detected patterns, Augmented Mode:

1. **Searches Multiple Indices**:
   - **Name Index**: Fast exact and fuzzy matching of identifiers
   - **Semantic Index**: Vector-based similarity search
   - **Structural Index**: Code relationships (calls, extends, implements)

2. **Provides Rich Context**:
   - Current IDE context (open files, cursor position)
   - Relevant code components grouped by type
   - Code relationships when needed
   - Pattern-specific guidance

### 4. Exploration Suggestions
The agent provides proactive suggestions to help you explore further:
- Related components to investigate
- Architectural patterns to consider
- Potential issues to check
- Best practices for the detected patterns

## Agent Conversation Example

### User: "I need to understand the payment processing in our system"

**Agent Response:**
```
### Clarifying Questions ###
To better understand your request and find the most relevant code, consider these aspects:

1. Are you looking for a specific type of component (controller, service, repository), or all of them?
2. Do you want to see incoming or outgoing relationships?
3. Are you interested in how services interact with repositories?

### Current IDE Context ###
Current file: OrderService.java
Current class: com.example.service.OrderService

### Relevant Code Found ###

#### Controllers/Endpoints ####
- **PaymentController** (src/main/java/com/example/controller/PaymentController.java)
  REST controller handling payment endpoints with methods for processing, refund, and status checks...

#### Services/Business Logic ####
- **PaymentService** (src/main/java/com/example/service/PaymentService.java)
  Main payment processing service with transaction management and validation...

### Exploration Suggestions ###
Based on the code structure, you might also want to explore:
- Check the corresponding service layer implementations
- Review the request/response DTOs used by these controllers
- Look at the repository/DAO layer for data access patterns
- Check for custom query methods or specifications

### Pattern-Specific Guidance ###
- Controllers handle HTTP requests. Check @RequestMapping annotations
- Look for @PathVariable, @RequestParam, and @RequestBody usage
- Services contain business logic. Look for @Service annotations
- Check for @Transactional boundaries and rollback conditions
```

### User: "Show me how PaymentService handles errors"

**Agent Response:**
```
### Current IDE Context ###
Current file: OrderService.java
Exploring: PaymentService error handling

### Relevant Code Found ###
[Specific error handling code from PaymentService]

### Next Steps ###
Based on our exploration so far, you might want to:
- Should we look at the tests for PaymentService?
- Would you like to see what calls PaymentService?
- Should we explore the error handling in PaymentController?
```

## Exploration Strategies

The agent adapts its exploration strategy based on your needs:

### 1. **Breadth-First Exploration**
- **When**: You want an overview or architecture understanding
- **Keywords**: "everything", "all", "overview", "architecture"
- **Agent Behavior**: Maps component types, high-level relationships, entry points

### 2. **Depth-First Exploration**
- **When**: You need detailed understanding of specific components
- **Keywords**: "deep", "detail", "specific", "exactly"
- **Agent Behavior**: Examines interfaces, implementations, dependencies, tests

### 3. **Relationship-Focused**
- **When**: Understanding connections between components
- **Keywords**: "connect", "relation", "depend", "use", "call"
- **Agent Behavior**: Maps dependencies, traces data flow, finds integration points

### 4. **Pattern-Based**
- **When**: Finding similar implementations or patterns
- **Keywords**: "similar", "pattern", "like", "example"
- **Agent Behavior**: Identifies patterns, finds similar code, compares implementations

### 5. **Problem-Solving**
- **When**: Debugging or fixing issues
- **Keywords**: "fix", "error", "problem", "issue", "debug"
- **Agent Behavior**: Analyzes issues, checks recent changes, suggests fixes

## Usage Examples

### Example 1: Finding Controllers
**Query**: "Show me all payment controllers"

**Augmentation Process**:
- Detects pattern: `controller`
- Detects keyword: `payment`
- Searches for classes ending with `Controller` containing "payment"
- Groups results by type (Controllers, Services, etc.)
- Includes relationships if asking to "understand"

### Example 2: Fixing a Service
**Query**: "Fix the user authentication service handler"

**Augmentation Process**:
- Detects patterns: `service`, `handler`
- Detects action: `fix`
- Detects keywords: `user`, `authentication`
- Finds relevant services and handlers
- Includes error-checking guidance
- Shows related components

### Example 3: Implementing New Feature
**Query**: "Implement a new OrderProcessor command"

**Augmentation Process**:
- Detects pattern: `command`
- Detects action: `implement`
- Detects identifier: `OrderProcessor`
- Finds existing command patterns
- Shows similar implementations
- Provides implementation guidance

## Comparison with Other Modes

| Feature | Neutral Mode | Dev Mode | Project Mode | Augmented Mode | Agent Mode |
|---------|--------------|----------|--------------|----------------|------------|
| System Prompt | None | Basic dev prompt | RAG context | Smart context | Full agent prompt |
| Context Size | None | Small | Large (entire project) | Medium (targeted) | Small + tools |
| Query Processing | Direct | Direct | With RAG | Pattern-based search | Tool-based |
| Best For | General chat | Coding help | Project Q&A | Targeted code questions | Code modifications |
| Performance | Fastest | Fast | Slower (loads all) | Fast (selective) | Varies |
| Accuracy | General | Good | Very good | Excellent for patterns | Excellent with tools |

## When to Use Augmented Mode

### ✅ Use Augmented Mode When:
- You need to find specific types of components (controllers, services, etc.)
- You're asking about code patterns in your project
- You want relevant context without loading the entire project
- You need to understand relationships between components
- You're looking for similar implementations

### ❌ Use Other Modes When:
- **Neutral Mode**: General questions unrelated to your project
- **Dev Mode**: General programming questions
- **Project Mode**: Need comprehensive project knowledge or exploring unfamiliar codebase
- **Agent Mode**: Need to modify code or use IDE tools

## Technical Details

### Search Strategy
1. **Hybrid Search**: Combines multiple search strategies
   - Name-based search (2x weight boost)
   - Semantic search (1.5x weight)
   - Structural search (1x weight)

2. **Pattern Boosting**: Results matching detected patterns get 50% score boost

3. **Smart Grouping**: Results are grouped by component type for clarity

### Performance Optimization
- Asynchronous query augmentation
- Cached embeddings for faster search
- Limited to top 20 results
- Timeout after 5 seconds

## Testing the Autonomous Agent

### Using the Test Action
1. Right-click in the editor → Zest → Test Autonomous Agent
2. Enter a query like "How does the payment system work?"
3. Watch as the agent explores autonomously
4. Review the complete exploration log

### What Happens Behind the Scenes
1. **Initial Analysis**: Agent analyzes your query and generates exploration questions
2. **Autonomous Loop**: For each question:
   - Searches for relevant code using augmentation
   - Calls LLM to understand the code
   - Extracts new questions and insights
   - Builds knowledge incrementally
3. **Summary Generation**: Creates a comprehensive summary of findings

### Example Autonomous Exploration Log

```markdown
# Autonomous Code Exploration Session

## Initial Query: How does the payment processing work in our system?

## Initial Analysis
Analyzing the payment processing system...

Topics identified:
- Payment controllers and endpoints
- Payment service layer
- Payment validation
- Transaction handling
- Payment gateways

Generated questions:
- Question: What payment methods are supported by the system?
- Question: How are payment transactions validated before processing?
- Question: What happens when a payment fails?
- Question: How are payment events handled and logged?

## Exploration Round 1

### Question: What payment methods are supported by the system?

#### Answer:
Found PaymentMethod enum in com.example.payment.model:
- CREDIT_CARD
- DEBIT_CARD  
- PAYPAL
- BANK_TRANSFER
- WALLET

PaymentController has endpoints for each method type with specific validators.

#### New Questions Discovered:
- How does the PaymentValidator work for different payment methods?
- What are the integration points with external payment gateways?

[... continues for several rounds ...]

## Exploration Summary
The payment system uses a layered architecture:
1. **Controllers**: Handle HTTP requests, delegate to services
2. **Services**: Orchestrate payment flow, validation, and gateway calls
3. **Validators**: Pluggable validation strategies per payment method
4. **Gateways**: Abstract interfaces for external payment providers
5. **Event System**: Async handling of payment events and failures

Key patterns: Strategy (validators), Template Method (payment flow), Observer (events)

Recommendations:
- Add circuit breaker for gateway calls
- Implement idempotency for payment requests
- Consider adding payment status webhooks
```

## Configuration

Currently, Augmented Mode uses default settings. Future versions may allow customization of:
- Pattern detection rules
- Search weights
- Result limits
- Context formatting

## Troubleshooting

### No Context Added
**Problem**: Query doesn't get augmented with context
**Solutions**:
1. Ensure project is indexed (`Zest > Index Project for Function-Level Search`)
2. Check that query contains recognizable patterns
3. Verify files are in the project scope

### Slow Response
**Problem**: Augmentation takes too long
**Solutions**:
1. Reduce project size or exclude large directories
2. Re-index project if index is corrupted
3. Check IDE memory settings

### Irrelevant Results
**Problem**: Context includes unrelated code
**Solutions**:
1. Use more specific keywords in query
2. Include class/method names if known
3. Specify the type of component you're looking for

## Advanced Usage

### Combining Patterns
You can reference multiple patterns in one query:
- "Show me how controllers call services for user authentication"
- "Find all DTOs used by payment repositories"

### Relationship Queries
Use relationship keywords to discover connections:
- "What calls the PaymentService?"
- "Show classes that extend BaseController"
- "Find implementations of UserRepository"

### Context-Aware Questions
Augmented Mode considers your current IDE context:
- "What services does this controller use?" (when cursor is in a controller)
- "Show related tests" (when in a source file)

## Future Enhancements

Planned improvements for Augmented Mode:
1. **Learning**: Adapt pattern detection based on project conventions
2. **Custom Patterns**: Define project-specific patterns
3. **Query Templates**: Save and reuse common query patterns
4. **Visual Indicators**: Show detected patterns in the UI
5. **Export Context**: Save augmented context for documentation

## API Integration

Developers can use the QueryAugmentationService programmatically:

```java
QueryAugmentationService service = project.getService(QueryAugmentationService.class);
String augmentedContext = service.augmentQuery("find all payment handlers");
```

This enables custom tools and plugins to leverage the augmentation capabilities.
