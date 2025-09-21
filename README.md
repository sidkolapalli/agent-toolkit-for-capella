# Capella AI Assistant (Plugin Only)

This repository contains only the Capella plugin and p2 update site used to install the AI Assistant into Capella.

## Build

- Java 17+ required (Tycho will download the Capella target automatically).
- Eclipse 2023‑03 repositories require relaxed XML entity limits. Export once per shell session:
  ```bash
  export JAVA_TOOL_OPTIONS="-Djdk.xml.maxGeneralEntitySizeLimit=0 -Djdk.xml.totalEntitySizeLimit=0"
  ```
- Run the Tycho build: `mvn -f plugins clean verify`
- Install from the generated update site: `plugins/ai-capella-repository/target/repository`

## Installing in Capella

1. `Help → Install New Software… → Add… → Local…` and select the repository folder above.
2. Accept the wizard, restart Capella, then open the view:`Window → Show View → Other… → Capella AI Assistant`.
3. If you ever uninstall/reinstall, reset the Capella perspective afterwards so the view docks properly (`Window → Perspective → Reset Perspective…`).

## Configuring the LLM

- The assistant view now exposes an **LLM Connection** panel where you can set provider, model, base URL, temperature, token/timeout limits, test the connection, and securely store API keys. Changes persist immediately via `LLMPreferences`.
- The legacy preference page still works: `Capella (menu) → Preferences… → Capella AI → LLM`.
- Environment fallbacks remain available (`CAPELLA_AI_PROVIDER`, `OPENAI_API_KEY`, etc.) if you prefer system-level configuration.
- Telemetry is opt-in via the "Telemetry (opt-in)" checkbox; when enabled it logs anonymous plan metrics locally to help prioritise features.

## Using the assistant

- Type in the **Conversation** section; pressing Enter or clicking **Send** dispatches the request on a background job. The assistant replies in the log pane.
- If the LLM provides a `TOOL_PLAN`, the view displays a summary. When *Enable tool actions* is on, a **Plan Editor** dialog opens so you can inspect every action, toggle unwanted steps, and confirm execution before anything touches the model. The preview runs a sandbox dry-run that groups upcoming creates/updates/deletes, surfaces warnings inline, highlights Undo availability, and cancelling still leaves the plan unapplied.
- The **Model & Diagram Tools** group exposes quick actions (list diagrams, create LAB, import draw.io, rename/delete/reveal elements). These operations run in EMF transactions and persist across sessions.

## Known requirements

- For large models the LLM prompt includes a cached `ModelSummary`. It invalidates automatically after tool actions but you can refresh manually via the **Explain model** button.
- When running headless or via scripts, remember to export `JAVA_TOOL_OPTIONS` (see Build section) before the Maven build so Tycho can read the Eclipse update sites.

Enjoy, and feel free to open issues/PRs for additional actions or provider support.
