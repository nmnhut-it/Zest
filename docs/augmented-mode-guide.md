# Augmented Mode Guide

## Overview

Augmented Mode is an intelligent query enhancement feature that automatically discovers and includes relevant code context based on patterns in your questions. Unlike Project Mode which includes the entire project's RAG knowledge base, Augmented Mode performs targeted searches to find exactly what's relevant to your query.

## How It Works

### Pattern Detection
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

### Context Augmentation
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
