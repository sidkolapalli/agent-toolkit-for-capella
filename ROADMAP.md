# Roadmap

Measured with `test-models/evals/` on the bundled Codex runtime (see the README's evaluation section). Update the scores whenever prompts, tools or models change.

## Latest score

| Date | Build | Runtime | Starter | Advanced | Total |
| --- | --- | --- | --- | --- | --- |
| 2026-10-20 | 0.2.1.202610200823 on the FSAE 2027 project (copy, Windows) | bundled Codex 0.160.1, default model | 12/12 | 9/9 | **21/21**, every plan step verified |
| 2026-10-20 | 0.2.1.202610200823 on Linux (WSL2) | external Codex 0.160.1 Linux release, default model | 12/12 | 9/9 | **21/21**, every plan step verified |
| 2026-10-20 | 0.2.1.202610200642 | bundled Codex 0.160.1, default model | 12/12 | 9/9 | **21/21**, every plan step verified |
| 2026-10-20 | 0.2.1.202610200353 | bundled Codex 0.160.1, default model | 12/12 | 9/9 | 21/21, 2 tasks left a step pending |

On 0.2.1.202610200642 all 21 tasks ended complete with every declared plan step independently verified, including the
laid-out BMS diagram (checked by `diagram_shows`). One task first got no model response ("model at capacity") and passed
on retry. A chat request needing four reviewed proposals also continued automatically past the three-proposal limit
with the real Codex runtime.

Earlier runs the same day scored 11/12 (enum values could not be set by name), then 18/21 (logging a
list aborted scripts; no API for interface exchange items). Single runs vary: a task that passes once
can fail on the next run, so compare several runs before trusting a change.

## FSAE task set (2026-10-20)

`test-models/evals/fsae-2027.json` holds 30 tasks drawn from Formula SAE 2027 work (requirement traceability, shutdown-path
functional chains, interfaces, state machines, a scenario, physical hardware and diagrams); it needs the
Requirements Viewpoint. On a disposable copy of the FSAE 2027 project the bundled Codex runtime passed
29/30. The failure was the scenario task: the API used a diagram name Capella 7 does not have and had
no way to create SA/LA/PA scenarios. After the fix (`api.arcadia.scenario(capability, name,
"exchange"|"functional"|"interface")` and the native ES/IS names) that task passed on its own with every
step verified. A full 30-task rerun on the fixed build is still to do.

## Scale (stress test, 2026-10-20)

A synthetic model grown to 312,000 semantic objects (6,000 logical components, 60,000 functions with
ports and exchanges; 34 MB `.capella` file) on Windows with a 6 GB heap:

| Measure | 156,000 objects | 312,000 objects |
| --- | --- | --- |
| Read tools (summary, search, method checks) | under 0.15 s | under 0.21 s |
| Capella full validation | 23 s | 48 s |
| Reviewed edit, Apply to verified | 49 s | about 100 s (two validations) |
| Capella memory | 1.2 GB | 1.6 GB (4.3 GB after the 100-component diagram) |

Fixed on the way: change capture and result checks stopped at 250,000 objects (now 2,000,000,
time-bounded); validation froze the UI (now off the UI thread, Capella stayed responsive throughout);
the agent's follow-up search failed after an edit on a large model (now restarts); a 20-component
chain laid out as one 90,604 px row (now wraps). A 100-component diagram (3,198 nodes, 999 edges)
shows in about 52 s and lays out in about 21 s.

Limits: full validation runs before and after each edit and grows linearly with model size; very
large single diagrams are legible only in parts.

## Working now

- Reviewed model edits across OA, SA, LA, PA and EPBS from chat, MCP clients and evaluation, through one shared pipeline with independent checks and native validation.
- Budget-limited tasks continue as fresh reviewed requests.
- Arcadia method gaps reported by `model_validate`.
- Clean diagram layout with Eclipse ELK: hierarchy, orthogonal wiring, ports and labels.
- Dual-era MCP endpoint (2026-07-28 and handshake clients), checked with the official MCP Python SDK 2.3.0 (`scripts/mcp-client-check.py`).
- Dev server and MCP require a per-start bearer token.
- Diagram steps verified by `diagram_shows`; budget continuations start from the verified plan.

## Next

1. Grow the evaluation set toward the work engineers actually need (requirements traceability, scenarios, state machines, larger subsystems) and keep the score in this file.
2. Review at the right level for large tasks: show changes on diagrams and against requirements, not only as a script.
3. macOS: run it (Linux is verified with an external Codex 0.160.1 release); consider bundling Linux and macOS Codex runtimes.
4. Faster verification on very large models: validate only what an edit touches.
5. Impact analysis: the downstream effect of a proposed change before it is reviewed.
