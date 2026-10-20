"""Summarises results/<arm>/*.json (repetitions >= 1) as Markdown, per PROTOCOL.md."""
import json, math, pathlib, statistics

HERE = pathlib.Path(__file__).parent
BASE = json.loads((HERE / "baseline-grade.json").read_text())
DIAGRAM = {"fsae-shutdown-sources-diagram", "fsae-apps-dataflow-diagram", "fsae-bspd-hardware-diagram"}
ARMS = {"A": "bare (capellambse)", "B": "bare + MCP", "C": "plugin"}

runs = [json.loads(p.read_text("utf-8")) for p in sorted((HERE / "results").glob("*/*.json"))]
runs = [r for r in runs if r.get("rep", 0) >= 1]


def wilson(k, n, z=1.96):
    if n == 0:
        return 0, 0
    p = k / n
    centre, half = p + z * z / (2 * n), z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n))
    return (centre - half) / (1 + z * z / n), (centre + half) / (1 + z * z / n)


def sign_test(wins, losses):
    n = wins + losses
    if n == 0:
        return 1.0
    tail = sum(math.comb(n, i) for i in range(0, min(wins, losses) + 1)) / 2 ** n
    return min(1.0, 2 * tail)


def broke(r):
    if not r.get("loaded"):
        return ["does not open"]
    found = []
    if r.get("errors", 0) > BASE["errors"]: found.append("new validation errors")
    if r.get("unresolved_proxies", 0) > BASE["unresolved_proxies"]: found.append("new unresolved proxies")
    if r.get("dangling_diagram_elements", 0) > BASE["dangling_diagram_elements"]: found.append("dangling diagram elements")
    if "grade_error" in r or "error" in r: found.append("run or grading error")
    return found


print("| Setup | Runs | Pass | 95% CI | Pass, no diagram tasks | Broke the model | Median agent time | Median tokens (in/out) |")
print("|---|---|---|---|---|---|---|---|")
per_task = {}
for arm, label in ARMS.items():
    mine = [r for r in runs if r["arm"] == arm]
    if not mine:
        continue
    k = sum(bool(r.get("passed")) for r in mine)
    lo, hi = wilson(k, len(mine))
    nd = [r for r in mine if r["id"] not in DIAGRAM]
    knd = sum(bool(r.get("passed")) for r in nd)
    damaged = sum(bool(broke(r)) for r in mine)
    seconds = statistics.median(r.get("agent_seconds") or 0 for r in mine)
    tin = statistics.median((r.get("usage") or {}).get("input_tokens", 0) for r in mine)
    tout = statistics.median((r.get("usage") or {}).get("output_tokens", 0) for r in mine)
    tokens = f"{tin:,.0f} / {tout:,.0f}" if tin else "n/a"
    print(f"| {arm}: {label} | {len(mine)} | {k}/{len(mine)} ({k / len(mine):.0%}) | {lo:.0%}–{hi:.0%} "
          f"| {knd}/{len(nd)} ({knd / max(1, len(nd)):.0%}) | {damaged} | {seconds:.0f} s | {tokens} |")
    for r in mine:
        per_task.setdefault(arm, {}).setdefault(r["id"], []).append(bool(r.get("passed")))

print("\nPairwise, per task (mean pass over repetitions; ties dropped), exact two-sided sign test:\n")
for a, b in (("C", "A"), ("C", "B"), ("B", "A")):
    if a not in per_task or b not in per_task:
        continue
    common = per_task[a].keys() & per_task[b].keys()
    wins = sum(statistics.mean(per_task[a][t]) > statistics.mean(per_task[b][t]) for t in common)
    losses = sum(statistics.mean(per_task[a][t]) < statistics.mean(per_task[b][t]) for t in common)
    print(f"- {a} vs {b}: {a} better on {wins} tasks, worse on {losses}, tied on {len(common) - wins - losses}; "
          f"p = {sign_test(wins, losses):.3g}")

print("\nIntegrity problems:\n")
for r in runs:
    if broke(r):
        print(f"- {r['arm']} {r['id']} r{r['rep']}: {', '.join(broke(r))}")

print("\nDiagram tasks (new diagrams: elements / layout stored / overlaps):\n")
for r in sorted(runs, key=lambda r: (r["id"], r["arm"], r["rep"])):
    if r["id"] in DIAGRAM:
        shown = "; ".join(f"{d['elements']} el, {'layout' if d['gmf'] else 'no layout'}, {d['overlaps']} overlaps"
                          for d in r.get("new_diagrams", [])) or "none"
        print(f"- {r['id']} {r['arm']} r{r['rep']}: {'pass' if r.get('passed') else 'fail'}; {shown}")

print("\nPer task:\n\n| Task | " + " | ".join(ARMS) + " |\n|---|" + "---|" * len(ARMS))
for task in sorted({r["id"] for r in runs}):
    cells = ["".join("✓" if p else "✗" for p in per_task.get(arm, {}).get(task, [])) for arm in ARMS]
    print(f"| {task} | " + " | ".join(cells) + " |")
