# Agent-Based Refactoring for Testability - Developer Guide

## Overview

The Agent-Based Refactoring for Testability feature is designed to help developers improve the testability of their code through AI-assisted refactoring. This feature uses a structured pipeline to analyze code, identify testability issues, create a detailed refactoring plan, and guide the user through the implementation of each step.

## Architecture

### Core Components

1. **`AgentBasedRefactoringAction`**: Entry point for the feature, registered as an IDE action.
2. **`RefactoringStateManager`**: Manages state persistence through JSON files.
3. **`RefactoringPlan`**: Model representing the full refactoring plan with issues and steps.
4. **`RefactoringProgress`**: Model tracking the progress of the refactoring operation.
5. **`RefactoringToolWindow`**: UI component showing the current step and controls.
6. **`RefactoringExecutionManager`**: Orchestrates interactions with the LLM.

### Pipeline Stages

The feature follows a pipeline architecture with several stages:

1. **`ConfigurationStage`**: Loads configuration options.
2. **`TargetClassDetectionStage`**: Identifies the class to be refactored.
3. **`ClassAnalysisStage`**: Analyzes the class and its dependencies.
4. **`RefactoringPlanningStage`**: Creates a plan for refactoring.
5. **`LlmApiCallStage`**: Sends the plan to the LLM for analysis.
6. **`RefactoringPlanAnalysisStage`**: Processes the LLM's response.
7. **`RefactoringExecutionStage`**: Executes the refactoring with LLM assistance.

### State Management

The feature uses three JSON files stored in `.zest/refactorings/` to maintain state:

- `current-plan.json`: The refactoring plan with issues and steps
- `current-progress.json`: Current progress, including completed steps
- `current-context.json`: Class context for resumption

## Key Features

1. **Detailed Analysis**: Uses `ClassAnalyzer` to gather comprehensive context.
2. **Structured Refactoring Plan**: Organizes refactoring into issues and concrete steps.
3. **Progress Persistence**: Allows pausing and resuming the refactoring process.
4. **Non-blocking UI**: Compact "balloon" interface that doesn't interfere with the IDE.
5. **Conversation Management**: Intelligently manages LLM conversations for continuity.

## How It Works

### 1. Initialization and Analysis

When the user activates the action, the pipeline:

1. Creates a `CodeContext` to maintain state
2. Detects and analyzes the target class using `ClassAnalyzer`
3. Generates a comprehensive LLM prompt to create a refactoring plan
4. Sends the prompt to the LLM via `LlmApiCallStage`

### 2. Plan Creation

After receiving the LLM's response:

1. `RefactoringPlanAnalysisStage` extracts a structured JSON plan
2. The plan is parsed into a `RefactoringPlan` object
3. The plan is saved to disk for persistence

### 3. Execution

For each step in the plan:

1. `RefactoringExecutionStage` initiates the execution
2. `RefactoringToolWindow` shows the current step
3. `RefactoringExecutionManager` handles LLM interactions
4. Progress is tracked and persisted between steps

## Development Guidance

### Adding New Refactoring Types

To add a new type of refactoring:

1. Extend the `RefactoringPlan` model if needed
2. Create a specialized planning stage
3. Update the `AgentBasedRefactoringAction` to use your new stages

### Modifying the UI

The UI is defined in `RefactoringToolWindow.createPanel()`. Key elements:

1. Header with progress info and buttons
2. Central panel with current step details
3. Progress bar at the bottom

### Enhancing LLM Prompts

The LLM prompts are created in:

1. `RefactoringPlanningStage`: For initial plan creation
2. `RefactoringExecutionManager`: For step execution

### Conversation Management

The `RefactoringExecutionManager` handles LLM interactions with these key methods:

1. `executeStep()`: Sends the current step to the LLM
2. `isNewChatNeeded()`: Determines when to start a new chat
3. `createStepExecutionPrompt()`: Creates the prompt for each step

## Key Files

- `AgentBasedRefactoringAction.java`: Entry point
- `RefactoringPlan.java`, `RefactoringIssue.java`, `RefactoringStep.java`: Core models
- `RefactoringStateManager.java`: State persistence
- `RefactoringToolWindow.java`: UI implementation
- `RefactoringExecutionManager.java`: LLM interaction

## Integration Points

### Class Analysis

The feature integrates with `ClassAnalyzer` to:

1. Collect class structure and related classes
2. Extract methods, fields, and relationships
3. Provide comprehensive context to the LLM

### ChatboxUtilities

Integration with the chat interface is handled through:

1. `ChatboxUtilities.sendTextAndSubmit()`: Sends prompts to the LLM
2. `ChatboxUtilities.clickNewChatButton()`: Starts new conversations

## Current Limitations and TODOs

1. **Step Application**: The feature currently doesn't automatically apply the step changes to code.
2. **Diff Visualization**: No visual diff showing proposed changes.
3. **Automated Testing**: No automated verification of refactoring.
4. **Multi-file Refactoring**: Limited support for refactoring that spans multiple files.

## Future Improvements

### High Priority

1. Add a visual diff viewer for step changes
2. Implement automatic code modification based on LLM suggestions
3. Add automated verification via test execution

### Medium Priority

1. Improve conversation history tracking
2. Add refactoring templates for common patterns
3. Support batch refactoring of multiple classes

### Low Priority

1. Add statistics and reporting
2. Create a refactoring history view
3. Support integration with version control

## Troubleshooting

### UI Issues

If the UI is not updating correctly:
- Check `RefactoringToolWindow.refreshUI()` method
- Ensure `updateStepStatusesFromProgress()` is called when needed

### State Persistence

If state is not persisting correctly:
- Check file permissions in the `.zest/refactorings/` directory
- Verify JSON serialization in `RefactoringStateManager`

### LLM Integration

If the LLM is not responding correctly:
- Check the prompt creation in `RefactoringExecutionManager`
- Verify context collection in `executeStep()` method

## Code Guidelines

1. **Thread Safety**: All UI operations should be wrapped in `ApplicationManager.getApplication().invokeLater()`
2. **PSI Operations**: All PSI operations should be within read actions
3. **State Management**: Always save state after modifications
4. **Error Handling**: Use detailed error messages and proper logging
5. **UI Design**: Keep the UI compact and non-intrusive

By following these guidelines and understanding the architecture, you can extend and enhance the Agent-Based Refactoring feature to better support Java developers in improving their code testability.
