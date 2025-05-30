# Workflow Builder Test Plan

## Testing Checklist

### 1. Basic Functionality Tests
- [ ] **Welcome Screen**
  - Appears on first load
  - "Get Started" button works
  - "Load Example" creates example workflow

- [ ] **Drag & Drop**
  - Can drag Code Agent from sidebar
  - Can drag Research Agent from sidebar
  - Can drag utility nodes (Input, Output, Composer)
  - Drop zone appears during drag
  - Nodes appear with animation

### 2. Node Interaction Tests
- [ ] **Node Selection**
  - Click node to select
  - Properties panel updates
  - Visual selection indicator
  - Ctrl+click for multi-select

- [ ] **Node Dragging**
  - Can drag nodes around canvas
  - Connections follow nodes
  - Smooth movement

- [ ] **Node Deletion**
  - Delete key removes selected node
  - Connections are cleaned up

### 3. Connection Tests
- [ ] **Creating Connections**
  - Drag from output port to input port
  - Visual feedback during connection
  - Success toast on connection
  - Error toast on invalid connection

- [ ] **Connection Validation**
  - Cannot connect node to itself
  - Cannot create duplicate connections
  - Proper error messages

### 4. Properties Panel Tests
- [ ] **Code Agent Properties**
  - Name can be edited
  - Task type dropdown works
  - Instructions textarea saves

- [ ] **Research Agent Properties**
  - Name can be edited
  - Task type shows research options
  - Instructions textarea saves

- [ ] **Live Updates**
  - Changes reflect immediately
  - Auto-save triggers

### 5. Workflow Execution Tests
- [ ] **Run Workflow**
  - Empty workflow shows error
  - Unconnected nodes show warning
  - Valid workflow runs
  - Nodes show running state
  - Success/error states display

- [ ] **Results Display**
  - Results modal appears
  - Results are formatted properly
  - Close button works

### 6. Save/Load Tests
- [ ] **Manual Save**
  - Ctrl+S saves workflow
  - Save button downloads JSON file
  - File contains complete workflow

- [ ] **Manual Load**
  - Ctrl+O opens file dialog
  - Can load saved workflow
  - Nodes and connections restore

- [ ] **Auto-save**
  - Changes trigger auto-save
  - Prompt to restore on reload
  - Can dismiss restore prompt

### 7. Keyboard Shortcuts
- [ ] Delete - removes selected items
- [ ] Ctrl+S - saves workflow
- [ ] Ctrl+O - opens workflow
- [ ] Ctrl+A - selects all nodes

### 8. Canvas Navigation
- [ ] Click and drag to pan
- [ ] Smooth panning motion
- [ ] Nodes move with canvas

### 9. Visual Feedback Tests
- [ ] **Hover States**
  - Palette items highlight
  - Nodes highlight
  - Ports enlarge on hover

- [ ] **Animations**
  - Node appearance animation
  - Connection animation
  - Toast notifications slide in

- [ ] **Status Indicators**
  - Idle state (gray)
  - Running state (orange pulse)
  - Success state (green)
  - Error state (red shake)

### 10. Error Handling Tests
- [ ] Network errors handled gracefully
- [ ] Invalid workflow configurations caught
- [ ] Clear error messages
- [ ] Recovery options available

## Performance Tests
- [ ] Create workflow with 10+ nodes
- [ ] Connect multiple nodes rapidly
- [ ] Drag multiple nodes simultaneously
- [ ] No lag or stuttering

## Cross-browser Tests
- [ ] Works in JCEF browser
- [ ] All features functional
- [ ] No console errors

## User Experience Tests
- [ ] Intuitive for first-time users
- [ ] Clear visual hierarchy
- [ ] Consistent interactions
- [ ] Professional appearance

## Known Issues to Verify Fixed
- [ ] ✅ Too many agent types (reduced to 2)
- [ ] ✅ Unclear drag & drop (visual feedback added)
- [ ] ✅ No save/load functionality (implemented)
- [ ] ✅ No visual feedback (animations added)
- [ ] ✅ No error prevention (validation added)

## Test Scenarios

### Scenario 1: Code Review Workflow
1. Add Input node
2. Add Code Agent (set to "review" task)
3. Add Output node
4. Connect Input → Code Agent → Output
5. Run workflow
6. Verify results

### Scenario 2: Search and Analyze Workflow
1. Add Input node
2. Add Research Agent (set to "search_text")
3. Add Code Agent (set to "analyze")
4. Add Composer node
5. Add Output node
6. Connect appropriately
7. Run workflow
8. Verify results

### Scenario 3: Error Recovery
1. Create invalid workflow (unconnected nodes)
2. Try to run - should show error
3. Fix connections
4. Run again - should work
5. Force close browser
6. Reopen - should prompt to restore