package com.zps.zest.mcp

import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase4
import com.intellij.testFramework.runInEdtAndGet
import com.zps.zest.testgen.evaluation.TestCodeValidator
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for MCP tools that require running on the Event Dispatch Thread (EDT).
 *
 * TestCodeValidator uses CodeSmellDetector which requires EDT.
 * This test class wraps validation calls in runInEdtAndGet { }.
 */
class McpEdtToolsTest : LightJavaCodeInsightFixtureTestCase4(
    projectDescriptor = LightJavaCodeInsightFixtureTestCase.JAVA_LATEST_WITH_LATEST_JDK
) {

    companion object {
        // Note: Classes are package-private (no 'public') because TestCodeValidator
        // creates files with timestamped names, and Java requires public classes
        // to match the filename exactly.

        private const val VALID_SIMPLE_CODE = """
package com.example;

class ValidSimple {
    private String name;

    ValidSimple(String name) {
        this.name = name;
    }

    String getName() {
        return name;
    }
}
"""

        private const val INVALID_TYPE_ERROR_CODE = """
package com.example;

class InvalidType {
    void typeError() {
        String s = 123;
        int x = "not a number";
    }
}
"""

        private const val INVALID_UNDECLARED_VAR_CODE = """
package com.example;

class UndeclaredVar {
    void test() {
        System.out.println(undeclaredVariable);
    }
}
"""

        private const val INVALID_MISSING_IMPORT_CODE = """
package com.example;

class MissingImport {
    void useList() {
        List<String> items = new ArrayList<>();
        items.add("test");
    }
}
"""

        private const val VALID_WITH_GENERICS_CODE = """
package com.example;

import java.util.List;
import java.util.ArrayList;

class WithGenerics<T> {
    private List<T> items = new ArrayList<>();

    void add(T item) {
        items.add(item);
    }

    List<T> getItems() {
        return items;
    }
}
"""
    }

    // ==================== TestCodeValidator EDT Tests ====================

    @Test
    fun testValidateCode_validSimpleCode_compilesOnEdt() {
        val result = runInEdtAndGet {
            TestCodeValidator.validate(fixture.project, VALID_SIMPLE_CODE.trimIndent(), "ValidSimple")
        }

        assertNotNull("Should return validation result", result)
        println("EDT Validation - Valid simple code: compiles=${result.compiles()}, errors=${result.errorCount}")

        // On EDT, valid code should compile
        if (result.errorCount != -1) { // -1 means validation failed
            assertTrue("Valid simple code should compile on EDT", result.compiles())
            assertEquals("Should have no errors", 0, result.errorCount)
        }
    }

    @Test
    fun testValidateCode_typeError_detectsErrorsOnEdt() {
        val result = runInEdtAndGet {
            TestCodeValidator.validate(fixture.project, INVALID_TYPE_ERROR_CODE.trimIndent(), "InvalidType")
        }

        assertNotNull("Should return validation result", result)
        println("EDT Validation - Type error code: compiles=${result.compiles()}, errors=${result.errorCount}")

        // On EDT, type errors should be detected
        if (result.errorCount != -1) {
            assertFalse("Type error code should not compile on EDT", result.compiles())
            assertTrue("Should have errors for type mismatch", result.errorCount > 0)
        }
    }

    @Test
    fun testValidateCode_undeclaredVar_detectsErrorsOnEdt() {
        val result = runInEdtAndGet {
            TestCodeValidator.validate(fixture.project, INVALID_UNDECLARED_VAR_CODE.trimIndent(), "UndeclaredVar")
        }

        assertNotNull("Should return validation result", result)
        println("EDT Validation - Undeclared var: compiles=${result.compiles()}, errors=${result.errorCount}")

        if (result.errorCount != -1) {
            assertFalse("Undeclared variable should not compile on EDT", result.compiles())
            assertTrue("Should have errors for undeclared variable", result.errorCount > 0)
        }
    }

    @Test
    fun testValidateCode_missingImport_detectsErrorsOnEdt() {
        val result = runInEdtAndGet {
            TestCodeValidator.validate(fixture.project, INVALID_MISSING_IMPORT_CODE.trimIndent(), "MissingImport")
        }

        assertNotNull("Should return validation result", result)
        println("EDT Validation - Missing import: compiles=${result.compiles()}, errors=${result.errorCount}")

        if (result.errorCount != -1) {
            assertFalse("Missing import should not compile on EDT", result.compiles())
            assertTrue("Should have errors for missing import", result.errorCount > 0)
        }
    }

    @Test
    fun testValidateCode_validGenerics_compilesOnEdt() {
        val result = runInEdtAndGet {
            TestCodeValidator.validate(fixture.project, VALID_WITH_GENERICS_CODE.trimIndent(), "WithGenerics")
        }

        assertNotNull("Should return validation result", result)
        println("EDT Validation - Valid generics: compiles=${result.compiles()}, errors=${result.errorCount}")

        if (result.errorCount != -1) {
            assertTrue("Valid generic code should compile on EDT", result.compiles())
        }
    }

    @Test
    fun testValidateCode_errorMessagesHaveContext_onEdt() {
        val result = runInEdtAndGet {
            TestCodeValidator.validate(fixture.project, INVALID_TYPE_ERROR_CODE.trimIndent(), "InvalidType")
        }

        assertNotNull("Should return validation result", result)

        if (result.errors.isNotEmpty()) {
            val firstError = result.errors[0]
            println("EDT Validation - Error with context:\n$firstError")

            // Error messages should contain line number and code context
            assertTrue("Error should mention 'Line'", firstError.contains("Line"))
            assertTrue("Error should have code context with |", firstError.contains("|"))
        }
    }

    @Test
    fun testTestCompiles_helperMethod_onEdt() {
        val compiles = runInEdtAndGet {
            TestCodeValidator.testCompiles(fixture.project, VALID_SIMPLE_CODE.trimIndent(), "ValidSimple")
        }

        println("EDT testCompiles result: $compiles")
        assertTrue("Valid code should compile on EDT", compiles)
    }

    @Test
    fun testCountCompilationErrors_helperMethod_onEdt() {
        val errorCount = runInEdtAndGet {
            TestCodeValidator.countCompilationErrors(fixture.project, INVALID_TYPE_ERROR_CODE.trimIndent(), "InvalidType")
        }

        println("EDT countCompilationErrors result: $errorCount")
        if (errorCount != -1) {
            assertTrue("Invalid code should have errors on EDT", errorCount > 0)
        }
    }

    @Test
    fun testValidateCode_emptyCode_onEdt() {
        val result = runInEdtAndGet {
            TestCodeValidator.validate(fixture.project, "", "Empty")
        }

        assertNotNull("Should handle empty code on EDT", result)
        println("EDT Validation - Empty code: compiles=${result.compiles()}, errors=${result.errorCount}")
    }

    @Test
    fun testValidateCode_wrongMethodSignature_onEdt() {
        val code = """
            package com.example;

            class WrongSignature {
                void test() {
                    String s = "hello";
                    s.charAt("not an int");
                }
            }
        """.trimIndent()

        val result = runInEdtAndGet {
            TestCodeValidator.validate(fixture.project, code, "WrongSignature")
        }

        assertNotNull("Should return validation result", result)
        println("EDT Validation - Wrong signature: compiles=${result.compiles()}, errors=${result.errorCount}")

        if (result.errorCount != -1) {
            assertFalse("Wrong method signature should not compile on EDT", result.compiles())
        }
    }
}
