# ai-capella

The Eclipse plug-in, shown in Eclipse Capella® as **AI Assistant for Capella**. It hosts the MCP server, the chat
assistant and the Groovy script API that both use.

| Path | Contents |
|---|---|
| `src/com/sidkolapalli/capella/ai/` | Java sources; [ARCHITECTURE.md](../../ARCHITECTURE.md) explains how they fit together |
| `resources/capella-api/` | The API documentation agents read on demand (`api.fs`, `capella_docs`) |
| `resources/codex/windows-x64/` | Provenance of the bundled Codex runtime; the executables come from `scripts/fetch-codex.py` |
| `plugin.xml`, `META-INF/` | Views, preferences and bundle manifest |

Tests are in [`../ai-capella.tests`](../ai-capella.tests). Build, install and use are covered in the
[README](../../README.md) and [docs/PLUGIN.md](../../docs/PLUGIN.md).
