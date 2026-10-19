---
name: ARCADIA Workflow
summary: Build and inspect native behavior, responsibility, realization and traceability across OA, SA, LA, PA and EPBS.
keywords: [arcadia, workflow, oa, sa, la, pa, epbs, capability, scenario, functional chain, deployment]
layer: [OA, SA, LA, PA, EPBS]
when_to_use: User wants an end-to-end engineering model or a coherent multi-layer change.
difficulty: advanced
---

# Arcadia engineering workflow

Start from the user's operational need, source obligations and explicit assumptions. Preserve the initialized model roots. Inspect native IDs, existing responsibilities, installed descriptors and `api/arcadia.md` before proposing a bounded coherent phase. A set of named objects across five layers is not a complete Arcadia model.

## Operational analysis

Describe the people/organizations/entities, capabilities, observable activities and operational interactions. Allocate leaf activities to their actual entities with `api.allocate`. Use `api.arcadia.involveFunction` and `involveComponent` for native capability involvement. Implementation decisions such as software, ECU hardware or protocols belong to later perspectives.

Create actual operational scenarios beneath their capability using `api.arcadia.operationalScenario`. The native OA INTERACTION scenario has activity InstanceRoles, ordered messages invoking actual FunctionalExchanges, sending/receiving events and message ends. A description-only Scenario does not establish a scenario. Model nominal, degraded and inhibited cases as the need requires, with meaningful preconditions, postconditions and verification obligations.

## System analysis

Reuse the unique initialized system as a black box and identify external actors. Allocate internal leaf SystemFunctions to that system and external functions to the actors. Do not create a second system or decompose SA into implementation components.

Translate operational needs into system capabilities, functions and exchanges. Create typed adjacent function/component realizations with lower-level source first; realize capabilities through `api.arcadia.realizeCapability`. Inspect their actual endpoints and meaning.

Store rules and verification obligations as native Constraints with a nonempty Text OpaqueExpression and actual constrained-element bindings. Use `api.arcadia.constraint(owner, name, expression, subjects)` from `api/arcadia.md`. A description alone does not establish a native specification. This stores a modeled obligation; it does not execute a control rule or complete physical verification.

```groovy
// Variables here are previously inspected, resolved native model objects.
def chain = api.arcadia.linearChain(api.container("SA", "root-function"),
    "Nominal request", [request, acquire, authorize, command, report, observe],
    [requestFlow, acquiredDemand, permission, commandResult, status])
api.arcadia.involveChain(systemCapability, chain)
```

The chain must contain actual FunctionalChainInvolvementFunction/Link objects involving the corresponding functions/exchanges, with correct source/target involvements. A row of FunctionalExchanges alone is not a chain. The helper builds linear chains; branching, nesting and richer scenario fragments require native metamodel services and separate verification.

## Logical architecture

Decompose responsibilities into logical components without selecting physical equipment. Allocate leaf LogicalFunctions, realize their SA counterparts and preserve the external actor boundary. A realization is an engineering claim: provide a rationale when one lower responsibility realizes several upper responsibilities.

Create component interfaces for cross-component functional flows and call `api.arcadia.allocateExchange(componentExchange, functionalExchange)`. Inspect both the ComponentExchangeFunctionalExchangeAllocation and matching component-port/function-port PortAllocations. Internal flows between functions allocated to the same component do not need an invented component exchange.

## Physical architecture

Separate BEHAVIOR components carrying functions from NODE components carrying equipment. Allocate leaf PhysicalFunctions to their behavior components and realize the corresponding logical responsibilities and interfaces. Keep unselected equipment, protocol, energy, timing and rating decisions explicitly unresolved.

```groovy
api.arcadia.deploy(behaviorPart, equipmentPart)
api.arcadia.allocateExchange(controlInterface, commandFlow)
api.arcadia.physicalLink("Control transport TBD", controllerNodePart, driveNodePart)
```

Deployment is a native PartDeploymentLink owned by its NODE location Part. Physical topology is a PhysicalLink with actual Part/PhysicalPort ends. Component containment and ComponentExchanges do not establish those structures. Distinguish control/data transport from traction energy. Where a PA interface realizes LA, inspect native FunctionalExchangeRealization, PortRealization and ComponentExchangeRealization endpoints.

## End-product breakdown and source traceability

Extend the initialized EPBS product ConfigurationItem into meaningful configuration items. Set stable item identifiers and link CI→PA artifacts through `PhysicalArtifactRealization`. EPBS contains neither functions nor exchanges. Select the actual ConfigurationItem rather than a same-named representing Part.

When Requirements Viewpoint is installed and active, create native source requirements with distinct provenance and stable ReqIF identifiers. Use `api.requirements.satisfy`, `verify` and `derive` with their documented endpoint directions. A verification Constraint describes the required procedure/acceptance criteria; it does not record a physical test as passed. Keep imported source, candidate interpretation, applicability, model allocation and physical verification as distinct states. See `skills/requirements/SKILL.md`.

## Review, persistence and completion

1. Propose a bounded coherent phase with exact source and checks; wait for its actual execution outcome before updating the plan. Use native persistent IDs and `api.findById`; never rewrite an attached object's ID directly.
2. Inspect ownership and actual endpoints of every created allocation, involvement, realization, scenario, chain, transport and deployment. Confirm allocations target leaves and no raw-demand bypass contradicts a modeled authorization guard.
   For structural postconditions use stored references. `SequenceMessage.invokedOperation` is derived; inspect stored sending/receiving ends, their events, and `EventSentOperation.operation`/`EventReceiptOperation.operation` instead. The host rejects unsupported named-classifier reference contracts before review.
3. Run full native validation once after a coherent edit. Continue `native_validation` cursors to review all captured diagnostics, checking `source_truncated` and `message_truncated`. Zero errors does not establish completeness or compliance.
4. Discover actual installed representation descriptions, create useful views, add the intended content and inspect persisted layout. Do not guess unsupported diagram names.
5. Save, reload and recheck IDs/references and native diagnostics. Exercise Undo/Redo of one phase and confirm coherent relationships/views. Do not replay a committed phase to recover a save failure.
6. Review engineering meaning: operational need, system boundary, nominal/degraded behavior, responsibility, interface semantics, cross-perspective traceability and every source obligation. Report unresolved choices and coverage gaps accurately. Native structural checks cannot certify a design, a vehicle or rule compliance.
