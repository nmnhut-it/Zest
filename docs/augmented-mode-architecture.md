# Augmented Mode Architecture

## System Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        User Query Input                          │
│                    "Show me payment controllers"                 │
└─────────────────────────────────────┬───────────────────────────┘
                                      │
                                      ▼
┌─────────────────────────────────────────────────────────────────┐
│                    Query Augmentation Agent                      │
├─────────────────────────────────────────────────────────────────┤
│  1. Query Analysis                                              │
│     - Pattern Detection (controller, service, handler...)       │
│     - Action Recognition (implement, fix, understand...)        │
│     - Identifier Extraction (PaymentController, UserService...) │
│                                                                 │
│  2. Ambiguity Assessment                                        │
│     - Vague Terms Detection                                     │
│     - Multiple Patterns Check                                   │
│     - Clarity Scoring (0-10)                                    │
│                                                                 │
│  3. Reflective Question Generation                              │
│     - "Are you looking for REST controllers specifically?"      │
│     - "Should I include test files in the search?"             │
│     - "Do you want to see incoming or outgoing relationships?" │
└─────────────────────────────────────┬───────────────────────────┘
                                      │
                ┌─────────────────────┼─────────────────────┐
                │                     │                     │
                ▼                     ▼                     ▼
┌───────────────────────┐ ┌───────────────────┐ ┌───────────────────┐
│    Name Index         │ │  Semantic Index   │ │ Structural Index  │
├───────────────────────┤ ├───────────────────┤ ├───────────────────┤
│ - Exact matching      │ │ - Vector search   │ │ - Relationships   │
│ - Fuzzy matching      │ │ - Similarity      │ │ - Dependencies    │
│ - CamelCase aware     │ │ - Context aware   │ │ - Call graphs     │
│ - Pattern suffixes    │ │ - Embeddings      │ │ - Inheritance     │
└───────────────────────┘ └───────────────────┘ └───────────────────┘
                │                     │                     │
                └─────────────────────┼─────────────────────┘
                                      │
                                      ▼
┌─────────────────────────────────────────────────────────────────┐
│                    Result Processing                             │
├─────────────────────────────────────────────────────────────────┤
│  - Pattern Boosting (1.5x for matching patterns)               │
│  - Score Combination (weighted by index type)                   │
│  - Grouping by Category                                         │
│  - Relationship Enrichment                                       │
└─────────────────────────────────────┬───────────────────────────┘
                                      │
                                      ▼
┌─────────────────────────────────────────────────────────────────┐
│                  Augmented Context Output                        │
├─────────────────────────────────────────────────────────────────┤
│  ### Clarifying Questions ###                                   │
│  1. Are you looking for REST controllers specifically?          │
│                                                                 │
│  ### Current IDE Context ###                                    │
│  Current file: OrderService.java                                │
│                                                                 │
│  ### Relevant Code Found ###                                    │
│  #### Controllers/Endpoints ####                                │
│  - PaymentController (handles payment processing)               │
│  - PaymentWebhookController (handles payment callbacks)         │
│                                                                 │
│  ### Exploration Suggestions ###                                │
│  - Check the PaymentService implementation                      │
│  - Review PaymentDTO request/response objects                   │
│                                                                 │
│  ### Pattern-Specific Guidance ###                              │
│  - Controllers use @RequestMapping for endpoints                │
│  - Check @PathVariable and @RequestBody usage                   │
└─────────────────────────────────────────────────────────────────┘
```

## Data Flow

### 1. JavaScript Interceptor
```javascript
// User types in chat
"Show me payment controllers"
    ↓
// augmentedModeInterceptor.js
window.enhanceWithAugmentedContext(data)
    ↓
// Calls IDE bridge
window.intellijBridge.callIDE('augmentQuery', {
    query: userMessage
})
```

### 2. Java Bridge Processing
```java
// JavaScriptBridgeActions.java
handleAugmentQuery(data)
    ↓
// QueryAugmentationService.java
queryAugmentationService.augmentQuery(query)
    ↓
// QueryAugmentationAgent.java
agent.augmentQuery(userQuery)
```

### 3. Index Search Flow
```
Query: "payment controllers"
    ↓
Pattern Detection: ["controller"]
Keywords: ["payment"]
    ↓
┌─────────────────────────────────────┐
│ Name Index Search                   │
│ - Query: "*Controller"              │
│ - Filter: contains "payment"        │
│ - Weight: 2.0x                      │
└─────────────────────────────────────┘
    +
┌─────────────────────────────────────┐
│ Semantic Index Search               │
│ - Embedding: [0.23, -0.45, ...]     │
│ - Similarity threshold: 0.5         │
│ - Weight: 1.5x                      │
└─────────────────────────────────────┘
    +
┌─────────────────────────────────────┐
│ Structural Index Search             │
│ - Find controllers                  │
│ - Include relationships             │
│ - Weight: 1.0x                      │
└─────────────────────────────────────┘
    ↓
Combined & Ranked Results
```

## Agent Behavior States

```
┌─────────────┐     Query      ┌─────────────┐
│   START     │ ─────────────> │  ANALYZE    │
└─────────────┘                └─────────────┘
                                      │
                               Ambiguous?
                                   ┌──┴──┐
                                   │ Yes │ No
                                   ▼     ▼
                          ┌─────────────┐ ┌─────────────┐
                          │  CLARIFY    │ │   SEARCH    │
                          └─────────────┘ └─────────────┘
                                   │             │
                                   └──────┬──────┘
                                          ▼
                                   ┌─────────────┐
                                   │  AUGMENT    │
                                   └─────────────┘
                                          │
                                          ▼
                                   ┌─────────────┐
                                   │  SUGGEST    │
                                   └─────────────┘
                                          │
                                          ▼
                                   ┌─────────────┐
                                   │   OUTPUT    │
                                   └─────────────┘
```

## Conversation Context Management

```
ConversationState {
    - User Inputs: ["show payment", "how does it work", ...]
    - Agent Responses: [augmented contexts]
    - Explored Topics: ["controllers", "payment flow", ...]
    - Mentioned Components: ["PaymentController", "PaymentService"]
    - Current Strategy: DEPTH_FIRST
    - Exploration Depth: 3
}
```

## Performance Characteristics

| Operation | Time | Notes |
|-----------|------|-------|
| Query Analysis | ~5ms | Pattern matching & parsing |
| Name Index Search | ~10ms | Lucene query |
| Semantic Search | ~50ms | Vector similarity |
| Structural Search | ~20ms | Graph traversal |
| Result Processing | ~10ms | Ranking & grouping |
| **Total** | **~100ms** | Full augmentation |

## Key Benefits

1. **Intelligent Context**: Only includes relevant code, not entire project
2. **Reflective Questions**: Helps clarify ambiguous requests
3. **Progressive Discovery**: Guides users through codebase exploration
4. **Pattern Recognition**: Understands code conventions and structures
5. **Relationship Awareness**: Shows how components connect
6. **Performance**: Fast, targeted searches vs. loading entire RAG
