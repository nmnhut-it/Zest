package com.zps.zest.mcp

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for MCP schema validation.
 * These tests don't require IntelliJ platform - pure unit tests.
 */
class McpSchemaValidationTest {

    private val objectMapper = ObjectMapper()

    // ==================== Schema Structure Tests ====================

    @Test
    fun testGetCurrentFileSchema_isValidJson() {
        val schema = buildGetCurrentFileSchema()
        assertValidJsonSchema(schema, "getCurrentFile")
    }

    @Test
    fun testLookupMethodSchema_isValidJson() {
        val schema = buildLookupMethodSchema()
        assertValidJsonSchema(schema, "lookupMethod")
        assertSchemaHasProperty(schema, "projectPath")
        assertSchemaHasProperty(schema, "className")
        assertSchemaHasProperty(schema, "methodName")
    }

    @Test
    fun testLookupClassSchema_isValidJson() {
        val schema = buildLookupClassSchema()
        assertValidJsonSchema(schema, "lookupClass")
        assertSchemaHasProperty(schema, "projectPath")
        assertSchemaHasProperty(schema, "className")
    }

    @Test
    fun testAnalyzeMethodUsageSchema_isValidJson() {
        val schema = buildAnalyzeMethodUsageSchema()
        assertValidJsonSchema(schema, "analyzeMethodUsage")
        assertSchemaHasProperty(schema, "projectPath")
        assertSchemaHasProperty(schema, "className")
        assertSchemaHasProperty(schema, "memberName")
    }

    @Test
    fun testValidateCodeSchema_isValidJson() {
        val schema = buildValidateCodeSchema()
        assertValidJsonSchema(schema, "validateCode")
        assertSchemaHasProperty(schema, "projectPath")
        assertSchemaHasProperty(schema, "code")
        assertSchemaHasProperty(schema, "className")
    }

    @Test
    fun testShowFileSchema_isValidJson() {
        val schema = buildShowFileSchema()
        assertValidJsonSchema(schema, "showFile")
        assertSchemaHasProperty(schema, "projectPath")
        assertSchemaHasProperty(schema, "filePath")
    }

    // ==================== Required Fields Tests ====================

    @Test
    fun testLookupMethodSchema_hasRequiredFields() {
        val schema = buildLookupMethodSchema()
        val json = objectMapper.readTree(schema)
        val required = json.get("required")

        assertNotNull("Schema should have required array", required)
        assertTrue("Required should be array", required.isArray)

        val requiredFields = required.map { it.asText() }.toSet()
        assertTrue("projectPath should be required", "projectPath" in requiredFields)
        assertTrue("className should be required", "className" in requiredFields)
        assertTrue("methodName should be required", "methodName" in requiredFields)
    }

    @Test
    fun testLookupClassSchema_hasRequiredFields() {
        val schema = buildLookupClassSchema()
        val json = objectMapper.readTree(schema)
        val required = json.get("required")

        assertNotNull("Schema should have required array", required)
        val requiredFields = required.map { it.asText() }.toSet()
        assertTrue("projectPath should be required", "projectPath" in requiredFields)
        assertTrue("className should be required", "className" in requiredFields)
    }

    // ==================== Property Type Tests ====================

    @Test
    fun testAllSchemas_useStringTypes() {
        val schemas = listOf(
            buildGetCurrentFileSchema(),
            buildLookupMethodSchema(),
            buildLookupClassSchema(),
            buildAnalyzeMethodUsageSchema(),
            buildValidateCodeSchema(),
            buildShowFileSchema()
        )

        for (schema in schemas) {
            val json = objectMapper.readTree(schema)
            val properties = json.get("properties")

            properties?.fields()?.forEach { (name, prop) ->
                val type = prop.get("type")?.asText()
                assertEquals("Property '$name' should be string type", "string", type)
            }
        }
    }

    @Test
    fun testAllSchemas_haveDescriptions() {
        val schemas = listOf(
            "getCurrentFile" to buildGetCurrentFileSchema(),
            "lookupMethod" to buildLookupMethodSchema(),
            "lookupClass" to buildLookupClassSchema(),
            "analyzeMethodUsage" to buildAnalyzeMethodUsageSchema(),
            "validateCode" to buildValidateCodeSchema(),
            "showFile" to buildShowFileSchema()
        )

        for ((toolName, schema) in schemas) {
            val json = objectMapper.readTree(schema)
            val properties = json.get("properties")

            properties?.fields()?.forEach { (name, prop) ->
                val description = prop.get("description")?.asText()
                assertNotNull("$toolName.$name should have description", description)
                assertTrue("$toolName.$name description should not be empty",
                    description?.isNotBlank() == true)
            }
        }
    }

    // ==================== Helper Functions ====================

    private fun assertValidJsonSchema(schema: String, toolName: String) {
        try {
            val json = objectMapper.readTree(schema)
            assertNotNull("$toolName schema should parse as JSON", json)
            assertEquals("$toolName schema should be object type", "object", json.get("type")?.asText())
            assertNotNull("$toolName schema should have properties", json.get("properties"))
        } catch (e: Exception) {
            fail("$toolName schema is not valid JSON: ${e.message}")
        }
    }

    private fun assertSchemaHasProperty(schema: String, propertyName: String) {
        val json = objectMapper.readTree(schema)
        val properties = json.get("properties")
        assertNotNull("Schema should have property '$propertyName'", properties?.get(propertyName))
    }

    // ==================== Schema Builders (copied from ZestMcpHttpServer) ====================

    private fun buildGetCurrentFileSchema(): String = """
        {
          "type": "object",
          "properties": {
            "projectPath": {
              "type": "string",
              "description": "Absolute path to the IntelliJ project"
            }
          },
          "required": ["projectPath"]
        }
    """.trimIndent()

    private fun buildLookupMethodSchema(): String = """
        {
          "type": "object",
          "properties": {
            "projectPath": {
              "type": "string",
              "description": "Absolute path to the IntelliJ project"
            },
            "className": {
              "type": "string",
              "description": "Fully qualified class name"
            },
            "methodName": {
              "type": "string",
              "description": "Method name to look up"
            }
          },
          "required": ["projectPath", "className", "methodName"]
        }
    """.trimIndent()

    private fun buildLookupClassSchema(): String = """
        {
          "type": "object",
          "properties": {
            "projectPath": {
              "type": "string",
              "description": "Absolute path to the IntelliJ project"
            },
            "className": {
              "type": "string",
              "description": "Fully qualified class name"
            }
          },
          "required": ["projectPath", "className"]
        }
    """.trimIndent()

    private fun buildAnalyzeMethodUsageSchema(): String = """
        {
          "type": "object",
          "properties": {
            "projectPath": {
              "type": "string",
              "description": "Absolute path to the IntelliJ project"
            },
            "className": {
              "type": "string",
              "description": "Fully qualified class name"
            },
            "memberName": {
              "type": "string",
              "description": "Method or field name to analyze usage patterns"
            }
          },
          "required": ["projectPath", "className", "memberName"]
        }
    """.trimIndent()

    private fun buildValidateCodeSchema(): String = """
        {
          "type": "object",
          "properties": {
            "projectPath": {
              "type": "string",
              "description": "Absolute path to the IntelliJ project"
            },
            "code": {
              "type": "string",
              "description": "Java code to validate"
            },
            "className": {
              "type": "string",
              "description": "Name of the class (used for error reporting)"
            }
          },
          "required": ["projectPath", "code", "className"]
        }
    """.trimIndent()

    private fun buildShowFileSchema(): String = """
        {
          "type": "object",
          "properties": {
            "projectPath": {
              "type": "string",
              "description": "Absolute path to the IntelliJ project"
            },
            "filePath": {
              "type": "string",
              "description": "Absolute path to the file to open in the editor"
            }
          },
          "required": ["projectPath", "filePath"]
        }
    """.trimIndent()
}
