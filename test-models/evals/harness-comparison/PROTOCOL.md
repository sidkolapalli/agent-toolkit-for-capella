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
