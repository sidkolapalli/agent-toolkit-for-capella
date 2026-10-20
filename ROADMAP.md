# Roadmap

Measured with `test-models/evals/` on the bundled Codex runtime (see the README's evaluation section). Update the scores whenever prompts, tools or models change.

## Latest score

| Date | Build | Runtime | Starter | Advanced | Total |
| --- | --- | --- | --- | --- | --- |
| 2026-10-20 | 0.2.1.202610200353 | bundled Codex 0.160.1, default model | 12/12 | 9/9 | **21/21** |

A later run on build 0.2.1.202610200544 (diagram verification, root-component placement, change
capture fixes) passed the 4 tasks that reached the model, 3 of them with every plan step verified;
the other 17 got no model response because the ChatGPT plan hit its Codex usage limit. Re-run the
full set on the current build before quoting a new score.

Earlier runs the same day scored 11/12 (enum values could not be set by name), then 18/21 (logging a
list aborted scripts; no API for interface exchange items). Single runs vary: a task that passes once
can fail on the next run, so compare several runs before trusting a change.

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
3. Linux and macOS: bundle or locate a Codex runtime, or document the Direct provider path.
4. Impact analysis: the downstream effect of a proposed change before it is reviewed.
