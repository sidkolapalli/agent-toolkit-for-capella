# Repository Guidelines

## Project Structure & Module Organization
The core Eclipse plug-in is in `plugins/ai-capella` with sources under `src/org/polarsys/capella/ai/**`, plus `plugin.xml` and `META-INF/MANIFEST.MF`. The Tycho reactor is defined in `plugins/pom.xml`, feature packaging lives in `plugins/ai-capella-feature`, and the p2 update site is emitted to `plugins/ai-capella-repository/target/repository`. Target platform metadata is in `releng/capella-target`. Design notes are in `docs/`, `ARCHITECTURE.md` and `ROADMAP.md`. Sample models and evaluation tasks live in `test-models/`.

## Build, Test, and Development Commands
- `export JAVA_TOOL_OPTIONS="-Djdk.xml.maxGeneralEntitySizeLimit=0 -Djdk.xml.totalEntitySizeLimit=0 -Djdk.xml.entityExpansionLimit=0"` - required once per shell for Eclipse update sites.
- `mvn -f plugins clean verify` - full Tycho build, runs tests, and produces the update site.
- `mvn -pl plugins/ai-capella -am clean verify` - rebuilds the plug-in with dependent modules.
- `mvn -f plugins clean -DskipTests` - faster build when tests are already covered.

## Coding Style & Naming Conventions
Java 17, Eclipse formatter, 4-space indentation, UTF-8. Keep packages under `org.polarsys.capella.ai.*`. Classes use UpperCamelCase; methods/fields use lowerCamelCase with action verbs (e.g., `buildPrompt`, `loadData`). When adding new UI contributions or services, update `plugin.xml`, `build.properties`, and `feature.xml`. Bundle and feature versions follow `0.x.y.qualifier`.

## Testing Guidelines
JUnit tests live in `plugins/ai-capella.tests/src` and should be named `*Test`. Keep tests deterministic; use the Eclipse platform harness for services or UI dependencies. Run `mvn -f plugins clean verify` before opening a PR.

## Commit & Pull Request Guidelines
Commit messages follow Conventional Commits: `feat:`, `fix:`, `docs:`, `test:`, `chore:`, with optional scopes like `fix(diagram)`. Pull requests should summarize affected modules, link issues (e.g., `#39`), list build/test evidence, and include screenshots or GIFs for UI changes.

## Configuration & Security Tips
Store API keys through the LLM preferences UI; they persist in the secure Eclipse preferences store. For automation, environment fallbacks like `CAPELLA_AI_PROVIDER` and `OPENAI_API_KEY` are supported. Do not commit generated `target/` or `bin/` directories, and never commit secrets.
