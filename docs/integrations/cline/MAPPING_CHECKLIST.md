# Cline → Capella Mapping Checklist

Runbook
- Clone Cline source into `capella-ai/third_party/cline` (see README for command)
- Index key files: `package.json`, extension activation, commands, agent loop
- Extract: tool registry (typed tools), diff/preview, guardrails, telemetry, settings
- Map each pattern to Capella equivalents; propose concrete diffs
- Update CAPELLA_GOD_PLAN.md tasks with owners/priority

Outputs
- `ARCHITECTURE.md`: overview + file references
- `DIFFS.md` (optional): recommended changes in Capella with file:line anchors
- Plan updates: parity milestones P1–P5 checked into GOD plan

Quick Questions
- How are tool calls typed/validated?
- Where is the propose/preview/apply loop defined?
- How are budgets/timeouts enforced?
- How are diffs presented and confirmed?
- What telemetry is recorded, and how is retention handled?

