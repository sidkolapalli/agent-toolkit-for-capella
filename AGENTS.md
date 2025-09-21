# Repository Guidelines

## Project Structure & Module Organization
The Capella AI Assistant plugin lives under `plugins/`. The `ai-capella` module hosts the Eclipse plug-in code (`src/org/polarsys/capella/ai/...`), `plugin.xml`, and `META-INF/MANIFEST.MF`. The `ai-capella-feature` module defines the installable feature, while `ai-capella-repository` publishes the p2 update site consumed by Capella. Tycho is configured in `plugins/pom.xml`; each module writes build artifacts under its own `target/` directory. Keep generated repositories and OSGi metadata out of commits.

## Build, Test, and Development Commands
- `mvn -f plugins clean verify` (Java 17) — compiles all plug-ins, packages the feature, and creates the update site at `plugins/ai-capella-repository/target/repository`.
- `mvn -pl plugins/ai-capella -am clean verify` — rebuilds only the core plug-in when iterating on Java code but still resolves dependencies.
- `mvn -f plugins clean -DskipTests` — quick rebuild when you only need refreshed binaries. Prefer the full verify goal before pushing.
Run builds from a shell with a proper Eclipse Tycho-compatible Maven installation.

## Coding Style & Naming Conventions
Use the Eclipse formatter with 4-space indentation and UTF-8 encoding (see `pom.xml` `project.build.sourceEncoding`). Keep package names under `org.polarsys.capella.ai.*` and class names in UpperCamelCase (`ChatView`, `ModelOps`). Event handler methods should describe the action (`onListDiagrams`, `onCreateLab`). Update `plugin.xml` and `feature.xml` whenever new views, commands, or categories are introduced, and bump module versions in `MANIFEST.MF` and feature descriptors.

## Testing Guidelines
No automated suites ship yet; when adding behaviour, contribute Tycho-friendly tests. Place unit tests in a new test fragment or plug-in (e.g., `plugins/ai-capella.tests`) and wire it into the aggregator. UI flows should be covered with SWTBot or integration tests where feasible. Always run `mvn -f plugins clean verify` before opening a PR to exercise compile-time checks.

## Commit & Pull Request Guidelines
Recent history uses short, imperative subjects (`Prune repository...`, `Add plugin-only README`). Follow that pattern, limit subjects to ~60 characters, and include context in the body when needed. Every PR should: 1) describe the change and affected modules, 2) link the Capella issue or discussion, 3) note build/test results, and 4) attach screenshots or GIFs for UI tweaks. Request reviews from maintainers responsible for the touched module and wait for a green Tycho build before merging.
