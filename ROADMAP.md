# Roadmap

Measured with `test-models/evals/` on the bundled Codex runtime (see the README's evaluation section). Update the scores whenever prompts, tools or models change.

## Working now

- Reviewed model edits across OA, SA, LA, PA and EPBS from chat, MCP clients and evaluation, through one shared pipeline with independent checks and native validation.
- Budget-limited tasks continue as fresh reviewed requests.
- Arcadia method gaps reported by `model_validate`.
- Clean diagram layout with Eclipse ELK: hierarchy, orthogonal wiring, ports and labels.
- Dual-era MCP endpoint (2026-07-28 and handshake clients).

## Next

1. Grow the evaluation set toward the work engineers actually need (requirements traceability, scenarios, state machines, larger subsystems) and keep the score in this file.
2. Review at the right level for large tasks: show changes on diagrams and against requirements, not only as a script.
3. A token for the dev server so MCP can be a product surface, not only a local development tool.
4. Linux and macOS: bundle or locate a Codex runtime, or document the Direct provider path.
5. Impact analysis: the downstream effect of a proposed change before it is reviewed.
