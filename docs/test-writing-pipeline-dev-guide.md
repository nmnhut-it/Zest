# Test Writing Pipeline - Developer Guide

## Overview

The Test Writing Pipeline is an AI-powered system that generates comprehensive test suites for Java classes using a step-by-step approach with human oversight. It mirrors the refactoring pipeline architecture but focuses on intelligent test generation while minimizing mocking dependencies.

## Architecture

### Pipeline Flow

```
AgentBasedTestWritingAction
    ↓
TestConfigurationStage
    ↓
TargetClassDetectionStage
    ↓
ClassAnalysisStage (detects JUnit/Mockito)
    ↓
TestAnalysisStage
    ↓
TestPlanningStage
    ↓
ChatboxLlmApiCallStage
    ↓
TestPlanAnalysisStage (includes testability scoring)
    ↓
TestExecutionStage
    ↓
TestWritingToolWindow (step-by-step execution)
```

### Key Components

#### Core Files
- **`AgentBasedTestWritingAction.java`** - Main entry point, orchestrates pipeline
- **`TestWritingStateManager.java`** - Persistent state management (resume capability)
- **`TestExecutionManager.java`** - Coordinates test case implementation
- **`TestWritingToolWindow.java`** - UI coordinator
- **`TestWritingUI.java`** - UI components

#### Data Models
- **`TestPlan.java`** - Overall test plan (like RefactoringPlan)
- **`TestScenario.java`** - Groups related test cases (like RefactoringIssue)
- **`TestCase.java`** - Individual test case (like RefactoringStep)
- **`TestWritingProgress.java`** - Tracks progress through test cases
- **`TestWritingStatus.java`** / **`TestStatus.java`** - Status enums

#### Pipeline Stages
- **`TestConfigurationStage.java`** - Basic setup
- **`TestAnalysisStage.java`** - Test-specific analysis
- **`TestPlanningStage.java`** - Creates test plans with LLM
- **`TestPlanAnalysisStage.java`** - Parses LLM responses, handles rejections
- **`TestExecutionStage.java`** - Manages test writing execution

## Template System

### Template Files Location
All templates are in `src/main/resources/templates/`:

#### Main Templates
- **`test_planning.template`** - Test plan generation prompt
- **`test_case_execution.template`** - Individual test case implementation
- **`review_tests.template`** - Test review and finalization

#### System Prompts
- **`test_planning_system_prompt.template`** - For test plan creation (no `/no_think`)
- **`test_execution_system_prompt.template`** - For test implementation (has `/no_think`)
- **`test_review_system_prompt.template`** - For test review (no `/no_think`)

### Template Variables

#### Common Variables
```
${className} - Target class name
${classContext} - Full class source code  
${testFilePath} - Path to test file
${packageName} - Package name
${junitVersion} - "JUnit 4" or "JUnit 5"
${mockitoAvailable} - "available" or "not available"
```

#### Execution Variables
```
${targetClass} - Target class name
${scenarioTitle} - Current test scenario
${testCaseTitle} - Current test case
${testCaseDescription} - Test case description
${currentTestCaseNumber} - Progress indicator
${totalTestCasesInScenario} - Progress indicator
${currentScenarioNumber} - Progress indicator
${totalScenarios} - Progress indicator
${testMethodName} - Suggested test method name
${setup} - Test setup requirements
${assertions} - Expected assertions
```

## Testability Scoring System

### Automatic Rejection Logic

Classes are scored 0-10 for testability:
- **0-3: Poor** → **REJECTED** with refactoring suggestions
- **4-6: Moderate** → **ACCEPTED** with warnings
- **7-10: Good** → **ACCEPTED** with confidence

### Rejection Response Format
```json
{
  "testabilityScore": 2,
  "testabilityAnalysis": "Class has static dependencies and singleton pattern...",
  "rejection": true,
  "suggestions": ["Remove static dependencies", "Implement dependency injection"]
}
```

### Acceptance Response Format
```json
{
  "testabilityScore": 7,
  "testabilityAnalysis": "Well-designed class with good separation of concerns...",
  "scenarios": [
    {
      "id": 1,
      "title": "Constructor Validation Tests",
      "testCases": [...]
    }
  ]
}
```

## Anti-Mocking Philosophy

### Guidelines Applied
1. **AVOID mocking whenever possible**
2. **Prefer real objects and dependency injection**
3. **Only mock external dependencies** (databases, web services, file systems)
4. **Use constructor injection over field injection**
5. **Create real test data objects**

### Template Integration
Templates automatically include:
- JUnit version detection and appropriate syntax
- Mockito availability awareness
- Anti-mocking guidelines in instructions
- Framework-specific best practices

## State Management

### Persistence Files
Located in `.zest/testing/`:
- **`current-test-plan.json`** - Current test plan
- **`current-test-progress.json`** - Progress tracking

### Resume Capability
- Pipeline can be interrupted and resumed
- User can start/stop at any test case
- Progress is automatically saved after each step
- State is cleared on completion or abortion

## User Interface

### Tool Window Features
- **Progress display** - Current scenario/test case
- **Test case description** - What needs to be implemented
- **Action buttons** - Complete, Skip, Abort
- **Progress bar** - Overall completion status

### User Workflow
1. Right-click Java class → "Agent: Step-by-Step Test Writing"
2. System analyzes class and creates test plan
3. Tool window shows first test case to implement
4. User implements test in IDE
5. User clicks "Complete" when done
6. System moves to next test case
7. Repeat until all tests are written

## Development Guidelines

### Adding New Features

#### New Template Variables
1. Add to template files in `/templates/`
2. Update replacement logic in:
   - `TestPlanningStage.createPlanningPromptWithContext()`
   - `TestExecutionManager.createTestCaseExecutionPrompt()`
3. Document in this guide

#### New Pipeline Stages
1. Implement `PipelineStage` interface
2. Add to pipeline in `AgentBasedTestWritingAction`
3. Update `CodeContext` if new data needed
4. Test error handling and recovery

#### New System Prompts
1. Create template in `/templates/`
2. Update `ChatboxLlmApiCallStage` or `TestExecutionManager`
3. Consider `/no_think` usage:
   - Planning/Analysis: No `/no_think` (allow thinking)
   - Code Generation: Use `/no_think` (faster execution)

### Error Handling

#### Template Missing
- Throws `RuntimeException` with clear message
- No fallback templates (fail fast)
- User should check template files exist

#### Low Testability Score
- `TestPlanAnalysisStage` throws `PipelineExecutionException`
- Includes detailed rejection message
- Suggests using refactoring pipeline first

#### State Corruption
- `TestWritingStateManager` validates JSON on load
- Corrupted state triggers fresh start
- Logs warnings for investigation

### Testing the Pipeline

#### Manual Testing
1. Test with different class types:
   - Well-designed classes (score 7+)
   - Moderately testable (score 4-6)  
   - Poorly designed (score 0-3)
2. Test resume functionality:
   - Stop pipeline mid-execution
   - Restart action - should resume
3. Test error scenarios:
   - Missing templates
   - Corrupted state files
   - Network issues during LLM calls

#### Test Classes for Validation
- **Good Testability**: Simple POJOs, dependency-injected services
- **Moderate Testability**: Classes with some static methods
- **Poor Testability**: Singletons, heavy static dependencies, final classes

## Integration Points

### With Existing Refactoring Pipeline
- Shares `CodeContext`, `ClassAnalysisStage`, `ChatboxLlmApiCallStage`
- Uses same template system approach
- Similar state management patterns
- Same tool window architecture

### With IDE Features
- Uses `ClassAnalyzer` for code analysis
- Integrates with `ChatboxUtilities` for LLM interaction
- Leverages PSI for code structure analysis
- Respects IDE indexing state

## Known Limitations & Future Work

### Current Limitations
1. **Template-dependent** - Requires all template files present
2. **No parallel test generation** - Sequential test case implementation
3. **Limited test type detection** - Could be smarter about integration vs unit tests
4. **No test execution** - Doesn't run generated tests automatically

### Future Enhancements
1. **Smart test type detection** - Analyze dependencies to suggest test types
2. **Batch test generation** - Option to generate multiple tests at once
3. **Test execution integration** - Run tests and validate they pass
4. **Coverage analysis** - Suggest additional test cases based on coverage gaps
5. **Custom templates** - Allow project-specific template overrides
6. **Test maintenance** - Update tests when source code changes

## Troubleshooting

### Common Issues

#### "Template not found" Error
- **Cause**: Missing template files in `/templates/`
- **Solution**: Ensure all template files are present in resources
- **Check**: Build process includes resource files

#### Pipeline Doesn't Resume
- **Cause**: Corrupted state files in `.zest/testing/`
- **Solution**: Delete `.zest/testing/` directory to reset
- **Prevention**: Check JSON serialization of new fields

#### Tool Window Doesn't Open
- **Cause**: Tool window ID conflicts or registration issues
- **Solution**: Check `TestWritingToolWindow.TOOL_WINDOW_ID` is unique
- **Debug**: Check IntelliJ logs for registration errors

#### Low Testability Rejection
- **Cause**: Class scored below 4/10 for testability
- **Solution**: Use refactoring pipeline first, or manually improve class design
- **Override**: Could add force flag to bypass (not recommended)

### Debug Logging
Enable debug logging for components:
```
com.zps.zest.testing - Test writing pipeline
com.zps.zest.testing.TestExecutionManager - Execution details
com.zps.zest.testing.TestWritingStateManager - State management
```

## Configuration

### Plugin.xml Entry
```xml
<action id="Zest.AgentBasedTestWritingAction"
        class="com.zps.zest.testing.AgentBasedTestWritingAction"
        text="Agent: Step-by-Step Test Writing"
        description="Use an AI agent to write comprehensive tests step-by-step">
    <add-to-group group-id="ZestGroup" anchor="after" relative-to-action="Zest.GenerateCodeCommentsAction"/>
</action>
```

### Dependencies Required
- `com.intellij.java` - PSI analysis
- `com.intellij.modules.platform` - Base platform
- Access to `ChatboxUtilities` - LLM integration
- Access to `ClassAnalyzer` - Code analysis

This pipeline represents a significant advancement in AI-assisted test development, providing both automation and human oversight for high-quality test generation.
