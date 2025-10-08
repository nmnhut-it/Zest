package com.zps.zest.chatui;

import junit.framework.TestCase;

import java.util.List;

public class CodeModificationToolsTest extends TestCase {

    private CodeModificationTools tools;

    @Override
    protected void setUp() {
        tools = new CodeModificationTools(null, null);
    }

    public void testFindFlexibleMatches_ExactMatch() {
        String content = "public class Test {\n    void method() {}\n}";
        String pattern = "void method() {}";

        List<CodeModificationTools.MatchResult> matches = tools.findFlexibleMatches(content, pattern);

        assertEquals(1, matches.size());
        assertEquals("void method() {}", content.substring(matches.get(0).startOffset, matches.get(0).endOffset));
    }

    public void testFindFlexibleMatches_ExtraSpaces() {
        String content = "public class Test {\n    void method() {}\n}";
        String pattern = "void  method()  {}";

        List<CodeModificationTools.MatchResult> matches = tools.findFlexibleMatches(content, pattern);

        assertEquals(1, matches.size());
    }

    public void testFindFlexibleMatches_TabsVsSpaces() {
        String content = "public class Test {\n\tvoid method() {}\n}";
        String pattern = "void    method()    {}";

        List<CodeModificationTools.MatchResult> matches = tools.findFlexibleMatches(content, pattern);

        assertEquals(1, matches.size());
    }

    public void testFindFlexibleMatches_ExtraNewlines() {
        String content = "public class Test {\n    void method() {\n\n    }\n}";
        String pattern = "void method() { }";

        List<CodeModificationTools.MatchResult> matches = tools.findFlexibleMatches(content, pattern);

        assertEquals(1, matches.size());
    }

    public void testFindFlexibleMatches_NoMatch() {
        String content = "public class Test {\n    void method() {}\n}";
        String pattern = "void nonExistent() {}";

        List<CodeModificationTools.MatchResult> matches = tools.findFlexibleMatches(content, pattern);

        assertEquals(0, matches.size());
    }

    public void testFindFlexibleMatches_MultipleMatches() {
        String content = "void foo() {}\nvoid bar() {}\nvoid foo() {}";
        String pattern = "void foo() {}";

        List<CodeModificationTools.MatchResult> matches = tools.findFlexibleMatches(content, pattern);

        assertEquals(2, matches.size());
    }

    public void testDetectWhitespaceMismatch_True() {
        String content = "void method() {}";
        String pattern = "void  method()  {}";

        boolean hasWhitespaceMismatch = tools.detectWhitespaceMismatch(content, pattern);

        assertTrue(hasWhitespaceMismatch);
    }

    public void testDetectWhitespaceMismatch_False_ExactMatch() {
        String content = "void method() {}";
        String pattern = "void method() {}";

        boolean hasWhitespaceMismatch = tools.detectWhitespaceMismatch(content, pattern);

        assertFalse(hasWhitespaceMismatch);
    }

    public void testDetectWhitespaceMismatch_False_NoMatch() {
        String content = "void method() {}";
        String pattern = "void nonExistent() {}";

        boolean hasWhitespaceMismatch = tools.detectWhitespaceMismatch(content, pattern);

        assertFalse(hasWhitespaceMismatch);
    }

    public void testFindFlexibleMatches_PreferStrictMatch() {
        String content = "void method() {}\nvoid  method()  {}";
        String pattern = "void method() {}";

        List<CodeModificationTools.MatchResult> matches = tools.findFlexibleMatches(content, pattern);

        assertEquals(1, matches.size());
        assertEquals("void method() {}", content.substring(matches.get(0).startOffset, matches.get(0).endOffset));
    }

    public void testFindFlexibleMatches_ComplexCode() {
        String content = "public void processData(String input) {\n" +
                "    if (input != null) {\n" +
                "        System.out.println(input);\n" +
                "    }\n" +
                "}";
        String pattern = "if (input != null) { System.out.println(input); }";

        List<CodeModificationTools.MatchResult> matches = tools.findFlexibleMatches(content, pattern);

        assertEquals(1, matches.size());
    }
}
