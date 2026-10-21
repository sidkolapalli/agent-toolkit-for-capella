# Does the harness add value? Protocol (fixed before the scored runs, 2026-10-20)

Question: on real Arcadia work, does the Capella AI harness beat the same model used as a bare agent?

## Setups (the only thing that varies)

| | Agent | Model access |
|---|---|---|
| **A: bare** | Codex CLI `exec`, shell and file edits on, sandbox `workspace-write` | a copy of the model files and capellambse 0.8.1 (Python); a one-page `NOTES.md`; Capella not running |
| **B: bare + MCP** | Codex CLI `exec`, sandbox `read-only` | the plugin's MCP server on a live Capella: read tools, docs, `propose_model_edit`/`edit_status` |
| **C: plugin** | the plugin's own loop (`POST /eval`) | native read tools, compile check, reviewed Groovy edits, postconditions and validation receipts |

Held constant: the same bundled `codex.exe` 0.160.1, model `gpt-6.1-sol` at reasoning effort `ultra` (what the
plugin runtime uses), the same ChatGPT account, web search off, apps/plugins/hooks/computer-use/browser/multi-agent
off, the 30 task prompts of `fsae-2027.json` verbatim (A and B get a short fixed preamble saying where the model
is), a fresh copy of the same pristine FSAE-2027 model for every run, and 30 minutes of agent time per task.
The user's `~/.codex/config.toml` is ignored for A and B (`--ignore-user-config`).

Edits are not read by a person in any setup: C auto-approves under `-Dcapella.ai.eval=true`; in B a script presses
Apply on each review dialog (the product's MCP review gate is unchanged).

Environment fixes found by the pilot, so the bare agent has a working shell like a normal Codex install:
`windows.sandbox="unelevated"` (Codex's Windows restricted-token sandbox; the user's own config enables it),
PATH without the Store app aliases (the sandbox cannot start Store `pwsh`, so Codex uses Windows PowerShell), the
capellambse venv first on PATH, and the `rg` that ships with the Codex app. In B, Codex's own per-call approval of
`propose_model_edit` is set to approve; the Capella review dialog still opens and is pressed by the script.

## Runs

30 tasks × 3 setups × 2 repetitions = 180 runs. The three setups run concurrently, each walking the tasks in the
same order, so they see the same period of model behaviour. A one-task pilot per setup (repetition 0) shakes out
the harness and is excluded. Nothing in any setup is tuned after the scored runs start.

## Scoring (same for every setup)

Each finished model is opened in a fresh Capella 7.0 and `grade.groovy` reads back:

1. **Pass**: the task's own native checks (`ModelPostconditions`), unchanged from `fsae-2027.json`.
2. **Integrity**: the model opens; native validation errors and warnings; unresolved proxies; diagram elements
   whose semantic target is gone. Pristine baseline: opens, 0 errors, 517 warnings, 28 unresolved proxies,
   0 dangling diagram elements (a capellambse load-and-save leaves all four unchanged).
3. **Diagrams**: for diagrams the run created, element count, whether a layout was stored, sibling-node overlaps.
4. Agent wall time and tokens.

## Analysis

- Pass rate per setup with a Wilson 95% interval, over 60 runs; also with the three diagram tasks excluded,
  since capellambse cannot author Sirius diagrams.
- Per-task majority pass (or mean of the two reps) compared pairwise with an exact two-sided sign test
  (McNemar on discordant tasks). 30 tasks detect only large gaps, roughly 20 points or more; smaller gaps are
  reported as not distinguishable.
- Integrity reported as counts of runs that broke something (model does not open, new validation errors,
  new unresolved proxies, new dangling diagram elements).

## Data

The FSAE model is sent to OpenAI in all three setups; the owner agreed on 2026-10-20 ("Use whatever you need").
Only disposable copies are used. Transcripts (`events.jsonl`), final messages and scores of every run are kept.

## Files

`run.py` (setups and runs; `python run.py <A|B|C> <rep>`), `capella.py` (launch, wait, score, stop),
`grade.groovy` (the scorer), `NOTES.md` (what setup A is given), `analyze.py` (the tables above). Paths point at the
author's machine; adjust `CAPELLA`, `CODEX`, the pristine model copy and the venv to rerun. Pilot (rep 0): one task,
all three setups passed with no integrity change.

## Amendment (2026-10-20, before any result was analysed)

The ChatGPT Plus 5-hour Codex window ran out about 20 minutes into the scored runs; every later run failed in
seconds with "usage limit". Those 33 runs measured the account, not the agent, so they were deleted. From now on a
run that hits the usage limit is not scored and is rerun when the window resets. All setups stop if the weekly
window reaches 80%, so the account stays usable; repetitions finish in order, so a stop leaves whole repetitions.

## Amendment 2 (2026-10-20, during the runs)

Setup C's two failures so far both ended "blocked: proposal did not apply": the agent's script threw on its own
assertion and rolled back, and `EvalRunner` stopped there. The chat does not stop there; it hands the receipt
back to the agent, which can correct the script (each correction is reviewed again). So `/eval` measured C more
strictly than the product behaves. Fixed in commit 1823063, but the fix is not installed during the runs: C
stays on the frozen build and its scored results stand. After the runs, the C tasks that ended this way are
rerun once on the fixed build and reported separately, never mixed into the scored table.

# Experiment 2: do the skills help? Protocol (fixed before any scored run, 2026-10-21)

Question: on method-heavy Arcadia work, does setup B (bare Codex + the MCP server) do better with the skills in
`skills/` than without them?

Experiment 1 could not answer questions like this one: its 30 tasks name every element to create, and every setup
passed nearly all of them.

## Setups

| | Agent | Model access |
|---|---|---|
| **B** | as in experiment 1 | the MCP server on a live Capella |
| **B+S** | as B | as B, plus the four skills in `skills/` (arcadia-method, capella-model-edits, capella-diagrams, capella-setup), copied into the agent's working directory as `.agents/skills/`, where Codex discovers repo skills (`run.py S`) |

Held constant: everything experiment 1 holds constant for B, the same installed plugin build in both arms (commit
recorded at the start), and the skills frozen at a commit recorded at the start. The pilot must show the B+S agent
reading at least one skill in its transcript; if it does not, installation is fixed before any scored run.

## Tasks

The 11 tasks of `../fsae-2027-method.json` (generated by `../make_fsae_2027_method.py`). Each task names a goal,
not the elements to create:

- 2 decompose a system function, realize it in LA, allocate it and connect it.
- 2 build functional chains along existing exchanges.
- 2 allocate functional exchanges to component exchanges.
- 1 defines exchange items and an interface.
- 1 moves allocations to leaf components.
- 3 create readable diagrams.

The gaps are the pristine model's own; nothing is seeded. Each task carries the skill it is meant to exercise
(`skill`). Prompts name only elements `fsae-2027.json` already names; checks select other elements by id.

## Runs

11 tasks × 2 arms × 3 repetitions = 66 runs, plus a one-task pilot per arm (repetition 0, excluded).

- Arms alternate per task (B then B+S on the same task), so a quota stop leaves paired data.
- The weekly cap, the usage-limit rerun rule and the 30-minute agent limit are those of experiment 1.
- Nothing is tuned after the scored runs start.

## Scoring

`grade.groovy`, in a fresh Capella:

1. **Pass** (primary): the task's native checks and all of its method checks pass, and the run adds no
   validation errors, unresolved proxies or dangling diagram elements over the pristine baseline.
2. **Partial credit**: the fraction of the task's method checks that pass.
3. **Diagram quality**, for every new diagram:
   - expected elements and edges present;
   - overlapping sibling shapes and sibling ports;
   - ports off their parent's border;
   - shapes clipped by their container;
   - edge labels more than 80 px from their own route.

   The thresholds were calibrated on experiment 1's `fsae-shutdown-sources-diagram` runs. A (judged clean by eye)
   scores 0 on every counter. B and C (broken) each have 4 port overlaps, 2 clipped shapes, 3–4 far labels, and
   worst labels at 2292 and 2682 px. A fresh layout from the current build also scores 0 on every counter (worst
   label 13 px). The old `diagram_shows` check passed all three.
4. Agent wall time, tokens and number of proposals.

## Analysis and decision rule

- Per task, mean pass over the three repetitions; compare B+S with B using an exact two-sided sign test on tasks
  that differ.
- Report partial credit and the diagram counters as paired per-task differences.
- With 11 tasks only large effects can show; anything else is reported as not distinguishable.

A skill is **kept** when, on the tasks tagged with it:

- B+S wins more tasks than it loses;
- B+S adds no integrity failures B did not have;
- and either the pass rate improves, or pass is equal and median time or tokens fall by at least 20%.

It is **rewritten** when it wins nothing and loses nothing. It is **dropped** when it loses more than it wins.

`capella-model-edits` and `capella-setup` have no tasks of their own. They are kept only if B+S shows no integrity
regression and the pilot shows them read.

## Experiment 2, amendment 1 (2026-10-21, before any scored run)

The Codex quota is spent until 2026-10-27, so experiment 2 runs first with headless Claude Code as the bare agent
(`run.py K` and `KS`, model `claude-sonnet-5-5`), then repeats with Codex (`B` and `S`) after the reset.
Everything else in the protocol holds. K and KS run `claude -p` with `--setting-sources project,local` (no user
settings or plugins), `--strict-mcp-config` with only the `capella` server, and no shell or file-write tools; KS
adds the skills in the agent's `.claude/skills`. Claude Code's built-in skills are present in both arms. The
two agents are analysed separately: the question is skills versus no skills within one agent.

# Experiment 3: does eliciting the team's decisions help? Protocol (fixed before any scored run, 2026-10-21)

Question: when a request leaves the team's decisions open, does the agent with the skills (including
`arcadia-elicit`) ask for them and build a model that reflects them, where the agent without skills guesses?

Experiments 1 and 2 cannot answer this: their prompts state everything the checks look for.

## Setups

| | Agent | Model access | Someone to ask |
|---|---|---|---|
| **E** | headless Claude Code (`claude-sonnet-5-5`), as K | the MCP server on a live Capella | a simulated team member |
| **ES** | as E | as E, plus every skill in `skills/` in the agent's `.claude/skills` | as E |

Both arms get the same context line: someone from the team answers questions; to ask, end the turn with them.

**Simulated team member** (`run.py stakeholder`): a separate `claude -p` (`claude-haiku-5-5`) with no tools, no MCP
servers, no user settings and no saved session. Its system prompt holds only the task's brief. It answers only
from the brief, briefly, only what was asked, and says "I don't know, your call." otherwise. It sees only the
agent's last message, never the model or the task's checks.

**Conversation** (`run.py conversation`): the agent's first turn gets the task prompt. A turn that ends with a
question mark and proposed no edit is answered by the team member, and the agent continues in the same session
(`--resume`). The conversation ends when a turn proposes an edit or asks nothing, or after 6 answers. The
transcript is kept as `conversation.md`.

## Tasks

The 4 tasks of `../fsae-2027-elicit.json` (generated by `../make_fsae_2027_elicit.py`): charging (LA), thermal (LA),
drive (LA) and charging operations (OA). Each prompt names the area and the perspective and leaves the team's
decisions open (the model itself records them as unresolved, e.g. `DEC-CHARGER UNRESOLVED`). Each brief holds 5–6
invented, plausible team decisions; none can be read from the model.

Every brief fact has a `mentions` check: a pattern over the text of every Capella element (names, descriptions,
constraint bodies, property values; requirements excluded). Facts with a usual wrong guess (4.2 V cells, 60 °C,
glycol, an on-board charger, hub motors, charging in the car, cold plates) also have an `absent` check. Dry run
before any agent: the pristine model matches no `mentions` check and every `absent` check; the briefs written into
the model match all of them.

**Regression**: 2 fully specified tasks of experiment 2 (`fsae-m-bms-interface`, `fsae-m-chain-imd-path`) run in ES
with an empty brief. The skill must not ask there: any round counts as a regression.

## Runs

4 tasks × 2 arms × 3 repetitions = 24 runs, plus the 2 regression tasks × 3 repetitions in ES. A one-task smoke test
(excluded) checks the driver end to end. Arms alternate per task. Nothing is tuned after the scored runs start.

## Scoring

1. **Facts reflected** (primary): the share of the task's `mentions` checks that pass.
2. **Contradictions**: `absent` checks that fail.
3. **Pass**: every check passes and no integrity regression over the pristine baseline (as experiment 2).
4. Rounds, questions (lines with a question mark in the agent's asking turns), agent time, tokens, agent and
   team-member cost.

## Analysis and decision rule

Per task, mean facts reflected over the three repetitions; compare ES with E with an exact two-sided sign test.
With 4 tasks no test can reach significance, so the per-run numbers are reported as they are.

`arcadia-elicit` is **kept** when ES reflects more facts than E on at least 3 of the 4 tasks, contradicts no more,
adds no integrity failures, asks at most 3 rounds on median, and asks nothing on the regression tasks. It is
**rewritten** when it asks but the answers do not reach the model, or when it asks on the regression tasks. It is
**dropped** when it reflects no more facts than E.

## Experiment 3, smoke test and calibration (2026-10-21, before any scored run)

Smoke test, ES on `fsae-e-charging` (excluded): the agent read `arcadia-elicit`, asked 6 questions in one round,
got the brief's answers and built the subsystem. It stored "45 degC", which the first temperature pattern missed,
so the number patterns now accept the usual unit spellings (°C, degC, degrees C, Celsius; A, amps; V, volts; ms,
milliseconds). Regraded with them: 6 of 6 facts, no contradiction. The simulated team member answered the skill's
calibration question with "I don't know", so it now has a fixed persona (leads the tractive-system electrics, new to
Arcadia/MBSE, model for a design review) and confirms summaries that agree with its facts. Both changes apply to E
and ES alike. Dry run repeated: pristine matches no fact, the briefs match every fact.

## Experiment 3, amendment 1 (2026-10-21, during repetition 1, applied to every run by regrading)

A grader bug hid every multi-valued text attribute, constraint bodies included: in the script sandbox
`instanceof Collection` is false for EMF lists. Fixed with `instanceof Iterable`. With bodies visible, the pristine
model already says 60 °C (the rules' cell limit), so that wrong-guess check is dropped. The off-board fact also
accepts "off-car" and "off-vehicle". All runs are regraded from their saved models with the fixed grader.

## Experiment 2, result (2026-10-21; headless Claude Code, claude-sonnet-5-5, skills frozen at eef6da8)

| | K (MCP) | KS (MCP + skills) |
|---|---|---|
| Passed | 33/33 | 33/33 |
| Median agent time | 79 s | 71 s (-10%) |
| Median tokens | 749k | 705k (-6%) |
| Cost | $11.19 | $11.30 |
| Integrity vs pristine | equal (0 errors, 28 proxies, 0 dangling) | equal |

Per-task median time: skills faster on 6 of 11 tasks (sign test p = 1.0), faster on 19 of 33 paired runs. Faster on the
derive, chain, interface, allocation and transport tasks; slower on all three diagram tasks. Decision rule: no gain in
pass rate and no 20% cut in time or tokens, so the four skills as written are not shown to help this agent on these
tasks. They are rewritten, not kept on faith; the next tests target what the agent does not already know
(docs/arcadia-coverage/GAPS.md), a weaker agent, and process measures.

## Experiment 3, repetition 1 result (2026-10-21; grader fixed per amendment 1, all runs regraded)

| | E (no skill) | ES v1 (b4ec066) | ES v2 (4b88e7c) |
|---|---|---|---|
| Passed | 4/4 | 3/4 | 3/4 |
| Brief facts in the model | 23/23 | 21/23 | 23/23 |
| Wrong guesses | 0 | 0 | 1 (cold plate on the motor loop; the check flags any cold plate) |
| Brief shown and answered before building | 0/4 | 0/4 | 2/4 |
| Median agent time | 92 s | 105 s | 141 s |
| Cost | $2.55 | $2.69 | $3.14 |

v1's recommended answers anchored the team member, who corrected the proposal and never mentioned the motor or the
torque cap; v2's open questions fixed that. Told that someone answers, the agent without the skill already asks one
good round, so fact capture shows no gain. v2 is exploratory (one repetition, after seeing v1), not a pre-registered
test. The prompt-only brief gate holds half the time; Capella's review dialog is the real gate.

# Experiment 4: do skills help where the agent's Arcadia runs out? Protocol (fixed before any scored run, 2026-10-21)

Question: on Arcadia constructs that the API can build but nothing teaches (`docs/arcadia-coverage/GAPS.md`,
category b), does a bare agent + the MCP server do better with the skills than without, for a strong and a weak model?

Experiments 2 and 3 found no gain: their tasks used parts of Arcadia the agent already knows.

## Setups

| Arm | Agent | Skills |
|---|---|---|
| **K** | headless Claude Code, `claude-sonnet-5-5`, as in experiment 2 (amendment 1) | none |
| **KS** | as K | the toolkit skills in the agent's `.claude/skills` |
| **KH** | as K with `claude-haiku-5-5` | none |
| **KSH** | as KH | as KS |

Held constant: the installed plugin build (commit recorded at the start), the skills frozen at a commit recorded at
the start (the pointer-style depth skill is written after this protocol; its commit is recorded here before the
pilot: **d9b2f9d**, the four experiment-2 skills plus `arcadia-depth`; `arcadia-elicit` is left out because these tasks have no one to answer questions; plugin build 0.2.1.202610211401 from 4e75156, with `capella-help`), the agent context line, tools and limits of experiment 2.

## Tasks

The 7 tasks of `../fsae-2027-depth.json` (generated by `../make_fsae_2027_depth.py`), all tagged `arcadia-method`.
Prompts state the goal in method terms, never the metamodel steps.

| Task | Gap | Checks (all from the model) |
|---|---|---|
| `fsae-d-modes` | b1 | one state machine on the LA root with ≥5 **modes** and no states, an initial pseudo-state, every mode reachable, every transition out of a mode triggered or guarded; the torque function available only in the ready-to-drive mode, the charging function only in the charging mode |
| `fsae-d-exchange-items` | b2 | trip exchange carries only EVENT items; cell-voltage exchange carries only FLOW or SHARED_DATA items whose elements (directly or through a class) are typed by a physical quantity with a unit and by a boolean |
| `fsae-d-interface` | b3 | the trip exchange is on a component exchange between the two components (or ancestors); one interface of that name carries every item of the trip exchange, provided by the BMS side (component or port) and required by the shutdown path side, never the reverse |
| `fsae-d-missions` | b4 | ≥2 SA missions, each exploiting ≥1 capability, every SA capability exploited |
| `fsae-d-duplicate` | b5 | a DUPLICATE logical function fed by the source and feeding both consumers; no direct source-to-consumer exchange left |
| `fsae-d-property-values` | b6 | on each of the three monitors a property value group matching `safety` with a boolean "programmable" value and an integer or float reaction time, both with the values given |
| `fsae-d-categories` | b5 | one LA exchange category of that name grouping every exchange into the trip-accepting function |

Dropped while writing (Java gaps, category a): capability include/extend/generalization (`api.create` and `api.link`
reject them) and properties on classes (`api.create("Property", …, aClass)` is rejected); the typing check therefore
also accepts exchange item elements typed directly.

Dry run (2026-10-21, Capella on port 8799, no LLM): the pristine model fails every task with no grader error, and a
hand-written solution per task, each on its own fresh copy, passes. Solutions are kept out of the repository.

## Runs

7 tasks × 4 arms × 3 repetitions = 84 runs, plus a one-task pilot per arm (repetition 0, excluded). The pilot must
show KS and KSH reading at least one skill. Arms run in pairs per model (K with KS, KH with KSH) on the same task.

## Scoring

As experiment 2 (pass, partial credit, time, tokens, cost), with one change to integrity: the API cannot set the
cardinalities of exchange item elements, so each such element adds one DWF_D_51 error whatever the agent does. The
grader reports their number (`eie_without_min_card`) and the pass rule counts `errors - eie_without_min_card` new
errors over the pristine baseline.

## Analysis and decision rule

Per model, compare KS with K and KSH with KH exactly as experiment 2 (per-task sign test, partial credit as paired
differences). The depth skill is **kept** when, for at least one model, it wins more tasks than it loses, adds no
integrity failures, and raises the pass rate (or equal pass and ≥20% less time or tokens); **rewritten** when it
wins nothing and loses nothing; **dropped** when it loses more than it wins for both models. With 7 tasks only
large effects can show.

## Harness amendment: agent file access (2026-10-21, before experiment 4's scored runs)

`run.py` listed Read, Glob and Grep in `--allowedTools`, which pre-approves them for every path. Agents could
therefore search the whole disk. Audit of all 156 Claude runs so far:
- Experiment 2: 10 of 33 K runs (no skills) grepped the user's home or temp folders for API examples; they saw
  file names and match counts only and read no file outside their own folder. No KS run did. The experiment 2
  result stands with this noted; it also shows the agents without skills searching where the skilled agents did not.
- Experiment 3: one E run grepped the temp folder (counts only).
- Experiment 4 pilot: the K agent grepped the temp folder and read KS's `arcadia-depth/INTERFACES.md`. That pilot,
  and the Haiku pilot started under the same setting, are void.
Fix: Read, Glob and Grep are no longer listed, so Claude Code allows them only inside the agent's folder (its own
skills included) and denies the rest in `-p` mode; verified with a probe. Experiment 4 pilots rerun.

## Experiment 4, result (2026-10-21; skills d9b2f9d, plugin build 0.2.1.202610211401, file access fixed per 9252b55)

Pass counts over the six valid tasks x 3 repetitions; `categories` failed in all 12 runs of every arm (every agent
grouped the 3 monitor trips and left out 3 other shutdown causes: the prompt is ambiguous), so it is reported but
excluded from the pass rate. Time and cost cover all 7 tasks.

| | K (Sonnet) | KS (Sonnet + skills) | KH (Haiku) | KSH (Haiku + skills) |
|---|---|---|---|---|
| Passed, valid tasks | 16/18 | 18/18 | 11/18 | 18/18 |
| Median agent time | 69 s | 40 s (-42%) | 121 s | 70 s (-42%) |
| Cost | $5.89 | $3.37 | $2.11 | $0.51 |
| New validation errors beyond the known cardinality gap | 2 in one run | 0 | 0 | 0 |

| Task | K | KS | KH | KSH |
|---|---|---|---|---|
| modes | PFP | PPP | FFP | PPP |
| interface | PFP | PPP | FPP | PPP |
| duplicate | PPP | PPP | FFF | PPP |
| property-values | PPP | PPP | PPF | PPP |
| exchange-items | PPP | PPP | PPP | PPP |
| missions | PPP | PPP | PPP | PPP |

With skills both models are faster on all 7 tasks (sign test p = 0.016 each). Every failure without the skill is a
construct the skill teaches (states for modes, interface sides swapped or missing, no control function, untyped or
wrong property values). Decision rule: pass rate up and time down by 42% for both models with no integrity cost, so
`arcadia-depth` is kept. Haiku with the skill passed more than Sonnet without it, at under a tenth of the cost.

# Experiment 5: the second batch of depth files. Protocol (fixed before any scored run, 2026-10-21)

Question: do the new `arcadia-depth` files (operational roles and communication means, capability include/extend/
generalization) help as the first batch did, and does the reworded categories task now measure CATEGORIES.md?

## Setups

The four arms of experiment 4 (K, KS, KH, KSH), unchanged, with the skills frozen at **82ed60b** (the experiment-4
skills plus OPERATIONAL.md and CAPABILITIES.md) and the same plugin build 0.2.1.202610211401. Haiku runs first.

A regression arm reruns KSH on experiment 4's 7 tasks with the larger skill, against experiment 4's KSH (18/18).

## Tasks

The 4 tasks of `../fsae-2027-depth-5.json` (generated by `../make_fsae_2027_depth.py`).

| Task | Gap | Checks (all from the model) |
|---|---|---|
| `fsae-d-roles` | b8 | one OA role of that name whose activity allocations are exactly the 4 named activities, allocated to the team personnel entity |
| `fsae-d-communication` | b8 | one communication means of that name between the team personnel and the vehicle, carrying every operational interaction between their activities |
| `fsae-d-capabilities` | b9 | charging includes energy storage, status extends shutdown, the new regenerative braking capability specialises brakes; none reversed |
| `fsae-d-categories-2` | b5 | as experiment 4's categories check; the prompt now says "every exchange that enters the function, whatever its source" |

Correction to experiment 4's protocol: capability include, extend and generalization are creatable with the generic
API (`api.create('AbstractCapabilityInclude', null, base)` then `api.set`), verified on Capella 7 today; they were
dropped from experiment 4 on a wrong reading. Component port kind (FLOW vs STANDARD) was dropped: Capella's glossary
says the difference is only graphical. Component exchanges on physical links wait for the physical path work (a3).

Dry run (2026-10-21, port 8799, no LLM): the pristine model fails every task with no grader error; one hand-written
solution using only the skill files' patterns passes all four with no new validation error.

## Runs

4 tasks × 4 arms × 3 repetitions = 48 runs, plus 7 × 3 = 21 regression runs, plus a one-task pilot per arm
(repetition 0, excluded) that must show the skilled arms reading the new files.

## Scoring, analysis and decision rule

As experiment 4. Each new file is judged on its own task: **kept** when, for at least one model, its task passes
more often with the skill and no less often for the other; **not shown** when no arm differs (the file stays only if
it is cheap and correct, and is marked untested in the skill's coverage). The regression arm must pass at least
17/18 valid tasks; otherwise the larger skill is reverted.

## Experiment 2, decision applied (2026-10-21)

Experiment 2's rule called for the four first skills to be rewritten. On review, three of them restated what every
agent already gets from the MCP server's tool descriptions and `capella_docs` (the perspective outline and method
rules, the propose/receipt/repair workflow, the diagram-per-question table in `api/diagram.md`), which explains the
null result. Rather than rewrite them, `arcadia-method`, `capella-model-edits` and `capella-diagrams` are removed;
`capella-setup` stays (user-invoked installation, which no eval measures). Experiments 4 and 5 keep their frozen copies.

# Experiment 6: the API gaps. Protocol (fixed before any scored run, 2026-10-21)

Question: with the new API (functional and exchange scenarios with messages, branching chains, physical paths
and component exchanges on them, every Capella transition; commits 5dd6df1 and 2db9d89) and a depth file for
each, does a small model build what it could not before?

This is an acceptance test of build plus skill together, not a skill-only comparison: the gaps could not be built
without the API.

## Setups

| Arm | Plugin build | Skills |
|---|---|---|
| **before** | 0.2.1.202610211401 (experiments 4 and 5) | the experiment-5 skills (82ed60b) |
| **after** | the build from 2db9d89 or later (recorded before the pilot) | those plus SCENARIOS.md, CHAINS.md, PHYSICAL.md, TRANSITIONS.md (commit recorded before the pilot) |

Agent: headless Claude Code with `claude-haiku-5-5`, as KSH. Before runs first, on the installed old build;
then the new build is installed and the after runs follow.

## Tasks

The 4 tasks of `../fsae-2027-depth-6.json`.

| Task | Gap | Checks (all from the model) |
|---|---|---|
| `fsae-d-fs-scenario` | a1 | a FUNCTIONAL scenario in the LA shutdown capability realization whose messages invoke the 3 named exchanges in order, each from its source function's lifeline to its target's |
| `fsae-d-branching-chain` | a2 | one LA chain of that name with a link for each of the 6 trips into the accepting function and the open request out of it, each link between the involvements of its own exchange's functions |
| `fsae-d-physical-path` | a3 | a physical path carrying the battery status component exchange whose nodes run energy-storage, low-voltage, shutdown equipment (either direction) |
| `fsae-d-transition` | a4 | the new logical component is realized by a PA component and its function by a PA function allocated to it |

Dry run: the pristine model fails all four with no grader error (2026-10-21). A solution per task with the new API
is run on the new build before the pilot.

## Runs, scoring, decision

4 tasks × 2 arms × 3 repetitions = 24 runs, plus a one-task pilot per arm. Scoring as experiment 4. A gap counts
as **closed** when after passes at least 2 of 3 and before passes fewer; **still open** otherwise, with the
failure read from the model.

# Experiment 7: does the elicitation skill improve the process for a weaker agent? Protocol (2026-10-21)

Question: experiment 3 found that a strong agent told someone can answer already asks one good round, so fact
capture showed no gain. Does `arcadia-elicit` change *how* a weaker agent works: calibrating to the user,
confirming a brief before building, recording open decisions, without asking needless questions on tasks that
are fully specified?

## Setups

| Arm | Agent | Skills |
|---|---|---|
| **EH** | as E (experiment 3) with `claude-haiku-5-5` | none |
| **ESH** | as EH | the toolkit skills at the commit recording this protocol (`arcadia-elicit`, `arcadia-depth`, `capella-setup`) |

The simulated team member is unchanged from experiment 3 (Haiku, hidden brief, novice in Arcadia, answers only what
is asked).

## Tasks

- Elicitation: the 4 tasks of `../fsae-2027-elicit.json`.
- Regression: 2 fully specified tasks from `../fsae-2027-depth.json` (`fsae-d-property-values`, `fsae-d-duplicate`),
  with the same team member available (no hidden facts).

## Measures (all pre-registered)

| Measure | How |
|---|---|
| Facts in the model | the elicit tasks' `mentions` checks, as experiment 3 |
| Wrong guesses | the `absent` checks |
| Calibration | the agent's first message asks about the user's experience or role: `(?i)experience|familiar|background|your role` |
| Brief gate | the agent message just before the build turn presents a summary and asks for confirmation: `(?i)confirm|look right|is (this|that) (right|correct)` |
| Open decisions recorded | grader `tbd_elements` minus the pristine model's count |
| Needless questions (regression tasks) | rounds asked; pass as experiment 4 |

## Runs and decision

(4 + 2) tasks × 2 arms × 3 repetitions = 36 runs, plus a one-task pilot per arm. `arcadia-elicit` is **kept** when
ESH beats EH on calibration and the brief gate, records at least as many open decisions, loses no facts, adds no
wrong guesses, and on the regression tasks asks no more rounds than EH and passes as often. **Rewritten** when the
process measures improve but a cost appears (lost facts, wrong guesses or needless questions). **Dropped** when the
process measures do not improve.

## Experiment 6, amendment 1 (2026-10-21, before any experiment-6 run)

The after build is **0.2.1.202610212140** (plugin code of 2db9d89; 900 tests pass), installed into a separate copy
of Capella 7.0.0 so the old build stays installed for the before runs and experiment 7. After skills: the
experiment-5 set (82ed60b) with `arcadia-depth` replaced by its state at **c58ddc9** (the four new files and their
router rows). One solution per task, written only with those files' patterns, passed all four on the new build
with no new validation error; its functional scenario renders correctly in Capella's FS diagram. The after runs
start when the before runs finish, alongside experiment 7.
