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
