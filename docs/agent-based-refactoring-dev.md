# Agent-Based Refactoring for Testability - Developer Documentation

## Overview

The Agent-Based Refactoring feature is an advanced tool that analyzes Java classes for testability issues and provides AI-assisted implementation of refactoring steps to address these issues. The feature uses a persistent state model to track progress and a non-blocking UI to maintain IDE usability during the refactoring process.

## Architecture

The feature follows a pipeline architecture with these key components:

1. **Data Model**
   - `RefactoringPlan`: Contains the overall refactoring plan with metadata and issues list
   - `RefactoringIssue`: Represents a specific testability issue (e.g., dependency injection problem)
   - `RefactoringStep`: Represents a concrete action to resolve an issue
   - `RefactoringProgress`: Tracks the current state of the refactoring process

2. **State Management**
   - `RefactoringStateManager`: Handles persistence of plans and progress using JSON files
   - State files are stored in `.zest/refactorings/` directory in the project
   - Supports resuming refactoring sessions across IDE restarts

3. **UI Components**
   - `RefactoringToolWindow`: Non-modal tool window showing refactoring progress and controls
   - Displays all refactoring steps in a table with their statuses

4. **Pipeline Stages**
   - `RefactoringPlanningStage`: Creates LLM prompt for analyzing the class
   - `RefactoringPlanAnalysisStage`: Processes LLM response into a structured plan
   - `RefactoringExecutionStage`: Manages the step-by-step refactoring process

5. **Execution Engine**
   - `RefactoringExecutionManager`: Handles the actual execution of refactoring steps
   - Manages chat interactions with the LLM, including context collection and chat continuity

## State Files

The feature uses three main state files:

1. `current-plan.json`: Contains the full refactoring plan with issues and steps
2. `current-progress.json`: Tracks the current progress (current issue/step, completed steps)
3. `current-context.json`: Stores analysis context about the class being refactored

## Key Workflows

### 1. Plan Creation

The planning flow follows these steps:
1. User activates the refactoring action on a Java class
2. `AgentBasedRefactoringAction` creates a `CodeContext` and starts the pipeline
3. `RefactoringPlanningStage` analyzes the class using `ClassAnalyzer`
4. This stage creates a prompt for the LLM with complete class context
5. `LlmApiCallStage` sends the prompt to the LLM and gets a response
6. `RefactoringPlanAnalysisStage` extracts a structured plan from the response
7. The structured plan is saved to `current-plan.json`

### 2. Execution Flow

The execution flow follows these steps:
1. `RefactoringExecutionStage` shows the tool window and starts execution
2. `RefactoringToolWindow` displays the plan and provides controls
3. `RefactoringExecutionManager` handles each step:
   - Loads comprehensive context (including fresh class analysis)
   - Manages chat continuity vs. starting new chats
   - Creates detailed prompts with all necessary context
   - Sends prompts to the LLM via `ChatboxUtilities`
4. User reviews LLM implementations and confirms/skips/aborts steps
5. Progress is continuously updated in `current-progress.json`

## Conversation Management

The feature intelligently manages chat conversations:
- New chats are started for the first step or after extended inactivity (30+ minutes)
- Continuations are used for sequential steps in the same session
- New chats receive full context including an overview of the entire plan
- All chats include comprehensive class analysis from `ClassAnalyzer`

## Integration Points

### 1. Class Analysis Integration

The feature integrates with the `ClassAnalyzer` utility to provide rich context:
- `ClassAnalyzer.collectClassContext()` is used to get fresh class structure
- Related classes are included in the context
- Imports and package information are included

### 2. Chat Integration

- `ChatboxUtilities` is used to interact with the LLM
- `ChatboxUtilities.clickNewChatButton()` is called when a new chat is needed
- `ChatboxUtilities.sendTextAndSubmit()` sends prompts and instructions

## Extension Points

### Adding New Testability Analysis Criteria

To add new testability criteria to the analysis:
1. Modify `RefactoringPlanningStage.process()` to include new criteria in the prompt
2. Update the JSON structure in the prompt template to include the new fields

### Supporting New Refactoring Types

To add support for new types of refactorings:
1. Extend the `RefactoringPlan` and `RefactoringIssue` models if needed
2. Create a specialized planning stage for your new refactoring type
3. Update the `AgentBasedRefactoringAction` to use your new pipeline

## Troubleshooting

### Common Issues

1. **Missing Context**: If the LLM seems to lack context:
   - Check `RefactoringExecutionManager.executeStep()` to ensure it's correctly loading class context
   - Verify that `ClassAnalyzer` is correctly analyzing the class
   - Examine `isNewChatNeeded()` to ensure chat continuity is working properly

2. **UI Responsiveness**: If the UI becomes sluggish:
   - Check that operations are properly wrapped in `ApplicationManager.getApplication().invokeLater()`
   - Ensure long-running operations are not blocking the EDT

3. **State Persistence**: If state isn't persisting:
   - Check `RefactoringStateManager` methods for proper file operations
   - Verify JSON serialization/deserialization in `saveProgress()` and `loadProgress()`

## Getting Started for Developers

To start working on this feature:

1. Begin with `AgentBasedRefactoringAction` to understand the entry point
2. Review the pipeline stages to understand the flow
3. Examine `RefactoringExecutionManager` to understand context handling and LLM interaction
4. Look at `RefactoringToolWindow` to understand the UI

Key files to understand first:
- `AgentBasedRefactoringAction.java`: Entry point for the feature
- `RefactoringPlan.java`: Core data model
- `RefactoringStateManager.java`: State persistence
- `RefactoringExecutionManager.java`: Core execution logic
- `RefactoringToolWindow.java`: UI implementation

## Future Improvements

Potential areas for enhancement:
1. Add better error recovery for failed refactoring steps
2. Implement automatic testing of refactored code
3. Support batch refactoring of multiple classes
4. Add a visual diff viewer to preview changes
5. Implement a history view of past refactorings

## Coding Standards

Follow these guidelines when modifying this code:
1. Always wrap PSI operations in `ApplicationManager.getApplication().runReadAction()`
2. Use `invokeLater()` for UI updates
3. Handle exceptions with proper logging and user feedback
4. Follow existing patterns for state management
5. Maintain the non-blocking nature of the feature

This feature is designed to be extensible and maintainable. The pipeline architecture allows for easy modification of individual stages without affecting the overall flow.
