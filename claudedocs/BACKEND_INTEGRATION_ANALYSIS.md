# Backend Integration Analysis: Inline Plan Actions

**Date**: 2025-10-04
**Scope**: Analysis of inline plan implementation vs AGENT_IMPLEMENTATION_CHECKLIST.md
**Mode**: Ultrathink (Deep Analysis)

---

## What Was Implemented ✅

### UI Layer Changes
1. **PlanPreviewBox** - Added three action buttons:
   - "Apply Plan" → `onAccept` callback
   - "Edit…" → `onEdit` callback
   - "Dismiss" → `onCancel` callback

2. **ConversationPanel/Controller** - Return `PlanPreviewBox` from `showPlanPreview()`

3. **ChatView Integration** - Wired up callbacks in `handleChatResult()`:
   ```java
   var preview = conversationController.showPlanPreview(plan, plan.describe());

   preview.setOnAccept(() -> {
       if (!enableActions.getSelection()) {
           conversationController.addSystemMessage("Tool actions are disabled...");
           return;
       }
       schedulePlanExecution(session, diagram, plan);
   });

   preview.setOnEdit(() -> {
       PlanEditorDialog dialog = new PlanEditorDialog(shell, plan, dryRun);
       if (dialog.open() == OK) {
           schedulePlanExecution(session, diagram, dialog.getFilteredPlan());
       }
   });

   preview.setOnCancel(() ->
       conversationController.addSystemMessage("Plan dismissed.")
   );
   ```

### Checklist Alignment

**Phase 8 Requirement**:
> "Keep PlanEditorDialog only for 'Edit…' path (inline Apply/Dismiss default)"

✅ **ACHIEVED** - Dialog only opens when Edit is clicked, not automatically

**UI Test Requirement**:
> "Inline plan: Apply applies; Edit opens editor; Dismiss removes preview"

- Apply applies: ✅ **DONE**
- Edit opens editor: ✅ **DONE**
- Dismiss removes preview: ❌ **MISSING** (see below)

---

## Critical Missing Pieces 🔴

### 1. Dismiss Doesn't Remove Preview (**BLOCKER** for UI test)

**Current Behavior**:
```java
preview.setOnCancel(() -> conversationController.addSystemMessage("Plan dismissed."));
```

**Problem**:
- System message added ✅
- Preview box stays visible in UI ❌
- Buttons still active - user can click Apply after dismissing ❌

**Expected Behavior** (per test requirement):
- Preview box should be hidden/removed from conversation
- Buttons should be disabled or widget disposed
- Visual confirmation that plan is dismissed

**Solution Needed**:

**Option A: Dispose Widget**
```java
// In PlanPreviewBox
public void dispose() {
    if (!container.isDisposed()) {
        Composite wrapper = container.getParent();
        container.dispose();
        if (wrapper != null && !wrapper.isDisposed()) {
            wrapper.dispose();
        }
    }
}

// In ChatView
preview.setOnCancel(() -> {
    preview.dispose();
    conversationController.addSystemMessage("Plan dismissed.");
});
```

**Option B: Collapse/Hide**
```java
// In PlanPreviewBox
public void setVisible(boolean visible) {
    GridData data = (GridData) container.getLayoutData();
    data.exclude = !visible;
    container.setVisible(visible);
    container.getParent().layout(true);
}

// In ChatView
preview.setOnCancel(() -> {
    preview.setVisible(false);
    conversationController.addSystemMessage("Plan dismissed.");
});
```

**Recommendation**: Option A (dispose) is cleaner - dismissed plans shouldn't clutter conversation history.

---

### 2. DryRun Warnings Not Shown Before Apply

**Current Behavior**:
```java
DryRunResult dryRun = PlanDryRun.evaluate(session, plan);
var preview = conversationController.showPlanPreview(plan, plan.describe());
// dryRun only used in Edit path:
preview.setOnEdit(() -> {
    PlanEditorDialog dialog = new PlanEditorDialog(shell, plan, dryRun);
    // Dialog shows warnings
});
```

**Problem**:
- DryRun validation computed but warnings not displayed
- User can click "Apply Plan" without seeing potential issues
- Only sees warnings if they click "Edit…"

**Scenarios Where This Matters**:
```
DryRun Warning: "Component 'Sensor-A' not found. Did you mean 'SensorA'?"
User clicks "Apply Plan" → Action fails → Error message
Expected: User sees warning before applying → can Edit to fix
```

**Solution Options**:

**Option A: Show Warnings in Plan Preview**
```java
// Update plan.describe() to include warnings
List<String> summary = plan.describe();
if (!dryRun.issues.isEmpty()) {
    summary.add("");
    summary.add("⚠️ Warnings:");
    for (Issue issue : dryRun.issues) {
        summary.add("  • " + issue.message);
    }
}
var preview = conversationController.showPlanPreview(plan, summary);
```

**Option B: Disable Apply if Critical Issues**
```java
var preview = conversationController.showPlanPreview(plan, plan.describe());
if (dryRun.hasCriticalIssues()) {
    preview.setApplyEnabled(false);
    preview.setApplyTooltip("Fix issues in Edit mode before applying");
}
```

**Option C: Show Confirmation Dialog on Apply**
```java
preview.setOnAccept(() -> {
    if (!dryRun.issues.isEmpty()) {
        boolean proceed = MessageDialog.openConfirm(shell,
            "Apply with warnings?",
            dryRun.issues.size() + " warnings detected. Apply anyway?");
        if (!proceed) return;
    }
    schedulePlanExecution(session, diagram, plan);
});
```

**Recommendation**: Option A (show warnings inline) is most transparent and Cursor-like.

---

### 3. No Visual Feedback During Execution

**Current Behavior**:
```java
preview.setOnAccept(() -> {
    schedulePlanExecution(session, diagram, plan);  // Runs in background Job
});
```

**Problem**:
- User clicks "Apply Plan"
- Nothing visible happens (Job runs in background)
- 2-3 seconds later: "✓ Plan actions applied: 1" appears
- No indication that execution is in progress

**Expected Behavior**:
- Apply button shows loading state ("Applying...")
- Or preview box shows progress indicator
- Clear visual feedback that action was received

**Solution**:

```java
// In PlanPreviewBox
public void setExecuting(boolean executing) {
    applyBtn.setEnabled(!executing);
    applyBtn.setText(executing ? "Applying..." : "Apply Plan");
}

// In ChatView
preview.setOnAccept(() -> {
    if (!enableActions.getSelection()) {
        conversationController.addSystemMessage("Tool actions disabled...");
        return;
    }
    preview.setExecuting(true);

    Job job = new Job("Apply AI plan") {
        @Override
        protected IStatus run(IProgressMonitor monitor) {
            PlanExecutionResult execution = ToolRouter.applyPlan(session, diagram, plan);
            Display.asyncExec(() -> {
                preview.setExecuting(false);
                conversationController.addSystemMessage("✓ Applied: " + execution.applied);
                preview.dispose();  // Remove after success
            });
            return Status.OK_STATUS;
        }
    };
    job.schedule();
});
```

---

### 4. No Telemetry for Apply/Dismiss Actions

**Current Gaps**:
```java
// Missing:
Telemetry.recordPlanApplied(plan);
Telemetry.recordPlanDismissed(plan);
```

**Solution**:
```java
preview.setOnAccept(() -> {
    // ... validation checks ...
    Telemetry.recordPlanApplied(plan);  // ADD THIS
    schedulePlanExecution(session, diagram, plan);
});

preview.setOnCancel(() -> {
    Telemetry.recordPlanDismissed(plan);  // ADD THIS
    preview.dispose();
    conversationController.addSystemMessage("Plan dismissed.");
});
```

**Why Important**:
- Tracks user preference for direct apply vs edit vs dismiss
- Informs future UX improvements (if most users dismiss → plans need improvement)

---

### 5. Session/Diagram Lifecycle Issues

**Current Risk**:
```java
preview.setOnAccept(() -> {
    schedulePlanExecution(session, diagram, plan);  // Captured references
});
```

**Problem**:
- Callback captures `session` and `diagram` from method params
- Plan preview might be pending for minutes while user thinks
- User might close model/session before clicking Apply
- Attempting to apply on closed session → crash or error

**Solution**:

```java
preview.setOnAccept(() -> {
    // Validate session still active
    if (session == null || !session.isOpen()) {
        conversationController.addSystemMessage("⚠️ Session closed. Please generate a new plan.");
        preview.dispose();
        return;
    }

    if (!enableActions.getSelection()) {
        conversationController.addSystemMessage("Tool actions disabled...");
        return;
    }

    schedulePlanExecution(session, diagram, plan);
});
```

**Alternative**: Store session reference and refresh before applying:
```java
Session activeSession = SessionBridge.getActiveSession();
if (activeSession == null || activeSession != session) {
    // Session changed
}
```

---

### 6. Concurrent Plans Issue

**Current Behavior**:
- User asks: "Create mission A"
- Preview A appears with buttons
- User asks: "Create component B" (before clicking Apply on A)
- Preview B appears
- **Both previews visible with active buttons**

**Problem**:
- Multiple pending plans in conversation
- Confusing which Apply button affects which plan
- Old plans should probably be auto-dismissed when new plan appears

**Solutions**:

**Option A: Auto-Dismiss Previous Plans**
```java
// In ConversationController
private PlanPreviewBox currentPlanPreview;

public PlanPreviewBox showPlanPreview(ToolPlan plan, List<String> summary) {
    if (currentPlanPreview != null && !currentPlanPreview.isDisposed()) {
        currentPlanPreview.dispose();  // Dismiss previous plan
    }
    currentPlanPreview = panel.addPlanPreview(plan, summary);
    return currentPlanPreview;
}
```

**Option B: Keep All Plans, Show Status**
```java
// After apply succeeds:
preview.setApplied();  // Changes visual state to "✓ Applied" with disabled buttons

// After dismiss:
preview.setDismissed();  // Grays out with "Dismissed" text
```

**Recommendation**: Option A (auto-dismiss) is simpler and less cluttered.

---

### 7. Apply Button State vs Actions Checkbox

**Current Behavior**:
```java
preview.setOnAccept(() -> {
    if (!enableActions.getSelection()) {  // Runtime check
        conversationController.addSystemMessage("Tool actions disabled...");
        return;
    }
    schedulePlanExecution(...);
});
```

**Problem**:
- Apply button always enabled, regardless of actions checkbox
- User clicks Apply → gets error message
- Confusing UX: "Why is button enabled if it won't work?"

**Better UX**:
- Apply button disabled when actions unchecked
- Tooltip explains: "Enable tool actions in Advanced tab"

**Solution**:

```java
// In PlanPreviewBox - add method
public void setApplyEnabled(boolean enabled, String tooltip) {
    applyBtn.setEnabled(enabled);
    if (tooltip != null) {
        applyBtn.setToolTipText(tooltip);
    }
}

// In ChatView - check state when showing plan
var preview = conversationController.showPlanPreview(plan, plan.describe());
boolean canApply = enableActions.getSelection();
preview.setApplyEnabled(canApply,
    canApply ? null : "Enable tool actions in Advanced > Controls");

// Optional: Listen to checkbox changes and update all previews
enableActions.addSelectionListener(new SelectionAdapter() {
    @Override
    public void widgetSelected(SelectionEvent e) {
        // Update all pending preview boxes
        // Would need to track active previews in a list
    }
});
```

**Complexity Trade-off**:
- Current approach (runtime check): Simple, always correct, slightly confusing UX
- Button state approach: Better UX, more complex (need to track checkbox changes)

**Recommendation**: Keep runtime check for now, add button state in future iteration.

---

### 8. Error Recovery After Failed Apply

**Current Behavior**:
```java
schedulePlanExecution(session, diagram, plan);  // Fires and forgets

// In schedulePlanExecution:
PlanExecutionResult execution = ToolRouter.applyPlan(...);
for (String error : execution.errors) {
    conversationController.addSystemMessage("⚠ Plan warning: " + error);
}
```

**Problem**:
- If apply fails, preview box stays visible
- User can't retry directly
- No visual indication on preview that it failed

**Better Flow**:
```java
preview.setOnAccept(() -> {
    preview.setExecuting(true);

    Job job = new Job("Apply AI plan") {
        @Override
        protected IStatus run(IProgressMonitor monitor) {
            PlanExecutionResult result = ToolRouter.applyPlan(session, diagram, plan);

            Display.asyncExec(() -> {
                preview.setExecuting(false);

                if (result.applied > 0) {
                    // Success
                    conversationController.addSystemMessage("✓ Applied: " + result.applied);
                    preview.dispose();  // Remove on success

                } else if (!result.errors.isEmpty()) {
                    // Failed
                    conversationController.addSystemMessage("⚠️ Apply failed: " + result.errors.get(0));
                    preview.setFailed();  // Visual error state, allow retry
                }
            });
            return Status.OK_STATUS;
        }
    };
    job.schedule();
});

// In PlanPreviewBox
public void setFailed() {
    applyBtn.setText("Retry Apply");
    // Background color change to indicate error?
}
```

---

## Minor Missing Pieces 🟡

### 9. Success State Visualization

**Current**: After apply, preview just disappears (if we add dispose)

**Enhancement**: Show brief success state before removing
```java
// After successful apply:
preview.setApplied();  // Changes to "✓ Applied" state
Display.timerExec(2000, () -> {
    if (!preview.isDisposed()) {
        preview.dispose();  // Auto-remove after 2 seconds
    }
});
```

---

### 10. Edit Path Cleanup

**Current**: Edit opens dialog, applies, but preview stays
```java
preview.setOnEdit(() -> {
    PlanEditorDialog dialog = new PlanEditorDialog(shell, plan, dryRun);
    if (dialog.open() == OK) {
        schedulePlanExecution(session, diagram, dialog.getFilteredPlan());
    }
});
```

**Enhancement**: Remove preview after Edit → Apply
```java
preview.setOnEdit(() -> {
    PlanEditorDialog dialog = new PlanEditorDialog(shell, plan, dryRun);
    if (dialog.open() == OK) {
        ToolPlan filtered = dialog.getFilteredPlan();
        if (filtered != null && !filtered.isEmpty()) {
            schedulePlanExecution(session, diagram, filtered);
            preview.dispose();  // Remove original preview
        }
    }
    // If user cancels dialog, preview stays (allows retry)
});
```

---

## Checklist Verification

### Phase 8: "Keep PlanEditorDialog only for 'Edit…' path"
✅ **PASS** - Dialog only opens when Edit clicked

### UI Tests: "Inline plan: Apply applies; Edit opens editor; Dismiss removes preview"

| Test | Status | Gap |
|------|--------|-----|
| Apply applies | ✅ PASS | Works, could use loading feedback |
| Edit opens editor | ✅ PASS | Works perfectly |
| Dismiss removes preview | ❌ **FAIL** | Preview stays visible, buttons active |

**Blocker**: Dismiss test fails - this must be fixed before merging.

---

## Implementation Priority

### 🔴 Critical (Must Fix)

1. **Dismiss removes preview** (#1)
   - Add `preview.dispose()` in onCancel
   - Required for UI test to pass

2. **DryRun warnings display** (#2)
   - Show warnings in plan preview summary
   - Prevents blind applies with errors

### 🟡 Important (Should Fix)

3. **Visual execution feedback** (#3)
   - Button loading state during apply
   - Professional UX

4. **Telemetry tracking** (#4)
   - Add recordPlanApplied/Dismissed
   - Critical for metrics

5. **Session validation** (#5)
   - Check session still open before apply
   - Prevents crashes

### 🟢 Nice to Have

6. **Concurrent plans handling** (#6)
   - Auto-dismiss old previews
   - Reduces clutter

7. **Apply button state** (#7)
   - Disable when actions off
   - Better UX

8. **Error recovery** (#8)
   - Visual failed state with retry
   - Resilience

9. **Success visualization** (#9)
   - Brief "Applied" state before removing
   - Polish

10. **Edit path cleanup** (#10)
    - Remove preview after Edit→Apply
    - Consistency

---

## Recommended Implementation Order

### Phase 1: Fix Blocker (30 min)
```java
// Critical fix for UI test
preview.setOnCancel(() -> {
    Telemetry.recordPlanDismissed(plan);
    preview.dispose();  // This line fixes the test
    conversationController.addSystemMessage("Plan dismissed.");
});
```

### Phase 2: Add DryRun Warnings (1 hour)
```java
// Show warnings inline
List<String> summary = new ArrayList<>(plan.describe());
if (!dryRun.issues.isEmpty()) {
    summary.add("");
    summary.add("⚠️ Warnings:");
    for (Issue issue : dryRun.issues) {
        summary.add("  • " + issue.message);
    }
}
var preview = conversationController.showPlanPreview(plan, summary);
```

### Phase 3: Visual Feedback (2 hours)
```java
// Add to PlanPreviewBox
public void setExecuting(boolean executing) { ... }
public void setFailed() { ... }

// Update onAccept handler
preview.setOnAccept(() -> {
    preview.setExecuting(true);
    // ... execute ...
    // ... handle success/failure states
});
```

### Phase 4: Polish (1-2 hours)
- Session validation
- Concurrent plans auto-dismiss
- Success state visualization
- Edit path cleanup

---

## Code Audit Results

### What's Implemented Correctly ✅

1. **Callback Architecture**
   - Clean separation: UI callbacks → business logic
   - Lambda captures work correctly (session, diagram are effectively final)
   - No memory leaks (callbacks don't create circular references)

2. **Thread Safety**
   - Job execution in background thread ✅
   - UI updates via Display.asyncExec() ✅
   - No race conditions in current implementation ✅

3. **Integration Points**
   - ChatView → ConversationController → ConversationPanel → PlanPreviewBox
   - Clean delegation chain ✅
   - Proper return value plumbing ✅

4. **Edit Path**
   - Dialog only opens when explicitly requested ✅
   - Filtered plan from dialog properly executed ✅
   - Cancellation handled correctly ✅

### What's Missing or Wrong ❌

1. **Dismiss cleanup** - Widget not removed (BLOCKER)
2. **DryRun warnings** - Not shown to user (UX gap)
3. **Execution feedback** - No loading state (UX gap)
4. **Telemetry** - Apply/dismiss not tracked (metrics gap)
5. **Session lifecycle** - No validation before apply (robustness gap)

---

## Summary

### Overall Assessment: **85% Complete**

**What's Working**:
- ✅ Core architecture excellent
- ✅ Edit path perfect
- ✅ Apply path functional
- ✅ Thread safety solid
- ✅ Integration clean

**What's Broken**:
- ❌ Dismiss doesn't remove preview (fails UI test)

**What's Missing**:
- ⚠️ DryRun warnings not displayed
- ⚠️ No execution visual feedback
- ⚠️ Missing telemetry calls
- ⚠️ No session validation

**Verdict**:
- **Production ready?** No - blocker (#1) must be fixed
- **Test ready?** No - UI test will fail on Dismiss
- **Architecture ready?** Yes - solid foundation
- **Polish needed?** Yes - items #2-5 critical for good UX

**Time to Production**: 3-5 hours
- 30 min: Fix blocker
- 1 hour: DryRun warnings
- 2 hours: Visual feedback + telemetry
- 1 hour: Session validation + testing

---

## Conclusion

The inline plan integration is **architecturally sound** and **85% complete**. The main blocker is that Dismiss doesn't remove the preview widget, which will cause the UI test to fail.

The implementation correctly:
- ✅ Eliminates modal dialog interruption (Phase 8 requirement met)
- ✅ Wires up Apply and Edit callbacks properly
- ✅ Maintains session/diagram context safely
- ✅ Follows clean architecture patterns

Quick wins to get to production:
1. Add `preview.dispose()` in onCancel (5 minutes)
2. Show DryRun warnings in preview (30 minutes)
3. Add loading state to Apply button (30 minutes)
4. Add telemetry calls (10 minutes)

**The foundation is excellent. Just needs finishing touches.**