# Arcadia coverage gaps

Ranked from [MATRIX.md](MATRIX.md). Rank = how central the concept is to the Arcadia activities (Arcadia
Reference – Activities, "ACT") × how often a real model needs it × how far we are from supporting it. Row
numbers refer to the matrix.

## Status (2026-10-21)

| Gap | State | Where |
|---|---|---|
| a1 scenario content | FS and ES lifelines and ordered messages built; fragments (LOOP/ALT/PAR), references and scenario realization still open. Experiment 6: Haiku 0/3 before, 3/3 after | `functionalScenario`, `exchangeScenario`; SCENARIOS.md |
| a2 non-linear chains | branching and joining chains and OA operational processes built; control nodes, sequence links and composite chains still open. Experiment 6: 0/3 before, 1/3 after; both misses left out the same 3 exchanges as the ambiguous categories prompt, so a reworded rerun is owed | `chain`; CHAINS.md |
| a3 physical paths | paths and component exchanges on links or paths built. Experiment 6: 0/3 before, 3/3 after | `physicalPath`, `allocateOnLink`; PHYSICAL.md |
| a4 transitions | every Capella transition kind, LA→PA and PA→EPBS included. Experiment 6: the task passed 3/3 before too (built by hand), so not shown | `api.ops.transition`; TRANSITIONS.md |
| a5 REC/RPL, libraries | open | |
| b1–b6 | taught and measured (experiment 4) | MODES, DATA, INTERFACES, MISSIONS, CONTROL, PROPERTIES |
| b5 categories, b8 roles and communication means, b9 capability relations | taught; experiment 5: roles, communication means and capability relations help (Haiku 1/9 to 8/9), categories untested (every arm passes) | CATEGORIES, OPERATIONAL, CAPABILITIES |
| b7 port kind | dropped: Capella's glossary says FLOW vs STANDARD is graphical only | |
| b10 validation vocabulary | open | |

Also still open in the API: properties on classes (`api.create("Property", …, aClass)` is rejected) and exchange
item element cardinalities (DWF_D_51).

## Top 10 overall

| Rank | Gap | Kind | Rows |
|---|---|---|---|
| 1 | Scenario content: messages, lifelines, executions, fragments, references; scenario allocation to components | a | 36–38, 57 |
| 2 | Functional chains beyond a straight line: control nodes, sequence links, composite chains; OA operational processes | a | 9, 34, 35 |
| 3 | Modes & states in full: function availability per mode/state, transition triggers/guards/events, pseudo-states, OA/SA machines | b | 11, 41–44 |
| 4 | Data model and exchange-item semantics: classes, types, units, values; mechanism FLOW/EVENT/OPERATION/SHARED_DATA; exchange item elements | b | 8, 28–32 |
| 5 | Interfaces provided/required by components and ports; communication links | b | 54, 55 |
| 6 | Physical paths and component-exchange allocation to physical links | a | 64, 65 |
| 7 | Transitions LA→PA, PA→EPBS and the per-kind transitions (data, interfaces, state machines) | a | 67, 71, 72 |
| 8 | Missions, capability exploitation, capability include/extend/generalization | b | 17, 18, 20 |
| 9 | Control functions in dataflows (`FunctionKind` SPLIT/GATHER/SELECT/ROUTE/DUPLICATE) and exchange/component/physical categories | b | 26, 27, 53, 66 |
| 10 | Non-functional properties as property values and groups (safety, performance data per element) | b | 73 |

Method-only activities (architecture drivers, candidate architectures and trade-offs, IVVQ strategy; rows 58,
76) have no native metaclass: they belong in skills (`arcadia-elicit`) as questions and
checklists, pointing at ACT 5.2.2.1–5.2.2.4 and 5.3.1, not in the API.

## (a) The API cannot create it (Java work)

| Rank | Gap | Why it matters (ACT) | What to build | Bundled help to follow |
|---|---|---|---|---|
| a1 | Scenario content (rows 36–38, 57). `api.arcadia.scenario` makes an empty ES/FS/IS; only OA activity scenarios get messages | Scenarios illustrate every capability: ACT 5.1.1.2.8, 5.1.2.2.10, 5.2.2.3.4 | `api.arcadia.scenarioMessages(scenario, orderedExchanges)` for FS/ES/IS with instance roles and executions, mirroring `operationalScenario`; then LOOP/ALT/PAR fragments and references | GL `79. Scenario.html`, `80. Sequence Message.html`, `12. Combined Fragment.html`; UI 7.3 (sequence message wizard); TH Basic Constructs (IS vs FS vs ES) |
| a2 | Non-linear chains (rows 9, 34, 35). `linearChain` rejects OA and has no control nodes | Chains carry latency/criticality and end-to-end behaviour: ACT 5.1.1.2.7, 5.1.2.2.9 | `chain(owner, name, involvements, sequenceLinks, controlNodes)` covering `OperationalProcess`; `FunctionalChainReference` | UI 5.5 "Sequencing and Control concepts", "Composite Functional Chains" |
| a3 | Physical paths and CE→PL allocation (rows 64, 65) | ACT 5.2.3.4.10 "Allocate component exchanges to physical links" | `api.arcadia.allocateOnLink(ce, link)` and `physicalPath(name, orderedLinks, ce)` | UI 7.1 "Physical Links and Component Exchanges"; UI 5.5 "Physical Paths"; GL `69. Physical Path.html` |
| a4 | Transitions LA→PA, PA→EPBS and per-kind transitions (rows 67, 71, 72); `OpsAPI` covers OA2SA and SA2LA only | Traceability steps ACT 5.2.2.2.2, 5.2.3.4.6 | Extend `api.ops.transition` kinds using the same `TransitionCommandHelper` entry points | TR `11.2. Transitions Between Engineering Phases.html` (all entry points) |
| a5 | REC/RPL and libraries (row 68); `OpsAPI` names them but has no method | Reuse of existing assets: ACT 5.2.3.2 | Defer; read-only listing first | RE `08…`, `09…` |

## (b) The API can do it, nothing teaches it (skill or doc content)

Every row here is reachable today through `api.create` + `api.set`/`api.link` (G) or a typed helper, but no
`capella_docs` page or toolkit skill shows it. Content belongs in short skill lines that point at the bundled
page (selective disclosure), plus one verified example in `capella_docs` per concept.

| Rank | Gap | Teach | Point at |
|---|---|---|---|
| b1 | Modes & states depth (rows 11, 41–44): availability of functions in modes/states, triggers/guards, choice/fork/join, modes vs states | Mode = chosen, state = suffered; set `availableInStates` on functions; machines at OA/SA too | TH `Focus on state machines/Focus on state machines.html` (sole reference is enough) |
| b2 | Data model and exchange-item semantics (rows 8, 28–32) | Pick the mechanism (FLOW for energy/material, EVENT for trips, OPERATION, SHARED_DATA); type items with classes and physical quantities with units | TH `Interfaces and Data Modeling/Basic Constructs.html`, `Advanced Constructs.html`, `Methodological Recommendations.html` |
| b3 | Interfaces provided/required, communication links (rows 54, 55) | Interfaces are contracts on components or ports; provide on the producer, require on the consumer | TH Basic Constructs (Interface, CEI/CDI); TH `Functional Analysis to Interface Engineering/…html`; UI 7.1 "Interface Generation from Allocated Functions" |
| b4 | Missions and capability structure (rows 17, 18, 20) | Missions exploit capabilities; include/extend/generalize capabilities | GL `53. Mission.html`, `7. Capability.html`; DG "Missions Capabilities Blank"; ACT 5.1.1.1, 5.1.2.2.2 (definitions only in bundle) |
| b5 | Control functions and categories (rows 26, 27, 53, 66) | `FunctionKind` for fork/join/decision in dataflows; categories to group exchanges and links | GL `33…`, `46…`, `21…`; UI `7.5. Categories Management.html` |
| b6 | Property values and groups (row 73) | Annotate NFP data (e.g. ASIL, latency budget) on elements in a group | GL `73…`, `72…`; ACT 5.2.4.1 |
| b7 | Component port kind and orientation, port allocation (rows 49, 52) | FLOW vs STANDARD ports; INOUT allowed on component ports only | GL `17. Component Port.html`; UI 7.3 (port and EI allocation wizard) |
| b8 | OA structure: roles, communication means, operational actors (rows 2, 7, 12) | Roles group activities shared by entities; communication means carry interactions | GL `78. Role.html`, `14…`; TH actors guide |
| b9 | Generalization (row 56) | Specialise components/capabilities rather than copy | TH Basic Constructs (Generalization) |
| b10 | Validation-rule vocabulary (row 75) | Read native rule ids before claiming completeness | VR rule pages (design/completeness, transition/justification) |

## (c) Probe tasks for experiment 4

One task per gap on a disposable copy of the FSAE model. Each states a goal, not a recipe, and is graded only
from the model (the `grade.groovy` style). Subsystems named are those the experiment-2 task file already names.
(a) gaps double as API acceptance tests: today they should fail or need generic scripting.

| Gap | Probe task | Objective checks |
|---|---|---|
| a1 | Write the functional scenario of a shutdown trip from fault detection to shutdown-path opening, in its SA capability | Scenario kind FUNCTIONAL under a capability; ≥4 sequence messages in order, each referencing an existing functional exchange; every message's sender/receiver lifeline matches the exchange's source/target function |
| a2 | Model the trip sources as one chain that branches to each source and joins at "open shutdown path" | One functional chain with ≥1 control node (OR/AND); every source function involved; sequence links connect involvements; no orphan involvement |
| a3 | Route the BMS-to-shutdown component exchange over its physical links | Component exchange allocated to ≥1 physical link; a physical path whose involved links are contiguous (shared node parts) from source node to target node |
| a4 | Carry the logical charging components into PA with traceability | Every new physical component realizes a logical component (`ComponentRealization`); functions realized; validation adds no transition-justification errors |
| b1 | Give the accumulator controller its operating modes (e.g. idle, charging, driving, fault) and say which functions run in each | State machine on the component; ≥3 modes, initial pseudo-state, transitions with triggers or guards; each mode lists ≥1 available function; no function available in "fault" except safety functions named in the prompt |
| b2 | Type the IMD and BMS fault exchanges | Fault items are EVENT; cell-voltage item is FLOW or SHARED_DATA with a class typed by a numeric type with a unit; every crossing exchange carries ≥1 item |
| b3 | Make the shutdown-loop interface explicit between its components | One interface carrying the loop items, provided by exactly one component (or port) and required by each consumer |
| b4 | State the car's competition missions and which capabilities each uses | ≥2 missions; each exploits ≥1 existing capability; every capability exploited by ≥1 mission |
| b5 | Show where the driver-demand signal is duplicated to two consumers | One function of kind DUPLICATE (or SPLIT) with one input and ≥2 outputs; downstream exchanges start at its ports |
| b6 | Record each safety function's integrity data | Property value group on each safety function with the same keys; values set, none blank |

Rule for experiment 4: run each probe with the toolkit skills before and after the matching skill line and
`capella_docs` example exist (same protocol as experiment 2); (a) probes run once now as a baseline and again
after each API addition.

## Should `capella_docs` serve the bundled help directly?

Yes, read-only, for the pages named above. The help is EPL-2.0, version-matched to the user's Capella, already
on disk in every install (19 jars, 321 HTML pages, toc.xml per jar), and needs no network. The plugin can read a
page with `Platform.getBundle("org.polarsys.capella.th.doc").getEntry("html/…")` and strip it to text; a short
allow-list (TH, TR, UI 5.5/7.1/7.3/7.5, GL, RE, VR, DG) keeps it small. Skills then point at
`capella_docs help/<bundle>/<page>` instead of restating. The Arcadia reference PDFs are Thales-copyright with
a no-reproduction notice: link them (ACT/DM/MM URLs in MATRIX.md), never serve or quote them.
