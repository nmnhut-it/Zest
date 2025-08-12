package com.zps.zest.codehealth.testplan.generation

import com.zps.zest.codehealth.testplan.models.*

/**
 * Utility for generating test code from test plans
 */
object TestCodeGenerator {
    
    /**
     * Generate a complete test class from a test plan
     */
    fun generateTestClass(
        plan: TestPlanData,
        framework: TestFramework,
        mockingFramework: MockingFramework
    ): String {
        val className = extractClassName(plan.methodFqn)
        val testClassName = "${className}Test"
        
        return buildString {
            // Package declaration
            val packageName = extractPackageName(plan.methodFqn)
            if (packageName.isNotEmpty()) {
                appendLine("package $packageName;")
                appendLine()
            }
            
            // Imports
            appendImports(this, framework, mockingFramework, plan)
            appendLine()
            
            // Class declaration
            appendLine("/**")
            appendLine(" * Test class for $className")
            appendLine(" * Generated from test plan: ${plan.id}")
            appendLine(" * Testability Score: ${plan.testabilityScore}/100")
            appendLine(" */")
            appendLine("public class $testClassName {")
            appendLine()
            
            // Fields (mocks and test subject)
            appendFields(this, plan, mockingFramework)
            appendLine()
            
            // Setup method
            appendSetupMethod(this, framework, mockingFramework, plan)
            appendLine()
            
            // Test methods
            if (plan.testCases.isNotEmpty()) {
                plan.testCases.forEach { testCase ->
                    appendTestMethod(this, testCase, framework, className)
                    appendLine()
                }
            } else {
                // Generate basic test method if no test cases defined
                appendBasicTestMethod(this, framework, className, extractMethodName(plan.methodFqn))
                appendLine()
            }
            
            // Helper methods if needed
            if (plan.setupRequirements.isNotEmpty()) {
                appendHelperMethods(this, plan)
            }
            
            appendLine("}")
        }
    }
    
    private fun appendImports(
        builder: StringBuilder,
        framework: TestFramework,
        mockingFramework: MockingFramework,
        plan: TestPlanData
    ) {
        // Framework imports
        when (framework) {
            TestFramework.JUNIT4 -> {
                builder.appendLine("import org.junit.Test;")
                builder.appendLine("import org.junit.Before;")
                builder.appendLine("import static org.junit.Assert.*;")
            }
            TestFramework.JUNIT5 -> {
                builder.appendLine("import org.junit.jupiter.api.Test;")
                builder.appendLine("import org.junit.jupiter.api.BeforeEach;")
                builder.appendLine("import org.junit.jupiter.api.DisplayName;")
                builder.appendLine("import static org.junit.jupiter.api.Assertions.*;")
            }
            TestFramework.TESTNG -> {
                builder.appendLine("import org.testng.annotations.Test;")
                builder.appendLine("import org.testng.annotations.BeforeMethod;")
                builder.appendLine("import static org.testng.Assert.*;")
            }
        }
        
        // Mocking framework imports
        when (mockingFramework) {
            MockingFramework.MOCKITO -> {
                builder.appendLine("import org.mockito.Mock;")
                builder.appendLine("import org.mockito.MockitoAnnotations;")
                builder.appendLine("import static org.mockito.Mockito.*;")
            }
            MockingFramework.EASYMOCK -> {
                builder.appendLine("import org.easymock.EasyMock;")
                builder.appendLine("import org.easymock.Mock;")
                builder.appendLine("import static org.easymock.EasyMock.*;")
            }
            MockingFramework.JMOCKIT -> {
                builder.appendLine("import mockit.Mocked;")
                builder.appendLine("import mockit.Expectations;")
            }
            MockingFramework.POWERMOCK -> {
                builder.appendLine("import org.powermock.api.mockito.PowerMockito;")
                builder.appendLine("import org.powermock.core.classloader.annotations.PrepareForTest;")
            }
        }
        
        // Class under test import
        val packageName = extractPackageName(plan.methodFqn)
        val className = extractClassName(plan.methodFqn)
        if (packageName.isNotEmpty()) {
            builder.appendLine("import $packageName.$className;")
        }
    }
    
    private fun appendFields(
        builder: StringBuilder,
        plan: TestPlanData,
        mockingFramework: MockingFramework
    ) {
        builder.appendLine("    // Test subject")
        val className = extractClassName(plan.methodFqn)
        builder.appendLine("    private $className ${className.lowercase()};")
        builder.appendLine()
        
        if (plan.mockingRequirements.isNotEmpty()) {
            builder.appendLine("    // Mocked dependencies")
            plan.mockingRequirements.forEach { mock ->
                val mockAnnotation = when (mockingFramework) {
                    MockingFramework.MOCKITO -> "@Mock"
                    MockingFramework.EASYMOCK -> "@Mock"
                    MockingFramework.JMOCKIT -> "@Mocked"
                    MockingFramework.POWERMOCK -> "@Mock"
                }
                builder.appendLine("    $mockAnnotation")
                builder.appendLine("    private ${mock.className} ${mock.className.lowercase()};")
            }
        }
    }
    
    private fun appendSetupMethod(
        builder: StringBuilder,
        framework: TestFramework,
        mockingFramework: MockingFramework,
        plan: TestPlanData
    ) {
        val setupAnnotation = when (framework) {
            TestFramework.JUNIT4 -> "@Before"
            TestFramework.JUNIT5 -> "@BeforeEach"
            TestFramework.TESTNG -> "@BeforeMethod"
        }
        
        builder.appendLine("    $setupAnnotation")
        builder.appendLine("    public void setUp() {")
        
        when (mockingFramework) {
            MockingFramework.MOCKITO -> {
                builder.appendLine("        MockitoAnnotations.openMocks(this);")
            }
            MockingFramework.EASYMOCK -> {
                builder.appendLine("        // EasyMock initialization handled by annotations")
            }
            MockingFramework.JMOCKIT -> {
                builder.appendLine("        // JMockit initialization handled by annotations")
            }
            MockingFramework.POWERMOCK -> {
                builder.appendLine("        MockitoAnnotations.openMocks(this);")
            }
        }
        
        // Initialize test subject
        val className = extractClassName(plan.methodFqn)
        if (plan.mockingRequirements.isNotEmpty()) {
            val constructorParams = plan.mockingRequirements.joinToString(", ") { it.className.lowercase() }
            builder.appendLine("        ${className.lowercase()} = new $className($constructorParams);")
        } else {
            builder.appendLine("        ${className.lowercase()} = new $className();")
        }
        
        builder.appendLine("    }")
    }
    
    private fun appendTestMethod(
        builder: StringBuilder,
        testCase: TestCase,
        framework: TestFramework,
        className: String
    ) {
        builder.appendLine("    @Test")
        
        if (framework == TestFramework.JUNIT5) {
            builder.appendLine("    @DisplayName(\"${testCase.description}\")")
        }
        
        val methodName = "test${testCase.name.replace(" ", "").replace("-", "")}"
        builder.appendLine("    public void $methodName() {")
        builder.appendLine("        // Arrange")
        builder.appendLine("        ${testCase.setup}")
        builder.appendLine()
        builder.appendLine("        // Act")
        builder.appendLine("        // TODO: Call the method under test")
        builder.appendLine("        // ${testCase.input}")
        builder.appendLine()
        builder.appendLine("        // Assert")
        builder.appendLine("        // TODO: Verify the results")
        builder.appendLine("        // Expected: ${testCase.expectedOutput}")
        
        if (testCase.assertions.isNotEmpty()) {
            testCase.assertions.forEach { assertion ->
                builder.appendLine("        // $assertion")
            }
        }
        
        builder.appendLine("    }")
    }
    
    private fun appendBasicTestMethod(
        builder: StringBuilder,
        framework: TestFramework,
        className: String,
        methodName: String
    ) {
        builder.appendLine("    @Test")
        
        if (framework == TestFramework.JUNIT5) {
            builder.appendLine("    @DisplayName(\"Test $methodName method\")")
        }
        
        builder.appendLine("    public void test${methodName.capitalize()}() {")
        builder.appendLine("        // Arrange")
        builder.appendLine("        // TODO: Set up test data")
        builder.appendLine()
        builder.appendLine("        // Act")
        builder.appendLine("        // TODO: Call ${className.lowercase()}.$methodName()")
        builder.appendLine()
        builder.appendLine("        // Assert")
        builder.appendLine("        // TODO: Verify the results")
        builder.appendLine("        fail(\"Test not implemented\");")
        builder.appendLine("    }")
    }
    
    private fun appendHelperMethods(builder: StringBuilder, plan: TestPlanData) {
        builder.appendLine("    // Helper methods")
        plan.setupRequirements.forEach { setup ->
            builder.appendLine("    private void setup${setup.type.name.lowercase().capitalize()}() {")
            builder.appendLine("        // ${setup.description}")
            if (setup.code != null) {
                builder.appendLine("        ${setup.code}")
            } else {
                builder.appendLine("        // TODO: Implement setup for ${setup.type}")
            }
            builder.appendLine("    }")
            builder.appendLine()
        }
    }
    
    private fun extractClassName(methodFqn: String): String {
        return if (methodFqn.contains(":")) {
            // JS/TS file - extract file name as class name
            val colonIndex = methodFqn.lastIndexOf(":")
            val filePath = methodFqn.substring(0, colonIndex)
            val fileName = filePath.substringAfterLast("/").substringAfterLast("\\")
            fileName.substringBeforeLast(".").capitalize()
        } else {
            // Java method - extract class name
            methodFqn.substringBeforeLast(".").substringAfterLast(".")
        }
    }
    
    private fun extractPackageName(methodFqn: String): String {
        return if (methodFqn.contains(":")) {
            // JS/TS file - no package
            ""
        } else {
            // Java method - extract package name
            val fullClassName = methodFqn.substringBeforeLast(".")
            fullClassName.substringBeforeLast(".")
        }
    }
    
    private fun extractMethodName(methodFqn: String): String {
        return if (methodFqn.contains(":")) {
            // JS/TS file - use file name
            val colonIndex = methodFqn.lastIndexOf(":")
            val filePath = methodFqn.substring(0, colonIndex)
            val fileName = filePath.substringAfterLast("/").substringAfterLast("\\")
            fileName.substringBeforeLast(".")
        } else {
            // Java method - extract method name
            methodFqn.substringAfterLast(".")
        }
    }
}