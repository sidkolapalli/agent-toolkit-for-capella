"""One arm of the harness comparison over the FSAE tasks: python run.py <A|B|C> <rep> [task ids].

A  bare Codex CLI: shell + capellambse on a copy of the model files, no Capella.
B  bare Codex CLI + the plugin's MCP server on a live Capella (edits approved by this script's clicker).
C  the plugin's own agent loop through /eval.
Every run starts from a fresh copy of the pristine model and is scored by grade.groovy in a fresh Capella.
"""
import json, pathlib, shutil, subprocess, sys, threading, time
import capella

HERE = capella.HERE
RESULTS = HERE / "results"
CODEX = r"<local-path>"
PYTHON = HERE / "bare-venv" / "Scripts" / "python.exe"
PORTS = {"A": 8781, "B": 8782, "C": 8783}
AGENT_LIMIT = 1800  # seconds per task, every arm
MODEL = "gpt-6.1-sol"  # what the plugin's runtime uses: model and effort from ~/.codex/config.toml
EFFORT = "ultra"

CONTEXT_A = f"""You are working on a Capella 7.0 model (Arcadia method) of a Formula SAE 2027 electric vehicle.
The project is in ./FSAE-2027-Electric-Arcadia. Capella is not running: change the model files directly.
Python with capellambse 0.8.1 is at {PYTHON} (run it with -I). Read ./NOTES.md first.
When you finish, the saved files are opened in Capella 7.0 and checked there.

Task: """

CONTEXT_B = """You are working on a Capella 7.0 model (Arcadia method) of a Formula SAE 2027 electric vehicle.
The model is open in a running Capella, reachable only through the `capella` MCP tools: read tools, the API
docs (capella_docs), and propose_model_edit / edit_status to change it. Poll edit_status until each proposal
finishes. When you finish, the model is checked in Capella.

Task: """

APPROVE = """import org.eclipse.swt.widgets.Display
Display.default.asyncExec { Display.default.shells.each { shell ->
  if (shell.data?.class?.simpleName == 'CodePreviewDialog') shell.data.buttonPressed(0) } }
return 'scheduled'"""


def workspace(arm, task, rep):
    ws = RESULTS / arm / f"{task}-r{rep}"
    shutil.rmtree(ws, ignore_errors=True)
    ws.mkdir(parents=True)
    shutil.copytree(HERE / "pristine" / "FSAE-2027-Electric-Arcadia", ws / "FSAE-2027-Electric-Arcadia")
    return ws


# The sandbox's restricted token cannot start Store app aliases (pwsh from WindowsApps); without them on PATH Codex
# uses Windows PowerShell. The venv first on PATH, as an activated venv would be, and rg (shipped with Codex).
ENV = capella.os.environ | {"CAPELLA_MCP_TOKEN": capella.TOKEN, "PATH": ";".join(
    [str(PYTHON.parent), str(HERE / "tools")] + [d for d in capella.os.environ["PATH"].split(";") if "WindowsApps" not in d])}


def codex(cwd, out, prompt, sandbox, extra=()):
    command = [CODEX, "exec", "--ignore-user-config", "--skip-git-repo-check", "--json",
               "-m", MODEL, "-c", f'model_reasoning_effort="{EFFORT}"', "-c", 'web_search="disabled"',
               "-c", 'approval_policy="never"', *[f for name in ("apps", "plugins", "hooks", "computer_use",
                                                              "browser_use", "multi_agent") for f in ("--disable", name)],
               "-c", 'windows.sandbox="unelevated"', *extra, "-s", sandbox, "-C", str(cwd), "-o", str(out / "last.md"), "-"]
    started = time.time()
    with open(out / "events.jsonl", "wb") as events, open(out / "codex-stderr.log", "wb") as errors:
        try:
            code = subprocess.run(command, input=prompt.encode("utf-8"), stdout=events, stderr=errors,
                                  timeout=AGENT_LIMIT, env=ENV).returncode
        except subprocess.TimeoutExpired:
            code = "timeout"
    usage = {}
    for line in (out / "events.jsonl").read_text("utf-8", "replace").splitlines():
        try:
            event = json.loads(line)
        except ValueError:
            continue
        if event.get("type") == "turn.completed":
            for key, value in (event.get("usage") or {}).items():
                usage[key] = usage.get(key, 0) + value
    return {"exit": code, "agent_seconds": round(time.time() - started), "usage": usage}


def scored(ws, port, task, extra):
    pid = capella.launch(ws, port)
    try:
        loaded = capella.wait_for_model(port)
        result = capella.grade(port, task) if loaded else {"id": task, "passed": False}
        return {"loaded": loaded, **result, **extra}
    finally:
        capella.stop(pid)


def arm_a(task, rep):
    ws = workspace("A", task, rep)
    shutil.copy(HERE / "NOTES.md", ws / "NOTES.md")
    run = codex(ws, ws, CONTEXT_A + capella.TASKS[task]["prompt"], "workspace-write")
    return scored(ws, PORTS["A"], task, run)


def arm_b(task, rep):
    ws = workspace("B", task, rep)
    (ws / "agent").mkdir()
    port = PORTS["B"]
    pid = capella.launch(ws, port)
    try:
        if not capella.wait_for_model(port):
            return {"id": task, "loaded": False, "passed": False, "error": "model did not open"}
        done = threading.Event()

        def approve():
            while not done.wait(3):
                capella.call(port, "/execute", APPROVE, timeout=120)

        clicker = threading.Thread(target=approve, daemon=True)
        clicker.start()
        mcp = ["-c", f'mcp_servers.capella.url="http://127.0.0.1:{port}/mcp"',
               "-c", 'mcp_servers.capella.bearer_token_env_var="CAPELLA_MCP_TOKEN"',
               # Codex's own per-call approval; the Capella review dialog still runs.
               "-c", 'mcp_servers.capella.tools.propose_model_edit.approval_mode="approve"']
        run = codex(ws / "agent", ws, CONTEXT_B + capella.TASKS[task]["prompt"], "read-only", mcp)
        done.set()
        clicker.join()
        return {"loaded": True, **capella.grade(port, task), **run}
    finally:
        capella.stop(pid)


def arm_c(task, rep):
    ws = workspace("C", task, rep)
    port = PORTS["C"]
    pid = capella.launch(ws, port)
    try:
        if not capella.wait_for_model(port):
            return {"id": task, "loaded": False, "passed": False, "error": "model did not open"}
        started = time.time()
        status, body = capella.call(port, "/eval", json.dumps({"tasks": [capella.TASKS[task]]}), timeout=AGENT_LIMIT)
        (ws / "eval.json").write_text(body, "utf-8")
        run = {"agent_seconds": round(time.time() - started), "eval_status": status}
        try:
            run["eval"] = {k: v for k, v in json.loads(body)["results"][0].items() if k != "checks"}
        except (ValueError, KeyError, IndexError):
            pass
        return {"loaded": True, **capella.grade(port, task), **run}
    finally:
        capella.stop(pid)


WEEKLY_CAP = 80  # percent of the weekly Codex window; stop there so the account stays usable


def limited(arm, task, rep):
    """True when the run hit the account's usage limit: an infrastructure failure, not the agent's."""
    ws = RESULTS / arm / f"{task}-r{rep}"
    text = "".join(path.read_text("utf-8", "replace") for path in (ws / "events.jsonl", ws / "eval.json")
                   if path.exists())
    return "usage limit" in text


def wait_for_quota():
    """False once the weekly cap is reached; otherwise waits until the 5-hour window allows work again."""
    while True:
        try:
            five_hour, weekly, usable = capella.quota()
        except Exception:
            five_hour, weekly, usable = 100, 0, False
        if weekly >= WEEKLY_CAP:
            return False
        if usable and five_hour < 100:
            return True
        time.sleep(300)


if __name__ == "__main__":
    arm, reps, chosen = sys.argv[1], [int(r) for r in sys.argv[2].split(",")], sys.argv[3:]
    runner = {"A": arm_a, "B": arm_b, "C": arm_c}[arm]
    for rep in reps:
        for task in chosen or list(capella.TASKS):
            target = RESULTS / arm / f"{task}-r{rep}.json"
            while not target.exists():
                if not wait_for_quota():
                    print(arm, "stopped: weekly Codex cap reached", flush=True)
                    sys.exit(0)
                try:
                    result = runner(task, rep)
                except Exception as failure:  # one broken run must not stop the arm
                    result = {"id": task, "passed": False, "error": repr(failure)}
                if limited(arm, task, rep):
                    continue  # rerun once the window resets
                result.update(arm=arm, rep=rep, finished=time.strftime("%Y-%m-%d %H:%M:%S"))
                target.write_text(json.dumps(result, indent=1), "utf-8")
                print(arm, rep, task, result.get("passed"), result.get("agent_seconds"), flush=True)
