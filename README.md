# Capella AI Assistant (Plugin Only)

This repository contains only the Capella plugin and p2 update site used to install the AI Assistant into Capella.

Build (p2 site)
- Java 17 required
- Run: mvn -f plugins clean verify
- Install from: plugins/ai-capella-repository/target/repository

In Capella
- Help → Install New Software… → Add → Local… → select the repository above
- Open Views: Window → Show View → Other… → Capella AI Assistant / AI Diagrams
- Configure LLM: Preferences → Capella AI → LLM
