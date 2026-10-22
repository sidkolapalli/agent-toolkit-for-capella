# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Capella AI Assistant is an Eclipse plug-in for Capella 7 that lets a chat agent inspect a live Arcadia model (OA, SA, LA, PA, EPBS), keep a task plan, and propose **Groovy scripts** that the user reviews before they run. The LLM writes code against a curated API rather than calling one JSON tool per operation. `README.md` is the authoritative user-facing description; `CONTRIBUTING.md` holds the build, style and commit guidelines.

## Build and Test

Java 17, Maven, Tycho. Export the XML entity limits once per shell (Eclipse update sites need them):

```bash
export JAVA_TOOL_OPTIONS="-Djdk.xml.maxGeneralEntitySizeLimit=0 -Djdk.xml.totalEntitySizeLimit=0 -Djdk.xml.entityExpansionLimit=0"
```

```bash
mvn -f plugins clean verify                       # full build: compile, tests, update site
mvn -f plugins clean verify -Dtest=ArcadiaAgentTest   # one test class
mvn -f plugins clean verify -DskipTests           # refresh binaries only
```

- Update site: `plugins/ai-capella-repository/target/repository` (plus a ZIP in `target/`).
- Tests: `plugins/ai-capella.tests` (JUnit, Tycho surefire, `useUIHarness=false`). They use Capella's real metamodel and a real EMF transactional editing domain behind a controlled `Session` proxy. They do not cover a full workbench.
- Live provider tests are skipped unless `CAPELLA_AI_RUN_LLM_TESTS=true` and an API key are set.
- Run `python scripts/fetch-codex.py` after cloning; it downloads the bundled Codex executables into `plugins/ai-capella/resources/codex/windows-x64/` and checks them against `provenance.json` (the runtime checks them again).

### Manual testing in Capella

Install the update site with **Help → Install New Software → Add → Local**, restart, and open **Window → Show View → Other → Capella AI Assistant**. The **AI Dev Server** view (port 8765, or `CAPELLA_AI_DEVSERVER_AUTOSTART=1`) exposes `GET /status` and `POST /execute` for testing without the UI. Every request needs `Authorization: Bearer <token>` from `~/.capella-ai/devserver-<port>.token` (or `CAPELLA_AI_DEVSERVER_TOKEN`). It also serves `POST /mcp` (`dev/McpServer`: read tools plus `propose_model_edit`/`edit_status` from `dev/McpEdits`, always reviewed by a person in Capella) and `POST /eval` (`agent/EvalRunner`, opt-in with `-Dcapella.ai.eval=true`; tasks in `test-models/evals/`). `test-dev-server.sh` is a read-only smoke test; `scripts/run-capella-dev.sh` (needs `CAPELLA_HOME` and `SAMPLE_SRC`) launches Capella with the dev server auto-started.

## Architecture

All code is under `plugins/ai-capella/src/org/polarsys/capella/ai/`.

### Request flow

1. **`ChatView`** captures the active model and selection when the user sends, and drives the turn on a background thread.
2. **`scripting/ScriptRouter.createRuntime`** picks the `agent/AgentRuntime`:
   - **`codex/CodexArcadiaAgent`** (default): a bundled Codex app-server (pinned version in `CodexLaunchConfig`) runs as an owned child process over JSONL stdio (`CodexAppServer`). Native tools are declared through `CodexToolSchemas`. ChatGPT sign-in goes through `CodexAccountService`.
   - **`agent/ArcadiaAgent`** ("Direct provider"): `llm/LLMService` sends HTTP requests to OpenAI, Anthropic, Azure OpenAI, OpenRouter or a custom endpoint, and `AgentResponseDecoder` parses the strict agent protocol.
3. **`agent/ArcadiaModelTools`** runs the read-only tools (architecture summary, search, inspect element or relations, structural checks, native validation, docs, capabilities) on the captured session. These tools never evaluate scripts.
4. Write proposals carry Groovy source and typed postconditions. **`scripting/GroovyCompilationValidator`** compiles them without running anything. Failures become bounded diagnostics (`agent/TaskPreparation`) that drive automatic corrections, counted against the task budget.
5. The user reviews in **`CodePreviewDialog`** (Changes tab and Script tab) and chooses Apply or Cancel. Every corrective script needs a fresh review.
6. **`agent/ReviewedEdit`** is the shared post-review pipeline used by the chat, MCP edits and evals: **`scripting/GroovyExecutor`** runs the exact reviewed source in a single undoable EMF transaction with a deadline and cooperative cancellation, then saves after commit.
7. Verification:
   - `agent/ModelChangeSummary` records net created, edited and deleted elements.
   - `agent/ModelPostconditions` and `NativeStructuralAssertions` check the declared expectations.
   - `scripting/NativeValidationDiagnostics` diffs validator output before and after.
   - The resulting `ArcadiaAgent.ExecutionOutcome` is returned to the agent as an authoritative receipt, and the agent continues in a fresh bounded turn. A committed script is never replayed.
8. A request that runs out of budget with edit steps pending continues as a fresh request (`agent/TaskContinuation`, at most `ChatView.MAX_AUTOMATIC_CONTINUATIONS` per user message). Blocked, needs-input and failed-save endings never continue.

### Script API (Groovy bindings: `api`, `selection`, `activeDiagram`, `log`)

- `CapellaAPI`: the root `api`; find, create, link and modify across layers. `RelationFactory` and `model/ElementFactory` handle the low-level construction.
- `api.diagram`: `DiagramAPI`, which builds on `tools/DiagramCommands`. `layout(d, "auto")` is `tools/ElkDiagramLayout` (Eclipse ELK layered, hierarchical, orthogonal routes and edge labels written back as GMF bendpoints and label offsets); the geometric strategies use `tools/DiagramWiring` for routing style and port sides.
- `api.ops`: `OpsAPI`, the native engineering operations.
- `api.arcadia`: `ArcadiaAPI` and `Arcadia{Structure,Behavior,Interface,Constraint}API`.
- `api.fs`: `FileSystemAPI`, on-demand docs from `resources/capella-api/` (`SKILL.md`, `api/`, `metamodel/`, `skills/`).
- `api.requirements`: `RequirementsAPI` (Requirements Viewpoint). `api.style`: `StyleAPI`. `api.skills`: `SkillsAPI` and `SkillRegistry` for user skills.
- `ApiCatalog` lists capabilities for the capabilities read tool.

When you add or change API methods, update the matching docs in `resources/capella-api/`. The agent discovers the API through those docs and through `ApiCatalog`.

### Other packages

- `session/`: `SessionSafeguards` (`runWrite`, `runWriteWithResult`, `deferSaves`, `requestSave`, `runOnUiThread`, cancellation control), `SessionBridge`, `SelectionContext`.
- `presentation/`: `ConversationStore` and `ConversationState` hold the per-model history, draft, plan and Codex thread ID saved in workspace plug-in metadata. Approvals and live model references are never saved.
- `privacy/Tokenizer`: optional best-effort tokenization of quoted names and UUIDs before anything is sent to a provider.
- `model_validate` (in `ArcadiaModelTools`) also reports Arcadia method gaps as warnings with a `rule` id; absence rules only run on whole-layer reads (`methodology_scope`).
- `model/`: `ModelSummary` (cached, invalidated after mutations), `Finders`, `ArchitectureLocator`, `ModelAnalytics`.
- `preferences/`: `LLMPreferences` (provider, keys in Eclipse secure storage), `codex/CodexPreferences` (runtime selection, binary, model), plus Advanced, Output and Skill preferences.
- `dev/DevServer`, `importer/SpecImporter`, `telemetry/` (opt-in and local only), `views/` (AI Diagrams, AI Log Inspector).

## Invariants: do not break these

- **Threading:** call `GroovyExecutor.execute` from a background thread, never from the SWT UI thread and never inside an existing model transaction; it rejects both. Model writes go through `SessionSafeguards.runWrite*`. UI updates go through `Display.asyncExec` or `SessionSafeguards.runOnUiThread`.
- **Review boundary:** only source the user approved is executed. Never re-run a script that already committed, including one whose save failed. Resuming a conversation never restores an approval.
- **Read tools stay read-only:** they must not mutate the model or evaluate generated code.
- **Codex sandbox:** shell, filesystem, patch, MCP, browser and web-search tools stay disabled in the child runtime, and generic approval requests are declined.
- **Honest reporting:** rollback, a save failure after commit, and unavailable or incomplete evidence are reported as separate outcomes. A step stays pending until its result is independently confirmed.
- Nested skills reuse the outer transaction and deadline; a failing nested skill rolls back the outer script.

## Verifying in Capella

Unit tests use a proxied `Session`; they cannot show rendering, OSGi wiring or install problems. For changes there, build, install into your Capella 7.0.0 (`$CAPELLA_HOME`) with the p2 director (`capellac.exe -application org.eclipse.equinox.p2.director`), launch it on a scratch copy of a project with the dev server on, and drive it over `/execute`. Diagram changes: export PNGs with `DialectUIManager.INSTANCE.export(...)` and look at them.

Known traps: Capella 7.0.0 ships Guava 21 and 31.1 only, so bundled dependencies must resolve against those (the feature ships Guava 30.1 for `xtext.xbase.lib`). Tycho's ECJ can hang on a ternary that binds the same pattern variable name in both branches; javac accepts it.

## Conventions

- Packages: `org.polarsys.capella.ai.*`. Java 17, UTF-8, google-java-format AOSP style (command in `CONTRIBUTING.md`). Test-called classes must be `public`: tests run in a separate OSGi bundle.
- When adding views, pages or services, update `plugin.xml`, `build.properties` and `plugins/ai-capella-feature/feature.xml`. Bundle and feature versions follow `0.x.y.qualifier`; the reactor is `plugins/pom.xml`, and the target platform is `releng/capella-target`.
- Tests go in `plugins/ai-capella.tests/src`, mirror the source package, are named `*Test`, and must be deterministic.
- Commits use Conventional Commits (`feat:`, `fix(diagram):`, ...).
- Don't commit `target/`, `bin/` or secrets.
