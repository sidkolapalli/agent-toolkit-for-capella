"""One arm of the harness comparison over the FSAE tasks: python run.py <A|B|C> <rep> [task ids].

A  bare Codex CLI: shell + capellambse on a copy of the model files, no Capella.
B  bare Codex CLI + the plugin's MCP server on a live Capella (edits approved by this script's clicker).
C  the plugin's own agent loop through /eval.
S  B plus the toolkit skills in the agent's .agents/skills, where Codex discovers them (experiment 2).
K  as B with headless Claude Code as the agent; KS adds the skills in its .claude/skills.
KH, KSH  K and KS with Claude Haiku as the agent (experiment 4).
E  as K on an underspecified task, with a simulated team member answering the agent's questions; ES adds the skills
   (experiment 3).
Every run starts from a fresh copy of the pristine model and is scored by grade.groovy in a fresh Capella.
"""
import collections, json, pathlib, shutil, subprocess, sys, threading, time
import capella

HERE = capella.HERE
RESULTS = HERE / "results"
CODEX = r"<local-path>"
PYTHON = HERE / "bare-venv" / "Scripts" / "python.exe"
PORTS = {"A": 8781, "B": 8782, "C": 8783, "S": 8784, "K": 8785, "KS": 8786, "E": 8787, "ES": 8788, "KH": 8789, "KSH": 8790}
SKILLS = pathlib.Path(capella.os.environ.get("TOOLKIT_SKILLS", HERE.parents[2] / "skills"))
AGENT_LIMIT = 1800  # seconds per task, every arm
MODEL = "gpt-6.1-sol"  # what the plugin's runtime uses: model and effort from ~/.codex/config.toml
EFFORT = "ultra"
CLAUDE_MODEL = "claude-sonnet-5-5"
HAIKU_MODEL = "claude-haiku-5-5"
STAKEHOLDER_MODEL = "claude-haiku-5-5"
ROUNDS = 6  # most answers the stakeholder gives in one run

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

CONTEXT_E = CONTEXT_B.replace("\nTask: ", """
Someone from the team that owns the car answers your questions: to ask, end your turn with your questions, and
their answers come back as the next message.

Task: """)

STAKEHOLDER = """You are a member of the Formula SAE electric team that owns this car. An assistant modelling the car in
Capella is asking you questions. Answer only from the team facts below, briefly, and only what was asked: never
volunteer a fact that was not asked for. For anything the facts do not cover, answer "I don't know, your call."
Asked to confirm a summary, confirm it, correcting only what contradicts the facts. Do not ask questions back.

About you, if asked: you lead the team's tractive-system electrics and know the car well, you are new to
Arcadia/MBSE, and the model is for the team's design review.

Team facts:
{facts}"""

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


def claude(cwd, out, prompt, port, resume=None, keep=False, model=CLAUDE_MODEL):
    """One headless Claude Code run with only the capella MCP server: no user settings, plugins or other MCP servers.
    An unsaved single turn by default; keep saves the conversation and resume continues it by session id."""
    config = out / "mcp.json"
    config.write_text(json.dumps({"mcpServers": {"capella": {
        "type": "http", "url": f"http://127.0.0.1:{port}/mcp",
        "headers": {"Authorization": f"Bearer {capella.TOKEN}"}}}}), "utf-8")
    command = [shutil.which("claude"), "-p", "--output-format", "stream-json", "--verbose", "--model", model,
               "--setting-sources", "project,local", "--strict-mcp-config", "--mcp-config", str(config),
               *(["--resume", resume] if resume else []), *([] if keep or resume else ["--no-session-persistence"]),
               # Read, Glob and Grep stay unlisted: Claude Code then allows them only inside the agent's own folder.
               "--allowedTools", "mcp__capella", "Skill",
               "--disallowedTools", "Bash", "PowerShell", "Edit", "Write", "NotebookEdit", "WebFetch", "WebSearch",
               "Agent"]
    started = time.time()
    try:
        done = subprocess.run(command, input=prompt.encode("utf-8"), capture_output=True, cwd=cwd, timeout=AGENT_LIMIT)
        code, output, errors = done.returncode, done.stdout, done.stderr
    except subprocess.TimeoutExpired as expired:
        code, output, errors = "timeout", expired.stdout or b"", expired.stderr or b""
    with open(out / "events.jsonl", "ab") as events, open(out / "claude-stderr.log", "ab") as log:
        events.write(output)
        log.write(errors)
    run = {"exit": code, "agent_seconds": round(time.time() - started), "proposed": False}
    for line in output.decode("utf-8", "replace").splitlines():
        try:
            event = json.loads(line)
        except ValueError:
            continue
        if event.get("type") == "assistant":
            run["proposed"] |= any(c.get("name") == "mcp__capella__propose_model_edit"
                                   for c in event["message"].get("content", []))
        if event.get("type") == "result":
            run.update(usage=event.get("usage"), cost_usd=event.get("total_cost_usd"), turns=event.get("num_turns"),
                       session_id=event.get("session_id"), text=event.get("result") or "")
    return run


def stakeholder(task, question, out):
    """(answer, cost) from a simulated team member who knows only the task's brief: a cheap model with no tools."""
    facts = "\n".join(f"- {fact}" for fact in task.get("brief", [])) or "(none)"
    command = [shutil.which("claude"), "-p", "--output-format", "json", "--model", STAKEHOLDER_MODEL, "--tools", "",
               "--setting-sources", "project,local", "--strict-mcp-config", "--mcp-config", '{"mcpServers": {}}',
               "--no-session-persistence", "--disable-slash-commands",
               "--system-prompt", STAKEHOLDER.format(facts=facts)]
    done = subprocess.run(command, input=question.encode("utf-8"), capture_output=True, cwd=out, timeout=300)
    reply = json.loads(done.stdout)  # a list of events; the last is the result
    reply = next(e for e in reply if e.get("type") == "result") if isinstance(reply, list) else reply
    return reply.get("result") or "", reply.get("total_cost_usd") or 0


def conversation(cwd, out, task, port):
    """The agent's turns; each turn that ends asking, without having proposed an edit, is answered by the stakeholder,
    until the agent stops asking or ROUNDS answers were given. The transcript goes to conversation.md."""
    run = claude(cwd, out, CONTEXT_E + task["prompt"], port, keep=True)
    total = {"exit": run["exit"], "agent_seconds": run["agent_seconds"], "cost_usd": run.get("cost_usd") or 0,
             "turns": run.get("turns") or 0, "rounds": 0, "questions": 0, "stakeholder_cost_usd": 0}
    usage = collections.Counter({k: v for k, v in (run.get("usage") or {}).items() if isinstance(v, int)})
    transcript = [f"## Agent\n\n{run.get('text', '')}"]
    while not run["proposed"] and "?" in run.get("text", "") and run.get("session_id") and total["rounds"] < ROUNDS:
        answer, cost = stakeholder(task, run["text"], out)
        total["rounds"] += 1
        total["questions"] += sum("?" in line for line in run["text"].splitlines())
        total["stakeholder_cost_usd"] += cost
        transcript.append(f"## Stakeholder\n\n{answer}")
        run = claude(cwd, out, answer, port, resume=run["session_id"])
        transcript.append(f"## Agent\n\n{run.get('text', '')}")
        total.update(exit=run["exit"], agent_seconds=total["agent_seconds"] + run["agent_seconds"],
                     cost_usd=total["cost_usd"] + (run.get("cost_usd") or 0),
                     turns=total["turns"] + (run.get("turns") or 0))
        usage.update({k: v for k, v in (run.get("usage") or {}).items() if isinstance(v, int)})
    (out / "conversation.md").write_text("\n\n".join(transcript) + "\n", "utf-8")
    return {**total, "usage": dict(usage)}


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


def arm_b(task, rep, arm="B"):
    ws = workspace(arm, task, rep)
    (ws / "agent").mkdir()
    if arm in ("S", "KS", "ES", "KSH"):
        shutil.copytree(SKILLS, ws / "agent" / (".agents" if arm == "S" else ".claude") / "skills")
    port = int(capella.os.environ.get("EVAL_PORT", PORTS[arm]))
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
        prompt = CONTEXT_B + capella.TASKS[task]["prompt"]
        run = (conversation(ws / "agent", ws, capella.TASKS[task], port) if arm.startswith("E")
               else claude(ws / "agent", ws, prompt, port, model=HAIKU_MODEL if arm.endswith("H") else CLAUDE_MODEL)
               if arm.startswith("K")
               else codex(ws / "agent", ws, prompt, "read-only", mcp))
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
    runner = {"A": arm_a, "B": arm_b, "C": arm_c}.get(arm) or (lambda task, rep: arm_b(task, rep, arm))
    for rep in reps:
        for task in chosen or list(capella.TASKS):
            target = RESULTS / arm / f"{task}-r{rep}.json"
            while not target.exists():
                if arm[0] not in "KE" and not wait_for_quota():
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
