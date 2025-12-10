# Capella Integration Issues Analysis

## Critical Issues 🔴

No outstanding critical issues. Previously reported items have been fixed; see **Resolved Issues ✅** for details.

---

## High-Risk Issues 🟡

### 4. **Brittle Reflection-Based Diagram API Access**
**Location**: `plugins/ai-capella/src/org/polarsys/capella/ai/tools/DiagramCommands.java:101-161`

```java
Class<?> cFactory = Class.forName("org.eclipse.sirius.diagram.ui.tools.api.command.CreateNodeCommand");
cmd = cFactory.getConstructor(org.eclipse.emf.transaction.TransactionalEditingDomain.class, ...)
    .newInstance(domain, dd, semantic, 100, 100, 0, 0);
```

**Issues**:
- Relies on internal Sirius UI APIs via reflection
- No compile-time checking - will break silently if Sirius changes APIs
- Constructor signatures may change between Sirius versions
- Catches `ClassNotFoundException` but continues with incomplete functionality

**Impact**:
- Diagram node/edge creation will fail silently in newer Sirius versions
- Plugin appears to work but produces incomplete diagrams
- Difficult to debug for end users

**Fix (Now Implemented)**:
- Removed reflection calls to `CreateNodeCommand`/`CreateEdgeCommand` and switched to a safe path: semantic creation (outside) + `DialectManager.refresh` + `DialectUIManager.reveal` for view materialization. See `DiagramCommands.createNode/createEdge`.

**Follow‑up**:
- Evaluate executing diagram tools via Sirius APIs for richer placement/labeling when needed, but avoid internal UI classes.

---

> _Note_: Issues 5 and 6 have been resolved; they are retained for traceability under **Resolved Issues ✅**.

---

## Medium-Risk Issues ⚠️

### 7. **Inconsistent EMF Resource Iteration**
**Location**: `plugins/ai-capella/src/org/polarsys/capella/ai/model/ModelOps.java:73-93`

```java
for (Resource res : rs.getResources()) {
    if (res == null) continue;  // Shouldn't happen but defensive
    var it = res.getAllContents();
    while (it.hasNext()) {
        // Nested try-catch swallows individual element failures
    }
}
```

**Issues**:
- Iterates all resources every time to find an element by ID
- No caching or indexing (though `ModelIndex` exists separately)
- Silent failures for individual elements via `catch (Exception ignore)`
- Performance degrades with large models

**Impact**: Slow performance on large Capella models (>10k elements).

**Fix (Phase 1)**:
- `ModelIndex` now invalidates incrementally on committed transactions via a `ResourceSetListener` attached to the session’s `TransactionalEditingDomain` (no TTL). Callers get fresh results after changes without full rescans on every query.

**Follow‑up**:
- Keep migrating callers from ad‑hoc scans to `ModelIndex` and typed finders.

---

> _Note_: Issue 8 is fixed; the target platform now pins the Capella 7.0.1 units. See **Resolved Issues ✅**.

---

### 9. **Missing Export-Package Declaration**
**Location**: `plugins/ai-capella/META-INF/MANIFEST.MF`

**Issue**: No `Export-Package` directive - other plugins cannot extend this plugin.

**Impact**:
- Limited extensibility
- Other plugins can't programmatically invoke AI features
- All functionality locked within the plugin

**Consideration**: If extensibility is desired, export public API packages:
```
Export-Package: org.polarsys.capella.ai.llm,
 org.polarsys.capella.ai.model
```

---

## Verified / No Action Required

### LLM Connection Already Off UI Thread
**Finding**: Chat streaming and fallback requests run in Eclipse background jobs with UI updates dispatched via `Display.asyncExec()`, so the interface stays responsive.

**Evidence**: `ToolRouter.handleChatStreaming` delegates to `LLMService.streamSse` (asynchronous HTTP client), and `ChatView.runSynchronousRequest` wraps `ToolRouter.handleChat` in an Eclipse `Job` before touching the UI (`plugins/ai-capella/src/org/polarsys/capella/ai/ChatView.java:724-757`, `plugins/ai-capella/src/org/polarsys/capella/ai/ChatView.java:1125-1137`, `plugins/ai-capella/src/org/polarsys/capella/ai/llm/LLMService.java:242-305`).

**Action**: No change required.

---

## Low-Risk Issues / Best Practice Violations ℹ️

No outstanding low-risk issues. Previously logged findings are recorded in **Resolved Issues ✅**.

---

## Resolved Issues ✅

- **Issue 1 – Typo in plugin.xml breaking view positioning**: `relative="org.eclipse.ui.editors"` is now applied so views dock correctly (`plugins/ai-capella/plugin.xml:31`).
- **Issue 2 – Missing icons directory**: `plugins/ai-capella/icons/chat.png` has been added and `build.properties` includes the folder, eliminating p2 validation warnings.
- **Issue 3 – Unsafe direct transaction access**: `DiagramCommands.createNode/createEdge` now execute inside `SessionSafeguards.runWrite`, keeping EMF transactions on the UI thread (`plugins/ai-capella/src/org/polarsys/capella/ai/tools/DiagramCommands.java`).
- **Issue 5 – Weak session acquisition logging**: `SessionBridge` logs failures with a dedicated logger (`plugins/ai-capella/src/org/polarsys/capella/ai/session/SessionBridge.java`).
- **Issue 6 – Missing bundle version constraints**: `MANIFEST.MF` constrains Eclipse, Sirius, and Capella dependencies to the 7.0.x line (`plugins/ai-capella/META-INF/MANIFEST.MF`).
- **Issue 8 – Target platform 0.0.0 versions**: Capella IU versions are pinned to 7.0.1 in the target definition (`releng/capella-target/capella.target`).
- **Issue 10 – Preference page hierarchy**: The LLM preference page is now rooted under the Capella preferences category (`plugins/ai-capella/plugin.xml:49`).
- **Issue 11 – ThreadLocal cleanup**: `SessionSafeguards.cleanup()` removes ThreadLocal state and is invoked from `Activator.stop` to prevent leaks (`plugins/ai-capella/src/org/polarsys/capella/ai/session/SessionSafeguards.java`, `plugins/ai-capella/src/org/polarsys/capella/ai/Activator.java`).
- **Issue 12 – Plan dismiss leaves preview active**: The inline plan preview now disposes on Dismiss to prevent lingering actions (`ChatView#handleChatResult` → `preview.dispose()`).
- **Issue 13 – Dry‑run warnings hidden before apply**: Dry‑run issues are surfaced inline in the preview summary so users see warnings before clicking Apply (`ChatView#handleChatResult`).
- **Issue 14 – Reflection usage for diagram creation**: Reflection to internal Sirius UI commands removed; now using refresh+reveal flow (`DiagramCommands#createNode/#createEdge`).
- **Issue 15 – ModelIndex TTL staleness**: Replaced TTL cache with transaction‑based invalidation via `ResourceSetListener` to keep results current without rescans.

---

## Summary

| Severity | Count | Must Fix Before Release |
|----------|-------|-------------------------|
| Critical 🔴 | 0 | — |
| High 🟡 | 1 | Recommended |
| Medium ⚠️ | 2 | Consider |

**Outstanding Actions**:
1. Replace reflection-based Sirius diagram creation with supported APIs (Issue 4).
2. Optimise `ModelOps.findById` lookups or route callers through `ModelIndex` (Issue 7).
3. Decide whether to export public API packages for extensibility (Issue 9).
