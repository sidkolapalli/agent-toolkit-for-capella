# Repository Guidelines

## Project Structure & Module Organization
- Python package in `src/capella_mcp/` (server, model manager, tools, Java client). Entry point: `python -m capella_mcp`.
- Java diagram service in `src/java/` (Spring Boot, Maven). Uses libs under `src/java/lib/`.
- Tests in `tests/` (unit, integration, API discovery). Generated artifacts in `diagrams_output/`.
- Scripts in `scripts/` (Windows helpers). Docs in `docs/`. MCP config in `config/.mcp.json`. Sample model in `test_gmhcd_copy/`.

## Build, Test, and Development Commands
- Python path: PowerShell `$env:PYTHONPATH='src'`; Bash `export PYTHONPATH=src`.
- Run server: `python -m capella_mcp` (optional `CAPELLA_MODEL_PATH=path/to/model.aird`).
- Run tests: `pytest -q` (target subsets: `pytest tests/unit`, `pytest tests/integration`).
- Java service (Java 17): `mvn -f src/java spring-boot:run` or `tools/apache-maven-3.9.4/bin/mvn -f src/java spring-boot:run`. Build: `mvn -f src/java clean package`.

## Coding Style & Naming Conventions
- Python: PEP 8, 4-space indentation, type hints. Names: modules/functions `snake_case`, classes `PascalCase`. Use `logging` (no prints); prefer small, focused functions. Keep public APIs in `capella_mcp.server` stable.
- Java: Standard Spring conventions. Package `org.polarsys.capella.mcp`, classes `PascalCase`, methods `camelCase`.

## Testing Guidelines
- Framework: pytest. Name files `test_*.py`, tests `test_*`. Place fast unit tests in `tests/unit/`; heavier or Java/Capella-dependent cases in `tests/integration/`.
- Run locally: ensure Python sees `src/`; start the Java service on `http://localhost:8080` for diagram-related tests. Optional features use `capellambse` if installed.
- Add tests for new behavior; prefer unit coverage and include regressions for fixed bugs.

## Commit & Pull Request Guidelines
- Use Conventional Commits: `feat(server): ...`, `fix(model-manager): ...`, `docs: ...`, `test: ...`, `chore: ...`.
- PRs must include: clear description, linked issue(s), test updates, reproduction/verification steps, and (when relevant) screenshots or files under `diagrams_output/`. All tests must pass (`pytest -q`).

## Security & Configuration Tips
- Do not commit proprietary model files; use the provided samples for tests. Avoid modifying vendored jars under `src/java/lib/`.
- Useful env vars: `CAPELLA_MODEL_PATH` (auto-load model), `CAPELLA_LOG_LEVEL` (logging level). Keep `PYTHONPATH=src` during development.

## Agent-Specific Instructions
- Make minimal, focused diffs; preserve existing public APIs and file layout. Follow styles above, update or add tests alongside code changes, and avoid large refactors unless requested.
