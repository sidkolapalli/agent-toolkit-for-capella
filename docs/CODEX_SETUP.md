# ChatGPT / OpenAI connection inside Capella

Install Capella AI Assistant, open its view, and click **Sign in with ChatGPT** in the assistant header. The default runtime is **Codex app-server**. Windows x64 installations include the official Codex **0.160.1** executable and its companion code-mode host, so the default path needs no separate Codex installation.

If Codex is already connected with ChatGPT or an OpenAI API key, the assistant reuses that connection. Otherwise, it opens the official OpenAI sign-in page in your system browser. Finish the browser flow and return to Capella. The assistant waits for that specific login's completion and then checks the account again before reporting a connection. Closing or cancelling the operation cancels its own pending attempt; it does not sign out an existing account.

Codex manages its authentication and credential store. The plugin's connection result contains only the authentication mode; it does not retain email, OAuth URLs or tokens in conversation history. Account setup starts no conversation and makes no inference request. A successful connection does not by itself establish access to a particular model or remaining usage.

Open a Capella model and describe the work in ordinary language. The assistant can inspect that captured model, show a plan, and propose changes. **Review changes** opens the summary and declared native checks with **Changes** selected. **Script** is optional and shows the exact immutable Groovy source. **Apply changes** runs that source through Capella; **Cancel** returns without executing it. Revised proposals require a new review. Native execution, saving and independently checked model results are reported separately.

Before review, the assistant checks each generated Groovy draft with Capella's compiler without running it. Syntax or supported-policy errors are returned to the agent with a line, column and bounded explanation. It can correct the draft twice within the same task budget. A failed check creates no model change, Undo entry or save; a corrected draft still needs fresh review. **Stop**, model changes and exhausted budgets end the attempt. Compilation success checks the script's language and policy; actual model behavior is verified after applying it.

The integration uses the experimental app-server and native `dynamicTools` protocols, pinned to **0.160.1**. It supports bounded native tools and reviewed scripts; it does not establish complete Arcadia methodology enforcement, complete FSAE coverage or competition compliance. See [OpenAI's app-server documentation](https://learn.chatgpt.com/docs/app-server) for the underlying account and runtime protocol.

## Advanced configuration

Use **Preferences → Capella → LLM** for the runtime, executable and model. An explicit executable or `CAPELLA_AI_CODEX_BINARY` overrides the bundle and must satisfy the pinned version check. With no override, Windows x64 uses the bundled runtime before external discovery. It verifies both executable sizes and SHA-256 digests and extracts them into versioned plugin state. Integrity failures produce an error; select a compatible explicit executable or reinstall the plugin.

Linux and macOS do not currently include a runtime bundle. Use an external Codex **0.160.1** executable or choose **Direct provider**. Direct provider uses its configured HTTP endpoint/model and stores provider API keys in local Eclipse secure storage. It has a separate agent protocol and request budget.

Leave the Codex model empty to use the configured default. Environment overrides include `CAPELLA_AI_RUNTIME=codex|direct`, `CAPELLA_AI_CODEX_BINARY` and `CAPELLA_AI_CODEX_MODEL`. These overrides can change which runtime Settings selects. See [runtime provenance](../plugins/ai-capella/resources/codex/windows-x64/PROVENANCE.md) for official sources, license and exact packaged hashes.

## Verification scope

Deterministic service tests exercise existing account reuse, queued and foreign login notifications, cancellation, timeout, official URL validation and error privacy. Actual account-only probes used the bundled runtime to reuse the existing ChatGPT account and to start/cancel login in a fresh isolated home. The isolated probe did not open a browser or complete login, and it left no credential file. Both probes closed their owned child processes and made no inference request. These checks do not claim native first-time sign-in UI acceptance.
