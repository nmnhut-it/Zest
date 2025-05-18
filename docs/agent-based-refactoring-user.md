# Agent-Based Refactoring for Testability - User Guide

## Overview

The Agent-Based Refactoring feature automatically analyzes your Java classes for testability issues and helps you implement refactoring steps to improve them. Unlike the simpler testability advisory feature, this tool actively assists you in implementing the changes using AI guidance.

## What is Testability?

Testability refers to how easily a class can be tested in isolation. Classes with good testability characteristics:
- Have dependencies injected rather than created internally
- Avoid static dependencies and singletons
- Don't directly access external resources like files or databases
- Have clear boundaries and interfaces
- Use dependency injection and interfaces for better mocking

## How to Use the Feature

1. **Launch the feature**:
   - Right-click on a Java class in your project
   - Select "ZPS: Agent-Based Refactoring for Testability" from the Generate menu

2. **Analysis phase**:
   - The tool will analyze your class using AI
   - It will identify testability issues and create a detailed refactoring plan
   - The plan will be organized by issues and concrete steps to address them

3. **Execution phase**:
   - A tool window will appear showing the refactoring plan
   - Each step will be executed one at a time with AI assistance
   - For each step:
     - The AI will explain what needs to change and why
     - The AI will suggest specific code changes
     - You can review these changes and apply them to your code
     - Mark the step as complete or skip it using the buttons

4. **Review and completion**:
   - After each step, you can verify the changes work as expected
   - The tool tracks your progress, so you can pause and resume refactoring later
   - Once all steps are complete, your class will have improved testability

## Example Testability Issues

The tool commonly identifies and helps fix issues such as:

1. **Hard-coded Dependencies**
   - Problem: `this.validator = new Validator();`
   - Solution: `this.validator = validator;` with constructor injection

2. **Static Utility Dependencies**
   - Problem: `DateUtils.formatDate(date);`
   - Solution: Inject a DateFormatter interface/implementation

3. **Direct File/DB Access**
   - Problem: `new FileReader(filename)`
   - Solution: Inject FileReader or use a FileService interface

4. **Singletons**
   - Problem: `Configuration.getInstance().getValue()`
   - Solution: Inject Configuration or ConfigProvider

5. **Final Classes/Methods**
   - Problem: `final class UserService`
   - Solution: Extract interface or remove final keyword

## Benefits

- **Guided Refactoring**: Step-by-step guidance rather than just advice
- **Educational**: Learn testability best practices by seeing concrete examples
- **Persistent**: You can pause and resume refactoring sessions later
- **Non-Blocking**: The IDE remains fully usable during refactoring
- **Comprehensive**: Addresses multiple testability issues simultaneously

## Tips for Best Results

- Start with smaller classes for your first refactorings
- Consider creating tests before and after refactoring to verify behavior
- Run your application after each refactoring step to ensure it still works
- For large classes, focus on the most critical testability issues first
- The refactoring process teaches you patterns you can apply in new code

## Limitations

- Some complex refactorings may require manual adjustments
- Changes to one class might require changes to dependent classes
- Fully automatic refactoring isn't always possible for very complex systems
- Some design patterns inherently limit testability and may require significant architectural changes

## Feedback

If you have feedback or encounter issues with the Refactoring for Testability feature, please report them to your Zest plugin administrator.
