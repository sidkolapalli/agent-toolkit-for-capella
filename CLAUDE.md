# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Capella AI Assistant is an Eclipse plugin that integrates LLM-powered AI assistance into Capella, a systems engineering tool for MBSE using the Arcadia methodology. The plugin enables conversational modeling, automated architecture generation, and guided Arcadia workflows.

## Build Commands

**Prerequisites**: Java 17+, Maven with Tycho support

Before building, export XML entity limits once per shell session:
```bash
export JAVA_TOOL_OPTIONS="-Djdk.xml.maxGeneralEntitySizeLimit=0 -Djdk.xml.totalEntitySizeLimit=0 -Djdk.xml.entityExpansionLimit=0"
```

**Full build** (compile, package feature, create update site):
```bash
mvn -f plugins clean verify
```

**Core plugin only** (faster iteration when working on Java code):
```bash
mvn -pl plugins/ai-capella -am clean verify
```

**Quick rebuild** (skip tests, just refresh binaries):
```bash
mvn -f plugins clean -DskipTests
```

**Update site location**: `plugins/ai-capella-repository/target/repository`

## Development Workflow

### Installing in Capella
1. Build the update site using `mvn -f plugins clean verify`
2. In Capella: `Help → Install New Software… → Add… → Local…`
3. Select `plugins/ai-capella-repository/target/repository`
4. Accept the wizard, restart Capella
5. Open the view: `Window → Show View → Other… → Capella AI Assistant`

### Testing Changes
- No automated test suite exists yet; test manually in Capella
- Always run `mvn -f plugins clean verify` before committing to catch compile-time errors
- When adding features, verify UI flows, EMF transactions, and undo/redo behavior

## Architecture

### Module Structure
```
plugins/
├── ai-capella/               # Core Eclipse plugin
│   ├── src/org/polarsys/capella/ai/
│   │   ├── ChatView.java            # Main assistant UI
│   │   ├── actions/                 # Arcadia action handlers (Operational, System, etc.)
│   │   ├── llm/                     # LLM integration (ToolRouter, LLMService)
│   │   ├── model/                   # Model operations (ModelSummary, ModelOps, Finders)
│   │   ├── session/                 # EMF transaction safety (SessionSafeguards, PlanDryRun)
│   │   ├── ui/                      # UI components (PlanEditorDialog)
│   │   ├── preferences/             # LLM configuration
│   │   ├── telemetry/               # Opt-in usage tracking
│   │   ├── importer/                # Draw.io import
│   │   └── tools/                   # Diagram commands
│   ├── plugin.xml                   # Eclipse extension points
│   └── META-INF/MANIFEST.MF         # OSGi bundle manifest
├── ai-capella-feature/       # Installable feature definition
├── ai-capella-repository/    # P2 update site
└── pom.xml                   # Tycho aggregator
```

### Key Components

**ChatView** (`ChatView.java`): Main UI view handling user conversations, streaming LLM responses, inline context cards, methodology coach, and template panels

**ToolRouter** (`llm/ToolRouter.java`): Routes LLM responses to appropriate handlers; parses `TOOL_PLAN` JSON actions for model manipulation

**Action Classes** (`actions/`): Implement Arcadia-specific operations organized by architecture layer:
- `OperationalActions`: Missions, activities, operational exchanges, capability links
- `SystemActions`: Components, functions, interfaces, capability allocations
- `TransformationActions`: Logical ↔ Physical layer transformations with traceability
- `FunctionalChainActions`: Functional chain creation, extension, scenario linking
- `RequirementActions`: Requirement creation, linking, coverage tracking
- `MassEditActions`: Batch operations (rename, reparent, allocate, delete)

**SessionSafeguards** (`session/SessionSafeguards.java`): Ensures all EMF model mutations run in UI-thread transactions with proper undo/redo support

**PlanDryRun** (`session/PlanDryRun.java`): In-memory sandbox for validating plans before execution; provides diff previews and validation warnings

**ModelSummary** (`model/ModelSummary.java`): Generates cached model snapshots for LLM context; produces layer-specific dashboards with coverage metrics

**ModelIndex** (`model/index/ModelIndex.java`): Searchable index of model elements exposed via Search Model button

**PlanEditorDialog** (`ui/PlanEditorDialog.java`): Rich UI for reviewing plans with checkbox toggles, action details, diff previews, and sandbox validation

### Data Flow: User Request → LLM → Model Update

1. User sends message in `ChatView`
2. `ToolRouter` builds LLM prompt with conversation history + `ModelSummary` context
3. `LLMService` streams response from configured provider (OpenAI, Anthropic, etc.)
4. `ToolRouter` parses `TOOL_PLAN` JSON actions
5. `PlanDryRun` validates plan in sandbox, generates diff preview
6. `PlanEditorDialog` shows user the plan with warnings/conflicts
7. User confirms → `SessionSafeguards` executes plan in EMF transaction
8. Changes apply to Capella model with full undo/redo support

## Coding Standards

**Package Structure**: All code under `org.polarsys.capella.ai.*`

**Naming Conventions**:
- Classes: UpperCamelCase (`ChatView`, `ModelOps`)
- Methods: camelCase with action prefixes (`onCreateMission`, `buildPrompt`)
- Event handlers: `on<Action>` pattern

**Formatting**: 4-space indentation, UTF-8 encoding (see `pom.xml` `project.build.sourceEncoding`)

**Eclipse Integration**:
- Update `plugin.xml` when adding views, commands, or categories
- Bump versions in `MANIFEST.MF` and feature descriptors for releases
- Keep OSGi metadata and generated repositories out of commits (see `.gitignore`)

## EMF and Capella Specifics

**Thread Safety**: All model access must occur on the Eclipse UI thread. Use `SessionSafeguards` or `Display.syncExec()` for EMF operations.

**Transactions**: EMF model mutations require explicit transactions. `SessionSafeguards.wrapInTransaction()` provides reliable undo/redo behavior.

**Model Navigation**: Use `Finders` utility for locating elements by name/type across architecture layers. `ArchitectureLocator` provides layer-specific navigation.

**Validation**: Capella's native validation results surface in `ModelSummary` and Plan Editor dry-runs to prevent invalid operations.

## LLM Integration

**Configuration**: LLM settings managed via `LLMPreferences` (provider, model, base URL, temperature, tokens, timeouts). API keys stored securely in Eclipse preferences.

**Providers Supported**: OpenAI, Anthropic, Azure OpenAI, Ollama (local)

**Tool Protocol**: LLMs return JSON `TOOL_PLAN` objects with action arrays. Each action specifies operation type, target elements, parameters, and validation rules.

**Streaming**: `LLMService` supports streaming responses for immediate feedback with interrupt capability.

**Context Management**: Conversation history maintained in `ChatView`; bounded to recent turns. `ModelSummary` caches model state and invalidates after mutations.

## Telemetry

**Opt-in Only**: Checkbox in ChatView enables anonymous usage logging

**Logged Metrics**: Plan counts (suggested vs executed), action types, error rates, template usage

**Purpose**: Prioritize feature development based on real usage patterns

**Storage**: Local plugin log only; no external transmission

## Common Tasks

**Adding a new Arcadia action**:
1. Add method to appropriate action class (`OperationalActions`, `SystemActions`, etc.)
2. Implement validation logic (dry-run compatibility)
3. Update `ToolRouter` to parse new action type from LLM JSON
4. Add template UI button in `ChatView` if needed
5. Test in Capella with undo/redo verification

**Extending ModelSummary**:
1. Add fields to summary data structure
2. Update caching logic in `buildSummary()`
3. Ensure invalidation after relevant model mutations
4. Test with large models (>10k elements)

**Adding LLM provider support**:
1. Extend `LLMService` with provider-specific API client
2. Add configuration options to `LLMPreferences`
3. Update ChatView LLM Connection panel
4. Test streaming and error handling

## Known Constraints

- Large models (>100k elements) may slow ModelSummary generation; caching mitigates this but initial load takes time
- Tycho requires Eclipse 2023-03+ repositories; XML entity limits must be relaxed (see Build Commands)
- UI operations must run on UI thread; background jobs need `Display.asyncExec()` for UI updates
- Draw.io import assumes specific shape naming conventions; edge mapping may require manual adjustment

## Arcadia Methodology Context

The plugin implements Arcadia's layered approach to systems engineering:
1. **Operational Analysis (OA)**: Missions, activities, capabilities
2. **System Analysis (SA)**: System functions, components, interfaces
3. **Logical Architecture (LA)**: Logical components, allocated functions
4. **Physical Architecture (PA)**: Physical components, deployment

The Methodology Coach tracks progress across these layers and suggests next steps based on Arcadia best practices.
