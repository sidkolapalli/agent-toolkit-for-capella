---
name: arcadia-method
description: Arcadia/MBSE method for Capella models - which perspective (OA, SA, LA, PA, EPBS) a change belongs in, how functions, exchanges, allocations, realizations, functional chains, scenarios and interfaces fit together, and what to check before claiming a model is right. Use whenever planning, building, reviewing or explaining a Capella model.
---

# Arcadia method

Develop the perspectives iteratively. Keep the operational need apart from the proposed
solution, and keep traceability between perspectives. Revisit an earlier perspective when a new
constraint emerges.

| Perspective | Question | Holds |
|---|---|---|
| OA, Operational Analysis | What do users need to do? | Entities, actors, capabilities, activities, interactions, scenarios. No implementation choices. |
| SA, System Analysis | What must the system do? | The one system as a black box, external actors, system functions, exchanges, capabilities. |
| LA, Logical Architecture | How is it organised, without choosing technology? | Logical components, functions, component exchanges, functional chains. |
| PA, Physical Architecture | How is it built? | BEHAVIOR components carrying functions, NODE components carrying equipment, deployments, physical links. |
| EPBS | What gets delivered? | Configuration items realizing PA artifacts. No functions or exchanges. |

## Rules per perspective

- **OA:** allocate leaf activities to the entities that actually perform them. A scenario is a
  native scenario under its capability, with instance roles and ordered messages; a description
  is not a scenario. Software, ECUs and protocols belong to later perspectives.
- **SA:** reuse the one initialized system. Never create a second one or decompose it here.
  Allocate internal leaf functions to the system and external functions to actors. Realize
  operational capabilities and functions, lower level as source.
- **LA:** decompose responsibilities into components. Allocate leaf functions, realize their SA
  counterparts, and keep the external actor boundary. A cross-component functional exchange needs
  a component exchange that allocates it, with matching port allocations. Exchanges inside one
  component need no component exchange. One lower element realizing several upper ones needs a
  rationale.
- **PA:** functions go on BEHAVIOR components, equipment on NODE components. Deployment is a
  PartDeploymentLink and topology is a PhysicalLink: containment and component exchanges don't
  establish them. Keep control/data transport apart from energy. Leave unselected equipment,
  protocol, timing and rating decisions explicitly unresolved.
- **EPBS:** extend the product configuration item with stable identifiers, linked to PA through
  PhysicalArtifactRealization.

## Structures that are easy to fake

- A **functional chain** has involvement objects for each function and exchange. A row of exchanges is not a chain.
- A **rule or verification obligation** is a native Constraint with a text expression bound to the
  constrained elements. A description is not a constraint.
- **Requirements** (Requirements Viewpoint): use satisfy/verify/derive traces. Keep the imported
  source, its interpretation, the model allocation and the verification status separate; importing
  a requirement does not establish compliance.

## Before proposing and before claiming done

1. Inspect the actual elements and IDs first. Don't invent objects, names or API methods; check
   whether an element already exists before creating it.
2. Allocate to leaves only. Every allocation, realization, involvement and deployment has the
   intended endpoints and owner.
3. Name elements descriptively. A function is a verb phrase ("Acquire wheel speed"); a component
   or actor is a noun.
4. Run native validation once after a coherent change and read every finding. Zero errors means
   structurally valid, not complete or compliant.
5. Mark unresolved engineering decisions explicitly. Don't guess safety limits, and don't claim
   coverage without a coverage check.

The API calls behind each rule (`api.allocate`, `api.arcadia.linearChain`,
`api.arcadia.allocateExchange`, `api.arcadia.deploy`, `api.arcadia.constraint`, ...) are in
`capella_docs` paths `api/arcadia.md`, `skills/arcadia-workflow/SKILL.md`,
`metamodel/<oa|sa|la|pa|epbs>/SKILL.md` and `skills/requirements/SKILL.md`.
