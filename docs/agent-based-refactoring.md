# Agent-Based Refactoring for Testability

This feature enhances the Zest plugin with an intelligent, agent-based approach to refactoring Java classes for better testability. Unlike the simpler advisory-based refactoring, this feature actively implements the changes with AI assistance.

## Features

- **Comprehensive Analysis**: Analyzes Java classes for testability issues using established criteria
- **Structured Refactoring Plan**: Creates a detailed, step-by-step plan for improving testability
- **Persistent State**: Maintains refactoring state across IDE sessions for long-running refactorings
- **Agent-Assisted Implementation**: Uses LLM to help implement each refactoring step
- **Interactive Workflow**: Guides the user through the refactoring process with clear steps
- **Progress Tracking**: Keeps track of completed, skipped, and pending refactoring steps

## How It Works

1. **Analysis Phase**:
   - Identifies testability issues in the class (Dependency Injection problems, Static dependencies, etc.)
   - Creates a structured plan with categorized issues and specific refactoring steps

2. **Planning Phase**:
   - Organizes the refactoring steps in a logical sequence
   - Determines file locations and code changes needed

3. **Execution Phase**:
   - Presents each step with detailed information about what needs to change
   - Uses the LLM to help implement the changes
   - Lets the user confirm, skip, or abort each step

## Usage

1. Right-click on a Java class in your project
2. Select "ZPS: Agent-Based Refactoring for Testability" from the Generate menu
3. Review the identified testability issues and refactoring plan
4. Step through the refactoring process, with the LLM assisting in implementing each change

## Implementation Details

The feature is built using a modular pipeline architecture:

- `AgentBasedRefactoringAction`: Entry point that initiates the refactoring process
- `RefactoringPlanningStage`: Creates the initial refactoring plan
- `RefactoringPlanAnalysisStage`: Processes the LLM's analysis and builds the plan
- `RefactoringExecutionStage`: Orchestrates the step-by-step refactoring process
- `RefactoringStateManager`: Manages persistent state between sessions
- `RefactoringExecutionManager`: Handles execution of individual refactoring steps
- `RefactoringManagerDialog`: Provides the UI for guiding users through the process

The state is stored in project-specific JSON files, allowing refactoring to be paused and resumed across sessions.

## Best Practices

- Review each proposed change carefully before accepting
- Use the refactoring feature on smaller classes first to get familiar with the process
- Consider running tests after each refactoring step to ensure functionality is preserved
- The refactoring process may require multiple iterations for complex classes
