"""Talks to one Capella dev server: wait for the model, score a task, run /eval, stop."""
import json, os, pathlib, subprocess, time, urllib.request

HERE = pathlib.Path(__file__).parent
TOKEN = os.environ.get("CAPELLA_AI_DEVSERVER_TOKEN", "capella-ai-scratch-token-0001")
# EVAL_TASKS picks the task file: fsae-2027.json (experiment 1) or fsae-2027-method.json (experiment 2).
TASKS = {t["id"]: t for t in json.loads(pathlib.Path(os.environ.get(
    "EVAL_TASKS", HERE.parent / "fsae-2027.json")).read_text("utf-8"))["tasks"]}
PRISTINE = HERE / "pristine-diagrams.json"


def call(port, path, body=None, timeout=3600):
    request = urllib.request.Request(f"http://127.0.0.1:{port}{path}", data=None if body is None else body.encode(),
                                     headers={"Authorization": f"Bearer {TOKEN}"})
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.status, response.read().decode("utf-8")
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode("utf-8")


CAPELLA = pathlib.Path(os.environ.get("CAPELLA_HOME", "capella"))  # the folder holding capella.exe, plugin installed
CODEX = os.environ.get("CODEX_EXE", "codex")  # arms A, B, C, S only


def launch(ws, port):
    """Capella with its default (Codex) runtime on one disposable workspace; dev server on port, eval mode on."""
    ws = pathlib.Path(ws)
    env = {k: v for k, v in os.environ.items()
           if k not in ("CAPELLA_AI_RUNTIME", "OPENAI_API_KEY", "CAPELLA_AI_BASE_URL", "CAPELLA_AI_MODEL")}
    env.update(CAPELLA_AI_DEVSERVER_AUTOSTART="1", CAPELLA_AI_DEVSERVER_TOKEN=TOKEN, CAPELLA_AI_DEVSERVER_PORT=str(port),
               CAPELLA_AI_DEVSERVER_OPEN_AIRD=str(ws / "FSAE-2027-Electric-Arcadia" / "FSAE-2027-Electric-Arcadia.aird"))
    with open(ws / "capella.log", "wb") as log:
        return subprocess.Popen(
            [str(CAPELLA / "capella.exe"), "-data", str(ws), "-eclipse.keyring", str(ws / ".keyring"), "-consoleLog",
             "-vmargs", "-Xms512m", "-Xmx3g", "-Xss4m", f"-Dcapella.ai.workspace.dir={ws / '.ai'}",
             "-Dcapella.ai.eval=true"],
            cwd=CAPELLA, env=env, stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT).pid


def wait_for_model(port, limit=600):
    """True once the dev server reports an open session; False if it never does (the model did not load)."""
    end = time.time() + limit
    while time.time() < end:
        try:
            status, body = call(port, "/status", timeout=20)
            if status == 200 and json.loads(body).get("hasSession"):
                return True
        except OSError:
            pass
        time.sleep(5)
    return False


def stop(pid):
    subprocess.run(["taskkill", "/F", "/T", "/PID", str(pid)], capture_output=True)
    time.sleep(3)


def grade(port, task_id):
    pristine = json.loads(PRISTINE.read_text()) if PRISTINE.exists() else []
    script = ((HERE / "grade.groovy").read_text("utf-8")
              .replace("TASK_JSON", json.dumps(json.dumps({"tasks": [TASKS[task_id]]})))
              .replace("PRISTINE_DIAGRAMS", json.dumps(pristine)))
    status, body = call(port, "/execute", script)
    result = json.loads(body)
    if status != 200 or not result.get("success", True):
        return {"id": task_id, "grade_error": body[:2000]}
    return json.loads("".join(line.split("G|", 1)[1] for line in result.get("logs", []) if "G|" in line))


def quota():
    """(5-hour %, weekly %, usable) from the Codex account; reading it uses no model quota."""
    p = subprocess.Popen([CODEX, "app-server"], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                         stderr=subprocess.DEVNULL, text=True, encoding="utf-8")
    try:
        def ask(message):
            p.stdin.write(json.dumps(message) + "\n")
            p.stdin.flush()
            for line in p.stdout:
                reply = json.loads(line)
                if reply.get("id") == message.get("id"):
                    return reply.get("result", {})
        ask({"id": 1, "method": "initialize", "params": {"clientInfo": {"name": "doe_quota", "version": "1"}}})
        p.stdin.write(json.dumps({"method": "initialized"}) + "\n")
        limits = ask({"id": 2, "method": "account/rateLimits/read"})
    finally:
        p.kill()
    window = limits["rateLimits"]
    return window["primary"]["usedPercent"], window["secondary"]["usedPercent"], limits.get("ordinaryUsageAllowed", True)
