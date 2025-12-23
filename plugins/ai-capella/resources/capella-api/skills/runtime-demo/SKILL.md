---
name: runtime-demo
description: Built-in demo skill for api.skills.run (echo args + logs)
version: 1.0.0
required_inputs: message
---

# runtime-demo

This is a minimal built-in skill used to validate the skills runtime pipeline.

## Inputs
- `message` (required): string to echo

## Usage

```groovy
// Run a built-in skill with args (args is a Map)
def res = api.skills.run("runtime-demo", [message: "hello"])
log.info("ok=" + res.success())
log.info("value=" + res.value())
log.info("logs=" + res.logs())
```

