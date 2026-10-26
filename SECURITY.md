# Security

Report a vulnerability privately through
[GitHub's private reporting](https://github.com/sidkolapalli/capella-agent-toolkit/security/advisories/new), not
in a public issue. Please include the affected build, the steps to reproduce and what an attacker gains.

Areas that matter most:

- The dev server and MCP endpoint: loopback binding, the bearer token, the browser-origin check.
- The review boundary: any path by which a script runs without a person approving that exact source in Capella,
  or runs twice.
- Prompt injection through model content that leads to an unreviewed change.

Only the latest release on `main` receives fixes.
