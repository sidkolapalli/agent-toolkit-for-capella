# AI Capella Plugin (Prototype)

Goal: In‑process chat assistant inside Capella that can read, modify, and visualize models using EMF/Sirius APIs.

This is a skeleton Eclipse/Capella plugin to host a Chat view and tool layer. It will be wired to the active Capella Session for safe, undoable edits and live diagram updates.

Key pieces to implement next:
- Bridge to current `Session` and `TransactionalEditingDomain`.
- EMF transactions (RecordingCommand) for CRUD.
- Sirius UI commands for node/edge add and layout.
- LLM connector and optional MCP server inside the plugin.
