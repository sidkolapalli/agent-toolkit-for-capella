# Bundled Codex runtime provenance

This directory packages the official OpenAI Codex **0.160.1** Windows x64 MSVC runtime. The release tag is [`rust-v0.160.1`](https://github.com/openai/codex/releases/tag/rust-v0.160.1). Its CLI and companion host executables are extracted from the official release archives and renamed to the local names below, without modifying their executable bytes.

| Packaged executable | Exact bytes | SHA-256 of extracted executable |
| --- | ---: | --- |
| `codex.exe` | 326,875,440 | `9e7c59c05cc1ce5677b1f94e835b2ac038ca3be14504e78d558eacdb0ea3f55d` |
| `codex-code-mode-host.exe` | 74,669,360 | `b905b5169bd758dbe3c31ca22c22ad0c24df0047ba7ae32652c706d3ac3ba78d` |

The plugin loader pins and checks these extracted executable sizes and digests. Archive digests below describe the upstream downloads; they are different from the extracted executable digests.

| Official release archive | Archive bytes | Upstream release SHA-256 |
| --- | ---: | --- |
| [codex-x86_64-pc-windows-msvc.exe.zip](https://github.com/openai/codex/releases/download/rust-v0.160.1/codex-x86_64-pc-windows-msvc.exe.zip) | 162,435,542 | `25d308d675f366ee1e825d96755779029e95ba43eca41eae5d960698eaae9913` |
| [codex-code-mode-host-x86_64-pc-windows-msvc.exe.zip](https://github.com/openai/codex/releases/download/rust-v0.160.1/codex-code-mode-host-x86_64-pc-windows-msvc.exe.zip) | 28,144,970 | `0aed582954478b8058ad80db892abff11a22872ebd91985df73b856509d3bec7` |

Machine-readable metadata is in [provenance.json](provenance.json). The packaged [LICENSE](LICENSE) is the upstream Apache License 2.0 and [NOTICE](NOTICE) preserves the upstream OpenAI and Ratatui notices. They were obtained from the same release tag's [LICENSE](https://raw.githubusercontent.com/openai/codex/rust-v0.160.1/LICENSE) and [NOTICE](https://raw.githubusercontent.com/openai/codex/rust-v0.160.1/NOTICE). Preserve both when redistributing these resources.

On supported Windows x64 installations, the runtime is extracted into versioned plugin state and launched as an owned child process. Explicit executable configuration takes precedence. The plugin checks the CLI version before use. The experimental app-server and native `dynamicTools` integration is described in [OpenAI's app-server documentation](https://learn.chatgpt.com/docs/app-server); bundling this runtime does not imply OpenAI endorsement of the Capella plugin or validate its engineering results.
