# Arcadia and Capella 7.0 coverage matrix

What the Arcadia method and Capella 7.0 offer, what our API can write, what our docs and skills teach, and
where the trusted specifics live. Skills stay short and point at the "best source" columns for detail
(disclosed / external reference, per `writing-for-agents`). Gaps and probe tasks: [GAPS.md](GAPS.md).

## Legend

**Our API** (write path; every element is readable through the generic read tools `model_search`,
`model_inspect`, `model_relationships`):

- **D** typed helper, named: checks the native pattern Capella's own tools create.
- **G** generic only: `api.create("EClass", name, parent)` (`ElementFactory.createUnder`, any concrete EClass
  with a containment) plus `api.set` (enums by name, references by value) or `api.link`. No typed checks;
  correctness is on the agent. Not verified per concept.
- **N** no write path the agent can use safely (needs Java).

**Taught**: `docs:` = the plugin's `capella_docs` pages (`plugins/ai-capella/resources/capella-api/`), `skills:`
= toolkit skills in `skills/`. `—` = never mentioned.

**Bundled help** (Capella 7.0 install, `capella/plugins/`), path = `jar!/path`:

| Key | Jar | Content |
|---|---|---|
| GL | `org.polarsys.capella.glossary.doc_7.0.0.202407091438.jar!/html/Glossary/` | 96 one-concept definitions (1–5 sentences) |
| DG | `org.polarsys.capella.diagrams.doc_7.0.0.202407091438.jar!/html/Diagrams/diagrams.html` | Per-diagram palette: every tool and what it creates |
| TH | `org.polarsys.capella.th.doc_7.0.0.202407091438.jar!/html/` | Thematic guides: state machines, data and interfaces, constraints, actors, FA→interfaces workflow |
| UI | `org.polarsys.capella.ui.doc_7.0.0.202407091438.jar!/html/` | User guide: diagrams, chains and paths, accelerators, wizards, categories, validation |
| TR | `org.polarsys.capella.transitions.doc_7.0.0.202407091438.jar!/html/11. Iterative Transitions/` | Transitions between perspectives |
| RE | `org.polarsys.capella.re.doc_7.0.0.202407091438.jar!/html/` | REC/RPL and libraries |
| PR | `org.polarsys.capella.properties.doc_7.0.0.202407091438.jar!/html/Model Editors/` | Property editor per element (fields) |
| VR | `org.polarsys.capella.validation.doc_7.0.0.202407091438.jar!/html/Validation Rules/` | Validation rules by category |
| RQ | `org.polarsys.capella.vp.requirements.doc_0.14.0.202407170938.jar!/html/` | Requirements viewpoint |

**Public** (official mbse-capella.org only; eclipse.dev/capella redirects there). The Arcadia reference PDFs
carry a Thales "may not be reproduced" notice: link them, never copy text into skills.

| Key | URL |
|---|---|
| ACT §n | https://mbse-capella.org/resources/arcadia-reference/Arcadia%20Reference%20-%20Activities.pdf (section n = when the method expects it) |
| DM §n | https://mbse-capella.org/resources/arcadia-reference/Arcadia%20Reference%20-%20Data%20Model.pdf |
| MM | https://mbse-capella.org/resources/arcadia-reference/Arcadia%20Language%20-%20MetaModel.pdf |
| AUG | https://mbse-capella.org/resources/arcadia-reference/Arcadia%20User%20Guide.pdf |
| NAV | https://mbse-capella.org/resources/arcadia-reference/html/output/ARCADIA/1a79525b3b624a7094acd0b580842ce6.html (navigable reference) |
| SYS | https://mbse-capella.org/arcadia_capella_sysml_tool.html (Arcadia/Capella vs SysML) |

**Alone?** = is the bundled help enough as the sole reference for an agent: **yes** (definition plus usage),
**def** (definition only; method usage needs ACT), **no** (not in the bundled help).

## Concepts

### Operational Analysis (OA)

| # | Concept (metaclass) | What it is | Method step | Our API | Taught | Bundled help | Public | Alone? |
|---|---|---|---|---|---|---|---|---|
| 1 | Operational Entity (`Entity`) | Real-world entity (system, organisation…) interacting with the system or its users | ACT 5.1.1.2.2 | D `api.create("OA","Entity",n)` | docs: metamodel/oa | GL `61. Operational Entity.html`; PR `Entity.html` | DM §4.1.1 | def |
| 2 | Operational Actor (`Entity`, `actor`/`human` flags) | Usually human entity | ACT 5.1.1.2.2 | G `api.set(e,"actor",true)` | docs: metamodel/oa | GL `59. Operational Actor.html`; TH `Enhancements of the implementation of Actors/…html` | DM §4.1.1 | yes |
| 3 | Operational Capability (`OperationalCapability`) | Ability expected of the actors/entities to reach a goal | ACT 5.1.1.1.4, 5.1.1.2.1 | G create; D `api.arcadia.involveComponent` | docs: metamodel/oa | GL `7. Capability.html`; PR `OperationalCapability.html` | DM §4.1.1 | def |
| 4 | Operational Activity (`OperationalActivity`) | Process step performed by an entity | ACT 5.1.1.2.3 | D `api.create("OA","OperationalActivity",n)` | docs: metamodel/oa, api/create | GL `58. Operational Activity.html`; PR `OperationalActivity.html` | DM §4.1.1 | def |
| 5 | Activity allocation (`ActivityAllocation`) | Activity performed by an entity | ACT 5.1.1.2.4 | D `api.allocate(activity, entity)` | docs: api/link, api/create | UI `05. Diagram Management/Allocating Functions on Components.html` | ACT 5.1.1.2.4 | yes |
| 6 | Operational Interaction (`FunctionalExchange` between activities) | Exchange between activities | ACT 5.1.1.2.5 | D `api.link(a,b,"FunctionalExchange")` | docs: api/link | GL `44. Interaction.html` | DM §4.1.1 | def |
| 7 | Communication Mean (`CommunicationMean`) | Medium between entities carrying interactions | ACT 5.1.1.2.5 | D `api.link(e1,e2,"CommunicationMean")` | docs: api/link | GL `14. Commmunication Mean.html` | DM §4.1.1 | def |
| 8 | Operational data (`ExchangeItem`, `Class` in OA data package) | Information carried by interactions | ACT 5.1.1.2.6 | D `api.arcadia.exchangeItem`; G classes | docs: api/arcadia | TH `Interfaces and Data Modeling/Basic Constructs.html` | DM §4.1.1 | yes |
| 9 | Operational Process (`OperationalProcess`) | Ordered activities and interactions, the OA functional chain | ACT 5.1.1.2.7 | **N** (`linearChain` rejects OA) | — | GL `62. Operational Process.html`; UI `05. Diagram Management/5.5. Creation of Functional Chains and Physical Paths.html` | ACT 5.1.1.2.7 | yes |
| 10 | Operational Scenario (`Scenario`, kinds ENTITY/INTERACTION) | Time-ordered interactions illustrating a capability | ACT 5.1.1.2.8 | D `api.arcadia.operationalScenario` (activity interaction only); entity scenario **N** | docs: api/arcadia | GL `79. Scenario.html`; DG "Operational Interaction Scenario", "Activity Scenario" | ACT 5.1.1.2.8 | def |
| 11 | Operational Modes & States | Operational conditions of entities | ACT 5.1.1.2.9 | G (see 41) | docs: skills/state-machine (LA/PA only) | TH `Focus on state machines/Focus on state machines.html` | ACT 5.1.1.2.9 | yes |
| 12 | Operational Role (`Role`, `RoleAllocation`) | Coherent activity set shared by entities | ACT 5.1.1.2.4 | G | docs: api/arcadia (word only) | GL `78. Role.html`; DG "Operational Role Blank" | DM §4.1.1 | def |
| 13 | Concept, Community of Interest, Organisational Unit, Location, Capability Configuration | Extended OA structuring concepts | — (MM only) | G | — | — | MM | no |
| 14 | Operational non-functional constraint (`Constraint`) | Constraint on operational need | ACT 5.1.1.2.10 | D `api.arcadia.constraint` | docs: api/arcadia | GL `20. Constraint.html`; TH `Focus on constraints and opaque expressions/…html` | DM §4.1.4 | yes |

### System Analysis (SA)

| # | Concept (metaclass) | What it is | Method step | Our API | Taught | Bundled help | Public | Alone? |
|---|---|---|---|---|---|---|---|---|
| 15 | System (`SystemComponent`, root) | The system of interest as a black box | ACT 5.1.2.2 | D `api.layer("SA").system` | docs: metamodel/sa | GL `90. System.html`; PR `SystemComponent.html` | DM §4.1.2 | def |
| 16 | System Actor (`SystemComponent` actor) | External entity interacting directly with the system | ACT 5.1.2.2.1 | D `api.create("SA","SystemComponent",n)` + `actor` | docs: metamodel/sa, api/create | GL `1. Actor.html`; TH actors guide | DM §4.1.2 | yes |
| 17 | Mission (`Mission`, `MissionInvolvement`) | Purpose the system is tasked with; exploits capabilities | ACT 5.1.1.1.2 | G | — | GL `53. Mission.html`; DG "Missions Blank", "Contextual Mission"; PR `Mission.html` | DM §4.1.2 | def |
| 18 | Capability exploitation (`CapabilityExploitation`) | Mission uses a capability | ACT 5.1.2.2.2 | G | — | DG "Missions Capabilities Blank" | DM §4.1.2 | def |
| 19 | System Capability (`Capability`) | Service the system provides for operational goals | ACT 5.1.2.2.2 | G create; D `involveFunction`/`involveComponent`/`involveChain`, `realizeCapability` | docs: api/arcadia | GL `7. Capability.html`; PR `Capability.html` | DM §4.1.2 | def |
| 20 | Capability include/extend/generalization | Structuring relations between capabilities | ACT 5.1.2.2.2 | G | — | DG "Capability Realization Blank" (Extends/Includes/Generalization tools) | MM | def |
| 21 | System Function and breakdown (`SystemFunction`, sub-functions) | Behaviour expected of system and actors | ACT 5.1.2.2.3 | D `api.create("SA","SystemFunction",n)` / `createIn` | docs: metamodel/sa | GL `89. System Function.html`, `36. Function.html`; DG "System Function Breakdown" | DM §4.1.2 | def |
| 22 | Function allocation (`ComponentFunctionalAllocation`) | Function performed by system or actor | ACT 5.1.2.2.4 | D `api.allocate(fn, comp)` | docs: api/link; skills: arcadia-method | UI `Allocating Functions on Components.html`, `07…/7.2. Contextual Allocations.html` | ACT 5.1.2.2.4 | yes |
| 23 | OA→SA justification (`OperationalAnalysisRealization`, `FunctionRealization`) | System functions realize activities | ACT 5.1.2.2.5 | D `api.link(..,"FunctionRealization")`, `api.ops.transition(sel,"OA2SA")`, `api.propagate` | docs: api/link, api/ops | TR `11.2. Transitions Between Engineering Phases.html` | ACT 5.1.2.2.5 | yes |
| 24 | Function Port (`FunctionInputPort`/`FunctionOutputPort`) | Expected input / available output of a function | ACT 5.1.2.2.6 | D implicit in `api.link(f1,f2,"FunctionalExchange")`; G explicit | — | GL `35. Function Port.html` | DM §4.1.2 | def |
| 25 | Functional Exchange (`FunctionalExchange`) | Data/material/energy between function ports | ACT 5.1.2.2.6 | D `api.link(..,"FunctionalExchange")` | docs: api/link | GL `38. Functional Exchange.html`; PR `FunctionalExchange.html` | DM §4.1.2 | def |
| 26 | Exchange Category (`ExchangeCategory`) | Groups functional exchanges for display and reasoning | — | G | — | GL `28. Exchange Catergory.html`; UI `07…/7.5. Categories Management.html` | MM | yes |
| 27 | Control functions (`FunctionKind` DUPLICATE/GATHER/SELECT/SPLIT/ROUTE) | Fork/join/decision/merge in a dataflow | ACT 5.1.2.2.6 | G `api.set(fn,"kind","SELECT")` | — | GL `33. Fork Function.html`, `46. Join Function.html`, `21. Decision.html`, `52. Merge Function.html` (empty) | MM | def |
| 28 | Exchange Item (`ExchangeItem`, `exchangeMechanism` FLOW/EVENT/OPERATION/SHARED_DATA) | Coherent data set carried by exchanges | ACT 5.1.2.2.7 | D `api.arcadia.exchangeItem(fe, item)`; G mechanism | docs: api/arcadia, skills/interface; skills: arcadia-elicit(Q) | GL `30. Exchange Item.html`, `27. Event.html`, `32. Flow.html`, `57. Operation.html`, `81. Shared Data.html`; TH `Interfaces and Data Modeling/Basic Constructs.html` | DM §4.1.2 | yes |
| 29 | Exchange Item Element (`ExchangeItemElement`) | Typed member (parameter/type) of an exchange item | ACT 5.1.2.2.7 | G | — | GL `29. Exchange Item Element.html`; TH Basic Constructs | DM §4.1.2 | yes |
| 30 | Data model: Class, Property, Association, Union, Collection, DataPkg | Information structure | ACT 5.1.2.2.8 | G | — | GL `9. Class.html`, `74. Property.html`, `2. Association.html`, `95. Union.html`, `11. Collection.html`; TH Basic/Advanced Constructs | DM §4.1.2 | yes |
| 31 | Simple types: Numeric, Boolean, String, Enumeration, Physical Quantity, Unit | Value domains | ACT 5.1.2.2.8 | G | docs: api/modify (Enumeration word only) | GL `56…`, `5…`, `85…`, `26…`, `71…`, `96…`; TH Basic Constructs | DM §4.1.2 | yes |
| 32 | Values and expressions (literals, references, unary/binary/opaque expressions) | Fixed or derived data values | ACT 5.1.2.2.8 | G | — | GL `48. Literal.html`, `75. Reference.html`, `4…`, `93…`; TH Advanced Constructs, constraints guide | MM | yes |
| 33 | Functional Chain, linear (`FunctionalChain`, involvements) | One path through the dataflow for a context or NFP | ACT 5.1.2.2.9 | D `api.arcadia.linearChain`, `involveChain` | docs: api/arcadia; skills: arcadia-method, capella-diagrams | GL `37. Functional Chain.html`; UI 5.5 | DM §4.1.2 | yes |
| 34 | Chain control nodes and sequence links (`ControlNode`, `SequenceLink`) | Branching, iteration, ordering inside a chain | ACT 5.1.2.2.9 | **N** | — | UI 5.5 "Sequencing and Control concepts" | ACT 5.1.2.2.9 | yes |
| 35 | Composite chain (`FunctionalChainReference`) | Chain reusing another chain | ACT 5.1.2.2.9 | **N** | — | UI 5.5 "Composite Functional Chains" | MM | yes |
| 36 | Functional / Exchange / Interface Scenario (`Scenario` FUNCTIONAL / DATA_FLOW / INTERFACE) | Time-ordered messages illustrating a capability | ACT 5.1.2.2.10 | D `api.arcadia.scenario` (empty only) | docs: api/arcadia, metamodel/sa | GL `79. Scenario.html`; DG "Functional Scenario", "Exchange Scenario", "Interface Scenario"; TH Basic Constructs (IS vs FS vs ES) | ACT 5.1.2.2.10 | yes |
| 37 | Scenario content (`SequenceMessage`, `Execution`, `InstanceRole`) | Messages, lifelines, executions | ACT 5.1.2.2.10 | **N** | — | GL `80. Sequence Message.html`; UI `07…/7.3. Modeling Wizards.html` (sequence message wizard) | ACT 5.1.2.2.10 | yes |
| 38 | Fragments (`CombinedFragment` LOOP/ALT/PAR, `InteractionUse`, `StateFragment`, `ConstraintDuration`) | Control structure and references in scenarios | ACT 5.1.2.2.10 | **N** | — | GL `12. Combined Fragment.html`, `43. Interaction Use.html`; DG scenario palettes | MM | yes |
| 39 | Non-functional constraint (`Constraint`) | Safety/security/performance constraint on elements | ACT 5.1.2.2.12 | D `api.arcadia.constraint` | docs: api/arcadia; skills: arcadia-method | GL `20. Constraint.html`; TH constraints guide | DM §4.1.4 | yes |
| 40 | Requirement (Requirements VP) and links | Textual requirement linked to model elements | ACT 5.1.2.3.1–5.1.2.3.2 | D `api.requirements.create/satisfy/verify/derive` | docs: skills/requirements | RQ `3_userguide/*.html` | ACT 5.1.2.3 | yes |

### Modes and states (all perspectives)

| # | Concept (metaclass) | What it is | Method step | Our API | Taught | Bundled help | Public | Alone? |
|---|---|---|---|---|---|---|---|---|
| 41 | State machine, region, mode, state, final state (`StateMachine`, `Region`, `Mode`, `State`, `FinalState`) | Modes (chosen) vs states (suffered) and their machine | ACT 5.1.2.2.11, 5.2.3.4.5 | G (generic create, documented) | docs: skills/state-machine; skills: arcadia-elicit(Q) | GL `83…`, `54. Mode.html`, `84. State.html`, `76. Region.html`; TH `Focus on state machines/…html`; PR `Mode.html` | ACT 5.1.2.2.11 | yes |
| 42 | Transition (`StateTransition`, kind, triggers, guard, effect; `ChangeEvent`, `TimeEvent`) | Change of mode/state and its cause | ACT 5.1.2.2.11 | G (source/target documented; triggers/guards not) | docs: skills/state-machine (partial) | GL `92. Transition.html`; TH state machines guide | MM | yes |
| 43 | Pseudo-states (initial, choice, fork, join, terminate, history, entry/exit) | Control points of a machine | ACT 5.1.2.2.11 | G | docs: skills/state-machine (initial only) | GL `41…`, `8…`, `34…`, `47…`, `91…`; TH state machines guide | MM | yes |
| 44 | Function availability in modes/states (`availableInStates`) | Functions active only in some modes/states | ACT 5.1.2.2.11 | G `api.set(fn,"availableInStates",[…])` | — | GL `83. State Machine.html` (entering a mode enables functions); TH state machines guide | ACT 5.1.2.2.11 | yes |

### Logical Architecture (LA)

| # | Concept (metaclass) | What it is | Method step | Our API | Taught | Bundled help | Public | Alone? |
|---|---|---|---|---|---|---|---|---|
| 45 | Logical Component and breakdown (`LogicalComponent`, `Part`) | Technology-neutral white-box parts | ACT 5.2.2.3.1 | D `api.create("LA","LogicalComponent",n)`, `api.ensurePart` | docs: metamodel/la; skills: arcadia-method | GL `50. Logical Component.html`, `65. Part.html`; DG "Logical Component Breakdown"; UI 7.3 (LC breakdown wizard) | DM §4.2.1 | yes |
| 46 | Logical Function (`LogicalFunction`) | Function refining system functions | ACT 5.2.2.2.1 | D create | docs: metamodel/la | GL `51. Logical Function.html` | DM §4.2.1 | def |
| 47 | SA→LA justification (`SystemAnalysisRealization`, function/component/exchange realizations) | Traceability to the system need | ACT 5.2.2.2.2 | D `api.link(..,"FunctionRealization")`, `api.ops.transition(sel,"SA2LA")`, `api.arcadia.realizeInterface` | docs: api/link, api/ops, metamodel/la | TR 11.2 | ACT 5.2.2.2.2 | yes |
| 48 | Capability Realization (`CapabilityRealization`) | Capability in LA/PA | ACT 5.2.2.3.4 | G create; D `realizeCapability`, `involve*` | docs: api/arcadia | GL `6. Capability Realization.html`; DG "Capability Realization Blank" | DM §4.2.1 | def |
| 49 | Component Port (`ComponentPort`, kind FLOW/STANDARD, orientation IN/OUT/INOUT) | What a component needs and delivers | ACT 5.2.2.3.3 | D implicit in `api.link(..,"ComponentExchange")`; G kind/orientation | — | GL `17. Component Port.html`, `39…`, `40…`, `63…`, `82…` | DM §4.2.1 | def |
| 50 | Component Exchange (`ComponentExchange`) | Connection between components carrying functional exchanges | ACT 5.2.2.3.3 | D `api.link(..,"ComponentExchange")` | docs: api/link; skills: arcadia-method | GL `16. Component Exchange.html` | DM §4.2.1 | def |
| 51 | Functional exchange on component exchange (`ComponentExchangeFunctionalExchangeAllocation`) | Which flows an interface carries | ACT 5.2.2.3.3 | D `api.arcadia.allocateExchange` | docs: api/arcadia | UI `07…/7.1. Modeling accelerators.html` | ACT 5.2.2.3.3 | yes |
| 52 | Port allocation (`PortAllocation` function port → component port) | Function ports realized by component ports | ACT 5.2.2.3.3 | G (made inside `allocateExchange`) | docs: api/arcadia | UI 7.1, 7.3 (port and EI allocation wizard) | MM | yes |
| 53 | Component exchange category (`ComponentExchangeCategory`) | Groups component exchanges | — | G | — | UI 7.5 | MM | yes |
| 54 | Interface, provided/required (`Interface`, `InterfaceImplementation`, `InterfaceUse`, `ExchangeItemAllocation`) | Contract grouping exchange items, provided or required by components/ports | ACT 5.2.2.3.3 | D `api.arcadia.allocateExchangeItem`; G provide/require | docs: skills/interface, api/arcadia | GL `45. Interface.html`; TH Basic Constructs, `Functional Analysis to Interface Engineering/…html`; UI 7.1 (interface generation) | DM §4.4.13 | yes |
| 55 | Communication Link (`CommunicationLink`, send/receive, call/execute…) | Interaction-driven link component→exchange item | ACT 5.2.2.3.3 | G | — | GL `13. Commmunication Link.html`; TH Basic Constructs | MM | yes |
| 56 | Generalization (`Generalization`) | Specialisation between components, capabilities, classes | — | G | — | DG CRB "Generalization"; TH Basic Constructs | MM | def |
| 57 | Scenario allocation to components (`ScenarioRealization`) | Scenario refined in the solution | ACT 5.2.2.3.4 | **N** | — | TR 11.2 (scenarios via transitions) | ACT 5.2.2.3.4 | def |
| 58 | Architecture drivers, viewpoints, candidate architectures, trade-off | Method activities without a native metaclass | ACT 5.2.2.1, 5.2.2.4, 5.2.4 | n/a (method; property values or separate models) | skills: arcadia-elicit (trade) | — | ACT 5.2.2.1–5.2.2.4; NAV | no |

### Physical Architecture (PA)

| # | Concept (metaclass) | What it is | Method step | Our API | Taught | Bundled help | Public | Alone? |
|---|---|---|---|---|---|---|---|---|
| 59 | Behaviour Physical Component (`PhysicalComponent` nature BEHAVIOR, kind SOFTWARE/HARDWARE…) | Implements functions | ACT 5.2.3.4.1 | D `api.create("PA","PhysicalComponent",n)` + `api.set(c,"nature","BEHAVIOR")` | docs: metamodel/pa, api/create | GL `3. Behavioral Physical Component.html`; PR `PhysicalComponent.html` | DM §4.2.2 | def |
| 60 | Node (implementation) Physical Component (nature NODE) | Resource hosting behaviour components | ACT 5.2.3.4.7 | D as above with NODE | docs: metamodel/pa; skills: arcadia-method | GL `55. Node Physical Component.html` | DM §4.2.2 | def |
| 61 | Deployment (`PartDeploymentLink`) | Behaviour implemented on node | ACT 5.2.3.4.8 | D `api.arcadia.deploy(behPart, nodePart)` | docs: api/arcadia; skills: arcadia-method | DG "Physical Architecture Blank" (deploy tools) | ACT 5.2.3.4.8 | def |
| 62 | Physical Function (`PhysicalFunction`) | Function refining logical functions | ACT 5.2.3.3.1 | D create | docs: metamodel/pa | GL `67. Physical Function.html` (page body is Physical Port — mislabelled) | DM §4.2.2 | def |
| 63 | Physical Port and Physical Link (`PhysicalPort`, `PhysicalLink`) | Material connection between nodes | ACT 5.2.3.4.9 | D `api.arcadia.physicalLink` | docs: api/arcadia, GAPS; skills: arcadia-method | GL `68. Physical Link.html`, `70. Physical Port.html` (body is Physical Function — mislabelled); PR `PhysicalLink.html`, `PhysicalPort.html` | DM §4.2.2 | def |
| 64 | Component exchange on physical link (`ComponentExchangeAllocation`) | Which interfaces a cable/bus carries | ACT 5.2.3.4.10 | G (unguarded) | — | UI 7.1 "Physical Links and Component Exchanges" | ACT 5.2.3.4.10 | yes |
| 65 | Physical Path (`PhysicalPath`, involvements) | Ordered physical links conveying a component exchange | ACT 5.2.3.4.10 | **N** | — | GL `69. Physical Path.html`; UI 5.5 "Physical Paths"; DG "Physical Path Description"; PR `PhysicalPath.html` | MM | yes |
| 66 | Physical link category (`PhysicalLinkCategory`) | Groups physical links | — | G | — | UI 7.5 | MM | yes |
| 67 | LA→PA justification (`LogicalArchitectureRealization`, `ComponentRealization`, function/port realizations) | Traceability to the logical solution | ACT 5.2.3.4.6 | D `api.link(..,"ComponentRealization")`, `realizeInterface`, `realizePort`; transition **N** | docs: api/link | TR 11.2 "LC / LA to PC / PA Transition" | ACT 5.2.3.4.6 | yes |
| 68 | Reuse: REC/RPL, libraries | Replicable fragments and shared libraries | ACT 5.2.3.2 | **N** (OpsAPI header names REC/RPL; only `transition` exists) | — | RE `08. Replicable Element (REC-RPL)/*.html`, `09. Libraries/*.html` | ACT 5.2.3.2 | yes |
| 69 | Component modes & states | Behaviour modes of physical components | ACT 5.2.3.4.5 | G (see 41) | docs: skills/state-machine | TH state machines guide | ACT 5.2.3.4.5 | yes |

### EPBS and transverse

| # | Concept (metaclass) | What it is | Method step | Our API | Taught | Bundled help | Public | Alone? |
|---|---|---|---|---|---|---|---|---|
| 70 | Configuration Item (`ConfigurationItem`, kind COTS/CS/HW/Interface/NDI/PrimeItem/System) | Designed/purchased part of the product breakdown | ACT 5.3.1.2.2 | D `api.create("EPBS","ConfigurationItem",n)`; G kind | docs: metamodel/epbs; skills: arcadia-method | GL `19. Configuration Item.html`, `22. End Product Breakdown Structure.html`; DG "Configuration Items Breakdown"; PR `ConfigurationItem.html` | DM §4.2.3 | def |
| 71 | CI realizes physical artefact (`PhysicalArtifactRealization`) | Traceability PA→EPBS | ACT 5.3.1.2.2 | D `api.link(..,"PhysicalArtifactRealization")`; PA→EPBS transition **N** | docs: api/link, metamodel/epbs | TR 11.2 | ACT 5.3.1.2 | def |
| 72 | Iterative transitions (data, exchange items, state machines, interfaces, activities, entities, capabilities, functions, actors, LA→PA) | Capella's copy-with-trace between perspectives | ACT 5.1.2.2.5, 5.2.2.2.2, 5.2.3.4.6 | D only OA2SA and SA2LA (`api.ops.transition`); rest **N** | docs: api/ops | TR `11.1. Concepts.html`, `11.2…html`, `11.3. Examples.html` | ACT (traceability steps) | yes |
| 73 | Property Value, Group, Enumeration property type (`StringPropertyValue`…, `PropertyValueGroup`) | Free annotations for viewpoints (safety, performance, IVVQ data) | ACT 5.2.4.1 (specialty analyses) | G (PVMT add-on not installed) | — | GL `73. Property Value.html`, `72. Property Value Group.html`, `25. Enumeration Property Type.html` | DM §4.2.4 | def |
| 74 | Generic trace / justification link (`GenericTrace`, `JustificationLink`) | Free traceability link | ACT traceability steps | D `api.link(..,"reference")` | docs: api/link | UI 7.3 "Generic Traceability Wizard" | MM | yes |
| 75 | Validation rules | Native completeness, consistency, transition-justification rules | ACT 5.1.1.2.11, 5.1.2.2.13 | D `api.validateNative`, read tool `native_validation`, `model_validate` | docs: api/validate | VR (24 rule pages); UI `12…/12.1. Model Validation.html` | — | yes |
| 76 | IVVQ strategy, integration contracts | Method activities, no native metaclass | ACT 5.3.1 | n/a | — | — | ACT 5.3.1; DM §4.5 | no |
| 77 | Product line variability | Add-on territory | ACT 5.4 | n/a | — | — | ACT 5.4; DM §4.7 | no |

## Diagram types

Create by abbreviation, or by the full description name where Capella offers it (`DiagramAPI.create` accepts any
available description name; target rules in `api/diagram.md`, not verified per type). Bundled help for all: DG
(the palette of each diagram) and UI `05. Diagram Management/5.1. Diagram concepts.html`.

| Perspective | Diagram (Capella 7 name) | Our API | Note |
|---|---|---|---|
| OA | Operational Capabilities Blank (OCB) | D `OCB` | |
| OA | Contextual Operational Capability (COC) | G full name | |
| OA | Operational Entity Blank (OAB) | D `OAB`/`OES` | |
| OA | Operational Entity Breakdown (OEBD) | G full name | |
| OA | Operational Activity Breakdown (OABD) | G full name | targets an activity (`api/diagram.md`) |
| OA | Operational Activity Interaction Blank (OAIB) | D `OAIB` | |
| OA | Operational Process Description (OPD) | **N** | needs an operational process (row 9) |
| OA | Operational Role Blank (ORB) | G full name | |
| OA | Operational Interaction Scenario / Activity Scenario (OES/OAS) | D on `operationalScenario` (activity); entity **N** | |
| SA | Missions Blank (MB), Missions Capabilities Blank (MCB), Contextual Mission (CM) | G full name | missions untaught (row 17) |
| SA | Contextual Capability (CC), Contextual System Actors (CSA) | G full name | |
| SA | System Architecture Blank (SAB) | D `SAB` | |
| SA | System Data Flow Blank (SDFB) | D `SDFB` | |
| SA | System Function Breakdown (SFBD) | D `SFB` | |
| SA–PA | Functional Chain Description (FCD) | G full name | linear chains only (row 33) |
| SA–PA | Exchange / Functional / Interface Scenario (ES/FS/IS) | D `ES`/`FS`/`IS` on an empty scenario | messages **N** (row 37) |
| SA–PA | Mode State Machine (MSM) | G full name | |
| any | Class Diagram Blank (CDB) | D `CDB` | data model untaught (row 30) |
| any | Interfaces Diagram Blank (IDB), Interface Diagram | G full name | |
| LA/PA | Contextual Component External / Detailed / Internal Interfaces (CEI/CDI/CII) | G full name | |
| LA/PA | Capability Realization Blank (CRB), Contextual Capability Realization Involvement (CRI) | G full name | |
| LA | Logical Architecture Blank (LAB) | D `LAB` | |
| LA | Logical Data Flow Blank (LDFB) | D `LDFB` | |
| LA | Logical Function Breakdown (LFBD) | D `LFB` | |
| LA | Logical Component Breakdown (LCBD) | G full name | |
| PA | Physical Architecture Blank (PAB) | D `PAB` | |
| PA | Physical Data Flow Blank (PDFB) | D `PDFB` | |
| PA | Physical Function Breakdown (PFBD) | D `PFB` | |
| PA | Physical Component Breakdown (PCBD) | G full name | |
| PA | Physical Path Description (PPD) | **N** | needs a physical path (row 65) |
| EPBS | EPBS Architecture Blank (EAB), Configuration Items Breakdown (CIBD) | G full name | |
| any | Package Dependencies (PD) | G full name | |
| — | Capability Realization Refinement, Modes & States | — | deprecated in Capella 7 (DG) |

## Counts

| | D | G | N | n/a (method only) |
|---|---|---|---|---|
| Concepts (77 rows; a row counts once, by its weakest write) | 28 | 34 | 12 | 3 |
| Diagram types (33 rows; 1 deprecated row not counted) | 15 | 15 | 2 | — |

Taught in `capella_docs`: 48 concept rows; in toolkit skills: 12; in neither: 28.

## Sources and packaging of the bundled help

- 19 doc jars in `capella/plugins/` (`org.polarsys.capella.*doc_7.0.0.202407091438.jar` plus
  `org.polarsys.capella.vp.requirements.doc_0.14.0.202407170938.jar`), 53 MB together, mostly images.
- 321 HTML pages (`html/**.html`, generated from the `.mediawiki` sources shipped beside them). Each jar
  registers `toc.xml` through the `org.eclipse.help.toc` extension in `plugin.xml`; the glossary has one
  `*-toc.xml` per entry.
- Licence: EPL-2.0 (`about.html`, `plugin.xml`), so serving or quoting it is allowed with attribution. The
  same sources are public at https://github.com/eclipse-capella/capella/tree/v7.0.0/doc/plugins.
- Biggest method-relevant pages: TH `Interfaces and Data Modeling/Basic Constructs.html` (≈6,400 words),
  `Advanced Constructs.html` (≈2,100), TH state machines (≈1,650), TH FA→interfaces (≈1,650), TR 11.2
  (≈1,600), UI 5.5 chains and paths (≈1,150), UI 7.3 wizards (≈2,000). Glossary pages are 1–5 sentences.
- Known defects: GL `67. Physical Function.html` holds the Physical Port text and `70. Physical Port.html` the
  Physical Function text; `20. Constraint.html`, `37. Functional Chain.html`, `62. Operational Process.html` and
  `63. Out Flow Port.html` carry the wrong heading (the definitions are right);
  `52. Merge Function.html` says "In construction"; UI `5.4. Typologie of Capella Diagrams.html` is an image.
- Not bundled: an Arcadia method guide. The bundled help is Capella-tool documentation plus the glossary;
  "when the method expects it" comes only from the public Arcadia reference (ACT/DM/MM/AUG).
- Also used: Capella 7.0 metamodel (`org.polarsys.capella.core.data.gen_7.0.0.202407091438.jar!/model/*.ecore`)
  for metaclasses and enums; our source (`ElementFactory`, `CapellaAPI`, `RelationFactory`, `Arcadia*API`,
  `OpsAPI`, `DiagramAPI`, `RequirementsAPI`) for the API column.
