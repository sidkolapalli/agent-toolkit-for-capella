# Capella Agent Toolkit

Tools that let LLM agents work on [Capella](https://eclipse.dev/capella/) models, and the experiments that show
which parts of them help.

- **An MCP server inside Capella.** An Eclipse plug-in for Capella 7 serves the model open in Capella to any MCP
  client (Claude Code, Codex, ...). Agents read through typed tools and propose changes as Groovy scripts; nothing
  runs until a person approves it in Capella's review dialog.
- **Agent skills, each kept only if it measurably helps.** [`skills/`](skills) teaches the parts of the Arcadia
  method agents get wrong, and how to interview the user before modelling.
- **A benchmark.** Task sets, a grader that judges the saved model in a fresh Capella, and the pre-registered
  protocol and result of every experiment. The model they run on, a Capella model of a Formula SAE 2027 electric
  car, is in its own repository: [fsae-2027-capella-model](https://github.com/sidkolapalli/fsae-2027-capella-model).

The plug-in, which appears in Capella as **Capella AI Assistant**, also has its own chat assistant; see
[docs/PLUGIN.md](docs/PLUGIN.md).

## What the experiments found

- A general-purpose agent using the MCP server matched the plug-in's purpose-built agent loop (45/45 against
  51/53 tasks).
- Skills that restated the tool documentation changed nothing (33/33 either way) and were deleted.
- [`arcadia-depth`](skills/arcadia-depth/SKILL.md), which teaches the constructs agents get wrong, took Claude
  Haiku from 11/18 to 18/18, ahead of Sonnet without it at a fraction of the cost.
- A skill helps only when the agent reads it. Codex `gpt-6-luna` read it in 16 of 42 runs on its own; one sentence
  in the MCP server's instructions raised that to 40 of 42, and the skilled agent then passed 40/42 against 29/42
  without the skills.

All eleven experiments are listed [below](#experiments).

## Quick start

You need Capella 7.0, a Java 17 JDK, Maven and Python 3.

**1. Build the plug-in.**

```bash
python scripts/fetch-codex.py   # the Codex runtime bundled for the in-Capella chat (Windows x64), checked against provenance.json
export JAVA_TOOL_OPTIONS='-Djdk.xml.maxGeneralEntitySizeLimit=0 -Djdk.xml.totalEntitySizeLimit=0 -Djdk.xml.entityExpansionLimit=0'
mvn -f plugins clean verify
```

On Windows PowerShell, set the variable with `$env:JAVA_TOOL_OPTIONS='...'`. The update site is written to
`plugins/ai-capella-repository/target/repository`.

**2. Install it.** In Capella, choose **Help → Install New Software → Add → Local**, select that folder, install
**Capella AI Assistant** and restart.

**3. Start the MCP server.** Open a model, then **Window → Show View → Other → AI Dev Server → Start** (port 8765).

**4. Connect your agent and add the skills.** Copy the folders in [`skills/`](skills) into `.claude/skills/`
(Claude Code) or `.agents/skills/` (Codex), in your project or home folder, and run `/capella-setup` (Codex:
`$capella-setup`). It registers the server with your agent. To do it by hand in Claude Code:

```bash
claude mcp add --transport http capella http://127.0.0.1:8765/mcp \
  --header "Authorization: Bearer $(cat ~/.capella-ai/devserver-8765.token)"
```

Then ask for the work in plain language, for example "Add a Charging mode to the vehicle and the transitions into
it". Each edit opens a review dialog in Capella.

## How edits stay safe

- The server listens on loopback only, refuses browser requests, and requires a bearer token that it writes to
  `~/.capella-ai/devserver-<port>.token` and deletes on stop.
- Read tools never change the model. `propose_model_edit` runs nothing until a person approves the exact script in
  Capella. An approved script runs once, in one undoable transaction, and `edit_status` returns a receipt: what
  changed, the declared checks, and the validator's findings before and after.
- Model content returned to the agent is data, not instructions.

## Skills

- [`capella-setup`](skills/capella-setup/SKILL.md): start the server and register it with your agent.
- [`arcadia-depth`](skills/arcadia-depth/SKILL.md): the parts of Arcadia an agent tends to get wrong (modes and
  states, exchange items, interfaces, missions, capability relations, operational roles, control functions,
  property values, categories, scenarios, chains, physical paths, transitions), each with its method rule, a
  verified API pattern and the matching page of Capella's help.
- [`arcadia-elicit`](skills/arcadia-elicit/SKILL.md): interview the user (experience, purpose, then the design
  decisions in Arcadia order) before modelling a new system.

Setup runs only when you call it. The other skills load when a task needs them, and the MCP server's instructions
point the agent to `arcadia-depth` before modelling.

## Experiments

The experiments ran on [fsae-2027-capella-model](https://github.com/sidkolapalli/fsae-2027-capella-model), a
Capella 7.0 model of a Formula SAE 2027 electric car: about 86,000 elements across the five Arcadia perspectives,
every numbered paragraph of the 2027 rules as a native requirement (identifier, page, heading and hash, not the
paragraph text), and three detailed safety slices with 86 paraphrased obligations. That repository also holds the
scripts that built it from the public rulebook. It needs
[Requirements Viewpoint 0.14.0](https://github.com/eclipse-capella/capella-requirements-vp/releases/tag/v0.14.0).

Every experiment ran headless agents on fresh copies of this model and graded the saved model in a fresh Capella,
never the agent's own account. Protocols were fixed before the runs;
[`PROTOCOL.md`](test-models/evals/harness-comparison/PROTOCOL.md) has each one, its amendments and its result. The
history was rewritten once before publication to remove private files; commit hashes cited in older versions of
`PROTOCOL.md` are from before that rewrite, and the current version cites the published ones.

| Experiment | Finding |
|---|---|
| 1. Plug-in vs bare agent + MCP (Codex) | The bare agent with the MCP server matched the plug-in's own agent loop: 45/45 vs 51/53 (cut short by the weekly quota) |
| 2. Four general skills (Sonnet) | No gain (33/33 either way); the skills only restated the tool descriptions and were deleted |
| 3. An interviewing skill (Sonnet) | No gain on facts: Sonnet already asks one good round when told someone can answer |
| 4. `arcadia-depth`, the parts of Arcadia agents miss | Sonnet 16/18 → 18/18 and 42% faster; Haiku 11/18 → 18/18 at a quarter of the cost, beating Sonnet without the skill |
| 5. Roles, communication means, capability relations | Haiku 1/9 → 8/9; Sonnet already knew them |
| 6. New API: scenarios, branching chains, physical paths | Haiku 0/3 → 3/3 on scenarios and paths; branching chains possible before, reliable after |
| 7. `arcadia-elicit` on Haiku | Asked anything 1/12 → 12/12; hidden facts in the model 7/69 → 51/69; no wrong guesses |
| Release baseline: the published files, rerun | Depth tasks reproduced (Haiku with skills 39/42). Elicitation did not: asked anything 8/12, hidden facts 34/69, still no wrong guesses. Restoring experiment 7's skill set did not bring it back (6/12); the cause is not yet known |
| 8. `arcadia-depth` on Codex `gpt-6-luna` (medium effort) | Not shown (13/42 vs 15/42): the agent saw the skill listed but opened it in only 4 of 42 runs |
| 9. The same, high effort, told in the prompt to read the skill | 20/42 → 42/42, faster and with fewer tokens; a construct file was read in 40 of 42 runs |
| 10. The same, with the pointer moved into the MCP server's instructions | 29/42 → 40/42; a construct file was read in 40 of 42 runs with nothing in the prompt |
| 11. The same skills at high effort with no pointer anywhere | Partial: a construct file was read in 16 of 42 runs, and 30/42 passed (all 16 runs that read one passed) |

To repeat a run (Windows, Python 3.11+, [Claude Code](https://claude.com/claude-code) signed in, Capella 7.0.0 with
this plug-in and the Requirements Viewpoint installed):

```bash
git clone https://github.com/sidkolapalli/fsae-2027-capella-model ../fsae-2027-capella-model   # next to this repository
cd test-models/evals/harness-comparison
export CAPELLA_HOME=/path/to/capella          # the folder holding capella.exe
export EVAL_TASKS=../fsae-2027-depth.json     # any task file in test-models/evals
export TOOLKIT_SKILLS=../../../skills         # the skills the "with skills" arms get
python -X utf8 run.py KSH 1 fsae-d-modes      # arm, repetitions, task ids (all tasks if none)
```

`run.py`'s docstring lists the arms (KH/KSH: Haiku without/with skills, K/KS: Sonnet, E*: with a simulated team
member, A/B/C/S: Codex). Each run unzips the model on first use (from the clone above, or set `EVAL_MODEL_ZIP`), launches its own Capella, and writes the
transcript and grade to `results/<arm>/` (or `$EVAL_RESULTS`). Costs are Claude Code's list-price estimates; a
Haiku run costs a few cents.

## Repository layout

| Path | Contents |
|---|---|
| [`plugins/`](plugins) | The Eclipse plug-in (MCP server, chat assistant, script API), its tests, feature and update site |
| [`skills/`](skills) | Agent skills |
| [`test-models/evals`](test-models/evals) | Task sets, and in `harness-comparison/` the harness, grader and protocol |
| [`test-models/native-integration`](test-models/native-integration) | Scripts that test the installed plug-in in a real Capella session |
| [`docs/`](docs) | The plug-in in detail, Codex setup, Arcadia coverage |
| [`scripts/`](scripts) | Codex download, MCP client check, dev launch and smoke test |

[`ARCHITECTURE.md`](ARCHITECTURE.md) explains how the code fits together and [`CONTRIBUTING.md`](CONTRIBUTING.md)
covers building, testing and commits.

## Platforms

Built and tested on Windows with Capella 7.0.0. The build and all tests also pass on Linux (Ubuntu under WSL2), and
Capella 7.0.0 for Linux ran the chat review, diagram and MCP edit flows. macOS has not been run. The bundled Codex
runtime is Windows x64 only; elsewhere the chat needs an external Codex 0.160.1 or a direct API provider (see
[docs/PLUGIN.md](docs/PLUGIN.md)). The MCP server needs neither.

## License

[Eclipse Public License 2.0](LICENSE), as for Capella itself. The vendored `writing-for-agents` skill keeps its MIT
license, and the Codex runtime fetched by `scripts/fetch-codex.py` is Apache-2.0 (see its `LICENSE` and `NOTICE`).
