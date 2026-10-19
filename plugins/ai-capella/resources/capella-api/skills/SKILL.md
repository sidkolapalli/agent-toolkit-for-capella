# Reusable Skills

Pre-built patterns for common Capella modeling tasks.

## Available Skills

| Skill | Description |
|-------|-------------|
| `state-machine/` | Create state machines with modes and transitions |
| `interface/` | Define interfaces between components |
| `arcadia-workflow/` | OA→SA→LA→PA→EPBS creation and traceability |

## How to Use

```groovy
// Read a skill
def skill = api.fs.read("skills/state-machine/SKILL.md")

// The skill contains copy-pasteable code
// Adapt the code to your specific needs
```

## Metadata (Fast Discovery)

Each skill includes YAML frontmatter (name, summary, keywords, etc.).
Use `api.fs.listSkillMetadata()` to view metadata without loading full files.

## Skill Structure

Each skill contains:
1. Description of what it does
2. Complete, working code example
3. Customization guidance

## Quick Links

- `api.fs.read("skills/state-machine/SKILL.md")`
- `api.fs.read("skills/interface/SKILL.md")`
- `api.fs.read("skills/arcadia-workflow/SKILL.md")`
