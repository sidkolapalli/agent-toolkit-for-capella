"""Writes test-models/evals/fsae-2027.json: FSAE 2027 EV tasks scored only by native checks."""
import json
import sys

LA, PA, SA = "LA", "PA", "SA"
P = "FSAE2027:"


def sel(t, n, l): return {"type": t, "name": n, "layer": l}
def exists(t, n, l): return {"kind": "element_exists", "type": t, "name": n, "layer": l}
def absent(t, n, l): return {"kind": "element_absent", "type": t, "name": n, "layer": l}


def rel(t, preds, owner=None):
    check = {"kind": "relation_exists", "type": t, "predicates": preds}
    if owner:
        check["owner"] = owner
    return {"kind": "native_structure", "check": check}


def contained(target, container):
    return {"kind": "native_structure", "check": {"kind": "contained_by", "target": target, "container": container}}


def shows(diagram, elements):
    return {"kind": "native_structure", "check": {"kind": "diagram_shows", "diagram": diagram, "elements": elements}}


def satisfies(source, req_uuid):
    return rel("CapellaOutgoingRelation", {"source": source, "target": {"id": req_uuid}})


def involves(chain, t, n, l, kind="FunctionalChainInvolvementFunction"):
    return rel(kind, {"involved": sel(t, n, l)}, sel("FunctionalChain", chain, l))


REQ = {"EV.8.6.6": "a4171639-6c11-46b6-8e30-186f9ec8311c", "T.4.2.4": "81062f60-8ef1-4998-8d40-1ff50929b1a1", "IC.4.8.1": "fbd0d966-7506-401b-b636-7e69ef805f5a",
       "T.4.2.1": "fe00bb07-67c3-47be-b71c-dfb6a3813a20", "EV.5.6.1": "89ab3034-3dad-4a73-80e1-a52d0ee16091",
       "EV.3.2.1": "c3fd1cae-ac22-4e8c-8039-36944f15e20f", "EV.10.6.1": "208025bd-45f7-429f-89a3-a3ad25a704a6",
       "IC.9.1.1": "a6338e74-b010-4690-8fa1-ab2a8749a589", "T.9.4.1": "f04779cb-2fce-4f07-ac6c-fd2187f2e802"}


def rid(k): return "FSAE-2027-V1:" + k + ":p001"


F = dict(IMD=P + "SD Independently detect insulation and IMD operational faults", ACCEPT=P + "SD Accept independent shutdown trips",
         OPEN=P + "SD Open high-side shutdown current path", COIL=P + "SD Remove isolation and precharge relay coil drive",
         INTERRUPT=P + "SD Interrupt all tractive battery current", DISCHARGE=P + "SD Reduce residual TS voltage to low voltage within 5 seconds",
         BMSTRIP=P + "SD Issue independent BMS trip for any monitored fault", CELLV=P + "SD Measure every required cell-group voltage and apply datasheet limits",
         BSPDMON=P + "DD BSPD standalone brake and current monitor", BSPDLATCH=P + "DD BSPD latched shutdown request",
         APPSACQ=P + "DD Acquire separate analogue APPS channels A and B", APPSDET=P + "DD Detect APPS wiring range and plausibility failures",
         MOTORINH=P + "DD Motor power inhibit enforcement", EXEC=P + "DD Execute permitted motor power and forward torque command",
         REMOVETORQUE=P + "SD Remove motor torque on shutdown", STATUS_SA=P + "status Indicate electrical and readiness states")
E = dict(IMD=P + "SD LA Independent IMD shutdown trip", SERIES=P + "SD LA Any series path open request", COIL=P + "SD LA Coil drive removed",
         RELAYS=P + "SD LA Isolation relays release and interrupt current", BMS=P + "SD LA Independent BMS shutdown trip",
         CELLV=P + "SD LA Cell voltage and sense validity", BSPDHW=P + "DD LA Hardware simultaneous condition or sensor wiring fault",
         BSPDTRIP=P + "DD LA BSPD trip request", DISCHARGE=P + "SD LA Residual energy discharge required",
         PERMITTED=P + "DD LA Permitted motor power and forward torque command", TORQUE=P + "SD LA Torque removed and motor free-spin",
         SHUTINH=P + "DD LA Shutdown motor inhibit", APPSA=P + "DD LA APPS channel A raw signal", APPSB=P + "DD LA APPS channel B raw signal")
C = dict(IMD=P + "SD Independent IMD Logical monitor", BMS=P + "SD Independent BMS Logical monitor", PATH=P + "SD Hardwired shutdown current path Logical subsystem",
         BSPD=P + "DD Independent nonprogrammable BSPD Logical component", APPS=P + "DD Independent analogue APPS channels Logical component",
         ISO=P + "SD Isolation precharge and discharge Logical interface", TRACT=P + "tractive-distribution Logical subsystem",
         STATUS=P + "status Logical subsystem", ES=P + "energy-storage Logical subsystem", WHEEL=P + "VP Wheel braking distribution topology TBD Logical subsystem")

tasks = []
def task(id_, prompt, checks): tasks.append({"id": "fsae-" + id_, "prompt": prompt, "checks": checks})
def lf(n): return sel("LogicalFunction", n, LA)
def lc(n): return sel("LogicalComponent", n, LA)


# Requirements traceability: architecture elements must satisfy the FSAE rules they implement.
task("trace-imd-function", f"Trace the logical function '{F['IMD']}' as satisfying the FSAE requirement {rid('EV.8.6.6')} (Insulation Monitoring Device).",
     [satisfies(lf(F['IMD']), REQ['EV.8.6.6'])])
task("trace-bspd-component", f"Trace the logical component '{C['BSPD']}' as satisfying the FSAE requirement {rid('IC.4.8.1')} (Brake System Plausibility Device).",
     [satisfies(lc(C['BSPD']), REQ['IC.4.8.1'])])
task("trace-apps-component", f"Trace the logical component '{C['APPS']}' as satisfying the FSAE requirement {rid('T.4.2.4')} (Accelerator Pedal Position Sensor).",
     [satisfies(lc(C['APPS']), REQ['T.4.2.4'])])
task("trace-precharge-component", f"Trace the logical component '{C['ISO']}' as satisfying the FSAE requirement {rid('EV.5.6.1')} (precharge and discharge circuits).",
     [satisfies(lc(C['ISO']), REQ['EV.5.6.1'])])
task("trace-shutdown-circuit", f"Trace the logical component '{C['PATH']}' as satisfying the FSAE requirement {rid('IC.9.1.1')} (shutdown circuit).",
     [satisfies(lc(C['PATH']), REQ['IC.9.1.1'])])
em = P + "EM Measure tractive voltage and current for the energy meter"
task("energy-meter-function", f"In the logical architecture, create the logical function '{em}', allocate it to the logical component '{C['ES']}', and trace it as satisfying the FSAE requirement {rid('EV.3.2.1')} (energy meter).",
     [exists("LogicalFunction", em, LA), rel("ComponentFunctionalAllocation", {"sourceElement": lc(C['ES']), "targetElement": lf(em)}),
      satisfies(lf(em), REQ['EV.3.2.1'])])


# Functional chains: safety paths that teams must show end to end (the model has none).
def chain_task(id_, name, functions, exchanges):
    flow = " -> ".join(f"'{F[f]}'" for f in functions)
    via = ", ".join(f"'{E[e]}'" for e in exchanges)
    checks = [exists("FunctionalChain", name, LA)] + [involves(name, "LogicalFunction", F[f], LA) for f in (functions[0], functions[-1])]
    checks.append(involves(name, "FunctionalExchange", E[exchanges[-1]], LA, "FunctionalChainInvolvementLink"))
    task(id_, f"In the logical architecture, create the functional chain '{name}' that follows {flow} through the existing functional exchanges {via}.", checks)


chain_task("chain-imd-shutdown", P + "Chain IMD fault to tractive current interruption", ["IMD", "ACCEPT", "OPEN", "COIL", "INTERRUPT"], ["IMD", "SERIES", "COIL", "RELAYS"])
chain_task("chain-bms-shutdown", P + "Chain cell voltage fault to shutdown", ["CELLV", "BMSTRIP", "ACCEPT", "OPEN"], ["CELLV", "BMS", "SERIES"])
chain_task("chain-bspd-shutdown", P + "Chain BSPD trip to shutdown", ["BSPDMON", "BSPDLATCH", "ACCEPT", "OPEN"], ["BSPDHW", "BSPDTRIP", "SERIES"])
chain_task("chain-torque-removal", P + "Chain shutdown torque removal", ["OPEN", "REMOVETORQUE", "MOTORINH", "EXEC"], ["TORQUE", "SHUTINH", "PERMITTED"])
chain_task("chain-discharge", P + "Chain residual energy discharge", ["OPEN", "DISCHARGE"], ["DISCHARGE"])

# Interfaces and exchange items: what the signals are (the model has none).
ia, ib, iface = "APPS channel A voltage", "APPS channel B voltage", P + "APPS sensor interface"
task("apps-interface", f"In the logical architecture, create the exchange items '{ia}' and '{ib}' and an interface '{iface}' that carries both.",
     [exists("ExchangeItem", ia, LA), exists("ExchangeItem", ib, LA), exists("Interface", iface, LA)]
     + [rel("ExchangeItemAllocation", {"allocatedItem": sel("ExchangeItem", i, LA)}, sel("Interface", iface, LA)) for i in (ia, ib)])
imdi = "IMD fault status"
task("imd-exchange-item", f"In the logical architecture, create the exchange item '{imdi}' and make the existing functional exchange '{E['IMD']}' convey it.",
     [exists("ExchangeItem", imdi, LA),
      {"kind": "native_structure", "check": {"kind": "reference_sequence_equals", "target": sel("FunctionalExchange", E['IMD'], LA),
                                             "feature": "exchangedItems", "expected": [sel("ExchangeItem", imdi, LA)]}}])
trips, tiface = ["BMS trip request", "IMD trip request", "BSPD trip request"], P + "Shutdown trip interface"
task("shutdown-trip-interface", f"In the logical architecture, create the interface '{tiface}' carrying three new exchange items: " + ", ".join(f"'{t}'" for t in trips) + ".",
     [exists("Interface", tiface, LA)] + [rel("ExchangeItemAllocation", {"allocatedItem": sel("ExchangeItem", t, LA)}, sel("Interface", tiface, LA)) for t in trips])
line = P + "BMS trip line"
task("bms-trip-line", f"In the logical architecture, connect '{C['BMS']}' to '{C['PATH']}' with a component exchange '{line}', and allocate the existing functional exchange '{E['BMS']}' to it.",
     [exists("ComponentExchange", line, LA),
      rel("ComponentExchangeFunctionalExchangeAllocation", {"sourceElement": sel("ComponentExchange", line, LA), "targetElement": sel("FunctionalExchange", E['BMS'], LA)})])

# Modes and states.
ts = ["TS inactive", "Precharging", "TS active", "Discharging"]
task("tractive-system-states", f"Give the logical component '{C['TRACT']}' a state machine '{P}Tractive system states' with the states " + ", ".join(f"'{s}'" for s in ts)
     + " and the transitions TS inactive to Precharging, Precharging to TS active, TS active to Discharging, and Discharging to TS inactive.",
     [exists("State", s, LA) for s in ts]
     + [rel("StateTransition", {"source": sel("State", a, LA), "target": sel("State", b, LA)}) for a, b in (("TS inactive", "Precharging"), ("Precharging", "TS active"))])
task("ready-to-drive-states", f"Give the logical component '{C['STATUS']}' a state machine '{P}Ready-to-drive states' with the states 'Not ready to drive' and 'Ready to drive', a transition from 'Not ready to drive' to 'Ready to drive', and trace the state 'Ready to drive' as satisfying the FSAE requirement {rid('EV.10.6.1')}.",
     [exists("State", "Ready to drive", LA), rel("StateTransition", {"source": sel("State", "Not ready to drive", LA), "target": sel("State", "Ready to drive", LA)}),
      satisfies(sel("State", "Ready to drive", LA), REQ['EV.10.6.1'])])

# Scenario.
scen, cap = P + "IMD fault shutdown scenario", P + "shutdown System capability"
task("imd-fault-scenario", f"In the system analysis, create an exchange scenario '{scen}' in the capability '{cap}'.",
     [exists("Scenario", scen, SA), contained(sel("Scenario", scen, SA), sel("Capability", cap, SA))])

# Architecture extensions.
tsal, tsalf = P + "TSAL Logical component", P + "Indicate tractive system active with TSAL"
task("tsal-component", f"In the logical architecture, create the logical component '{tsal}' inside '{C['STATUS']}', create the logical function '{tsalf}', and allocate the function to the new component.",
     [contained(lc(tsal), lc(C['STATUS'])), rel("ComponentFunctionalAllocation", {"sourceElement": lc(tsal), "targetElement": lf(tsalf)})])
inert, inertfe = P + "SD Open shutdown circuit on inertia switch trigger", P + "SD LA Inertia switch trip"
task("inertia-switch", f"Add the inertia switch to the logical architecture: create the logical function '{inert}', connect it to '{F['ACCEPT']}' with a functional exchange '{inertfe}', allocate the new function to '{C['PATH']}', and trace it as satisfying the FSAE requirement {rid('T.9.4.1')}.",
     [exists("FunctionalExchange", inertfe, LA), rel("ComponentFunctionalAllocation", {"sourceElement": lc(C['PATH']), "targetElement": lf(inert)}),
      satisfies(lf(inert), REQ['T.9.4.1'])])
emn, emb, esq = P + "Energy meter Equipment", P + "Energy meter firmware Physical behavior", P + "energy-storage Equipment"
task("energy-meter-hardware", f"In the physical architecture, create a node component '{emn}' inside '{esq}' and a behavior component '{emb}' deployed on it.",
     [{"kind": "attribute_equals", "type": "PhysicalComponent", "name": emn, "layer": PA, "attribute": "nature", "expected": "NODE"},
      {"kind": "attribute_equals", "type": "PhysicalComponent", "name": emb, "layer": PA, "attribute": "nature", "expected": "BEHAVIOR"},
      contained(sel("PhysicalComponent", emn, PA), sel("PhysicalComponent", esq, PA)),
      rel("PartDeploymentLink", {"deployedElement": sel("Part", emb, PA), "location": sel("Part", emn, PA)})])
plink = P + "SD IMD contact drive link"
task("imd-physical-link", f"In the physical architecture, connect '{P}SD IMD Equipment approved model TBD' and '{P}SD Normally-open independent IMD shutdown contact' with a physical link '{plink}'.",
     [exists("PhysicalLink", plink, PA)])
pimd = P + "SD Monitor insulation resistance in hardware"
task("imd-realization", f"Create the physical function '{pimd}' that realizes the logical function '{F['IMD']}'.",
     [rel("FunctionRealization", {"sourceElement": sel("PhysicalFunction", pimd, PA), "targetElement": lf(F['IMD'])})])
rtds, rtdl = P + "SD Sound ready-to-drive signal", P + "SD Generate ready-to-drive sound"
task("ready-to-drive-sound", f"In the system analysis, create the system function '{rtds}' under '{F['STATUS_SA']}' and trace it as satisfying the FSAE requirement {rid('EV.10.6.1')}.",
     [contained(sel("SystemFunction", rtds, SA), sel("SystemFunction", F['STATUS_SA'], SA)), satisfies(sel("SystemFunction", rtds, SA), REQ['EV.10.6.1'])])
task("ready-to-drive-sound-la", f"Create the system function '{rtds}', a logical function '{rtdl}' that realizes it, and allocate the logical function to '{C['STATUS']}'.",
     [rel("FunctionRealization", {"sourceElement": lf(rtdl), "targetElement": sel("SystemFunction", rtds, SA)}),
      rel("ComponentFunctionalAllocation", {"sourceElement": lc(C['STATUS']), "targetElement": lf(rtdl)})])

# Diagrams.
d1 = P + "Shutdown trip sources"
task("shutdown-sources-diagram", f"Create a Logical Architecture Blank diagram '{d1}' showing '{C['IMD']}', '{C['BMS']}', '{C['BSPD']}' and '{C['PATH']}' with their allocated functions and the functional exchanges between those functions, and lay it out cleanly.",
     [shows(d1, [lc(C[k]) for k in ("IMD", "BMS", "BSPD", "PATH")])])
d2 = P + "APPS plausibility data flow"
task("apps-dataflow-diagram", f"Create a Logical Data Flow Blank diagram '{d2}' showing the logical functions '{F['APPSACQ']}' and '{F['APPSDET']}' and the functional exchanges '{E['APPSA']}' and '{E['APPSB']}', and lay it out cleanly.",
     [shows(d2, [lf(F['APPSACQ']), lf(F['APPSDET']), sel("FunctionalExchange", E['APPSA'], LA), sel("FunctionalExchange", E['APPSB'], LA)])])
d3 = P + "BSPD hardware view"
bspdhw = [P + "DD Independent nonprogrammable BSPD Equipment", P + "DD Independent BSPD tractive current sensor", P + "DD Independent BSPD hard braking sensor input"]
task("bspd-hardware-diagram", f"Create a Physical Architecture Blank diagram '{d3}' showing " + ", ".join(f"'{n}'" for n in bspdhw) + ", and lay it out cleanly.",
     [shows(d3, [sel("PhysicalComponent", n, PA) for n in bspdhw])])

# Model maintenance.
wheel2 = P + "VP Diagonal split wheel braking Logical subsystem"
task("resolve-tbd-name", f"The braking topology is decided: rename the logical component '{C['WHEEL']}' to '{wheel2}'.",
     [exists("LogicalComponent", wheel2, LA), absent("LogicalComponent", C['WHEEL'], LA)])
summary = "Approved IMD that opens the shutdown circuit on low insulation resistance between the tractive system and chassis."
task("document-imd", f"Set the summary of the logical component '{C['IMD']}' to exactly: {summary}",
     [{"kind": "attribute_equals", "type": "LogicalComponent", "name": C['IMD'], "layer": LA, "attribute": "summary", "expected": summary}])

data = {"description": "FSAE 2027 electric vehicle tasks for a disposable copy of the FSAE-2027-Electric-Arcadia project: requirements traceability, "
        "functional chains, interfaces and exchange items, modes and states, scenarios, architecture extensions, diagrams and model maintenance. "
        "Scored only by native checks. Run with restore=true. Needs the Requirements Viewpoint add-on.",
        "requires": "http://www.polarsys.org/capella/requirements", "restore": True, "tasks": tasks}
with open(sys.argv[1], "w", encoding="utf-8") as out:
    out.write(json.dumps(data, indent=2, ensure_ascii=False) + "\n")
print(len(tasks), "tasks")
