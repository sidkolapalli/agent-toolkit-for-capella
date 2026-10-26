"""Draws docs/images/results-{light,dark}.svg from the experiment results in PROTOCOL.md.
python docs/images/make_results_chart.py  (needs matplotlib)"""
import pathlib
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

HERE = pathlib.Path(__file__).parent
# Experiments 8-11, Codex gpt-6-luna, 14 depth tasks x 3 runs (PROTOCOL.md).
ROWS = ["medium effort, no pointer (exp 8)", "high effort, no pointer (exp 11)",
        "high effort, pointer in the prompt (exp 9)", "high effort, pointer in the MCP server (exp 10)"]
READ = [2, 16, 40, 40]
WITH = [15, 30, 42, 40]
WITHOUT = [13, None, 20, 29]  # experiment 11 ran no arm without the skills

THEMES = {
    "light": dict(bg="#ffffff", ink="#1f2328", muted="#59636e", grid="#d1d9e0",
                  series=["#2a78d6", "#eb6834", "#1baf7a"]),
    "dark": dict(bg="#0d1117", ink="#f0f6fc", muted="#9198a1", grid="#3d444d",
                 series=["#3987e5", "#d95926", "#199e70"]),
}

for name, t in THEMES.items():
    fig, ax = plt.subplots(figsize=(9, 3.6), dpi=100)
    fig.patch.set_facecolor(t["bg"])
    ax.set_facecolor(t["bg"])
    series = [("read the skill's construct file", READ, "o"), ("passed, with the skills", WITH, "s"),
              ("passed, without the skills", WITHOUT, "^")]
    for (label, values, marker), color, dy in zip(series, t["series"], (-0.22, 0, 0.22)):
        points = [(v, i + dy) for i, v in enumerate(values) if v is not None]
        ax.scatter([p[0] for p in points], [p[1] for p in points], s=70, marker=marker, color=color,
                   edgecolors=t["bg"], linewidths=1.5, label=label, zorder=3)
        for x, y in points:
            ax.annotate(str(x), (x, y), xytext=(7, 0), textcoords="offset points", va="center",
                        fontsize=9, color=t["muted"])
    ax.set_yticks(range(len(ROWS)), ROWS, color=t["ink"], fontsize=10)
    ax.invert_yaxis()
    ax.set_xlim(0, 46)
    ax.set_xticks([0, 7, 14, 21, 28, 35, 42])
    ax.set_xlabel("runs out of 42", color=t["muted"], fontsize=10)
    ax.tick_params(colors=t["muted"], length=0)
    ax.grid(axis="x", color=t["grid"], linewidth=0.8)
    for side in ax.spines.values():
        side.set_visible(False)
    fig.text(0.012, 0.95, "A skill helps when the agent reads it (Codex gpt-6-luna, 14 depth tasks x 3 runs)",
             color=t["ink"], fontsize=11, ha="left", va="top")
    legend = fig.legend(loc="upper left", bbox_to_anchor=(0.005, 0.87), ncol=3, frameon=False, fontsize=9,
                        handletextpad=0.3, columnspacing=1.4)
    for text in legend.get_texts():
        text.set_color(t["ink"])
    fig.tight_layout(rect=(0, 0, 1, 0.78))
    fig.savefig(HERE / f"results-{name}.svg", facecolor=t["bg"])
    plt.close(fig)
    print("wrote", HERE / f"results-{name}.svg")
