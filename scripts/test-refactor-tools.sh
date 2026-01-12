#!/bin/bash
# Quick test runner for refactor tools

set -e

echo "🧪 Testing Refactor Tools..."
echo ""

# Colors
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Function to run tests
run_tests() {
    local test_pattern=$1
    local description=$2

    echo -e "${YELLOW}Running: ${description}${NC}"

    if ./gradlew test --tests "$test_pattern" --console=plain 2>&1 | tee /tmp/test-output.log; then
        echo -e "${GREEN}✓ ${description} PASSED${NC}"
        return 0
    else
        echo -e "${RED}✗ ${description} FAILED${NC}"
        return 1
    fi
}

# Track failures
FAILURES=0

# Run unit tests
echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "  Unit Tests"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""

run_tests "com.zps.zest.mcp.refactor.RefactorToolsTest" "RefactorabilityAnalyzer & Coverage Tools" || ((FAILURES++))

# Run integration tests
echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "  Integration Tests"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""

run_tests "com.zps.zest.mcp.refactor.McpRefactorIntegrationTest" "MCP Tool Chain & End-to-End" || ((FAILURES++))

# Summary
echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "  Test Summary"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""

if [ $FAILURES -eq 0 ]; then
    echo -e "${GREEN}✅ All tests PASSED!${NC}"
    echo ""
    echo "Next steps:"
    echo "  • Run full test suite: ./gradlew test"
    echo "  • Check coverage: ./gradlew jacocoTestReport"
    echo "  • Test manually: Open IntelliJ → Claude Code → /refactor"
    exit 0
else
    echo -e "${RED}❌ ${FAILURES} test suite(s) FAILED${NC}"
    echo ""
    echo "Check logs at: build/test-results/test/"
    echo "Or run with --info for details: ./gradlew test --tests ... --info"
    exit 1
fi
