# AI Capella Plugin — Progress Log

This log tracks TDD work toward an in‑Capella chat assistant that can read, modify, and visualize models.

## Milestones
- [x] Define architecture and entry points (MCP + in‑process Java tools)
- [x] Add native diagram lifecycle to Java service (create/list/delete, refresh)
- [x] Expand diagram type mappings (OA/SA/LA/PA + common)
- [x] Scaffold plugin project structure (initial view)
- [x] Add session bridge and diagram UI command helpers (skeleton)
- [ ] Wire plugin to current Capella Session (read‑only queries)
- [x] Wire plugin to Session for diagram listing and basic diagram creation
- [x] Add basic rename/delete via EMF transactions (undo/redo-ready)
- [x] Diagram reveal + arrange via Sirius UI APIs (best-effort)
- [ ] True node/edge creation via Sirius UI command factories
- [x] Added Diagrams view (list/open/arrange by UID)
- [ ] LLM tool-calling integration in the plugin
- [ ] End‑to‑end tests with GMHCD model inside Capella

## Latest Update
- Created tests to exercise Java native diagram service with the repo’s `test_gmhcd_copy/GMHCD.aird`.
- Added plugin skeleton for a Chat view; next step is wiring to the active Session.
- Added `SessionBridge`, `DiagramMappings`, and `DiagramCommands` for in‑process ops using Sirius UI APIs.
- ChatView now lists diagrams, creates LA/LAB and attempts to add element by UUID to current diagram (refresh/arrange).

## Next Actions
- Implement Sirius UI command paths for explicit node/edge creation.
- Provide Tycho feature + repository (p2 site) for plugin packaging.
- Add a viewpoints panel and selection sync from editors to the views.

## Testing Notes
- Integration tests will skip if Java service isn’t running on `http://localhost:8080`.
- For context diagrams, ensure `capellambse` and `capellambse-context-diagrams` are installed.
