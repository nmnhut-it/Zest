# Enhanced Research Agent - Exploratory Context Discovery

## Overview
Enhanced the Research Agent to be more exploratory and comprehensive in discovering necessary context, frameworks, existing code, and utility functions related to user queries.

## Changes Made

### 1. Enhanced LLMAnalyzer (Exploratory Analysis)

#### New Analysis Focus
- **Framework & Architecture Discovery**: Identifies frameworks (Spring, React, etc.), architectural patterns, and custom abstractions
- **Utility & Helper Discovery**: Finds utility classes, helpers, validators, formatters, and shared constants
- **Dependency & Integration Mapping**: Maps external services, databases, messaging systems, and module communication
- **Testing & Quality Infrastructure**: Discovers testing frameworks, utilities, fixtures, and mocking approaches
- **Critical Questions**: Asks what we don't know, what assumptions need verification, and what functionality likely exists

#### Enhanced Response Format
Added new fields to the LLM response:
- `FRAMEWORKS`: Identified frameworks and libraries
- `UTILITIES`: Discovered utility classes and helpers

#### Iteration-Specific Exploration
Each iteration now has specific exploratory questions:
- **Iteration 0**: Identify frameworks, entry points, and utilities
- **Iteration 1**: Deep dive into dependencies and integrations
- **Iteration 2**: Explore testing infrastructure and usage patterns
- **Iteration 3**: Find configuration, initialization, and edge cases
- **Iteration 4+**: Fill critical gaps and verify assumptions

#### Smart Search Generation
- Processes identified frameworks to generate framework-specific searches
- Processes utilities to find related helpers and test files
- Generates up to 15 exploratory keywords per iteration (increased from 5)

### 2. Enhanced KeywordGeneratorService (Context-Aware Keywords)

#### Comprehensive Prompt Building
- Thinks like a developer exploring the codebase
- Considers frameworks, utilities, design patterns, and naming conventions
- Provides richer examples showing framework-specific terms

#### Framework Detection
- **From File Names**: Detects patterns like Controller, Service, Component
- **From Code**: Identifies framework annotations and imports
- **From Project Structure**: Analyzes file patterns to infer frameworks

#### Enhanced Keyword Generation
Generates keywords in categories:
1. Core terms from query
2. Common class/method names
3. Framework-specific terms
4. Utility/helper class names
5. Common patterns (Factory, Builder, Manager)
6. Configuration terms
7. Test-related terms

#### Language-Specific Intelligence
Expanded language-specific keywords for:
- **Java**: Spring annotations, common patterns, testing frameworks
- **JavaScript/TypeScript**: React hooks, event handling, testing tools
- **Python**: Django/Flask patterns, class structures, pytest
- **C/C++**: Namespaces, templates, interfaces

#### Exploratory Keywords
Automatically adds exploration terms:
- Utils, Helper, Manager, Factory
- Service, Repository, DAO, Model
- Config, Configuration, Settings, Constants
- Test, Spec, Mock, Stub, Fixture

## Benefits

1. **Better Framework Understanding**: Automatically identifies and explores framework-specific patterns
2. **Utility Discovery**: Finds existing utility functions and helpers that can be reused
3. **Comprehensive Context**: Builds a complete mental model of the codebase architecture
4. **Smarter Exploration**: Each iteration asks specific questions to guide discovery
5. **Reduced Search Iterations**: More comprehensive initial keywords lead to better results

## Usage

The enhanced system automatically:
1. Analyzes the current context to detect frameworks and patterns
2. Generates comprehensive keywords including framework-specific terms
3. Explores the codebase systematically through targeted questions
4. Builds a complete understanding of utilities, helpers, and architectural patterns
5. Provides the LLM with rich context for better code suggestions

## Example Scenarios

### Scenario 1: User asks about "authentication"
- Detects Spring Security, JWT, OAuth patterns
- Searches for AuthService, SecurityConfig, AuthUtils
- Finds related filters, interceptors, and middleware
- Discovers test fixtures and mocks

### Scenario 2: User asks about "button click handling"
- Detects React/Angular/Vue frameworks
- Searches for event handlers, listeners, components
- Finds UI utilities and helper functions
- Discovers related test cases and examples

### Scenario 3: User asks about "database operations"
- Detects ORM frameworks (Hibernate, Sequelize)
- Searches for repositories, DAOs, models
- Finds database utilities and connection managers
- Discovers migration scripts and test data