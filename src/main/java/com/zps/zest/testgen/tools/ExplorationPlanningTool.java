package com.zps.zest.testgen.tools;

import dev.langchain4j.agent.tool.Tool;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Tool for creating and managing exploration plans during context gathering.
 * Helps the AI agent organize investigation tasks systematically.
 */
public class ExplorationPlanningTool {

    private final List<PlanItem> plan = new ArrayList<>();
    private final AtomicInteger nextItemId = new AtomicInteger(1);
    private int toolBudget = 20; // Default budget
    private int toolsUsed = 0;

    @Tool("""
        Create a structured exploration plan before gathering context.

        This tool helps organize the investigation into systematic steps.
        You should call this FIRST before any other context gathering tools
        (except findProjectDependencies which can be called before planning).

        Parameters:
        - targetInfo: Description of what needs to be explored (e.g., "getProfile and saveProfile methods in ProfileStorageService")
        - toolBudget: Number of tool calls available for this session (provided in the initial request)

        The plan should include standard investigation areas:
        1. [DEPENDENCY] - Understand test frameworks and libraries
        2. [USAGE] - Find and analyze all callers of target methods
        3. [SCHEMA] - Read database schemas and config files
        4. [TESTS] - Research existing test patterns
        5. [ERROR] - Investigate error handling approaches
        6. [INTEGRATION] - Understand component interactions
        7. [VALIDATION] - Check input validation patterns

        Returns: Confirmation that the plan is created and ready for execution.

        Note: The plan is internal to you. You should output the plan in your response
        to show the user what you're going to investigate.
        """)
    public String createExplorationPlan(String targetInfo, Integer toolBudget) {
        if (toolBudget != null && toolBudget > 0) {
            this.toolBudget = toolBudget;
        }

        // Clear any existing plan
        plan.clear();
        nextItemId.set(1);
        toolsUsed = 0;

        return String.format("""
            ✅ Exploration plan framework created

            Target: %s
            Tool budget: %d

            ALREADY AVAILABLE (pre-computed):
            - Direct callers to target methods (static analysis)
            - Target class structure and dependencies
            - Project dependencies and testing frameworks
            - Call site code snippets with error handling (10 lines context)

            YOUR PLAN SHOULD FOCUS ON GAPS:
            - External files referenced in code (schemas, configs)
            - Indirect references NOT found by static analysis
            - Integration patterns crucial for testing

            Present your plan to the user in TWO parts:

            PART 1 - What's Already Known:
            Review the "Pre-Computed Analysis" section and list:
            - Method usage patterns available (mention method names and call site counts)
            - Testing framework detected
            - Dependencies found
            - Classes already analyzed

            PART 2 - Investigation Plan (GAPS):
            If pre-computed data is sufficient for testing, you can SKIP exploration entirely.
            Otherwise, use addPlanItems([...]) to create plan for what's NOT yet known.
            Focus on: referenced files, indirect callers, integration details, schemas.

            Remember: Pre-computed data already has direct callers. Only explore
            if you need indirect references or external resources crucial for tests.

            If skipping exploration: Acknowledge pre-computed data is sufficient,
            then proceed directly to markContextCollectionDone().

            Output both parts so the user understands your investigation strategy.
            """, targetInfo, this.toolBudget);
    }

    @Tool("""
        Add an item to the exploration plan.

        Parameters:
        - category: One of [DEPENDENCY, USAGE, SCHEMA, TESTS, ERROR, INTEGRATION, VALIDATION, OTHER]
        - description: What needs to be investigated
        - toolsNeeded: Estimated number of tool calls needed (optional, default 1)

        Returns: Confirmation with the assigned item ID.
        """)
    public String addPlanItem(String category, String description, Integer toolsNeeded) {
        int id = nextItemId.getAndIncrement();
        int tools = toolsNeeded != null && toolsNeeded > 0 ? toolsNeeded : 1;

        PlanItem item = new PlanItem(id, category, description, tools);
        plan.add(item);

        return String.format("Added plan item #%d [%s]: %s (est. %d tools)",
                id, category, description, tools);
    }

    @Tool("""
        Mark a plan item as complete and record findings.

        This helps track progress and ensures nothing is forgotten.

        Parameters:
        - itemId: The ID of the plan item to mark complete (from addPlanItem)
        - findings: Brief summary of what was discovered

        Returns: Confirmation and updated progress.
        """)
    public String completePlanItem(int itemId, String findings) {
        for (PlanItem item : plan) {
            if (item.id == itemId) {
                if (item.completed) {
                    return String.format("⚠️ Item #%d was already marked complete", itemId);
                }

                item.completed = true;
                item.findings = findings;

                int completed = (int) plan.stream().filter(i -> i.completed).count();
                int total = plan.size();

                return String.format("""
                    ✅ Plan item #%d completed
                    Findings: %s

                    Progress: %d/%d items complete (%.0f%%)
                    """, itemId, findings, completed, total, (completed * 100.0 / total));
            }
        }

        return "❌ Plan item #" + itemId + " not found in plan";
    }

    @Tool("""
        Check current plan status - what's done, what's pending, budget remaining.

        Returns: Summary of plan progress and tool usage.
        """)
    public String getPlanStatus() {
        if (plan.isEmpty()) {
            return "No plan created yet. Call createExplorationPlan() first.";
        }

        int completed = (int) plan.stream().filter(i -> i.completed).count();
        int total = plan.size();
        int pending = total - completed;

        StringBuilder status = new StringBuilder();
        status.append("**PLAN STATUS**\n");
        status.append("```\n");
        status.append(String.format("Progress: %d/%d items complete (%.0f%%)\n",
                completed, total, (completed * 100.0 / total)));
        status.append(String.format("Tool budget: %d/%d used (%.0f%%)\n",
                toolsUsed, toolBudget, (toolsUsed * 100.0 / toolBudget)));
        status.append("```\n\n");

        if (completed > 0) {
            status.append("**COMPLETED ITEMS:**\n");
            for (PlanItem item : plan) {
                if (item.completed) {
                    status.append(String.format("  ✅ #%d [%s] %s\n",
                            item.id, item.category, item.description));
                    if (item.findings != null && !item.findings.isEmpty()) {
                        status.append(String.format("     → %s\n", item.findings));
                    }
                }
            }
            status.append("\n");
        }

        if (pending > 0) {
            status.append("**PENDING ITEMS:**\n");
            for (PlanItem item : plan) {
                if (!item.completed) {
                    status.append(String.format("  ⏳ #%d [%s] %s\n",
                            item.id, item.category, item.description));
                }
            }
        }

        return status.toString();
    }

    @Tool("""
        Add multiple items to the exploration plan in one call (batch operation).

        This saves tool calls - use this instead of calling addPlanItem() multiple times.

        Parameters:
        - items: List of plan items to add, each with:
          * category: One of [DEPENDENCY, USAGE, SCHEMA, TESTS, ERROR, INTEGRATION, VALIDATION, OTHER]
          * description: What needs to be investigated
          * toolsNeeded: Estimated number of tool calls (optional, default 1)

        Example:
        addPlanItems([
          {category: "DEPENDENCY", description: "Find test frameworks", toolsNeeded: 1},
          {category: "USAGE", description: "Analyze getProfile usage", toolsNeeded: 3},
          {category: "SCHEMA", description: "Read user_profiles.sql", toolsNeeded: 1}
        ])

        Returns: Confirmation with assigned item IDs and range.
        """)
    public String addPlanItems(List<PlanItemInput> items) {
        if (items == null || items.isEmpty()) {
            return "No items provided to add";
        }

        int startId = nextItemId.get();
        List<Integer> addedIds = new ArrayList<>();

        for (PlanItemInput input : items) {
            int id = nextItemId.getAndIncrement();
            int tools = (input.toolsNeeded != null && input.toolsNeeded > 0) ? input.toolsNeeded : 1;

            PlanItem item = new PlanItem(id, input.category, input.description, tools);
            plan.add(item);
            addedIds.add(id);
        }

        int endId = nextItemId.get() - 1;

        return String.format("✅ Added %d plan items (#%d-#%d)\n" +
                "Categories: %s\n" +
                "Total plan items: %d",
                items.size(),
                startId,
                endId,
                items.stream().map(i -> i.category).distinct().collect(Collectors.joining(", ")),
                plan.size());
    }

    @Tool("""
        Mark multiple plan items as complete in one call (batch operation).

        This saves tool calls - use this instead of calling completePlanItem() multiple times.

        Parameters:
        - completions: List of completed items, each with:
          * itemId: The ID of the plan item to mark complete
          * findings: Brief summary of what was discovered

        Example:
        completePlanItems([
          {itemId: 1, findings: "Found JUnit 5, Mockito, AssertJ"},
          {itemId: 2, findings: "getProfile called from 5 locations"},
          {itemId: 3, findings: "Schema has user_id primary key, email unique"}
        ])

        Returns: Summary of completed items and updated progress.
        """)
    public String completePlanItems(List<PlanItemCompletion> completions) {
        if (completions == null || completions.isEmpty()) {
            return "No completions provided";
        }

        StringBuilder result = new StringBuilder();
        int successCount = 0;
        int alreadyCompleteCount = 0;
        int notFoundCount = 0;

        for (PlanItemCompletion completion : completions) {
            boolean found = false;
            for (PlanItem item : plan) {
                if (item.id == completion.itemId) {
                    found = true;
                    if (item.completed) {
                        alreadyCompleteCount++;
                    } else {
                        item.completed = true;
                        item.findings = completion.findings;
                        successCount++;
                    }
                    break;
                }
            }
            if (!found) {
                notFoundCount++;
            }
        }

        int completed = (int) plan.stream().filter(i -> i.completed).count();
        int total = plan.size();

        result.append(String.format("✅ Batch completion: %d items marked complete\n", successCount));
        if (alreadyCompleteCount > 0) {
            result.append(String.format("⚠️ %d items were already complete\n", alreadyCompleteCount));
        }
        if (notFoundCount > 0) {
            result.append(String.format("❌ %d items not found in plan\n", notFoundCount));
        }
        result.append(String.format("\nProgress: %d/%d items complete (%.0f%%)",
                completed, total, (completed * 100.0 / total)));

        return result.toString();
    }

    /**
     * Record that a tool was used (called internally by ContextGatheringTools).
     */
    public void recordToolUse() {
        toolsUsed++;
    }

    /**
     * Check if all plan items are complete.
     * Empty plan is considered complete (AI determined no exploration needed).
     */
    public boolean allPlanItemsComplete() {
        if (plan.isEmpty()) {
            return true; // Empty plan means AI determined pre-computed data is sufficient
        }
        return plan.stream().allMatch(i -> i.completed);
    }

    /**
     * Get list of incomplete plan items for error messages.
     */
    @NotNull
    public List<String> getIncompletePlanItems() {
        return plan.stream()
                .filter(i -> !i.completed)
                .map(i -> String.format("#%d [%s] %s", i.id, i.category, i.description))
                .collect(Collectors.toList());
    }

    /**
     * Get the tool budget.
     */
    public int getToolBudget() {
        return toolBudget;
    }

    /**
     * Get tools used count.
     */
    public int getToolsUsed() {
        return toolsUsed;
    }

    /**
     * Get percentage of budget used.
     */
    public double getBudgetUsedPercent() {
        return toolBudget > 0 ? (toolsUsed * 100.0 / toolBudget) : 0;
    }

    /**
     * Reset the planning tool for a new session.
     */
    public void reset() {
        plan.clear();
        nextItemId.set(1);
        toolsUsed = 0;
        toolBudget = 20;
    }

    /**
     * Get pre-tool hook: reminder of current task and checklist before AI makes tool calls.
     * Returns concise markdown with current goal and up to 5 pending items.
     */
    @NotNull
    public String getPreToolHook() {
        if (plan.isEmpty()) {
            return "";
        }

        // Find current in-progress item or first pending
        PlanItem current = plan.stream()
                .filter(i -> !i.completed)
                .findFirst()
                .orElse(null);

        if (current == null) {
            return "🎯 All plan items complete - ready for validation";
        }

        StringBuilder hook = new StringBuilder();
        hook.append("🎯 **Current**: [").append(current.category).append("] ")
            .append(current.description).append("\n");

        // Show up to 5 items with status
        List<PlanItem> displayItems = plan.stream().limit(5).toList();
        for (PlanItem item : displayItems) {
            String status = item.completed ? "✓" : "○";
            hook.append(status).append(" ").append(item.id).append(". [")
                .append(item.category).append("] ")
                .append(item.description);
            if (item.id == current.id) {
                hook.append(" **(IN PROGRESS)**");
            }
            hook.append("\n");
        }

        if (plan.size() > 5) {
            int remaining = plan.size() - 5;
            long remainingIncomplete = plan.stream().skip(5).filter(i -> !i.completed).count();
            hook.append("...").append(remaining).append(" more items (")
                .append(remainingIncomplete).append(" incomplete)\n");
        }

        hook.append("**Budget**: ").append(toolsUsed).append("/").append(toolBudget)
            .append(" tools (").append(String.format("%.0f%%", getBudgetUsedPercent())).append(")");

        return hook.toString();
    }

    /**
     * Get post-tool hook: reminder of progress and what's next after tool execution.
     * Returns concise markdown showing what was accomplished and what remains.
     */
    @NotNull
    public String getPostToolHook() {
        if (plan.isEmpty()) {
            return "";
        }

        int completed = (int) plan.stream().filter(i -> i.completed).count();
        int total = plan.size();

        StringBuilder hook = new StringBuilder();
        hook.append("**Progress**: ").append(completed).append("/").append(total)
            .append(" items complete (").append(String.format("%.0f%%", completed * 100.0 / total))
            .append(")\n");

        // Find next pending item
        PlanItem next = plan.stream()
                .filter(i -> !i.completed)
                .findFirst()
                .orElse(null);

        if (next != null) {
            hook.append("⚡ **Next**: #").append(next.id).append(" [")
                .append(next.category).append("] ")
                .append(next.description);
        } else {
            hook.append("✅ All items complete - call `markContextCollectionDone()`");
        }

        return hook.toString();
    }

    /**
     * Internal class to represent a plan item.
     */
    private static class PlanItem {
        final int id;
        final String category;
        final String description;
        final int toolsNeeded;
        boolean completed;
        String findings;

        PlanItem(int id, String category, String description, int toolsNeeded) {
            this.id = id;
            this.category = category;
            this.description = description;
            this.toolsNeeded = toolsNeeded;
            this.completed = false;
            this.findings = "";
        }
    }

    /**
     * Input record for batch plan item creation.
     */
    public static class PlanItemInput {
        public final String category;
        public final String description;
        public final Integer toolsNeeded;

        public PlanItemInput(String category, String description, Integer toolsNeeded) {
            this.category = category;
            this.description = description;
            this.toolsNeeded = toolsNeeded;
        }
    }

    /**
     * Input record for batch plan item completion.
     */
    public static class PlanItemCompletion {
        public final int itemId;
        public final String findings;

        public PlanItemCompletion(int itemId, String findings) {
            this.itemId = itemId;
            this.findings = findings;
        }
    }
}
