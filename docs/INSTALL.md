# Install the Capella Agent Toolkit

The toolkit has two parts:

1. **Three agent skills** in the open Agent Skills (`SKILL.md`) format, under `skills/<name>/`:
   - `capella-setup`: manual only. It sets `disable-model-invocation: true`, so you start it yourself.
   - `arcadia-depth`: `SKILL.md` plus 13 construct `.md` files.
   - `arcadia-elicit`: `SKILL.md`, `BUILD.md` and `QUESTIONS.md`.
2. **A local MCP server** that runs inside Capella at `http://127.0.0.1:8765/mcp` (Streamable HTTP).

For each agent you do three things: install the skills, connect the MCP server, and check that both work.

## Before you start

### Set a fixed MCP token

Every request to the Capella MCP server needs the header `Authorization: Bearer <token>`. By default Capella writes a new token to `~/.capella-ai/devserver-8765.token` **every time it starts**, so any agent config that stores the token stops working after a Capella restart.

To avoid this, set `CAPELLA_AI_DEVSERVER_TOKEN` to a fixed value before you start Capella. Capella then uses that value as its token, and your agent reads the same variable. Most sections below assume you have done this.

```powershell
# Windows (PowerShell): persist a random token for new processes
setx CAPELLA_AI_DEVSERVER_TOKEN ([guid]::NewGuid().ToString('N'))
```

```sh
# macOS / Linux: add it to your shell profile (~/.zshrc or ~/.bashrc)
echo "export CAPELLA_AI_DEVSERVER_TOKEN=$(openssl rand -hex 32)" >> ~/.zshrc
```

Then restart Capella and your agent. Running programs do not see the new variable until you restart them.

### Copy whole folders

Always copy the whole skill folder, not just `SKILL.md`. The extra `.md` files are loaded on demand. Keep the folder names exactly `capella-setup`, `arcadia-depth` and `arcadia-elicit`: several agents require the folder name to match the `name` field in `SKILL.md`.

---

## Any agent

Our layout (`skills/<name>/SKILL.md`, folder name equal to `name`) is the layout the Agent Skills specification describes. Two cross-agent installers find it with no extra flags. Neither one configures MCP servers, so connect the Capella server separately in each agent (see the agent sections below).

### Option A: `npx skills` (Vercel skills CLI)

Requires Node.js 22.20 or later. It installs into the current project by default; add `-g` for all projects. It detects your agents and asks which ones to install to.

```sh
# see what the repo offers
npx skills add sidkolapalli/capella-agent-toolkit --list

# interactive install
npx skills add sidkolapalli/capella-agent-toolkit

# all three skills into Claude Code, Codex and Cursor for this project
npx skills add sidkolapalli/capella-agent-toolkit --skill capella-setup --skill arcadia-depth --skill arcadia-elicit -a claude-code -a codex -a cursor

# user-wide, no prompts
npx skills add sidkolapalli/capella-agent-toolkit --skill '*' -g -a claude-code -y

# one skill by URL
npx skills add https://github.com/sidkolapalli/capella-agent-toolkit/tree/main/skills/arcadia-elicit
```

In an interactive install, choose **Symlink** (recommended, one shared copy) or **Copy** (`--copy`, separate copies). To manage installed skills:

```sh
npx skills list
npx skills check
npx skills update
npx skills remove arcadia-elicit
```

### Option B: `gh skill install` (GitHub CLI)

Requires GitHub CLI 2.90.0 or later. `gh skill` is in public preview. The default target is GitHub Copilot at project scope.

```sh
# all skills, default agent (GitHub Copilot), project scope
gh skill install sidkolapalli/capella-agent-toolkit --all

# one skill for a specific agent and scope
gh skill install sidkolapalli/capella-agent-toolkit arcadia-elicit --agent claude-code --scope user
gh skill install sidkolapalli/capella-agent-toolkit arcadia-depth --agent codex

# preview a skill, or install from a local clone
gh skill preview sidkolapalli/capella-agent-toolkit arcadia-elicit
gh skill install ./capella-agent-toolkit --from-local --all

# update later
gh skill update --all
```

Notes:

- With no version, `gh skill install` uses the latest tagged release, and the default branch only if there are no tags. To pin, use `--pin <tag-or-commit-sha>` or `<skill>@<commit-sha>`. Pinned skills are skipped by `gh skill update`.
- It writes source-tracking metadata into each installed `SKILL.md` frontmatter, so installed copies differ from the repo.
- At project scope, GitHub Copilot, Cursor, Codex, Gemini CLI, Antigravity, Amp, Cline, OpenCode and Warp share `.agents/skills`.

### Option C: copy into `.agents/skills`

`.agents/skills/` (in a project, or `~/.agents/skills/` in your home folder) is the cross-client location. Codex, Cursor, GitHub Copilot, Gemini CLI, Windsurf / Devin Desktop and OpenCode all read it. Claude Code uses `.claude/skills/` instead (see below).

```sh
git clone https://github.com/sidkolapalli/capella-agent-toolkit
mkdir -p .agents/skills
cp -r capella-agent-toolkit/skills/capella-setup capella-agent-toolkit/skills/arcadia-depth capella-agent-toolkit/skills/arcadia-elicit .agents/skills/
```

Some agents load project-level skills only after you mark the folder as trusted.

Source: [Agent Skills specification](https://agentskills.io/specification), [Adding skills support](https://agentskills.io/client-implementation/adding-skills-support), [vercel-labs/skills README](https://github.com/vercel-labs/skills/blob/main/README.md), [skills CLI commands](https://github.com/vercel-labs/skills/blob/main/docs/commands.md), [skills CLI agents](https://github.com/vercel-labs/skills/blob/main/docs/agents.md), [gh skill install](https://cli.github.com/manual/gh_skill_install), [GitHub changelog: manage agent skills with GitHub CLI](https://github.blog/changelog/2026-04-16-manage-agent-skills-with-github-cli/)

---

## Claude Code

### 1. Install the skills

Personal install (all your projects on this machine). Run from a clone of the toolkit:

```sh
mkdir -p ~/.claude/skills
cp -r skills/capella-setup skills/arcadia-depth skills/arcadia-elicit ~/.claude/skills/
```

One project only (commit `.claude/skills` so teammates get it):

```sh
mkdir -p .claude/skills
cp -r /path/to/capella-agent-toolkit/skills/capella-setup /path/to/capella-agent-toolkit/skills/arcadia-depth /path/to/capella-agent-toolkit/skills/arcadia-elicit .claude/skills/
```

Claude Code picks up new skills in the current session without a restart. If the top-level skills folder did not exist when the session started, run:

```
/reload-skills
```

### 2. Connect the Capella MCP server

**Recommended (fixed token).** Add the server at user scope. Use single quotes so your shell leaves `${CAPELLA_AI_DEVSERVER_TOKEN}` alone; Claude Code expands it when it connects.

```sh
claude mcp add-json capella '{"type":"http","url":"http://127.0.0.1:8765/mcp","headers":{"Authorization":"Bearer ${CAPELLA_AI_DEVSERVER_TOKEN}"}}' --scope user
```

`CAPELLA_AI_DEVSERVER_TOKEN` must be set, with the same value, for both Capella and Claude Code. If it is not set, the server still loads but sends the literal `${CAPELLA_AI_DEVSERVER_TOKEN}` text, and Capella rejects it. `claude mcp list` shows a missing-variable warning in that case.

Scopes: `local` (default, only you in the current project), `project` (shared through `.mcp.json`), `user` (you, in all projects). Set it with `-s` / `--scope`.

**Per project (shared).** Put this in `.mcp.json` at the project root. In interactive sessions, Claude Code asks you to approve project servers before it uses them.

```json
{
  "mcpServers": {
    "capella": {
      "type": "http",
      "url": "http://127.0.0.1:8765/mcp",
      "headers": {
        "Authorization": "Bearer ${CAPELLA_AI_DEVSERVER_TOKEN}"
      }
    }
  }
}
```

**Without a fixed token (macOS / Linux).** A `headersHelper` runs a command on every connection and again after a 401 or 403, so it can read the current token file each time:

```json
{
  "mcpServers": {
    "capella": {
      "type": "http",
      "url": "http://127.0.0.1:8765/mcp",
      "headersHelper": "echo '{\"Authorization\": \"Bearer '\"$(cat ~/.capella-ai/devserver-8765.token)\"'\"}'"
    }
  }
}
```

- The helper must print a JSON object of strings, and it has 10 seconds to finish.
- The command is POSIX shell syntax. The docs do not say which shell runs it on Windows, so on Windows test it first or use the `${CAPELLA_AI_DEVSERVER_TOKEN}` form.
- In a project `.mcp.json`, the helper runs only after you accept the workspace trust dialog. Until then only static headers are sent.
- For project or plugin helpers, Claude Code removes environment variables whose names contain `TOKEN`, `KEY` and similar words. That is why this helper reads the file instead of the variable.

**Shortcut, fixed token only.** `--header` stores the token value at the time you run the command, so use it only when `CAPELLA_AI_DEVSERVER_TOKEN` is pinned:

```sh
claude mcp add --transport http capella http://127.0.0.1:8765/mcp \
  --header "Authorization: Bearer $CAPELLA_AI_DEVSERVER_TOKEN"
```

### 3. Check it works

```sh
claude mcp list
```

In a session, run `/mcp` to see the `capella` server, then start the setup skill:

```
/capella-setup
```

`arcadia-depth` and `arcadia-elicit` also load on their own when your request matches their description, or run them with `/arcadia-depth` and `/arcadia-elicit`. `capella-setup` never loads on its own.

If you get a 401, write a debug log and search it for `never expanded toward a remote server`:

```sh
claude --debug-file /tmp/claude-debug.log
```

Source: [Claude Code skills](https://code.claude.com/docs/en/skills), [Claude Code MCP](https://code.claude.com/docs/en/mcp)

---

## OpenAI Codex (CLI, IDE extension, ChatGPT desktop app)

### 1. Install the skills

Standalone skills work in the Codex CLI, the IDE extension and the ChatGPT desktop app.

User install (every repo):

```sh
git clone https://github.com/sidkolapalli/capella-agent-toolkit.git
mkdir -p ~/.agents/skills
cp -R capella-agent-toolkit/skills/capella-setup capella-agent-toolkit/skills/arcadia-depth capella-agent-toolkit/skills/arcadia-elicit ~/.agents/skills/
```

Or symlink them, so `git pull` in the clone updates them:

```sh
mkdir -p ~/.agents/skills
for s in capella-setup arcadia-depth arcadia-elicit; do ln -s "$PWD/capella-agent-toolkit/skills/$s" ~/.agents/skills/$s; done
```

Project install. Codex scans `.agents/skills` from the current folder up to the repo root. Our toolkit keeps its skills in `skills/`, so copy them into your own project:

```sh
mkdir -p .agents/skills
cp -R /path/to/capella-agent-toolkit/skills/capella-setup /path/to/capella-agent-toolkit/skills/arcadia-depth /path/to/capella-agent-toolkit/skills/arcadia-elicit .agents/skills/
```

Alternative: the built-in `$skill-installer` skill. It installs into `$CODEX_HOME/skills/<skill-name>` (default `~/.codex/skills`) and stops if the folder already exists.

```
$skill-installer install https://github.com/sidkolapalli/capella-agent-toolkit/tree/main/skills/capella-setup
$skill-installer install https://github.com/sidkolapalli/capella-agent-toolkit/tree/main/skills/arcadia-depth
$skill-installer install https://github.com/sidkolapalli/capella-agent-toolkit/tree/main/skills/arcadia-elicit
```

Codex detects new skills automatically. If one does not appear, restart Codex.

Each skill includes `agents/openai.yaml`. For `capella-setup` it sets `policy.allow_implicit_invocation: false`, so Codex never picks it on its own; `$capella-setup` still works. The `arcadia-depth` and `arcadia-elicit` files declare the Capella MCP server as a dependency.

To turn a skill off without deleting it, add this to `~/.codex/config.toml` and restart Codex:

```toml
[[skills.config]]
path = "/home/you/.agents/skills/arcadia-elicit/SKILL.md"
enabled = false
```

### 2. Connect the Capella MCP server

The CLI, IDE extension and ChatGPT desktop app share `~/.codex/config.toml` (or `.codex/config.toml` in a trusted project). One setup covers all three.

**Recommended (fixed token).** Codex sends the variable's value as `Authorization: Bearer <token>`. No token goes in the file.

```toml
[mcp_servers.capella]
url = "http://127.0.0.1:8765/mcp"
bearer_token_env_var = "CAPELLA_AI_DEVSERVER_TOKEN"
```

Or add it from the CLI:

```sh
codex mcp add capella --url http://127.0.0.1:8765/mcp --bearer-token-env-var CAPELLA_AI_DEVSERVER_TOKEN
```

**Without a fixed token (macOS / Linux only).** `http_headers_helper` runs a command that prints the headers as JSON. After a 401 or 403 Codex runs it again, so a new token file is picked up. Do not combine it with `bearer_token_env_var`, which takes precedence. It does not work on Windows.

```toml
[mcp_servers.capella]
url = "http://127.0.0.1:8765/mcp"
http_headers_helper = '''printf '{"Authorization":"Bearer %s"}' "$(cat "$HOME/.capella-ai/devserver-8765.token")"'''
```

The IDE extension (gear menu > MCP servers > Add server, then Restart extension) and the desktop app (Settings > MCP servers > Add server, then Restart) can add a server by name and URL, but their forms have no header or token field. Set the token in `config.toml` or with `codex mcp add`.

### 3. Check it works

```sh
codex mcp list
```

In the Codex TUI, run `/mcp`. Then run `/skills`, or type `$` and the skill name:

```
$capella-setup
```

Source: [Build skills](https://learn.chatgpt.com/docs/build-skills), [MCP](https://learn.chatgpt.com/docs/extend/mcp?surface=cli), [Developer commands](https://learn.chatgpt.com/docs/developer-commands?surface=cli), [skill-installer SKILL.md (openai/codex)](https://github.com/openai/codex/blob/main/codex-rs/skills/src/assets/samples/skill-installer/SKILL.md)

---

## Cursor

### 1. Install the skills

Cursor reads skills from `.cursor/skills/` and `.agents/skills/` (project) and from `~/.cursor/skills/` and `~/.agents/skills/` (user). It also reads `.claude/skills/`, `.codex/skills/`, `~/.claude/skills/` and `~/.codex/skills/`, so skills you already installed for Claude Code or Codex work in Cursor too.

User install (all projects):

```sh
# macOS / Linux
git clone https://github.com/sidkolapalli/capella-agent-toolkit
mkdir -p ~/.cursor/skills
cp -r capella-agent-toolkit/skills/capella-setup capella-agent-toolkit/skills/arcadia-depth capella-agent-toolkit/skills/arcadia-elicit ~/.cursor/skills/
```

```powershell
# Windows (PowerShell)
git clone https://github.com/sidkolapalli/capella-agent-toolkit
New-Item -ItemType Directory -Force "$HOME\.cursor\skills" | Out-Null
Copy-Item -Recurse capella-agent-toolkit\skills\capella-setup, capella-agent-toolkit\skills\arcadia-depth, capella-agent-toolkit\skills\arcadia-elicit "$HOME\.cursor\skills\"
```

For one project, copy the same folders into `.cursor/skills/` in that repo. Skills in `~/.cursor/skills/` stay on your machine; they reach Cloud Agents only after you sync them.

Cursor cannot import standalone skills from a GitHub URL. Copying the folders is the supported route.

### 2. Connect the Capella MCP server

Put this in `~/.cursor/mcp.json` (all projects) or `.cursor/mcp.json` (one project):

```json
{
  "mcpServers": {
    "capella": {
      "url": "http://127.0.0.1:8765/mcp",
      "headers": {
        "Authorization": "Bearer ${env:CAPELLA_AI_DEVSERVER_TOKEN}"
      }
    }
  }
}
```

This needs the fixed token from [Before you start](#set-a-fixed-mcp-token). Cursor cannot read a token file for an HTTP server (`envFile` works only for STDIO servers). Restart Cursor after editing the file.

### 3. Check it works

Open **Customize** in the sidebar and make sure the `capella` server is enabled. In Agent chat, type `/` and pick the skill:

```
/capella-setup
```

`capella-setup` runs only when you type it. `arcadia-depth` and `arcadia-elicit` can also be applied automatically.

Source: [Cursor skills](https://cursor.com/docs/context/skills), [Cursor MCP](https://cursor.com/docs/context/mcp)

---

## GitHub Copilot (VS Code agent mode, Copilot CLI, cloud agent)

### 1. Install the skills

| Where | VS Code | Copilot CLI |
|---|---|---|
| Project | `.github/skills/`, `.claude/skills/`, `.agents/skills/` | same |
| Personal | `~/.copilot/skills/`, `~/.claude/skills/`, `~/.agents/skills/` | `~/.copilot/skills/`, `~/.agents/skills/` |

Use `~/.copilot/skills/` or `~/.agents/skills/` for a personal install that works in both.

```sh
git clone https://github.com/sidkolapalli/capella-agent-toolkit.git

# Project install (run in the repo where you want the skills)
mkdir -p .github/skills
cp -r capella-agent-toolkit/skills/capella-setup capella-agent-toolkit/skills/arcadia-depth capella-agent-toolkit/skills/arcadia-elicit .github/skills/

# Personal install (all projects)
mkdir -p ~/.copilot/skills
cp -r capella-agent-toolkit/skills/capella-setup capella-agent-toolkit/skills/arcadia-depth capella-agent-toolkit/skills/arcadia-elicit ~/.copilot/skills/
```

Or use the GitHub CLI (2.90.0 or later; project scope installs to `.agents/skills`):

```sh
gh skill install sidkolapalli/capella-agent-toolkit --all
gh skill install sidkolapalli/capella-agent-toolkit --all --agent github-copilot --scope user
```

Copilot CLI can also read a cloned `skills/` folder directly:

```sh
export COPILOT_SKILLS_DIRS="$HOME/capella-agent-toolkit/skills"
```

For the cloud agent, commit the three folders under `.github/skills/` (or `.agents/skills/` or `.claude/skills/`).

Do not rename the folders: in VS Code a skill whose `name` does not match its folder fails to load without a warning. Keep the relative links in each `SKILL.md`; VS Code loads only the extra files that `SKILL.md` links to.

### 2. Connect the Capella MCP server

**Recommended (fixed token).** Put this in `~/.copilot/mcp-config.json` (or `$COPILOT_HOME/mcp-config.json`). Both Copilot CLI and the VS Code Copilot harness read it. Copilot CLI requires the `tools` field.

```json
{
  "mcpServers": {
    "capella": {
      "type": "http",
      "url": "http://127.0.0.1:8765/mcp",
      "headers": {
        "Authorization": "Bearer ${CAPELLA_AI_DEVSERVER_TOKEN}"
      },
      "tools": ["*"]
    }
  }
}
```

Or add it from Copilot CLI. Single quotes keep your shell from expanding the variable; the CLI expands it on each connection.

```sh
copilot mcp add --transport http \
  --header 'Authorization: Bearer ${CAPELLA_AI_DEVSERVER_TOKEN}' \
  capella http://127.0.0.1:8765/mcp
```

Other places:

- **Per project:** `.mcp.json` at the repo root or `.github/mcp.json`, same format. Copilot CLI loads them only after you trust the folder. VS Code also reads a workspace-root `.mcp.json`.
- **VS Code only:** `.vscode/mcp.json` uses a top-level `servers` object and `${env:NAME}`. Copilot CLI does not read this file, and the VS Code guided flow labels it deprecated.

```json
{
  "servers": {
    "capella": {
      "type": "http",
      "url": "http://127.0.0.1:8765/mcp",
      "headers": {
        "Authorization": "Bearer ${env:CAPELLA_AI_DEVSERVER_TOKEN}"
      }
    }
  }
}
```

Use `${NAME}` in the portable files (`.mcp.json`, `~/.copilot/mcp-config.json`) and `${env:NAME}` only in `.vscode/mcp.json`. Avoid `${input:...}` prompts: VS Code stores the value you type, which goes stale when Capella's token changes, and Copilot (non-Local) harness sessions do not receive such servers.

The cloud agent runs in a GitHub Actions environment, so it cannot reach the Capella server on your machine. Committed skills still work there; steps that need Capella do not.

### 3. Check it works

```sh
copilot mcp get capella
copilot skill list
```

Inside a Copilot CLI session:

```
/skills reload
/skills info arcadia-depth
```

In VS Code agent mode or Copilot CLI, run the setup skill:

```
/capella-setup
```

Source: [VS Code agent skills](https://code.visualstudio.com/docs/agent-customization/agent-skills), [VS Code MCP configuration](https://code.visualstudio.com/docs/agents/reference/mcp-configuration), [VS Code migrate customizations](https://code.visualstudio.com/docs/agent-customization/migrate-customizations), [VS Code MCP servers](https://code.visualstudio.com/docs/agent-customization/mcp-servers), [Copilot CLI: add skills](https://docs.github.com/en/copilot/how-tos/copilot-cli/customize-copilot/add-skills), [Copilot CLI: add MCP servers](https://docs.github.com/en/copilot/how-tos/copilot-cli/customize-copilot/add-mcp-servers), [Copilot CLI command reference](https://docs.github.com/en/copilot/reference/copilot-cli-reference/cli-command-reference), [Cloud agent: add skills](https://docs.github.com/en/copilot/how-tos/copilot-on-github/customize-copilot/customize-cloud-agent/add-skills), [Cloud agent environment](https://docs.github.com/en/copilot/how-tos/copilot-on-github/customize-copilot/customize-cloud-agent/customize-the-agent-environment), [gh skill install](https://cli.github.com/manual/gh_skill_install)

---

## Claude Desktop and claude.ai

Skills work in both. The MCP server works only in Claude Desktop.

### 1. Install the skills

1. Turn on code execution. Free, Pro and Max: **Settings > Capabilities > Code execution and file creation** (called **Create and edit files** in the newer Settings layout). Team and Enterprise: an owner turns on code execution and Skills under **Organization settings > Plugins & skills**, on the **Policy** tab.
2. Zip each skill folder. The folder itself must be the top level of the ZIP (`<skill-name>/SKILL.md`).

   ```sh
   # macOS / Linux
   cd skills
   zip -r capella-setup.zip capella-setup/
   zip -r arcadia-depth.zip arcadia-depth/
   zip -r arcadia-elicit.zip arcadia-elicit/
   unzip -l arcadia-depth.zip   # every entry must start with arcadia-depth/
   ```

   ```powershell
   # Windows (cmd or PowerShell), from the repo root; tar.exe ships with Windows 10+
   tar -a -c -f capella-setup.zip -C skills capella-setup
   tar -a -c -f arcadia-depth.zip -C skills arcadia-depth
   tar -a -c -f arcadia-elicit.zip -C skills arcadia-elicit
   tar -tf arcadia-depth.zip   # every entry must start with arcadia-depth/
   ```

   On Windows, do not use `Compress-Archive` in Windows PowerShell 5.1. It writes backslashes in entry names (`capella-setup\SKILL.md`). `Compress-Archive` in PowerShell 7 (`pwsh`) is fine.
3. In claude.ai or Claude Desktop, open **Customize > Skills**, click **+**, then **+ Create skill**, then **Upload a skill**. Upload `capella-setup.zip`, then `arcadia-depth.zip`, then `arcadia-elicit.zip`.
4. Turn each skill on in **Customize > Skills**.

Notes:

- Uploaded skills are private to your account until you share them. Each user uploads their own copy.
- The sources disagree on the description limit. An older support article says 200 characters; the newer docs and the Agent Skills spec say 1,024. `arcadia-depth`'s description is about 440 characters. If its upload is rejected, shorten the description.
- Claude picks skills automatically from your request; chat has no documented manual invocation. It is not documented whether claude.ai honours `disable-model-invocation`, so `capella-setup`'s manual-only setting may not apply here.

### 2. Connect the Capella MCP server (Claude Desktop only)

claude.ai on the web and custom connectors connect from Anthropic's cloud, not from your machine, so they cannot reach `http://127.0.0.1:8765/mcp`. Making it reachable would need a public tunnel, which exposes your Capella model to the internet. We do not recommend it.

Claude Desktop runs local servers from `claude_desktop_config.json` as launched processes, so the Capella HTTP endpoint needs a bridge. Anthropic's docs name `mcp-remote` for this. You need Node.js (`node --version`).

Open the file from **Settings > Developer > Edit Config**:

- macOS: `~/Library/Application Support/Claude/claude_desktop_config.json`
- Windows: `%APPDATA%\Claude\claude_desktop_config.json`

The configs below read the current token file each time the bridge starts. **They have not yet been tested against a live Claude Desktop.**

Windows:

```json
{
  "mcpServers": {
    "capella": {
      "command": "powershell",
      "args": ["-NoProfile", "-Command", "$env:CAPELLA_AI_AUTH='Bearer '+(Get-Content (Join-Path $env:USERPROFILE '.capella-ai\\devserver-8765.token') -Raw).Trim(); npx.cmd -y mcp-remote http://127.0.0.1:8765/mcp --header 'Authorization:${CAPELLA_AI_AUTH}'"]
    }
  }
}
```

macOS (if `npx` is not found, use its absolute path from `which npx`):

```json
{
  "mcpServers": {
    "capella": {
      "command": "/bin/sh",
      "args": ["-c", "CAPELLA_AI_AUTH=\"Bearer $(cat \"$HOME/.capella-ai/devserver-8765.token\")\" exec npx -y mcp-remote http://127.0.0.1:8765/mcp --header 'Authorization:${CAPELLA_AI_AUTH}'"]
    }
  }
}
```

- Keep `Authorization:${CAPELLA_AI_AUTH}` with no space. Claude Desktop on Windows does not escape spaces in `args`.
- `--allow-http` is not needed for `127.0.0.1`.

After saving, fully quit and restart Claude Desktop. After a Capella restart, restart Claude Desktop again, or use **Developer > Reload MCP Configuration** (enable it first with **Help > Troubleshooting > Enable Developer Mode**). We assume the reload restarts the bridge and so reads the new token; the docs only say it applies config changes.

### 3. Check it works

Click **+** (Add files, connectors, and more) > **Connectors** > **Manage connectors** and look for `capella`. If it fails, read the logs in `~/Library/Logs/Claude` (macOS) or `%APPDATA%\Claude\logs` (Windows): `mcp.log` and `mcp-server-capella.log`. Then make a request that matches one of the skills; Claude picks the skill itself.

Source: [Use skills in Claude](https://support.claude.com/en/articles/12512180-use-skills-in-claude), [How to create custom skills](https://support.claude.com/en/articles/12512198-how-to-create-custom-skills), [Skills how-to](https://claude.com/docs/skills/how-to.md), [Connect local MCP servers](https://modelcontextprotocol.io/docs/develop/connect-local-servers), [MCP Apps getting started](https://claude.com/docs/connectors/building/mcp-apps/getting-started), [MCP Apps troubleshooting](https://claude.com/docs/connectors/building/mcp-apps/troubleshooting), [Custom connectors using remote MCP](https://support.claude.com/en/articles/11175166-getting-started-with-custom-connectors-using-remote-mcp), [Testing connectors](https://claude.com/docs/connectors/building/testing.md), [mcp-remote](https://github.com/geelen/mcp-remote)

---

## Gemini CLI

### 1. Install the skills

Install all three from GitHub. `--path skills` is required, because Gemini looks for `SKILL.md` only at the source root and one folder below it.

```sh
# user scope (default, all projects)
gemini skills install https://github.com/sidkolapalli/capella-agent-toolkit --path skills

# this project only
gemini skills install https://github.com/sidkolapalli/capella-agent-toolkit --path skills --scope workspace
```

Add `--consent` to skip the confirmation prompt. From a local clone, point at the `skills/` folder itself:

```sh
git clone https://github.com/sidkolapalli/capella-agent-toolkit
gemini skills install ./capella-agent-toolkit/skills
# or symlink, so edits in the clone show up immediately:
gemini skills link ./capella-agent-toolkit/skills
```

Manual copy also works. Each skill folder must sit directly under the skills folder (`~/.gemini/skills/arcadia-depth/SKILL.md`).

```sh
# macOS / Linux, user scope
mkdir -p ~/.gemini/skills && cp -r capella-agent-toolkit/skills/* ~/.gemini/skills/
```

```powershell
# Windows PowerShell, user scope
New-Item -ItemType Directory -Force -Path "$HOME\.gemini\skills"; Copy-Item -Recurse capella-agent-toolkit\skills\* "$HOME\.gemini\skills\"
```

For a project, use `.gemini/skills/` or `.agents/skills/` and commit it. Workspace skills load only in a trusted folder: run `/trust` and restart if needed.

Gemini CLI ignores `disable-model-invocation`, so it may start `capella-setup` on its own. Gemini asks for your approval before it activates any skill. To keep `capella-setup` manual, disable it and enable it only when you need it:

```
/skills disable capella-setup
/skills enable capella-setup
```

### 2. Connect the Capella MCP server

Add this to `~/.gemini/settings.json` (user) or `.gemini/settings.json` (project). Keep the name `capella`: Gemini warns against underscores in server names.

```json
{
  "mcpServers": {
    "capella": {
      "httpUrl": "http://127.0.0.1:8765/mcp",
      "headers": {
        "Authorization": "Bearer ${CAPELLA_AI_DEVSERVER_TOKEN}"
      }
    }
  }
}
```

`CAPELLA_AI_DEVSERVER_TOKEN` must be set in the shell that starts `gemini`. Either use the fixed token from [Before you start](#set-a-fixed-mcp-token), or export it from the token file before each run (repeat after every Capella restart):

```sh
# macOS / Linux
export CAPELLA_AI_DEVSERVER_TOKEN="$(cat ~/.capella-ai/devserver-8765.token)"
gemini
```

```powershell
# Windows PowerShell
$env:CAPELLA_AI_DEVSERVER_TOKEN = Get-Content "$HOME\.capella-ai\devserver-8765.token"
gemini
```

You can also run `gemini mcp add --transport http -s user capella http://127.0.0.1:8765/mcp` and then add the `headers` block to `~/.gemini/settings.json` by hand. Do not pass the token with `-H`; that saves it to disk.

### 3. Check it works

```sh
gemini mcp list
```

The server should show as `(http) - Connected`. In a session:

```
/skills list
/skills reload
```

Source: [Gemini CLI skills](https://geminicli.com/docs/cli/skills/), [Getting started with skills](https://geminicli.com/docs/cli/tutorials/skills-getting-started/), [Creating skills](https://geminicli.com/docs/cli/creating-skills/), [CLI reference](https://geminicli.com/docs/cli/cli-reference/), [Configuration](https://geminicli.com/docs/reference/configuration/), [MCP servers](https://geminicli.com/docs/tools/mcp-server/), [skillLoader.ts](https://github.com/google-gemini/gemini-cli/blob/main/packages/core/src/skills/skillLoader.ts)

---

## Windsurf / Devin Desktop

Windsurf was renamed Devin Desktop in v3.0.12 (June 2, 2026). Cascade was removed in v3.9.19 (September 8, 2026); Devin Local is now the only agent. Devin Local shares its harness with the `devin` CLI, so one install covers both. Old Windsurf workflows do not work with Devin Local; use skills.

### 1. Install the skills

Global install:

```sh
# macOS / Linux
git clone https://github.com/sidkolapalli/capella-agent-toolkit
mkdir -p ~/.config/devin/skills
cp -R capella-agent-toolkit/skills/capella-setup capella-agent-toolkit/skills/arcadia-depth capella-agent-toolkit/skills/arcadia-elicit ~/.config/devin/skills/
```

```powershell
# Windows PowerShell
git clone https://github.com/sidkolapalli/capella-agent-toolkit
New-Item -ItemType Directory -Force "$env:APPDATA\devin\skills" | Out-Null
Copy-Item -Recurse -Force capella-agent-toolkit\skills\capella-setup, capella-agent-toolkit\skills\arcadia-depth, capella-agent-toolkit\skills\arcadia-elicit "$env:APPDATA\devin\skills\"
```

Per project (commit with the repo):

```sh
mkdir -p .devin/skills
cp -R capella-agent-toolkit/skills/capella-setup capella-agent-toolkit/skills/arcadia-depth capella-agent-toolkit/skills/arcadia-elicit .devin/skills/
```

Devin also reads `.agents/skills/`, `~/.agents/skills/`, `.windsurf/skills/`, `~/.codeium/<channel>/skills/` and `.claude/skills/`, so skills installed there for other agents are picked up without another copy.

Or install the repo as a Devin plugin. A repo with only `skills/<name>/SKILL.md` installs as a skills-only plugin. You must be signed in, and the repo must be reachable by git.

```sh
devin auth login
devin plugins install sidkolapalli/capella-agent-toolkit
```

Plugin skills are named `/capella-agent-toolkit:<skill>`, for example `/capella-agent-toolkit:arcadia-elicit`.

Devin's docs describe a `triggers: [user]` field for manual-only skills and do not mention `disable-model-invocation`, so Devin may start `capella-setup` on its own.

### 2. Connect the Capella MCP server

Edit the user config: `~/.config/devin/mcp_config.json` (Windows: `%APPDATA%\devin\mcp_config.json`). Project config is `.devin/mcp_config.json`; `.devin/mcp_config.local.json` is the gitignored per-user override.

```json
{
  "mcpServers": {
    "capella": {
      "url": "http://127.0.0.1:8765/mcp",
      "transport": "http",
      "headers": {
        "Authorization": "Bearer ${env:CAPELLA_AI_DEVSERVER_TOKEN}"
      }
    }
  }
}
```

`url`, `transport` and `headers` are documented for Devin Local. The `${env:...}` substitution in headers is documented only for the legacy Cascade agent. It should work, but verify it (step 3). The same applies to `${file:~/.capella-ai/devserver-8765.token}`; if you use that form, restart the Devin session after each Capella restart. An unset `${env:...}` variable becomes an empty string.

Fallback: put a literal `"Authorization": "Bearer <token>"` in the gitignored `.devin/mcp_config.local.json`. That only stays valid if Capella runs with a fixed `CAPELLA_AI_DEVSERVER_TOKEN`.

You can also use `devin mcp add` (positional URL, `-s`/`--scope`, repeatable `-H`), but editing the file keeps the token out of it.

Devin Local asks before every MCP tool call. To pre-approve all Capella tools, add this to your Devin permissions config:

```json
{
  "permissions": {
    "allow": ["mcp__capella__*"]
  }
}
```

A server left in the old Windsurf config `~/.codeium/<channel>/mcp_config.json` is still imported.

### 3. Check it works

`devin mcp get capella` shows the server details, but it is not documented to show resolved headers. To confirm the token works, ask for something that calls a Capella tool in a session. A 401 means the token did not resolve. Run the setup skill with:

```
/capella-setup
```

Source: [Devin Desktop changelog](https://docs.devin.ai/desktop/changelog), [Devin Local](https://docs.devin.ai/desktop/devin-local), [Devin CLI skills](https://docs.devin.ai/cli/extensibility/skills/overview), [Devin CLI plugins](https://docs.devin.ai/cli/extensibility/plugins/overview), [Read config from other tools](https://docs.devin.ai/cli/reference/configuration/read-config-from), [Devin CLI MCP configuration](https://docs.devin.ai/cli/extensibility/mcp/configuration), [Legacy Cascade MCP](https://docs.devin.ai/desktop/cascade/mcp), [Devin CLI commands](https://docs.devin.ai/cli/reference/commands)

---

## OpenCode

### 1. Install the skills

Global install:

```sh
git clone https://github.com/sidkolapalli/capella-agent-toolkit
mkdir -p ~/.config/opencode/skills
cp -r capella-agent-toolkit/skills/capella-setup \
      capella-agent-toolkit/skills/arcadia-depth \
      capella-agent-toolkit/skills/arcadia-elicit \
      ~/.config/opencode/skills/
```

One project:

```sh
mkdir -p .opencode/skills
cp -r /path/to/capella-agent-toolkit/skills/capella-setup \
      /path/to/capella-agent-toolkit/skills/arcadia-depth \
      /path/to/capella-agent-toolkit/skills/arcadia-elicit \
      .opencode/skills/
```

OpenCode also reads `.claude/skills/` and `.agents/skills/` (in the project, up to the git root, and in your home folder). If you already installed the skills there, skip this step. Do not install the same skill in two places; duplicate names can stop a skill from showing up. If `OPENCODE_DISABLE_CLAUDE_CODE` or `OPENCODE_DISABLE_CLAUDE_CODE_SKILLS` is set, skills in `.claude/skills` are not loaded.

The agent loads skills itself through its `skill` tool. To start one, ask for it by name, for example "use the arcadia-elicit skill".

OpenCode ignores `disable-model-invocation`, so it may load `capella-setup` on its own. To require your approval first, add this to `opencode.json`:

```json
{
  "$schema": "https://opencode.ai/config.json",
  "permission": {
    "skill": {
      "capella-setup": "ask"
    }
  }
}
```

### 2. Connect the Capella MCP server

Put the server in `~/.config/opencode/opencode.json` (user) or `opencode.json` at the project root. The two files are merged; the project file wins. `oauth: false` stops OpenCode from trying OAuth.

**Recommended (fixed token):**

```json
{
  "$schema": "https://opencode.ai/config.json",
  "mcp": {
    "capella": {
      "type": "remote",
      "url": "http://127.0.0.1:8765/mcp",
      "enabled": true,
      "oauth": false,
      "headers": {
        "Authorization": "Bearer {env:CAPELLA_AI_DEVSERVER_TOKEN}"
      }
    }
  }
}
```

If the variable is not set in the shell that runs `opencode`, the header becomes `Bearer ` and authentication fails.

**Alternative (token file):**

```json
{
  "$schema": "https://opencode.ai/config.json",
  "mcp": {
    "capella": {
      "type": "remote",
      "url": "http://127.0.0.1:8765/mcp",
      "enabled": true,
      "oauth": false,
      "headers": {
        "Authorization": "Bearer {file:~/.capella-ai/devserver-8765.token}"
      }
    }
  }
}
```

OpenCode reads its config at startup. The docs do not say when `{file:...}` is substituted; we assume at startup, so restart OpenCode after each Capella restart. Neither snippet contains a secret, so `opencode.json` is safe to commit.

### 3. Check it works

```sh
opencode mcp list
opencode debug skill
```

Source: [OpenCode skills](https://opencode.ai/docs/skills/), [OpenCode MCP servers](https://opencode.ai/docs/mcp-servers/), [OpenCode config](https://opencode.ai/docs/config/), [OpenCode CLI](https://opencode.ai/docs/cli/)

---

## Plugins

**Claude Code.** The repository is a Claude Code plugin and its own marketplace: `.claude-plugin/plugin.json` and
`.claude-plugin/marketplace.json` at the root, the skills in `skills/`, and the Capella MCP server in the root
`.mcp.json` (the fixed-token form from [Before you start](#set-a-fixed-mcp-token)). Installing the plugin installs
all three skills and the server.

```sh
# inside Claude Code
/plugin marketplace add sidkolapalli/capella-agent-toolkit
/plugin install capella-agent-toolkit@capella-tools

# or from your shell
claude plugin marketplace add sidkolapalli/capella-agent-toolkit
claude plugin install capella-agent-toolkit@capella-tools

# one step (Claude Code v2.1.292 or later from the shell)
claude plugin install capella-agent-toolkit --marketplace sidkolapalli/capella-agent-toolkit

# enable for collaborators (each collaborator still runs this once)
claude plugin install capella-agent-toolkit@capella-tools --scope project
```

- Plugin skills are named `/capella-agent-toolkit:capella-setup`, `/capella-agent-toolkit:arcadia-depth` and
  `/capella-agent-toolkit:arcadia-elicit`. The plugin's server shows in `/mcp` as
  `plugin:capella-agent-toolkit:capella`; you add or remove it by installing or uninstalling the plugin.
- Claude Code starts plugin MCP servers automatically. `CAPELLA_AI_DEVSERVER_TOKEN` must be set for both Capella and
  Claude Code, or the server answers 401.
- Try a local checkout without installing: `claude --plugin-dir /path/to/capella-agent-toolkit`.

**Windsurf / Devin Desktop.** `devin plugins install sidkolapalli/capella-agent-toolkit` installs the repo as a plugin
(see above). Devin falls back to `.claude-plugin/plugin.json` and honours the root `.mcp.json`.

**Others.** `npx skills add` reads skill paths from `.claude-plugin/marketplace.json`. The ChatGPT desktop app can read
`.claude-plugin/marketplace.json`; the Codex CLI is not documented to read it, so test before relying on it. Native
Gemini CLI, Codex and Cursor plugin manifests are not shipped yet.

Source: [Create plugins](https://code.claude.com/docs/en/plugins/create), [Plugin components](https://code.claude.com/docs/en/plugins/components), [Marketplace reference](https://code.claude.com/docs/en/plugins/marketplace-reference), [Create a marketplace](https://code.claude.com/docs/en/plugins/create-marketplace), [Install plugins](https://code.claude.com/docs/en/plugins/install), [Host a marketplace](https://code.claude.com/docs/en/plugins/host-marketplace), [Claude Code MCP](https://code.claude.com/docs/en/mcp), [Devin CLI plugins](https://docs.devin.ai/cli/extensibility/plugins/overview), [Devin CLI skills](https://docs.devin.ai/cli/extensibility/skills/overview), [skills CLI: creating skills](https://github.com/vercel-labs/skills/blob/main/docs/creating-skills.md), [OpenAI: build plugins](https://developers.openai.com/plugins/build/plugins), [Gemini CLI extensions reference](https://geminicli.com/docs/extensions/reference/)