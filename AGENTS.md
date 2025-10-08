# Repository Guidelines

## Project Structure & Module Organization
The Eclipse plug-in lives in `plugins/ai-capella`, with `src/org/polarsys/capella/ai/**`, `plugin.xml`, and `META-INF/MANIFEST.MF`. Bundle and feature build descriptors must stay in sync whenever you add views, commands, or services. The Tycho aggregator resides in `plugins/pom.xml`; feature packaging sits in `plugins/ai-capella-feature`, and the p2 site is emitted by `plugins/ai-capella-repository`. Target platform metadata is in `releng/capella-target`; refresh it before relying on newer Capella drops. Design notes and rollout plans live in `docs/` and `PLAN_NEXT.md`—skim them before large UX or tooling changes.

## Build, Test, and Development Commands
- `export JAVA_TOOL_OPTIONS="-Djdk.xml.maxGeneralEntitySizeLimit=0 -Djdk.xml.totalEntitySizeLimit=0 -Djdk.xml.entityExpansionLimit=0"` — raise XML entity limits required by the Capella repositories (run once per shell).
- `mvn -f plugins clean verify` — full Tycho build, producing the update site at `plugins/ai-capella-repository/target/repository`.
- `mvn -pl plugins/ai-capella -am clean verify` — rebuilds the plug-in while pulling dependent modules.
- `mvn -f plugins clean -DskipTests` — fast iteration when binaries are needed but tests already ran elsewhere.

## Coding Style & Naming Conventions
Use the Eclipse formatter with 4-space indentation and UTF-8 sources. Keep packages under `org.polarsys.capella.ai.*`; classes stay in UpperCamelCase, fields and methods in lowerCamelCase with verbs (`loadData`, `sessionBridge`). Bundle and feature versions follow `0.x.y.qualifier`; bump them alongside extension additions. Update `plugin.xml`, `build.properties`, and `feature.xml` whenever you register new UI surfaces or commands.

## Testing Guidelines
Automated suites are not yet checked in. When adding behaviour, introduce plug-in or fragment tests (e.g., `plugins/ai-capella.tests`) and wire them into the Tycho reactor. Name test classes `*Test` and keep scenarios deterministic—stub Eclipse services via the platform harness. Always run `mvn -f plugins clean verify` locally before sending a change, even if individual modules were rebuilt earlier.

## Commit & Pull Request Guidelines
Follow the existing Conventional Commits style (`feat:`, `docs:`, `chore:`) with imperative subjects under ~65 characters and explanatory bodies when behaviour changes. Pull requests should outline affected modules, reference Capella tickets, list build/test evidence, and include screenshots or GIFs for UI updates. Tag reviewers responsible for the touched area and wait for a green Tycho build before merging.

## Configuration & Security Tips
Store API keys through the assistant’s LLM Connection panel; they persist in the secure preferences store. CLI automation can rely on environment fallbacks such as `CAPELLA_AI_PROVIDER` and `OPENAI_API_KEY`. Keep generated `target/` and `bin/` directories out of commits, and never check sensitive credentials into the repository.
