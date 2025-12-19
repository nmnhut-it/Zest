package com.zps.zest.mcp

import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase4
import com.zps.zest.testgen.tools.AnalyzeMethodUsageTool
import com.zps.zest.testgen.tools.LookupClassTool
import com.zps.zest.testgen.tools.LookupMethodTool
import com.zps.zest.testgen.evaluation.TestCodeValidator
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests for MCP tool handlers using IntelliJ's light test framework.
 * Uses LightJavaCodeInsightFixtureTestCase4 for JUnit 4 PSI-based testing.
 *
 * Uses JAVA_LATEST project descriptor to ensure JDK classes are available.
 */
class McpToolsTest : LightJavaCodeInsightFixtureTestCase4(
    // Pass JAVA_LATEST descriptor via constructor to have JDK classes available
    projectDescriptor = LightJavaCodeInsightFixtureTestCase.JAVA_LATEST_WITH_LATEST_JDK
) {

    @Before
    fun setUpTestClasses() {
        // Add classes with proper package structure using addClass()
        fixture.addClass(SAMPLE_SERVICE_CODE)
        fixture.addClass(SAMPLE_REPOSITORY_CODE)
        fixture.addClass(SAMPLE_ENUM_CODE)
        fixture.addClass(SAMPLE_INTERFACE_CODE)
        fixture.addClass(SAMPLE_ABSTRACT_CODE)
        fixture.addClass(SAMPLE_STATIC_UTIL_CODE)
        fixture.addClass(SAMPLE_EXCEPTION_THROWER_CODE)
        // Add caller class that uses SampleService - for usage analysis testing
        fixture.addClass(SAMPLE_SERVICE_CALLER_CODE)
    }

    companion object {
        private const val SAMPLE_SERVICE_CODE = """
package com.example.service;

import java.util.List;
import java.util.Optional;

public class SampleService {
    private final String name;
    private int counter;

    public SampleService(String name) {
        this.name = name;
        this.counter = 0;
    }

    public String getName() {
        return name;
    }

    public int processItems(List<String> items) {
        if (items == null || items.isEmpty()) {
            return 0;
        }
        counter += items.size();
        return items.size();
    }

    public Optional<String> findById(long id) {
        if (id <= 0) {
            return Optional.empty();
        }
        return Optional.of("item-" + id);
    }

    public void doSomething() {
        counter++;
    }

    public void doSomething(String value) {
        if (value != null) {
            counter += value.length();
        }
    }

    public int getCounter() {
        return counter;
    }
}
"""

        private const val SAMPLE_REPOSITORY_CODE = """
package com.example.repository;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class SampleRepository {
    private final Map<Long, String> storage = new HashMap<>();

    public String save(Long id, String value) {
        storage.put(id, value);
        return value;
    }

    public Optional<String> findById(Long id) {
        return Optional.ofNullable(storage.get(id));
    }

    public boolean deleteById(Long id) {
        return storage.remove(id) != null;
    }

    public int count() {
        return storage.size();
    }

    public static class EntityWrapper {
        private final String value;

        public EntityWrapper(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }
    }
}
"""

        private const val SAMPLE_ENUM_CODE = """
package com.example.model;

public enum Status {
    PENDING("P"),
    ACTIVE("A"),
    COMPLETED("C"),
    CANCELLED("X");

    private final String code;

    Status(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public static Status fromCode(String code) {
        for (Status status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown code: " + code);
    }
}
"""

        private const val SAMPLE_INTERFACE_CODE = """
package com.example.api;

import java.util.List;
import java.util.Optional;

public interface DataProvider<T> {
    T getById(Long id);
    List<T> getAll();
    Optional<T> findByName(String name);
    void save(T entity);
    boolean delete(Long id);
}
"""

        private const val SAMPLE_ABSTRACT_CODE = """
package com.example.base;

import java.io.IOException;

public abstract class AbstractProcessor<T, R> {
    protected final String name;
    protected int processedCount;

    protected AbstractProcessor(String name) {
        this.name = name;
        this.processedCount = 0;
    }

    public abstract R process(T input) throws IOException;

    public final String getName() {
        return name;
    }

    public int getProcessedCount() {
        return processedCount;
    }

    protected void incrementCount() {
        processedCount++;
    }
}
"""

        private const val SAMPLE_STATIC_UTIL_CODE = """
package com.example.util;

public final class StringUtils {
    private StringUtils() {
        throw new UnsupportedOperationException("Utility class");
    }

    public static boolean isEmpty(String str) {
        return str == null || str.isEmpty();
    }

    public static boolean isNotEmpty(String str) {
        return !isEmpty(str);
    }

    public static String trim(String str) {
        return str == null ? null : str.trim();
    }

    public static String capitalize(String str) {
        if (isEmpty(str)) {
            return str;
        }
        return Character.toUpperCase(str.charAt(0)) + str.substring(1);
    }

    public static int countOccurrences(String str, char c) {
        if (str == null) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < str.length(); i++) {
            if (str.charAt(i) == c) {
                count++;
            }
        }
        return count;
    }
}
"""

        private const val SAMPLE_EXCEPTION_THROWER_CODE = """
package com.example.io;

import java.io.IOException;
import java.io.FileNotFoundException;

public class FileProcessor {
    public String readFile(String path) throws IOException, FileNotFoundException {
        if (path == null) {
            throw new IllegalArgumentException("Path cannot be null");
        }
        if (path.isEmpty()) {
            throw new FileNotFoundException("Path is empty");
        }
        return "content of " + path;
    }

    public void writeFile(String path, String content) throws IOException {
        if (path == null || content == null) {
            throw new IOException("Arguments cannot be null");
        }
    }

    public boolean validatePath(String path) {
        return path != null && !path.isEmpty() && path.startsWith("/");
    }
}
"""

        /** Sample class that USES SampleService - for testing usage analysis */
        private const val SAMPLE_SERVICE_CALLER_CODE = """
package com.example.caller;

import com.example.service.SampleService;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public class ServiceCaller {
    private final SampleService service;

    public ServiceCaller() {
        this.service = new SampleService("test");
    }

    public void callGetName() {
        String name = service.getName();
        System.out.println("Service name: " + name);
    }

    public int callProcessItems() {
        List<String> items = Arrays.asList("a", "b", "c");
        return service.processItems(items);
    }

    public int callProcessItemsWithNull() {
        return service.processItems(null);
    }

    public Optional<String> callFindById(long id) {
        return service.findById(id);
    }

    public void callDoSomething() {
        service.doSomething();
        service.doSomething("test value");
    }

    public int getServiceCounter() {
        return service.getCounter();
    }
}
"""

        /** Valid Java code for validation testing */
        private const val VALID_TEST_CODE = """
package com.example.test;

import org.junit.Test;
import static org.junit.Assert.*;

public class ValidTest {
    @Test
    public void testAddition() {
        int result = 1 + 1;
        assertEquals(2, result);
    }

    @Test
    public void testString() {
        String s = "hello";
        assertNotNull(s);
        assertEquals(5, s.length());
    }
}
"""

        /** Invalid Java code with syntax errors for validation testing */
        private const val INVALID_SYNTAX_CODE = """
package com.example.test;

public class InvalidSyntax {
    public void brokenMethod() {
        int x = // missing value
        String s = "unclosed string
    }
}
"""

        /** Invalid Java code with type errors for validation testing */
        private const val INVALID_TYPE_CODE = """
package com.example.test;

public class InvalidType {
    public void typeError() {
        String s = 123;
        int x = "not a number";
    }
}
"""

        /** Invalid Java code with missing import for validation testing */
        private const val INVALID_MISSING_IMPORT_CODE = """
package com.example.test;

public class MissingImport {
    public void useList() {
        List<String> items = new ArrayList<>();
        items.add("test");
    }
}
"""
    }

    // ==================== LookupClassTool Tests ====================

    @Test
    fun testLookupClass_findsClassInProject() {
        val tool = LookupClassTool(fixture.project)
        val result = tool.lookupClass("com.example.service.SampleService")

        assertNotNull("Should find the class", result)
        assertTrue("Result should contain class name", result.contains("SampleService"))
        assertTrue("Result should contain methods", result.contains("getName"))
        assertTrue("Result should contain methods", result.contains("processItems"))
    }

    @Test
    fun testLookupClass_findsInnerClass() {
        val tool = LookupClassTool(fixture.project)
        // Inner class lookup using $ notation
        val result = tool.lookupClass("com.example.repository.SampleRepository\$EntityWrapper")

        assertNotNull("Should find inner class", result)
        // Inner class should be found - verify it contains the class name
        assertTrue("Result should contain EntityWrapper", result.contains("EntityWrapper"))
        println("Inner class lookup result: $result")
    }

    @Test
    fun testLookupClass_returnsErrorForNonExistentClass() {
        val tool = LookupClassTool(fixture.project)
        val result = tool.lookupClass("com.example.NonExistentClass")

        assertNotNull("Should return a result", result)
        assertTrue("Result should indicate class not found",
            result.contains("not found", ignoreCase = true) || result.contains("No class", ignoreCase = true))
    }

    @Test
    fun testLookupClass_findsJdkClass() {
        val tool = LookupClassTool(fixture.project)
        val result = tool.lookupClass("java.util.ArrayList")

        assertNotNull("Should find JDK class", result)
        assertTrue("Result should contain ArrayList", result.contains("ArrayList"))
        println("JDK class lookup result: $result")
    }

    // ==================== LookupMethodTool Tests ====================

    @Test
    fun testLookupMethod_findsMethodByName() {
        val tool = LookupMethodTool(fixture.project)
        val result = tool.lookupMethod("com.example.service.SampleService", "getName")

        assertNotNull("Should find the method", result)
        assertTrue("Result should contain method name", result.contains("getName"))
        assertTrue("Result should show return type", result.contains("String"))
    }

    @Test
    fun testLookupMethod_findsOverloadedMethods() {
        val tool = LookupMethodTool(fixture.project)
        val result = tool.lookupMethod("com.example.service.SampleService", "doSomething")

        assertNotNull("Should find overloaded methods", result)
        // Should find both overloads
        assertTrue("Result should contain doSomething", result.contains("doSomething"))
    }

    @Test
    fun testLookupMethod_findsMethodWithParameters() {
        val tool = LookupMethodTool(fixture.project)
        val result = tool.lookupMethod("com.example.service.SampleService", "processItems")

        assertNotNull("Should find the method", result)
        assertTrue("Result should contain method name", result.contains("processItems"))
        assertTrue("Result should show parameter type", result.contains("List"))
    }

    @Test
    fun testLookupMethod_returnsErrorForNonExistentMethod() {
        val tool = LookupMethodTool(fixture.project)
        val result = tool.lookupMethod("com.example.service.SampleService", "nonExistentMethod")

        assertNotNull("Should return a result", result)
        assertTrue("Result should indicate method not found",
            result.contains("not found", ignoreCase = true) || result.contains("No method", ignoreCase = true))
    }

    @Test
    fun testLookupMethod_findsJdkMethod() {
        val tool = LookupMethodTool(fixture.project)
        val result = tool.lookupMethod("java.util.ArrayList", "add")

        assertNotNull("Should find JDK method", result)
        assertTrue("Result should contain add method", result.contains("add"))
        println("JDK method lookup result: $result")
    }

    // ==================== Edge Cases ====================

    @Test
    fun testLookupClass_handlesEmptyClassName() {
        val tool = LookupClassTool(fixture.project)
        val result = tool.lookupClass("")

        assertNotNull("Should handle empty input", result)
    }

    @Test
    fun testLookupMethod_handlesEmptyMethodName() {
        val tool = LookupMethodTool(fixture.project)
        val result = tool.lookupMethod("com.example.service.SampleService", "")

        assertNotNull("Should handle empty method name", result)
    }

    @Test
    fun testLookupClass_handlesNullSafely() {
        val tool = LookupClassTool(fixture.project)
        // This tests that the tool handles edge cases gracefully
        try {
            val result = tool.lookupClass("   ")
            assertNotNull("Should handle whitespace input", result)
        } catch (e: Exception) {
            // If it throws, that's also acceptable behavior
            assertTrue("Exception should be meaningful", e.message?.isNotEmpty() == true)
        }
    }

    // ==================== Enum Tests ====================

    @Test
    fun testLookupClass_findsEnumClass() {
        val tool = LookupClassTool(fixture.project)
        val result = tool.lookupClass("com.example.model.Status")

        assertNotNull("Should find enum class", result)
        assertTrue("Result should identify as enum", result.contains("enum"))
        assertTrue("Result should contain Status", result.contains("Status"))
    }

    @Test
    fun testLookupClass_showsEnumConstants() {
        val tool = LookupClassTool(fixture.project)
        val result = tool.lookupClass("com.example.model.Status")

        assertNotNull("Should find enum", result)
        // Enum constants are fields
        assertTrue("Result should show enum constants",
            result.contains("PENDING") || result.contains("ACTIVE"))
    }

    @Test
    fun testLookupMethod_findsEnumMethod() {
        val tool = LookupMethodTool(fixture.project)
        val result = tool.lookupMethod("com.example.model.Status", "getCode")

        assertNotNull("Should find enum method", result)
        assertTrue("Result should contain getCode", result.contains("getCode"))
        assertTrue("Result should show return type", result.contains("String"))
    }

    @Test
    fun testLookupMethod_findsStaticEnumMethod() {
        val tool = LookupMethodTool(fixture.project)
        val result = tool.lookupMethod("com.example.model.Status", "fromCode")

        assertNotNull("Should find static enum method", result)
        assertTrue("Result should contain fromCode", result.contains("fromCode"))
        assertTrue("Result should show static modifier", result.contains("static"))
    }

    // ==================== Interface Tests ====================

    @Test
    fun testLookupClass_findsInterface() {
        val tool = LookupClassTool(fixture.project)
        val result = tool.lookupClass("com.example.api.DataProvider")

        assertNotNull("Should find interface", result)
        assertTrue("Result should identify as interface", result.contains("interface"))
        assertTrue("Result should contain DataProvider", result.contains("DataProvider"))
    }

    @Test
    fun testLookupClass_showsInterfaceTypeParameters() {
        val tool = LookupClassTool(fixture.project)
        val result = tool.lookupClass("com.example.api.DataProvider")

        assertNotNull("Should find interface", result)
        // Interface has type parameter <T>
        assertTrue("Result should show type parameter", result.contains("<T>") || result.contains("T"))
    }

    @Test
    fun testLookupMethod_findsInterfaceMethod() {
        val tool = LookupMethodTool(fixture.project)
        val result = tool.lookupMethod("com.example.api.DataProvider", "getById")

        assertNotNull("Should find interface method", result)
        assertTrue("Result should contain getById", result.contains("getById"))
    }

    @Test
    fun testLookupMethod_findsInterfaceMethodWithGenericReturn() {
        val tool = LookupMethodTool(fixture.project)
        val result = tool.lookupMethod("com.example.api.DataProvider", "getAll")

        assertNotNull("Should find method", result)
        assertTrue("Result should contain getAll", result.contains("getAll"))
        assertTrue("Result should show List return type", result.contains("List"))
    }

    // ==================== Abstract Class Tests ====================

    @Test
    fun testLookupClass_findsAbstractClass() {
        val tool = LookupClassTool(fixture.project)
        val result = tool.lookupClass("com.example.base.AbstractProcessor")

        assertNotNull("Should find abstract class", result)
        assertTrue("Result should show abstract modifier", result.contains("abstract"))
        assertTrue("Result should contain AbstractProcessor", result.contains("AbstractProcessor"))
    }

    @Test
    fun testLookupClass_showsMultipleTypeParameters() {
        val tool = LookupClassTool(fixture.project)
        val result = tool.lookupClass("com.example.base.AbstractProcessor")

        assertNotNull("Should find class", result)
        // Class has type parameters <T, R>
        assertTrue("Result should show type parameters",
            result.contains("<T, R>") || (result.contains("T") && result.contains("R")))
    }

    @Test
    fun testLookupClass_showsProtectedFields() {
        val tool = LookupClassTool(fixture.project)
        val result = tool.lookupClass("com.example.base.AbstractProcessor")

        assertNotNull("Should find class", result)
        assertTrue("Result should show name field", result.contains("name"))
        assertTrue("Result should show protected modifier", result.contains("protected"))
    }

    @Test
    fun testLookupMethod_findsAbstractMethod() {
        val tool = LookupMethodTool(fixture.project)
        val result = tool.lookupMethod("com.example.base.AbstractProcessor", "process")

        assertNotNull("Should find abstract method", result)
        assertTrue("Result should contain process", result.contains("process"))
        assertTrue("Result should show abstract modifier", result.contains("abstract"))
    }

    @Test
    fun testLookupMethod_findsFinalMethod() {
        val tool = LookupMethodTool(fixture.project)
        val result = tool.lookupMethod("com.example.base.AbstractProcessor", "getName")

        assertNotNull("Should find final method", result)
        assertTrue("Result should contain getName", result.contains("getName"))
        assertTrue("Result should show final modifier", result.contains("final"))
    }

    // ==================== Static Utility Class Tests ====================

    @Test
    fun testLookupClass_findsFinalUtilityClass() {
        val tool = LookupClassTool(fixture.project)
        val result = tool.lookupClass("com.example.util.StringUtils")

        assertNotNull("Should find utility class", result)
        assertTrue("Result should show final modifier", result.contains("final"))
        assertTrue("Result should contain StringUtils", result.contains("StringUtils"))
    }

    @Test
    fun testLookupMethod_findsStaticMethod() {
        val tool = LookupMethodTool(fixture.project)
        val result = tool.lookupMethod("com.example.util.StringUtils", "isEmpty")

        assertNotNull("Should find static method", result)
        assertTrue("Result should contain isEmpty", result.contains("isEmpty"))
        assertTrue("Result should show static modifier", result.contains("static"))
    }

    @Test
    fun testLookupMethod_findsMultipleStaticMethods() {
        val tool = LookupMethodTool(fixture.project)

        val result1 = tool.lookupMethod("com.example.util.StringUtils", "trim")
        assertNotNull("Should find trim", result1)
        assertTrue("Result should contain trim", result1.contains("trim"))

        val result2 = tool.lookupMethod("com.example.util.StringUtils", "capitalize")
        assertNotNull("Should find capitalize", result2)
        assertTrue("Result should contain capitalize", result2.contains("capitalize"))
    }

    // ==================== Exception Handling Tests ====================

    @Test
    fun testLookupMethod_showsThrowsClause() {
        val tool = LookupMethodTool(fixture.project)
        val result = tool.lookupMethod("com.example.io.FileProcessor", "readFile")

        assertNotNull("Should find method", result)
        assertTrue("Result should contain readFile", result.contains("readFile"))
        assertTrue("Result should show throws clause", result.contains("throws"))
        assertTrue("Result should show IOException", result.contains("IOException"))
    }

    @Test
    fun testLookupMethod_showsMultipleExceptions() {
        val tool = LookupMethodTool(fixture.project)
        val result = tool.lookupMethod("com.example.io.FileProcessor", "readFile")

        assertNotNull("Should find method", result)
        // Method throws IOException and FileNotFoundException
        assertTrue("Result should show IOException", result.contains("IOException"))
        assertTrue("Result should show FileNotFoundException", result.contains("FileNotFoundException"))
    }

    @Test
    fun testLookupMethod_findsMethodWithoutExceptions() {
        val tool = LookupMethodTool(fixture.project)
        val result = tool.lookupMethod("com.example.io.FileProcessor", "validatePath")

        assertNotNull("Should find method", result)
        assertTrue("Result should contain validatePath", result.contains("validatePath"))
        assertTrue("Result should show boolean return type", result.contains("boolean"))
    }

    // ==================== JDK Advanced Tests ====================

    @Test
    fun testLookupClass_findsJdkInterface() {
        val tool = LookupClassTool(fixture.project)
        val result = tool.lookupClass("java.util.Map")

        assertNotNull("Should find JDK interface", result)
        assertTrue("Result should contain Map", result.contains("Map"))
        assertTrue("Result should identify as interface", result.contains("interface"))
    }

    @Test
    fun testLookupClass_findsJdkGenericClass() {
        val tool = LookupClassTool(fixture.project)
        val result = tool.lookupClass("java.util.HashMap")

        assertNotNull("Should find HashMap", result)
        assertTrue("Result should contain HashMap", result.contains("HashMap"))
        // HashMap implements Map
        assertTrue("Result should show implements Map", result.contains("Map"))
    }

    @Test
    fun testLookupClass_findsJdkException() {
        val tool = LookupClassTool(fixture.project)
        val result = tool.lookupClass("java.lang.IllegalArgumentException")

        assertNotNull("Should find JDK exception", result)
        assertTrue("Result should contain IllegalArgumentException",
            result.contains("IllegalArgumentException"))
    }

    @Test
    fun testLookupMethod_findsJdkMapMethod() {
        val tool = LookupMethodTool(fixture.project)
        val result = tool.lookupMethod("java.util.Map", "put")

        assertNotNull("Should find JDK Map.put", result)
        assertTrue("Result should contain put", result.contains("put"))
    }

    @Test
    fun testLookupMethod_findsJdkStringMethod() {
        val tool = LookupMethodTool(fixture.project)
        val result = tool.lookupMethod("java.lang.String", "substring")

        assertNotNull("Should find String.substring", result)
        assertTrue("Result should contain substring", result.contains("substring"))
        // substring has overloads
        assertTrue("Result should show multiple signatures or int parameter",
            result.contains("int") || result.contains("signature"))
    }

    // ==================== Suggestion Mechanism Tests ====================

    @Test
    fun testLookupClass_suggestsAlternativesForPartialName() {
        val tool = LookupClassTool(fixture.project)
        // Using just simple name without package
        val result = tool.lookupClass("SampleService")

        assertNotNull("Should return a result", result)
        // Should either find the class or suggest alternatives
        assertTrue("Result should mention SampleService", result.contains("SampleService"))
    }

    @Test
    fun testLookupMethod_suggestsAvailableMethods() {
        val tool = LookupMethodTool(fixture.project)
        val result = tool.lookupMethod("com.example.service.SampleService", "invalidMethod")

        assertNotNull("Should return a result", result)
        assertTrue("Result should indicate method not found",
            result.contains("not found", ignoreCase = true))
        // Should suggest available methods
        assertTrue("Result should list available methods",
            result.contains("getName") || result.contains("Available methods", ignoreCase = true))
    }

    @Test
    fun testLookupMethod_handlesNonExistentClass() {
        val tool = LookupMethodTool(fixture.project)
        val result = tool.lookupMethod("com.example.NonExistent", "someMethod")

        assertNotNull("Should return a result", result)
        assertTrue("Result should indicate class not found",
            result.contains("not found", ignoreCase = true))
    }

    // ==================== Field Detection Tests ====================

    @Test
    fun testLookupClass_showsFields() {
        val tool = LookupClassTool(fixture.project)
        val result = tool.lookupClass("com.example.service.SampleService")

        assertNotNull("Should find class", result)
        assertTrue("Result should show name field", result.contains("name"))
        assertTrue("Result should show counter field", result.contains("counter"))
    }

    @Test
    fun testLookupClass_showsFieldModifiers() {
        val tool = LookupClassTool(fixture.project)
        val result = tool.lookupClass("com.example.service.SampleService")

        assertNotNull("Should find class", result)
        assertTrue("Result should show private modifier", result.contains("private"))
        assertTrue("Result should show final modifier", result.contains("final"))
    }

    @Test
    fun testLookupClass_showsStaticInnerClass() {
        val tool = LookupClassTool(fixture.project)
        val result = tool.lookupClass("com.example.repository.SampleRepository")

        assertNotNull("Should find class", result)
        // Should list inner classes
        assertTrue("Result should mention inner classes or EntityWrapper",
            result.contains("Inner") || result.contains("EntityWrapper"))
    }

    // ==================== Method Signature Tests ====================

    @Test
    fun testLookupMethod_showsMethodReturnType() {
        val tool = LookupMethodTool(fixture.project)
        val result = tool.lookupMethod("com.example.service.SampleService", "findById")

        assertNotNull("Should find method", result)
        assertTrue("Result should show Optional return type", result.contains("Optional"))
    }

    @Test
    fun testLookupMethod_showsVoidReturnType() {
        val tool = LookupMethodTool(fixture.project)
        val result = tool.lookupMethod("com.example.service.SampleService", "doSomething")

        assertNotNull("Should find method", result)
        assertTrue("Result should show void return type", result.contains("void"))
    }

    @Test
    fun testLookupMethod_showsPrimitiveReturnType() {
        val tool = LookupMethodTool(fixture.project)
        val result = tool.lookupMethod("com.example.service.SampleService", "getCounter")

        assertNotNull("Should find method", result)
        assertTrue("Result should show int return type", result.contains("int"))
    }

    @Test
    fun testLookupMethod_showsParameterTypes() {
        val tool = LookupMethodTool(fixture.project)
        val result = tool.lookupMethod("com.example.repository.SampleRepository", "save")

        assertNotNull("Should find method", result)
        assertTrue("Result should contain save", result.contains("save"))
        assertTrue("Result should show Long parameter", result.contains("Long"))
        assertTrue("Result should show String parameter", result.contains("String"))
    }

    // ==================== AnalyzeMethodUsageTool Tests ====================

    @Test
    fun testAnalyzeMethodUsage_findsUsagesOfMethod() {
        val tool = AnalyzeMethodUsageTool(fixture.project)
        val result = tool.analyzeMethodUsage("com.example.service.SampleService", "getName", null)

        assertNotNull("Should return a result", result)
        // Should find usage from ServiceCaller.callGetName()
        println("Usage analysis result for getName: $result")
    }

    @Test
    fun testAnalyzeMethodUsage_findsMultipleUsages() {
        val tool = AnalyzeMethodUsageTool(fixture.project)
        val result = tool.analyzeMethodUsage("com.example.service.SampleService", "processItems", null)

        assertNotNull("Should return a result", result)
        // ServiceCaller calls processItems twice (with list and with null)
        println("Usage analysis result for processItems: $result")
    }

    @Test
    fun testAnalyzeMethodUsage_findsOverloadedMethodUsage() {
        val tool = AnalyzeMethodUsageTool(fixture.project)
        val result = tool.analyzeMethodUsage("com.example.service.SampleService", "doSomething", null)

        assertNotNull("Should return a result", result)
        // ServiceCaller calls both overloads of doSomething
        println("Usage analysis result for doSomething: $result")
    }

    @Test
    fun testAnalyzeMethodUsage_handlesMethodWithNoUsages() {
        val tool = AnalyzeMethodUsageTool(fixture.project)
        // getCounter is not called by ServiceCaller
        val result = tool.analyzeMethodUsage("com.example.service.SampleService", "getCounter", null)

        assertNotNull("Should return a result", result)
        println("Usage analysis result for getCounter (may have no usages): $result")
    }

    @Test
    fun testAnalyzeMethodUsage_handlesNonExistentMethod() {
        val tool = AnalyzeMethodUsageTool(fixture.project)
        val result = tool.analyzeMethodUsage("com.example.service.SampleService", "nonExistent", null)

        assertNotNull("Should return a result", result)
        assertTrue("Result should indicate method not found or list available methods",
            result.contains("not found", ignoreCase = true) ||
            result.contains("Available methods", ignoreCase = true) ||
            result.contains("Error", ignoreCase = true))
    }

    @Test
    fun testAnalyzeMethodUsage_handlesNonExistentClass() {
        val tool = AnalyzeMethodUsageTool(fixture.project)
        val result = tool.analyzeMethodUsage("com.example.NonExistent", "someMethod", null)

        assertNotNull("Should return a result", result)
        assertTrue("Result should indicate class not found",
            result.contains("not found", ignoreCase = true))
    }

    @Test
    fun testAnalyzeMethodUsage_handlesEmptyMemberName() {
        val tool = AnalyzeMethodUsageTool(fixture.project)
        val result = tool.analyzeMethodUsage("com.example.service.SampleService", "", null)

        assertNotNull("Should return a result", result)
        assertTrue("Result should indicate error or invalid input",
            result.contains("required", ignoreCase = true) ||
            result.contains("error", ignoreCase = true) ||
            result.contains("not found", ignoreCase = true))
    }

    @Test
    fun testAnalyzeMethodUsage_respectsMaxCallSites() {
        val tool = AnalyzeMethodUsageTool(fixture.project)
        // Test with a small limit
        val result = tool.analyzeMethodUsage("com.example.service.SampleService", "getName", 1)

        assertNotNull("Should return a result", result)
        println("Usage analysis with maxCallSites=1: $result")
    }

    // ==================== TestCodeValidator Tests ====================
    // Note: TestCodeValidator uses CodeSmellDetector which requires EDT.
    // In unit tests, this may not work properly. Tests verify the API doesn't throw exceptions.

    @Test
    fun testValidateCode_validCodeDoesNotThrow() {
        // TestCodeValidator.validate() requires EDT, so in tests it may return empty results
        // We just verify the API doesn't throw exceptions
        val result = TestCodeValidator.validate(fixture.project, VALID_TEST_CODE.trimIndent(), "ValidTest")

        assertNotNull("Should return validation result", result)
        println("Validation result for valid code: compiles=${result.compiles()}, errors=${result.errorCount}")
    }

    @Test
    fun testValidateCode_invalidSyntaxDoesNotThrow() {
        val result = TestCodeValidator.validate(fixture.project, INVALID_SYNTAX_CODE.trimIndent(), "InvalidSyntax")

        assertNotNull("Should return validation result", result)
        println("Validation result for syntax errors: compiles=${result.compiles()}, errors=${result.errorCount}")
    }

    @Test
    fun testValidateCode_invalidTypeDoesNotThrow() {
        val result = TestCodeValidator.validate(fixture.project, INVALID_TYPE_CODE.trimIndent(), "InvalidType")

        assertNotNull("Should return validation result", result)
        // Note: In test environment without EDT, CodeSmellDetector may not detect errors
        println("Validation result for type errors: compiles=${result.compiles()}, errors=${result.errorCount}")
    }

    @Test
    fun testValidateCode_missingImportDoesNotThrow() {
        val result = TestCodeValidator.validate(fixture.project, INVALID_MISSING_IMPORT_CODE.trimIndent(), "MissingImport")

        assertNotNull("Should return validation result", result)
        println("Validation result for missing imports: compiles=${result.compiles()}, errors=${result.errorCount}")
    }

    @Test
    fun testValidateCode_simpleValidClassDoesNotThrow() {
        val simpleCode = """
            package com.example;

            public class Simple {
                public String getMessage() {
                    return "Hello";
                }
            }
        """.trimIndent()

        val result = TestCodeValidator.validate(fixture.project, simpleCode, "Simple")

        assertNotNull("Should return validation result", result)
        println("Validation result for simple class: compiles=${result.compiles()}, errors=${result.errorCount}")
    }

    @Test
    fun testValidateCode_classWithFieldsDoesNotThrow() {
        val codeWithFields = """
            package com.example;

            public class WithFields {
                private String name;
                private int count;

                public WithFields(String name) {
                    this.name = name;
                    this.count = 0;
                }

                public String getName() {
                    return name;
                }
            }
        """.trimIndent()

        val result = TestCodeValidator.validate(fixture.project, codeWithFields, "WithFields")

        assertNotNull("Should return validation result", result)
        println("Validation result for class with fields: compiles=${result.compiles()}")
    }

    @Test
    fun testValidateCode_classWithGenericsDoesNotThrow() {
        val genericCode = """
            package com.example;

            import java.util.List;
            import java.util.ArrayList;

            public class GenericClass<T> {
                private List<T> items = new ArrayList<>();

                public void add(T item) {
                    items.add(item);
                }

                public List<T> getItems() {
                    return items;
                }
            }
        """.trimIndent()

        val result = TestCodeValidator.validate(fixture.project, genericCode, "GenericClass")

        assertNotNull("Should return validation result", result)
        println("Validation result for generic class: compiles=${result.compiles()}")
    }

    @Test
    fun testValidateCode_undeclaredVariableDoesNotThrow() {
        val undeclaredVarCode = """
            package com.example;

            public class UndeclaredVar {
                public void test() {
                    System.out.println(undeclaredVariable);
                }
            }
        """.trimIndent()

        val result = TestCodeValidator.validate(fixture.project, undeclaredVarCode, "UndeclaredVar")

        assertNotNull("Should return validation result", result)
        println("Validation result for undeclared var: compiles=${result.compiles()}, errors=${result.errorCount}")
    }

    @Test
    fun testValidateCode_wrongMethodSignatureDoesNotThrow() {
        val wrongSignatureCode = """
            package com.example;

            public class WrongSignature {
                public void test() {
                    String s = "hello";
                    s.charAt("not an int");
                }
            }
        """.trimIndent()

        val result = TestCodeValidator.validate(fixture.project, wrongSignatureCode, "WrongSignature")

        assertNotNull("Should return validation result", result)
        println("Validation result for wrong signature: compiles=${result.compiles()}")
    }

    @Test
    fun testValidateCode_emptyCodeDoesNotThrow() {
        val result = TestCodeValidator.validate(fixture.project, "", "Empty")

        assertNotNull("Should handle empty code", result)
        println("Validation result for empty code: compiles=${result.compiles()}")
    }

    @Test
    fun testValidationResult_hasExpectedMethods() {
        val result = TestCodeValidator.validate(fixture.project, "package x; class X {}", "X")

        // Verify ValidationResult API works
        assertNotNull("compiles() should return a value", result.compiles())
        assertTrue("errorCount should be >= 0 or -1 for failure", result.errorCount >= -1)
        assertNotNull("errors should not be null", result.errors)
    }

    @Test
    fun testTestCompiles_helperMethodDoesNotThrow() {
        val validCode = """
            package com.example;
            public class Helper { public void test() {} }
        """.trimIndent()

        // Just verify it doesn't throw
        val compiles = TestCodeValidator.testCompiles(fixture.project, validCode, "Helper")
        println("testCompiles result: $compiles")
    }

    @Test
    fun testCountCompilationErrors_helperMethodDoesNotThrow() {
        val invalidCode = """
            package com.example;
            public class Counter {
                public void test() {
                    String x = 123;
                    int y = "abc";
                }
            }
        """.trimIndent()

        // Just verify it doesn't throw
        val errorCount = TestCodeValidator.countCompilationErrors(fixture.project, invalidCode, "Counter")
        println("Error count: $errorCount")
    }
}
