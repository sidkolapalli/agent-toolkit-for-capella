# api.arcadia: native engineering relationships

Use these typed helpers inside a reviewed model transaction. Inspect the active model and obtain native IDs before proposing changes. All endpoints must be resolved semantic objects in the captured session; detached, foreign-session and wrong-layer endpoints are rejected.

## Capabilities and behavior

```groovy
api.arcadia.involveFunction(capability, leafFunctionOrActivity)
api.arcadia.involveComponent(capability, componentOrEntity)
api.arcadia.involveChain(capability, functionalChain)
api.arcadia.realizeCapability(lowerCapability, upperCapability)

def chain = api.arcadia.linearChain(rootFunction, "Nominal drive request",
    orderedLeafFunctions, orderedFunctionalExchanges)

def scenario = api.arcadia.operationalScenario(operationalCapability,
    "Nominal motion request", orderedOperationalInteractions,
    "Demand is valid and drive permission is present",
    "Requested motion is produced and status reaches the driver")
```

- Capability involvement uses native owned involvement objects. Repeating an identical involvement returns the existing relationship.
- Capability realization is lower-to-upper and adjacent: SA→OA, LA→SA, PA→LA. EPBS configuration items use `PhysicalArtifactRealization` instead.
- `linearChain` is for SA/LA/PA. Pass the native root function, at least two distinct leaf functions, and exactly one fewer exchanges in their actual source→target order. It creates the chain, function involvements and link involvements with actual endpoints. It does not model branching, loops or nested chains. A duplicate chain name is rejected.
- Native `FunctionalChainInvolvementFunction` and `FunctionalChainInvolvementLink` objects have no `name` attribute. Identify them by native UUID or by their stored `involved` reference and exact owning chain. Do not call `api.set(involvement, "name", ...)` or use a type/name postcondition selector for them. The FunctionalChain itself has a name.
- `linearChain` populates `involved` with each actual function or exchange. `realizedFlow` is a separate reference and is not populated by this helper. Check the link's `source` and `target` against the actual function involvements; do not substitute `realizedFlow` for `involved`.
- `operationalScenario` creates a native OA INTERACTION scenario beneath its OperationalCapability. Roles represent actual OperationalActivities; ordered messages invoke the actual FunctionalExchanges using sending/receiving events and ends. It stores pre/postconditions. An OA role here represents an activity, not an Entity Part. A duplicate scenario name is rejected.
- Preconditions, postconditions and descriptions express modeled assumptions. They do not execute a controller or prove safety. Represent nominal and inhibited cases separately with meaningful exchanges and verification obligations.

## Deployment and topology

```groovy
def deployment = api.arcadia.deploy(behaviorPart, nodePart)

def cable = api.arcadia.physicalLink("Control transport TBD", sourceNodePart, targetNodePart)
// Or supply existing native NODE ports explicitly:
def link = api.arcadia.physicalLink("Control transport TBD",
    sourceNodePart, sourcePhysicalPort, targetNodePart, targetPhysicalPort)
```

- Deployment requires a BEHAVIOR PhysicalComponent Part and a NODE location Part in the same PA. The native PartDeploymentLink is owned by the location Part. Identical deployment is idempotent; an existing different host is rejected. Remove an old deployment explicitly before redeploying.
- Physical links require explicit NODE instances. The convenience overload creates dedicated PhysicalPorts; the explicit overload checks port ownership. Links contain two actual PhysicalLinkEnds referencing the intended Parts and ports. A ComponentExchange is a behavioral interface and does not create physical cabling or deployment.
- Do not guess electrical ratings, routing, protocol, power or timing. Keep unresolved choices explicit. A physical link labeled control transport does not represent traction energy.

## Functional transport

```groovy
def allocation = api.arcadia.allocateExchange(componentExchange, functionalExchange)

// An interface carries an exchange item; a functional exchange conveys one
api.arcadia.allocateExchangeItem(anInterface, exchangeItem)
api.arcadia.exchangeItem(functionalExchange, exchangeItem)
```

This creates the native ComponentExchangeFunctionalExchangeAllocation and component-port/function-port PortAllocations. Source/target component responsibilities must match the actual allocated leaf functions. Ports must have compatible directions; the helper supports native direct and instance-based exchange endpoints. Identical valid allocations are returned unchanged. Same-component function flows remain internal; do not invent component interfaces to carry them.

## Interface realization between layers

```groovy
// First realize both endpoint functions/components in the same source-to-target order.
api.link(lowerSourceFunction, upperSourceFunction, "FunctionRealization")
api.link(lowerTargetFunction, upperTargetFunction, "FunctionRealization")
def realization = api.arcadia.realizeInterface(lowerFunctionalExchange, upperFunctionalExchange)
// For two native FLOW component exchanges:
def transportRealization = api.arcadia.realizeInterface(lowerComponentExchange, upperComponentExchange)
```

The helper creates one native FunctionalExchangeRealization or ComponentExchangeRealization and both endpoint PortRealizations atomically. The lower exchange owns the exchange realization; each lower port owns its port realization. SA→OA functional exchange realization connects native OperationalActivities, so it creates no nonexistent OA ports. LA→SA and PA→LA connect actual output/input ports. FLOW component exchanges require matching native component port kind and orientation, with OUT/INOUT source and IN/INOUT target. Direct and instance-based native component endpoints are supported.

Endpoint function/component realizations must already connect the intended lower and upper responsibilities. Same-layer, reversed, skipped-layer, foreign, unresolved, ambiguous, incorrectly owned and duplicate relationships are rejected. Identical correct realizations are reused without saving again. For individual relationships, `api.link(lower, upper, "FunctionalExchangeRealization")`, `"ComponentExchangeRealization"` and `"PortRealization"` use the same checks. STANDARD/delegation/assembly interfaces require their appropriate native construction services.

## Completion checks

Create a scoped native Constraint with an actual expression and subjects:

```groovy
def authorization = api.arcadia.constraint(authorizeFunction,
    "Authorization and inhibited output",
    "Active propulsion requires valid demand AND enable permission; otherwise command is zero and status is inhibited.",
    [authorizeFunction, commandFunction])
```

`constraint(owner, name, expression, subjects)` creates a concrete Capella Constraint in the owner's `ownedConstraints`, its native OpaqueExpression in `ownedSpecification`, and the actual stored `constrainedElements`. It preserves the exact expression text and subject order. The expression uses native `capella:linkedText`; it is authored text, not an executed controller or test. Names are bounded to 240 characters, expressions to 12,000, and subjects to 1–32 distinct resolved native ModelElements in the same semantic Project and captured session as the owner. Cross-layer subjects are allowed within that project.

An identical name/expression/subject list returns the existing correct Constraint without another save or Undo entry. Conflicting, duplicate, malformed, detached, unresolved or foreign objects are rejected. IDs are assigned before containment. The change and expression form one native undoable transaction. A `description` by itself does not populate the native expression or scope. Verification obligations should constrain the actual relevant scenarios, chain, functions, allocations, deployments or trace endpoints; a named empty Constraint does not establish those bindings. Creating or linking a Constraint never marks a requirement verified.

After a coherent phase, inspect the actual involvement, message, chain, deployment and transport endpoints, then run native validation. Save and reload before claiming persistence. Review every warning using `native_validation` cursors. Native consistency establishes neither engineering completeness nor rule compliance; keep source coverage, assumptions and physical verification separate.

Check native feature metadata before proposing generic writes: `element.eClass().getEStructuralFeature("name")` can be absent, including on the chain involvements above. A stored attribute or reference must exist and be changeable, with `derived`, `transient` and `volatile` all false. Follow its declared value type, multiplicity and containment; prefer the typed helpers for relationships and scoped Constraints. Unknown-feature writes fail and roll back the whole reviewed model transaction. A derived getter, such as `SequenceMessage.invokedOperation`, may help inspection but cannot be a stored-reference postcondition; check its actual sending/receiving ends and event operations instead.

Proposal postconditions can check actual structure instead of just object existence. Each declaration belongs to a pending edit `stepId`; at most 12 are allowed. For example:

```json
{"stepId":"interfaces","kind":"native_structure","check":{"kind":"relation_exists","type":"FunctionalExchangeRealization","predicates":{"sourceElement":{"id":"lower-exchange-uuid"},"targetElement":{"id":"upper-exchange-uuid"}},"owner":{"id":"lower-exchange-uuid"}}}
```

Other structural checks are `reference_equals` (`target`, `feature`, `expected` selector or null), `reference_sequence_equals` (`target`, `feature`, `expected` ordered selectors, at most 32), and `contained_by` (`target`, `container`). Selectors use `id` alone or a registered native `type` and exact `name`, optional `layer`, and optional one `container` selector. Relation checks permit 1–4 stored single-reference predicates and an optional exact owner. These checks never execute supplied expressions, read derived references or resolve proxies. Every declared check for a step must pass before the assistant marks that edit complete; ambiguous or incomplete evidence leaves it unverified.
