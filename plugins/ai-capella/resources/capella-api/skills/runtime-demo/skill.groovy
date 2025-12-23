// Built-in demo skill.
//
// Available bindings:
// - api, session, log (standard code execution bindings)
// - args (Map<String, Object>) provided by api.skills.run(...)

def message = args != null ? args.get("message") : null
if (message == null || String.valueOf(message).isBlank()) {
    throw new IllegalArgumentException("Missing required arg: message")
}

log.info("runtime-demo: message=" + message)
return "runtime-demo: " + message

