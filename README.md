# Capella AI Assistant

An Eclipse plug-in for working with live Arcadia models inside Capella 7. Its chat agent can inspect the current model, maintain a task plan, propose reviewed Groovy changes, and check actual execution with Capella's installed validator. Model, diagram and documentation APIs cover Operational Analysis (OA), System Analysis (SA), Logical Architecture (LA), Physical Architecture (PA) and End-Product Breakdown Structure (EPBS).

The same plug-in serves an MCP server, so any agent (Claude Code, Codex, ...) can work on the open model, with every edit reviewed by a person in Capella. [`skills/`](skills) holds agent skills for it, each kept only after a measured with/without test. Everything needed to repeat those tests is here: an [example model](#the-example-model-and-the-experiments), the tasks, the grader and the protocol.

## Build

Use a Java 17 JDK and Maven. The Tycho reactor downloads its declared Capella 7.0.1 target platform.

Run `python scripts/fetch-codex.py` once to download the bundled Windows Codex executables from the official OpenAI release; it checks every byte against `plugins/ai-capella/resources/codex/windows-x64/provenance.json` and skips files already present.

Eclipse update sites require the following XML entity settings:

```powershell
$env:JAVA_TOOL_OPTIONS='-Djdk.xml.maxGeneralEntitySizeLimit=0 -Djdk.xml.totalEntitySizeLimit=0 -Djdk.xml.entityExpansionLimit=0'
mvn -f plugins clean verify
```

On Linux or macOS:

```bash
export JAVA_TOOL_OPTIONS='-Djdk.xml.maxGeneralEntitySizeLimit=0 -Djdk.xml.totalEntitySizeLimit=0 -Djdk.xml.entityExpansionLimit=0'
mvn -f plugins clean verify
```

The update site is generated in `plugins/ai-capella-repository/target/repository`, with an archive in the same target directory. It includes the required Groovy 4 runtime. The build target includes Windows x64, Linux x64 and macOS Apple Silicon. On 2026-10-20 the full build and all tests also passed on Linux (Ubuntu under WSL2, JDK 17, GTK), and Capella 7.0.0 for Linux ran the chat review, diagram creation and MCP edit flows. With the official Codex 0.160.1 Linux release on `PATH` (the `codex` and `codex-code-mode-host` executables side by side), live Codex edits and the full 21-task evaluation (21/21, every plan step verified) passed on Linux. macOS has not been run.

## Install and configure

1. In Capella, choose **Help → Install New Software → Add → Local** and select the generated update-site directory, or choose **Archive** and select its ZIP.
2. Install **Capella AI Assistant**, restart, then open **Window → Show View → Other → Capella AI Assistant**.
3. The default runtime is **Codex app-server**. Click **Sign in with ChatGPT** in the assistant header. On Windows x64, the plugin includes the official Codex **0.160.1** runtime and its companion host; a separate Codex installation is not required. An existing Codex ChatGPT connection is reused; an existing OpenAI API-key account is recognized as connected. Otherwise, finish ChatGPT sign-in in your system browser; the assistant confirms the connection before you start chatting.
4. Open a model and ask for the work in normal language. Review the proposed changes, then choose **Apply changes** or **Cancel**. The **Changes** tab is selected by default; the optional **Script** tab retains the exact Groovy source that will run.

Advanced runtime, model and provider settings remain under **Preferences → Capella → LLM**. Leave the Codex model blank to use its configured model. **Direct provider** uses an HTTP provider with API keys stored locally in Eclipse secure storage. Linux and macOS currently require a compatible external Codex executable or Direct provider; the bundled runtime is Windows x64 only. An external Codex must be version 0.160.1 with `codex-code-mode-host` in the same folder as `codex`; Capella checks this before a request. See [connection setup](docs/CODEX_SETUP.md) and [bundled runtime provenance](plugins/ai-capella/resources/codex/windows-x64/PROVENANCE.md).

The LLM preferences include:

- **Allow generated code to run:** disabling this returns generated code for inspection without executing it.
- **Review code before running:** enabled by default. **Review changes** opens a readable summary and expected native model checks. Choose **Apply changes** or **Cancel**; the exact generated source remains available in **Script**. Each corrective script requires a fresh review.
- **Replace quoted names and UUIDs before sending:** enables best-effort tokenization of quoted labels and UUIDs in requests, history and model context, plus explicit native names and IDs in agent tool data. It is not a general redaction filter for arbitrary free text.

Environment fallbacks include `CAPELLA_AI_PROVIDER`, `CAPELLA_AI_MODEL`, `CAPELLA_AI_BASE_URL`, `OPENAI_API_KEY`, `ANTHROPIC_API_KEY`, `AZURE_OPENAI_API_KEY`, `OPENROUTER_API_KEY` and `CAPELLA_AI_API_KEY` (custom providers).

Codex runtime overrides are `CAPELLA_AI_RUNTIME=codex|direct`, `CAPELLA_AI_CODEX_BINARY` (an executable path), and `CAPELLA_AI_CODEX_MODEL`. An explicit executable overrides the bundled runtime; with no override, a supported installation selects the bundle before searching for an external executable. Both bundled executables are checked against pinned byte sizes and SHA-256 digests before use. Codex owns ChatGPT authentication and credential storage; the plugin does not copy its tokens or email into conversation history. Sign-in opens only the official HTTPS OpenAI authorization page, waits for the matching login result, and confirms it with an account check. Cancelling stops that pending login without signing out an existing account. This account setup does not run model inference.

[OpenAI's app-server documentation](https://learn.chatgpt.com/docs/app-server) describes the native protocol. This integration uses the experimental app-server and `dynamicTools` interfaces, pins runtime **0.160.1**, and communicates over local JSONL stdio. It remains a preview integration; connection success alone does not verify a model's access, Arcadia decisions or engineering results.

Codex runs as an owned child process of the Java plugin. Native function tools expose the captured Capella model, documentation and capabilities, task plans, and a reviewed edit proposal. The proposal pauses its tool call while the user reviews the exact Groovy source. Native transactions, change capture, Undo, saving and independent model checks stay in Capella. Capella records their authoritative execution receipt locally before returning it to Codex, so a transport failure cannot erase a committed result. It concludes the original turn and discards any answer produced before that receipt, then starts a bounded fresh turn on the same Codex thread with the actual outcome, original task and current plan. This continuation may gather native read evidence or propose a separately reviewed correction; it never replays a committed script, including one whose saving failed.

The internal Codex code-mode host is enabled so models that require it can orchestrate the registered tools. Execution environments are explicitly empty on fresh threads and every turn, excluding native filesystem/patch tools. Generic shell, image, app, plugin, browser, agent, hosted search and MCP tools are disabled in the child runtime, which also uses a read-only sandbox with network access disabled. Generic file/shell approval requests are declined rather than routed into a model approval.

The Codex thread ID is persisted with each model's local conversation and resumed on the next request or after restarting Capella. Privacy-tokenized conversations start a fresh remote thread from re-encoded local history because token dictionaries are not durably stored; a previous private thread is also invalidated when privacy is turned off. New conversation clears that binding. An interrupted or committed operation is recorded as history and must be inspected before a follow-up; resuming never executes a saved script or restores an approval. Recent local execution evidence is supplied on resume. Initial native tools use app-server's experimental `dynamicTools` contract rather than assistant text containing JSON commands. SDK migration does not by itself establish Arcadia methodology correctness or complete requirements coverage.

## Use with your own agent

Any MCP client (Claude Code, Codex, ...) can work on the model open in Capella through the plugin's MCP server, with every edit still reviewed by a person in Capella. [`skills/`](skills) holds agent skills that teach the method and the workflow:

- [`capella-setup`](skills/capella-setup/SKILL.md): start the server and register it with your agent.
- [`arcadia-depth`](skills/arcadia-depth/SKILL.md): the parts of Arcadia an agent tends to get wrong (modes and states, exchange items and data types, interfaces, missions, capability relations, operational roles and communication means, control functions, property values, categories), each with its method rule, a verified API pattern and the matching page of Capella's own help.
- [`arcadia-elicit`](skills/arcadia-elicit/SKILL.md): interview the user (experience, purpose, then the design decisions in Arcadia order) before modelling a new system.

A skill stays here only if it measurably helps an agent: each one is tested with and without it on real tasks
(`test-models/evals/harness-comparison/PROTOCOL.md`). Skills that restated what the MCP server and `capella_docs`
already give every agent (the Arcadia outline, the edit-and-receipt workflow, diagram choice) were removed after
showing no gain.

Copy the folders into your agent's skills directory: `.claude/skills/` (Claude Code) or `.agents/skills/` (Codex), in your project or home directory, then run `/capella-setup` (Codex: `$capella-setup`). Setup runs only when you call it; the other skills load on their own when a task needs them, and the MCP server's instructions tell the agent to read `arcadia-depth` before modelling (without that pointer, Codex `gpt-6-luna` opened it in 4 of 42 runs; with it, in 40 of 42).

## The example model and the experiments

[`test-models/fsae-2027/FSAE-2027-Electric-Arcadia.zip`](test-models/fsae-2027) is a Capella 7.0 model of a Formula SAE 2027 electric car: about 86,000 elements across the five Arcadia perspectives, every numbered paragraph of the 2027 rules as a native requirement (identifier, page, heading and hash, not the paragraph text), and three detailed safety slices (shutdown circuit, driver demand, vehicle protection) with 86 paraphrased obligations. The scripts in [`test-models/fsae-acceptance`](test-models/fsae-acceptance) built it from the public rulebook; its design decisions stay open on purpose. It needs [Requirements Viewpoint 0.14.0](https://github.com/eclipse-capella/capella-requirements-vp/releases/tag/v0.14.0). Unzip it and import the project into a Capella workspace.

Every experiment ran headless agents on fresh copies of this model and graded the saved model in a fresh Capella, never the agent's own account. Protocols were fixed before the runs; [`PROTOCOL.md`](test-models/evals/harness-comparison/PROTOCOL.md) has each one, its amendments and its result.

| Experiment | Finding |
|---|---|
| 1. Plug-in vs bare agent + MCP (Codex) | The bare agent with the MCP server matched the plug-in's own agent loop: 45/45 vs 51/53 (cut short by the weekly quota) |
| 2. Four general skills (Sonnet) | No gain (33/33 either way); the skills only restated the tool descriptions and were deleted |
| 3. An interviewing skill (Sonnet) | No gain on facts: Sonnet already asks one good round when told someone can answer |
| 4. `arcadia-depth`, the parts of Arcadia agents miss | Sonnet 16/18 → 18/18 and 42% faster; Haiku 11/18 → 18/18 at a quarter of the cost, beating Sonnet without the skill |
| 5. Roles, communication means, capability relations | Haiku 1/9 → 8/9; Sonnet already knew them |
| 6. New API: scenarios, branching chains, physical paths | Haiku 0/3 → 3/3 on scenarios and paths; branching chains possible before, reliable after |
| 7. `arcadia-elicit` on Haiku | Asked anything 1/12 → 12/12; hidden facts in the model 7/69 → 51/69; no wrong guesses |
| Release baseline: the published files, rerun | Depth tasks reproduced (Haiku with skills 39/42). Elicitation did not: asked anything 8/12, hidden facts 34/69, still no wrong guesses. Restoring experiment 7's skill set did not bring it back (6/12); the cause is not yet known. See PROTOCOL |
| 8. `arcadia-depth` on Codex `gpt-6-luna` (medium effort) | Not shown (13/42 vs 15/42): the agent saw the skill listed but opened it in only 4 of 42 runs |
| 9. The same, high effort, told in the prompt to read the skill | 20/42 → 42/42, faster and with fewer tokens; a construct file was read in 40 of 42 runs |
| 10. The same, with the pointer moved into the MCP server's instructions | 29/42 → 40/42; a construct file was read in 40 of 42 runs with nothing in the prompt |

To repeat a run (Windows, Python 3.11+, [Claude Code](https://claude.com/claude-code) signed in, Capella 7.0.0 with this plug-in and the Requirements Viewpoint installed):

```bash
cd test-models/evals/harness-comparison
export CAPELLA_HOME=/path/to/capella          # the folder holding capella.exe
export EVAL_TASKS=../fsae-2027-depth.json     # any task file in test-models/evals
export TOOLKIT_SKILLS=../../../skills         # the skills the "with skills" arms get
python -X utf8 run.py KSH 1 fsae-d-modes      # arm, repetitions, task ids (all tasks if none)
```

`run.py`'s docstring lists the arms (KH/KSH: Haiku without/with skills, K/KS: Sonnet, E*: with a simulated team member, A/B/C/S: Codex). Each run unzips the model on first use, launches its own Capella, and writes the transcript and grade to `results/<arm>/`. Costs are Claude Code's list-price estimates; a Haiku run costs a few cents.

## Working with models

Example requests:

- "Create two system functions called Acquire Data and Process Data, and connect them."
- "Create a logical component called Sensor and allocate its functions."
- "Realize the logical component Sensor with a physical component, preserving traceability."
- "Create an EPBS configuration item called Sensor Assembly and link it to its physical components."
- "Rename the selected element and show the result."

Scripts have `api`, `selection`, `activeDiagram` and `log` bindings. `api.fs` exposes on-demand API documentation, `api.diagram` provides diagram operations, and `api.ops` provides native engineering operations. Details and examples live in `plugins/ai-capella/resources/capella-api/`.

Before proposing a change, the chat agent can use eight typed read tools: architecture summary, paged element search, native element inspection, relationship inspection, bounded structural checks, full native validation, shipped documentation and available API capabilities. These reads use the captured session and cannot evaluate generated Groovy, AQL or shell commands. Codex uses native function calls and a configurable active-turn deadline; reviews pause that deadline, and repeated proposals share bounded tool and edit limits. The Direct provider runtime retains bounded requests, reads and three reviewed proposals, with legacy prose or Groovy-block compatibility.

Generated Groovy is compiled before review without defining or initializing its classes, calling the model APIs or creating a transaction. Compiler and policy failures can trigger two automatic corrections within the existing task budget. Each corrected draft is checked again and requires fresh review. Diagnostics preserve bounded error categories and locations, without forwarding raw compiler excerpts or stack traces. Failed preparation creates no model changes, Undo entry or save. Compilation does not establish native API behavior or engineering correctness; the applied result still requires its independent model checks.

After approval, the assistant records net created, edited and deleted elements, applies the exact reviewed source, and compares native validator diagnostics before and after committed changes. A proposal can declare typed checks for expected element existence, absence or stored attribute values; these checks are displayed before review and read independently afterward. An edit step completes only when its declared checks pass after observed committed changes and no additional native error diagnostics appear. Counts cover observed changes; displayed element lists and diagnostic details are bounded. Rollback, save failure after commit, unavailable evidence and incomplete validation are reported separately. These checks verify the declared native state; they do not establish engineering completeness or safety compliance. Steps without independently confirmed results stay pending.

Programmatic callers must invoke `GroovyExecutor.execute` from a background thread. Nested skills invoked from a script reuse its transaction and cancellation deadline; a failing executed skill makes the outer transaction roll back.

Model writes run in an EMF transaction as one undoable script. Failed scripts roll back before saving; successful changes save after commit. If saving fails after commit, the assistant reports that changes were applied and asks you to save again rather than rerunning the script. Use Capella's **Undo** command to revert an applied operation.

**Stop** cancels generation or pending review. During a model transaction the SWT thread is occupied, so the button cannot dispatch until that operation returns; the script deadline and externally requested cancellation still trigger cooperative rollback. Arbitrary blocking Java/native calls can delay cancellation. This in-process executor is not an isolation boundary for hostile code, and filesystem or other external side effects are not undone by an EMF rollback.

**New conversation** starts fresh for the current model and clears its saved script variables. Switching models restores each model's conversation, draft and task plan. Messages, drafts and plans are saved locally in this Capella workspace's plug-in metadata; execution approvals and live model references are never saved. Reopening a conversation does not rerun its scripts. Recent context is bounded to keep requests manageable.

The assistant captures the model and selection when you send, keeps that context visible during the request, and stops a pending request if its active model changes. It renders selectable Markdown and code, preserves your place while you read earlier messages, and offers **Latest response** when new messages arrive below. **Review changes** names the target model and starts with the proposed changes and native checks; **Script** shows the exact source. **Dismiss** returns you to revision without running code. Actual execution and cancellation results are included in subsequent requests.

When the provider is not configured, the assistant guides you to **Settings**. Generation failures retain your draft and offer **Retry reply** using the original selection; this generates a new response and never reruns an existing script. Malformed or incomplete provider replies are rejected before execution. If multiple open models cannot be resolved, select the intended model before sending.

## Tests

`mvn -f plugins clean verify` compiles the plug-in, runs the Eclipse test harness and packages the update site. Live provider tests require `CAPELLA_AI_RUN_LLM_TESTS=true` and an API key; they are skipped by default.

Deterministic regressions cover:

- Responses API JSON decoding, malformed/incomplete responses and retry backoff.
- Action-disable and confirmation settings, including changes during review, and conversation tokenization.
- Strict agent protocol, typed read-tool limits, scoped plan status, cancellation and correction budgets; native data tokenization preserves valid JSON.
- Net model change observation, rollback/Undo, deleted subtrees, owned representation resources and native diagnostic multiset comparisons.
- Script deadlines, cancellation, rollback, save timing and undo using SWT dispatch and a real EMF transactional editing domain.
- OA/SA/LA/PA creation, exchanges, allocation, editing, cross-layer realizations, undo and serialization/reload using Capella's real metamodel.
- EPBS configuration-item breakdowns and physical-artifact realizations, plus deletion reference cleanup and undo.
- Account setup without inference: existing sign-in reuse, matching login notifications, cancellation races, official authorization-URL validation and safe errors without credential details.

These model tests use a controlled Session proxy. They do not establish that every diagram type, transition or arbitrary script works in a full Capella workbench.

Account-only probes with the bundled **0.160.1** executable reused an existing ChatGPT connection without opening a browser. A separate fresh Codex home started and immediately cancelled its own login, opened no browser, created no credential file, and closed its owned child process. Installed workbench testing also confirmed that **Sign in with ChatGPT** reuses the existing account, displays the connected state, and leaves conversation history empty. First-time browser sign-in completion remains untested.

The bundled Windows release (`0.2.1.202610090008`) passed **545 tests: 531 passed, 14 skipped, zero failures or errors**. With the bundled runtime and an existing ChatGPT connection, live native chat read the disposable project, then proposed and applied separately reviewed creation, UUID-preserving rename and deletion across all five layers. The EPBS representing Part was preserved during rename and removed before its configuration item. All declared native checks passed, each change committed and saved, and the final replies agreed with the actual execution receipts. Native errors remained zero; deletion returned warnings from 41 to the original 33. This confirms the tested workflow, not complete Arcadia engineering autonomy or FSAE design completeness.

The compiler-repair build (`0.2.1.202610091235`) passed **784 tests: 770 passed, 14 skipped, zero failures or errors** and was installed in Capella 7.0.0. Its native chat acceptance used a deterministic local provider: an invalid draft produced compiler feedback and an automatic correction, with native file bytes, dirty flags and Undo/Redo unchanged before review. Fresh review then applied the exact corrected source once, saved five layer elements and the EPBS representing Part, and passed all five declared native checks. Native errors stayed at zero; unallocated fixture elements added eight warnings. This verifies the correction/execution workflow, not a live model's ability to solve every compiler error.

Native workbench acceptance was also run on installed Capella 7.0.0 with Requirements Viewpoint 0.14.0 on Windows. It exercised all five perspectives, native requirements and diagrams, deletion cleanup, Undo/Redo, nested scripts, rollback, deadlines, save failures and save/reopen. Chat review, Run, Cancel, Stop during generation and execution feedback were checked using a local mock provider. Acceptance of a live HTTP provider through the optional Direct provider runtime still requires credentials and a separate run.

The structured agent workflow additionally passed seven installed chat scenarios: five-layer creation, rename and deletion; rollback followed by a separately reviewed correction; dismissal; cancellation; and a read-only question. All eight typed read tools left the native model unchanged. Creation, rename and deletion completed their declared model checks without additional native error diagnostics. A read-only FSAE Requirements Viewpoint probe confirmed native UUID identity, source identifiers and relationship inspection. These scripted provider fixtures exercise execution behavior; they do not measure a live model's planning quality.

The FSAE acceptance project imports all 3,197 pinned 2027 source records and contains candidate architecture across the five perspectives. Its three detailed safety slices scope 588 records through 86 candidate derived obligations. The saved model reopened with 86,658 unique semantic IDs and no unresolved semantic references; nine overview diagrams and three detailed safety diagrams survived reload. Installed native validation reported zero errors and 517 warnings. These results establish the checked native structure and persistence; engineering completeness and competition compliance remain unresolved. See `test-models/fsae-acceptance/model-completeness.md` for the completion gates.

## Evaluating agent quality

`test-models/evals/arcadia-starter.json` holds tasks scored only by native checks (element existence, attributes, allocations and realizations), never by the agent's own claims. `arcadia-advanced.json` adds harder multi-step tasks, and `fsae-2027.json` holds 30 Formula SAE 2027 tasks for the FSAE acceptance project (it needs the Requirements Viewpoint). To score the configured runtime and model:

1. Open a **disposable copy** of a Capella project. Evaluation approves every proposal automatically, and approved edits commit and save.
2. Start Capella with `-Dcapella.ai.eval=true` (or `CAPELLA_AI_EVAL=1`) and the dev server enabled. Without this opt-in `/eval` returns 403.
3. `curl -X POST http://127.0.0.1:8765/eval -H "Authorization: Bearer $(cat ~/.capella-ai/devserver-8765.token)" --data-binary @test-models/evals/arcadia-starter.json`

On 2026-10-20 the bundled Codex runtime passed all 21 starter and advanced tasks on a disposable copy of the native-integration project, every task ending with all its declared plan steps independently verified, including building a laid-out logical architecture diagram. The same 21 tasks also passed on a disposable copy of the FSAE 2027 acceptance project (about 86,000 model elements); see `ROADMAP.md` for the score history. Each task runs through the same loop as the chat (read continuation, compile repair, reviewed-edit pipeline, budget continuation). The report gives pass/fail per task with requests, tool calls, proposals, commits, repairs, continuations and duration. With `"restore": true` each task's changes are undone and saved before the next. Grow the task file toward the work you actually need from the agent; a change to prompts, tools or models is only an improvement if this score goes up.

## Local dev server

Open **AI Dev Server** and click **Start**, or opt into startup with `CAPELLA_AI_DEVSERVER_AUTOSTART=1`. Its default port is 8765. It listens on loopback only, refuses requests that carry a browser `Origin`, and requires `Authorization: Bearer <token>` on every request. The running server writes a fresh random token to `~/.capella-ai/devserver-<port>.token` (owner-only on Linux and macOS) and deletes it on stop; set `CAPELLA_AI_DEVSERVER_TOKEN` to choose your own.

- `GET /status`: server and session status.
- `POST /execute`: execute an explicitly submitted script.
- `POST /mcp`: [Model Context Protocol](https://modelcontextprotocol.io) endpoint (JSON-RPC 2.0, Streamable HTTP without server streaming). It exposes the eight native read tools for the model open in Capella to any MCP client, for example `claude mcp add --transport http capella http://127.0.0.1:8765/mcp --header "Authorization: Bearer $(cat ~/.capella-ai/devserver-8765.token)"`. Reads never save or change the model. `propose_model_edit` submits a Groovy script and returns a `proposal_id` immediately; nothing runs until a person approves it in Capella's review dialog (always, whatever the chat's review preference), after which it runs once through the same transaction, change capture, declared checks and native validation comparison as chat edits. `edit_status` returns the review state and the execution receipt. Repeating an identical proposal returns the same proposal, so a client retry never runs a script twice.

Use `test-dev-server.sh` for an opt-in smoke test against a disposable model. Telemetry and dataset logging are opt-in and local.

## License

[Eclipse Public License 2.0](LICENSE), as for Capella itself. The vendored `writing-for-agents` skill keeps its MIT license, and the Codex runtime fetched by `scripts/fetch-codex.py` is Apache-2.0 (see its `LICENSE` and `NOTICE`).
