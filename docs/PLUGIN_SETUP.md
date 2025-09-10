# Capella AI Plugin — Setup Guide

This plugin provides a Chat view inside Capella and utilities to operate on the active model session with EMF/Sirius APIs.

## Build/Install (Developer)

Option A — Import into Eclipse/Capella:
- File → Import → Existing Projects into Workspace
- Select `plugins/ai-capella` folder
- Ensure the target Capella SDK is in your target platform
- Launch a runtime workbench, then open View: Window → Show View → Other… → Capella AI Assistant

Option B — Tycho (p2 update site):
- A Tycho build is provided under `plugins/`.
- Prereq: a Capella/Eclipse target platform available during build.
- Build all: `mvn -f plugins clean verify`
- Install from p2 repo: `plugins/ai-capella-repository/target/repository`

## Running
- Open a Capella project and a diagram/model file in the editor
- Open the “Capella AI Assistant” view (right side of editor area)
- The view is a scaffold; next iterations will wire chat → tool calls → EMF transactions
  - LLM: Open Preferences → Capella AI → LLM. Set Provider (e.g., openai/anthropic), API Base, Model, and API Key. Click “Test Connection”, then OK.
  - Chat: Type a prompt in the view and press Send. Replies appear inline.
  - Import draw.io: Click “Import draw.io → Capella”, choose a .drawio file. The plugin creates LogicalComponents in LA and opens a LAB diagram, revealing and arranging them. For first iteration edges are recorded but not yet mapped to exchanges.
  - LLM mapping toggle: “Use LLM Mapping” asks the configured LLM to classify edges as functional vs component before import. Current version creates ComponentExchanges between components; functional mapping is reserved for future function-level modeling.

## Next Steps
- Wire SessionBridge to list active diagrams/viewpoints
- Add commands to create/open LAB/LDFB/PAB diagrams and arrange layout
- Add CRUD with EMF RecordingCommand + undo/redo integration
- Integrate LLM tool-calling and stream responses into the Chat view
 - Package a p2 site (Tycho feature/repository) for easy installation
