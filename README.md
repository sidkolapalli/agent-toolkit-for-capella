# Capella AI Assistant (Plugin Only)

This repository contains only the Capella plugin and p2 update site used to install the AI Assistant into Capella.

## Build

- Java 17+ required (Tycho will download the Capella target automatically).
- Eclipse 2023‑03 repositories require relaxed XML entity limits. Export once per shell session:
  ```bash
  export JAVA_TOOL_OPTIONS="-Djdk.xml.maxGeneralEntitySizeLimit=0 -Djdk.xml.totalEntitySizeLimit=0 -Djdk.xml.entityExpansionLimit=0"
  ```
- Run the Tycho build: `mvn -f plugins clean verify`
- Install from the generated update site: `plugins/ai-capella-repository/target/repository`

## Installing in Capella

1. `Help → Install New Software… → Add… → Local…` and select the repository folder above.
2. Accept the wizard, restart Capella, then open the view:`Window → Show View → Other… → Capella AI Assistant`.
3. If you ever uninstall/reinstall, reset the Capella perspective afterwards so the view docks properly (`Window → Perspective → Reset Perspective…`).

## Dev Server (Local Testing)

The plugin ships with a lightweight HTTP dev server for LLM and Groovy execution testing.

**Manual start (UI):**
1. Launch Capella and open a `.aird` model.
2. `Window → Show View → Other… → Capella AI → AI Dev Server`, then click **Start**.

**Auto‑start (headless launch):**
```bash
CAPELLA_AI_DEVSERVER_AUTOSTART=1 \
CAPELLA_AI_DEVSERVER_PORT=8765 \
CAPELLA_AI_DEVSERVER_OPEN_AIRD="/path/to/YourModel.aird" \
/path/to/capella -clean -consoleLog -data /path/to/workspace
```

**Endpoints:**
- `GET /status` – server + session status
- `GET /prompt` – current system prompt
- `POST /execute` – run Groovy code
- `POST /chat` – LLM prompt + auto‑execute returned code

Use the provided smoke test script:
```bash
./test-dev-server.sh 8765
```

## Configuring the LLM

- The assistant view now exposes an **LLM Connection** panel where you can set provider, model, base URL, temperature, token/timeout limits, test the connection, and securely store API keys. Changes persist immediately via `LLMPreferences`.
- The legacy preference page still works: `Capella (menu) → Preferences… → Capella AI → LLM`.
- Environment fallbacks remain available (`CAPELLA_AI_PROVIDER`, `OPENAI_API_KEY`, etc.) if you prefer system-level configuration.
- The `Explain model` button now produces layer dashboards with coverage metrics, validation warnings, and dependency highlights so you can gauge model health at a glance.
- Use the new **Search model** button to query the indexed Capella model (name/type keywords) directly from the assistant view.
- Telemetry is opt-in via the "Telemetry (opt-in)" checkbox; when enabled it logs anonymous plan metrics locally to help prioritise features.

## Using the assistant

- Type in the **Conversation** section; pressing Enter or clicking **Send** dispatches the request on a background job. The assistant replies in the log pane.
- Multi-turn context is preserved: the assistant keeps the most recent exchanges so follow-up prompts can reference earlier analysis or decisions without repeating details.
- Inline context cards summarize the current model selection, the most recent AI plan preview, and a quick health snapshot of the model so you can react faster.
- A Methodology coach card tracks Arcadia progress with step checklists and recommended commands so you always know what to tackle next.
- Use `/` to open the quick command palette or press Ctrl/Cmd + Space for inline suggestions; the assistant seeds prompts for common Arcadia tasks (scaffolding components, extending chains, syncing layers, linking requirements).
- Replies can stream live with a **Stop** button so you can interrupt or redirect the conversation mid-generation.
- Before running a plan, the editor now shows a colour-coded diff of creates/updates/deletes with inline warnings and sample attribute changes; a dedicated "Undo last plan" button invokes Capella's undo command when available.
- If the sandbox dry-run flags missing elements or conflicts, a clarification dialog appears with suggested matches and automatically drafts a follow-up prompt so you can fix the plan before anything touches the model.
- If the LLM provides a `TOOL_PLAN`, the view displays a summary. When *Enable tool actions* is on, a **Plan Editor** dialog opens so you can inspect every action, toggle unwanted steps, and confirm execution before anything touches the model. The preview runs a sandbox dry-run that groups upcoming creates/updates/deletes, surfaces warnings inline, highlights Undo availability, and cancelling still leaves the plan unapplied.
- Logical ↔ Physical transformations are now first-class plan actions: propagate logical components into the Physical architecture (optionally cloning hierarchies) with automatic trace links, sync existing physical components from their logical counterparts or run in "link only" mode, and add trace mappings independently. Dry-runs flag name clashes or missing endpoints so you can fix conflicts before the plan applies, and destructive sync steps default to unchecked with explicit confirmation hints.
- The **Operational templates** panel offers quick buttons for creating missions, activities, operational exchanges, and linking activities to capabilities without crafting prompts. Each action validates prerequisites (layers, actors, capabilities) through the dry-run sandbox before execution and reports via telemetry when opt-in is enabled.
- The **System templates** panel mirrors this for System Analysis: create components/functions, wire component exchanges, and allocate capabilities via guided dialogs that enforce parent/component existence before plans run.
- The **Functional chain templates** let you spin up chains, add functions/exchanges, and hook chains to scenarios. Dry-runs verify every chain/function/exchange/situation exists before generating plan diffs.
- The **Requirement templates** cover the remaining Arcadia traceability loop: create requirements in the right package, link them to architecture elements, and record coverage status/notes with sandbox checks before execution.
- The **Mass edit templates** accelerate renames, reparenting, capability allocation, mapping, and deletions for large selections; each action validates element presence via dry-run before applying.
- The **Model & Diagram Tools** group exposes quick actions (list diagrams, create LAB, import draw.io, rename/delete/reveal elements). These operations run in EMF transactions and persist across sessions.

## Known requirements

- For large models the LLM prompt includes a cached `ModelSummary`. It invalidates automatically after tool actions but you can refresh manually via the **Explain model** button.
- When running headless or via scripts, remember to export `JAVA_TOOL_OPTIONS` (see Build section) before the Maven build so Tycho can read the Eclipse update sites.

Enjoy, and feel free to open issues/PRs for additional actions or provider support.
