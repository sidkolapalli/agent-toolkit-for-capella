"""Downloads the bundled Codex runtime pinned in provenance.json and checks every hash: python scripts/fetch-codex.py"""
import hashlib, io, json, pathlib, sys, urllib.request, zipfile

DIR = pathlib.Path(__file__).resolve().parents[1] / "plugins" / "ai-capella" / "resources" / "codex" / "windows-x64"


def sha256(data): return hashlib.sha256(data).hexdigest()


for exe in json.loads((DIR / "provenance.json").read_text("utf-8"))["executables"]:
    target = DIR / exe["packaged_name"]
    if target.exists() and sha256(target.read_bytes()) == exe["sha256"]:
        print("ok", target.name)
        continue
    archive = exe["source_archive"]
    print("downloading", archive["url"])
    data = urllib.request.urlopen(archive["url"]).read()
    if sha256(data) != archive["sha256"]:
        sys.exit(f"{archive['name']}: archive hash does not match provenance.json")
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        payload = z.read(next(n for n in z.namelist() if n.lower().endswith(".exe")))
    if sha256(payload) != exe["sha256"]:
        sys.exit(f"{exe['packaged_name']}: executable hash does not match provenance.json")
    target.write_bytes(payload)
    print("ok", target.name)
