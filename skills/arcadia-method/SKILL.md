---
name: arcadia-method
description: Arcadia method for Capella models. Use when planning, building or reviewing a model, choosing the perspective (OA, SA, LA, PA, EPBS) a change belongs in, or judging whether a model is done.
---

# Arcadia method

Work perspective by perspective, iterating: keep the operational need apart from the solution, and
trace each perspective to the one above it.

| Perspective | Question | Holds |
|---|---|---|
| OA, Operational Analysis | What do users need to do? | Entities, actors, capabilities, activities, interactions, scenarios. No implementation choices. |
| SA, System Analysis | What must the system do? | The one system as a black box, external actors, system functions, exchanges, capabilities. |
| LA, Logical Architecture | How is it organised, before technology? | Logical components, functions, component exchanges, functional chains. |
| PA, Physical Architecture | How is it built? | BEHAVIOR components carrying functions, NODE components carrying equipment, deployments, physical links. |
| EPBS | What gets delivered? | Configuration items realizing PA artifacts. |

## Native

The model holds only what is **native**: an element the reads and the validator can see. A
functional chain has involvements for each function and exchange; a rule is a Constraint bound to
its subjects; a scenario has instance roles and ordered messages; a deployment is a
PartDeploymentLink. A description or a name records intent, never structure.

## Steps

1. **Place the change.** Name the perspective from the table. Done when every element you will
   create has a perspective and an owner.
2. **Read the rules for that perspective.** Read its section of `capella_docs`
   `skills/arcadia-workflow/SKILL.md` and `metamodel/<oa|sa|la|pa|epbs>/SKILL.md`. Done when you can
   name the allocation, realization and exchange rules your change touches.
3. **Inspect before creating.** Done when every element you reuse has its native ID from a read, and
   every element you create was searched for first.
4. **Build.** Allocate to leaf functions only. Name functions as verb phrases ("Acquire wheel speed")
   and components and actors as nouns. Leave unselected engineering decisions (equipment, protocol,
   rating, safety limits) as explicit TBDs.
5. **Review.** Done when native validation ran once after the coherent change, every finding is
   read, and every allocation, realization, involvement and deployment has the intended endpoints.
   Zero validation errors means structurally valid, not complete: report open TBDs and unchecked
   coverage alongside.
