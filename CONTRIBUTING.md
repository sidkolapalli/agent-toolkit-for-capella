# Contributing

`README.md` describes what the plug-in does; `CLAUDE.md` and `ARCHITECTURE.md` explain how the code fits together and the invariants changes must keep.

## Project structure
The plug-in is `plugins/ai-capella` (sources under `src/org/polarsys/capella/ai/**`, plus `plugin.xml` and `META-INF/MANIFEST.MF`). The Tycho reactor is `plugins/pom.xml`, the feature is `plugins/ai-capella-feature`, and the p2 update site is written to `plugins/ai-capella-repository/target/repository`. The target platform is `releng/capella-target`. Sample models and evaluation tasks live in `test-models/`. Run `git lfs pull` after cloning: the bundled Codex executables are LFS-tracked.

## Build and test
Java 17 and Maven. Export the XML entity limits once per shell (Eclipse update sites need them):

```bash
export JAVA_TOOL_OPTIONS="-Djdk.xml.maxGeneralEntitySizeLimit=0 -Djdk.xml.totalEntitySizeLimit=0 -Djdk.xml.entityExpansionLimit=0"
mvn -f plugins clean verify                 # compile, run all tests, build the update site
mvn -f plugins clean verify -DskipTests     # binaries and update site only
```

Tests live in `plugins/ai-capella.tests/src`, mirror the source package, are named `*Test`, and must be deterministic. They run in a separate OSGi bundle, so anything a test calls must be `public`. They use Capella's real metamodel and a real transactional editing domain behind a proxied `Session`; they do not cover rendering, OSGi wiring or installation, so check those in a running Capella (see `CLAUDE.md`, "Verifying in Capella"). `test-dev-server.sh` is a read-only smoke test against the dev server (needs `curl` and `jq`).

## Code style
Java sources are formatted with [google-java-format](https://github.com/google/google-java-format) 1.22.0 in AOSP style (4-space indent). Format the files you change before committing:

```bash
java --add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED \
     --add-exports=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED \
     --add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED \
     --add-exports=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED \
     --add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED \
     --add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED \
     -jar google-java-format-1.22.0-all-deps.jar --aosp --replace <files>
```

The one-time reformat is listed in `.git-blame-ignore-revs`; run `git config blame.ignoreRevsFile .git-blame-ignore-revs` so `git blame` skips it.

Keep packages under `org.polarsys.capella.ai.*`. When you add views, pages or services, update `plugin.xml`, `build.properties` and `plugins/ai-capella-feature/feature.xml`. Bundle and feature versions follow `0.x.y.qualifier`. When you add or change a script API method, update the matching docs in `plugins/ai-capella/resources/capella-api/`: the agent learns the API from them.

## Commits and pull requests
Commit messages follow Conventional Commits (`feat:`, `fix(diagram):`, `docs:`, `test:`, `refactor:`, `chore:`). Pull requests should name the affected modules, link issues, give build and test evidence, and include screenshots for UI changes.

## Secrets
API keys belong in the LLM preferences page, which stores them in Eclipse secure storage. For automation, environment fallbacks such as `CAPELLA_AI_PROVIDER` and `OPENAI_API_KEY` work. Never commit secrets, `target/` or `bin/`.
