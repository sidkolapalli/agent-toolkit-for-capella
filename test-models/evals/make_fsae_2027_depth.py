"""Writes fsae-2027-depth.json: Arcadia-depth FSAE 2027 tasks for experiment 4 (harness-comparison/PROTOCOL.md).

Each task asks for a construct the agent rarely gets right unaided (docs/arcadia-coverage/GAPS.md, category b). Prompts
state the goal as a systems engineer would, not the metamodel steps. Method checks are scored by
harness-comparison/grade.groovy and test the Arcadia construct itself, not names.
"""
import json
import pathlib


def lf(name):
    return {"type": "LogicalFunction", "name": name, "layer": "LA"}


def lc(name):
    return {"type": "LogicalComponent", "name": name, "layer": "LA"}


def fe(name):
    return {"type": "FunctionalExchange", "name": name, "layer": "LA"}


ROOT = lc("FSAE2027:Electric Vehicle Logical System")
BMS = lc("FSAE2027:SD Independent BMS Logical monitor")
IMD = lc("FSAE2027:SD Independent IMD Logical monitor")
BSPD = lc("FSAE2027:DD Independent nonprogrammable BSPD Logical component")
PATH = lc("FSAE2027:SD Hardwired shutdown current path Logical subsystem")
BMS_TRIP = fe("FSAE2027:SD LA Independent BMS shutdown trip")
CELLS = fe("FSAE2027:SD LA Cell voltage and sense validity")
BOUND = lf("FSAE2027:DD Bound total wheel torque by driver demand")


def safety(element, programmable, ms):
    return {"kind": "property_group", "element": element, "group": "(?i)safety",
            "values": [{"name": "(?i)programmable", "type": "BooleanPropertyValue", "value": programmable},
                       {"name": "(?i)reaction|response", "type": "number", "value": ms}]}


TASKS = [
    {"id": "fsae-d-modes", "skill": "arcadia-method",
     "prompt": "The car has vehicle-level operating modes that the driver and team choose: off, low-voltage on, tractive "
               "system active, ready to drive, and charging. Model them in the logical architecture on "
               "'FSAE2027:Electric Vehicle Logical System', the Arcadia way, with where the car starts, the allowed "
               "changes between them and what triggers each change. Then make the logical functions follow the modes: "
               "'FSAE2027:DD Bound total wheel torque by driver demand' is available only when ready to drive, and "
               "'FSAE2027:charging Recharge traction energy under protected conditions' only while charging.",
     "checks": [],
     "method": [{"kind": "machine_modes", "component": ROOT, "min": 5},
                {"kind": "available_only", "function": BOUND, "pattern": "(?i)ready"},
                {"kind": "available_only", "function": lf("FSAE2027:charging Recharge traction energy under protected conditions"),
                 "pattern": "(?i)charg"}]},
    {"id": "fsae-d-exchange-items", "skill": "arcadia-method",
     "prompt": "Describe the information on two flows of 'FSAE2027:SD Independent BMS Logical monitor' in the logical "
               "architecture. 'FSAE2027:SD LA Independent BMS shutdown trip' is a discrete trip signal. 'FSAE2027:SD LA "
               "Cell voltage and sense validity' continuously carries the measured cell-group voltage, in volts, and "
               "whether each voltage sense line is valid. Give each functional exchange the exchange item that fits its "
               "nature, and define the cell-voltage data in the data model with proper types, including the unit.",
     "checks": [],
     "method": [{"kind": "item_kind", "exchange": BMS_TRIP, "mechanisms": ["EVENT"]},
                {"kind": "item_kind", "exchange": CELLS, "mechanisms": ["FLOW", "SHARED_DATA"]},
                {"kind": "item_typed", "exchange": CELLS}]},
    {"id": "fsae-d-interface", "skill": "arcadia-method",
     "prompt": "Make the BMS shutdown trip an explicit interface contract in the logical architecture. The trip "
               "'FSAE2027:SD LA Independent BMS shutdown trip' goes from 'FSAE2027:SD Independent BMS Logical monitor' to "
               "'FSAE2027:SD Hardwired shutdown current path Logical subsystem'. It must travel on a component exchange "
               "between those two components, its information must be an exchange item, and an interface "
               "'FSAE2027:BMS trip interface' must carry that item, with each component on the correct side of the "
               "contract.",
     "checks": [{"kind": "element_exists", "type": "Interface", "name": "FSAE2027:BMS trip interface", "layer": "LA"}],
     "method": [{"kind": "transported", "exchange": BMS_TRIP},
                {"kind": "interface_contract", "name": "FSAE2027:BMS trip interface", "exchange": BMS_TRIP,
                 "provider": BMS, "consumer": PATH}]},
    {"id": "fsae-d-missions", "skill": "arcadia-method",
     "prompt": "In the system analysis, state what the car is for at competition: its missions, at least the static "
               "events and the dynamic events (split the dynamic events further if you like). Link each mission to the "
               "system capabilities it relies on, so that every system capability serves at least one mission.",
     "checks": [],
     "method": [{"kind": "missions", "min": 2}]},
    {"id": "fsae-d-duplicate", "skill": "arcadia-method",
     "prompt": "The plausible driver torque request that 'FSAE2027:DD Detect APPS wiring range and plausibility failures' "
               "sends to 'FSAE2027:DD Bound total wheel torque by driver demand' must now also reach a new logical "
               "function 'FSAE2027:DD Log driver torque request', allocated to the same component as the detecting "
               "function. Both consumers receive the very same signal. Show that copy explicitly in the logical "
               "dataflow the way Arcadia models it.",
     "checks": [],
     "method": [{"kind": "control_function", "function_kind": "DUPLICATE",
                 "source": lf("FSAE2027:DD Detect APPS wiring range and plausibility failures"),
                 "consumers": [BOUND, lf("FSAE2027:DD Log driver torque request")]}]},
    {"id": "fsae-d-property-values", "skill": "arcadia-method",
     "prompt": "Record the safety data of the three independent trip monitors in the logical architecture as structured "
               "Capella property values, grouped the same way on each so a safety viewpoint can read them: whether the "
               "device is programmable, and its worst-case reaction time in milliseconds as a number. "
               "'FSAE2027:SD Independent IMD Logical monitor': not programmable, 30000 ms. 'FSAE2027:SD Independent BMS "
               "Logical monitor': programmable, 500 ms. 'FSAE2027:DD Independent nonprogrammable BSPD Logical "
               "component': not programmable, 500 ms. Name the group 'Safety'.",
     "checks": [],
     "method": [safety(IMD, False, 30000), safety(BMS, True, 500), safety(BSPD, False, 500)]},
    {"id": "fsae-d-categories", "skill": "arcadia-method",
     "prompt": "Every functional exchange that brings a protective trip into 'FSAE2027:SD Accept independent shutdown "
               "trips' should be grouped as one family, 'FSAE2027:Shutdown trip', in the logical architecture, so that "
               "trips can be filtered and reasoned about together.",
     "checks": [],
     "method": [{"kind": "category", "name": "FSAE2027:Shutdown trip", "into": lf("FSAE2027:SD Accept independent shutdown trips")}]},
]


def ent(name):
    return {"type": "Entity", "name": name, "layer": "OA"}


def cap(name):
    return {"type": "Capability", "name": name, "layer": "SA"}


TEAM = ent("FSAE2027:Team personnel Operational actor")
VEHICLE = ent("FSAE2027:Vehicle in operational context")
INSPECTION = ["FSAE2027:VP Inspect protection geometry and inject brake failures",
              "FSAE2027:SD Demonstrate every shutdown feature and fault effect",
              "FSAE2027:DD Provide and exercise safe inspection interfaces",
              "FSAE2027:process Inspect and present the vehicle"]

# Experiment 5: the second batch of depth files, plus the categories task reworded (PROTOCOL.md, experiment 5).
TASKS_5 = [
    {"id": "fsae-d-roles", "skill": "arcadia-method",
     "prompt": "At technical inspection the team personnel play one coherent part, 'FSAE2027:Inspection presenter', made "
               "up of these operational activities: " + ", ".join(f"'{a}'" for a in INSPECTION) + ". Model that part in "
               "the operational analysis the Arcadia way, so it could later be handed to another entity, and say that "
               "'FSAE2027:Team personnel Operational actor' plays it.",
     "checks": [],
     "method": [{"kind": "role", "name": "FSAE2027:Inspection presenter", "entity": TEAM,
                 "activities": [{"type": "OperationalActivity", "name": a, "layer": "OA"} for a in INSPECTION]}]},
    {"id": "fsae-d-communication", "skill": "arcadia-method",
     "prompt": "In the operational analysis, the team personnel and the vehicle exchange inspection stimuli and responses. "
               "Model the means by which 'FSAE2027:Team personnel Operational actor' and 'FSAE2027:Vehicle in operational "
               "context' communicate, name it 'FSAE2027:Inspection access', and make every operational interaction between "
               "their activities travel on it.",
     "checks": [],
     "method": [{"kind": "communication_mean", "name": "FSAE2027:Inspection access", "a": TEAM, "b": VEHICLE}]},
    {"id": "fsae-d-capabilities", "skill": "arcadia-method",
     "prompt": "Record in the system analysis how three capabilities depend on each other. Charging the car always "
               "performs energy storage as part of it: 'FSAE2027:charging System capability' and 'FSAE2027:energy-storage "
               "System capability'. The status indications optionally add behaviour to a shutdown: "
               "'FSAE2027:status System capability' and 'FSAE2027:shutdown System capability'. And add a new capability "
               "'FSAE2027:Regenerative braking System capability', a specialised form of 'FSAE2027:brakes System "
               "capability'.",
     "checks": [{"kind": "element_exists", "type": "Capability", "name": "FSAE2027:Regenerative braking System capability", "layer": "SA"}],
     "method": [{"kind": "capability_relation", "relation": "include", "from": cap("FSAE2027:charging System capability"),
                 "to": cap("FSAE2027:energy-storage System capability")},
                {"kind": "capability_relation", "relation": "extend", "from": cap("FSAE2027:status System capability"),
                 "to": cap("FSAE2027:shutdown System capability")},
                {"kind": "capability_relation", "relation": "generalization",
                 "from": cap("FSAE2027:Regenerative braking System capability"), "to": cap("FSAE2027:brakes System capability")}]},
    {"id": "fsae-d-categories-2", "skill": "arcadia-method",
     "prompt": "Group every functional exchange that enters 'FSAE2027:SD Accept independent shutdown trips', whatever its "
               "source, as one family 'FSAE2027:Shutdown trip' in the logical architecture, so that everything that can "
               "trip the car can be filtered and reasoned about together.",
     "checks": [],
     "method": [{"kind": "category", "name": "FSAE2027:Shutdown trip", "into": lf("FSAE2027:SD Accept independent shutdown trips")}]},
]


def pc(name):
    return {"type": "PhysicalComponent", "name": name, "layer": "PA"}


ACCEPT = "FSAE2027:SD Accept independent shutdown trips"
TRIPS_IN = ["FSAE2027:SD LA Independent IMD shutdown trip", "FSAE2027:SD LA GLV supply loss",
            "FSAE2027:SD LA Direct manual switch opening", "FSAE2027:SD LA Interlock continuity lost",
            "FSAE2027:DD LA BSPD trip request", "FSAE2027:SD LA Independent BMS shutdown trip"]

# Experiment 6: the API gaps (GAPS.md category a), as acceptance probes: before and after the new build.
TASKS_6 = [
    {"id": "fsae-d-fs-scenario", "skill": "arcadia-method",
     "prompt": "Illustrate the logical shutdown capability 'FSAE2027:shutdown Logical capability realization' with a "
               "functional scenario 'FSAE2027:BMS trip opens the shutdown path': the BMS issues its trip "
               "('FSAE2027:SD LA Independent BMS shutdown trip'), the accepted trip requests the shutdown path to open "
               "('FSAE2027:SD LA Any series path open request'), and the trip cause is latched "
               "('FSAE2027:SD LA BMS IMD BSPD protective-trip cause'), in that order.",
     "checks": [],
     "method": [{"kind": "scenario_messages", "capability": {"type": "CapabilityRealization", "name": "FSAE2027:shutdown Logical capability realization", "layer": "LA"},
                 "scenario_kind": "FUNCTIONAL",
                 "exchanges": [fe("FSAE2027:SD LA Independent BMS shutdown trip"), fe("FSAE2027:SD LA Any series path open request"),
                               fe("FSAE2027:SD LA BMS IMD BSPD protective-trip cause")]}]},
    {"id": "fsae-d-branching-chain", "skill": "arcadia-method",
     "prompt": "In the logical architecture, model one functional chain 'FSAE2027:Any trip opens the shutdown path' that "
               f"gathers every trip entering '{ACCEPT}' and continues to 'FSAE2027:SD Open high-side shutdown current path'.",
     "checks": [],
     "method": [{"kind": "chain_exchanges", "name": "FSAE2027:Any trip opens the shutdown path",
                 "exchanges": [fe(n) for n in TRIPS_IN] + [fe("FSAE2027:SD LA Any series path open request")]}]},
    {"id": "fsae-d-physical-path", "skill": "arcadia-method",
     "prompt": "The battery safety status ('FSAE2027:PA Battery safety status transport') runs in a harness from "
               "'FSAE2027:energy-storage Equipment' through 'FSAE2027:low-voltage Equipment' to 'FSAE2027:shutdown "
               "Equipment'. Model that physical route in the physical architecture and make the component exchange "
               "travel on it.",
     "checks": [],
     "method": [{"kind": "path_carries", "exchange": {"type": "ComponentExchange", "name": "FSAE2027:PA Battery safety status transport", "layer": "PA"},
                 "stops": [pc("FSAE2027:energy-storage Equipment"), pc("FSAE2027:low-voltage Equipment"), pc("FSAE2027:shutdown Equipment")]}]},
    {"id": "fsae-d-transition", "skill": "arcadia-method",
     "prompt": "Add a logical component 'FSAE2027:Charge port interlock monitor' inside 'FSAE2027:charging Logical "
               "subsystem', with a logical function 'FSAE2027:Monitor charge port interlock' allocated to it. Then carry "
               "both into the physical architecture the way Capella does, so that each keeps its traceability to the "
               "logical one.",
     "checks": [{"kind": "element_exists", "type": "LogicalComponent", "name": "FSAE2027:Charge port interlock monitor", "layer": "LA"}],
     "method": [{"kind": "realized_in_pa", "component": lc("FSAE2027:Charge port interlock monitor"),
                 "function": lf("FSAE2027:Monitor charge port interlock")}]},
]


def write(name, description, tasks):
    doc = {"description": description,
           "requires": "The FSAE-2027-Electric-Arcadia model, the same pristine copy as fsae-2027.json.",
           "tasks": tasks}
    out = pathlib.Path(__file__).with_name(name)
    out.write_text(json.dumps(doc, indent=1, ensure_ascii=False) + "\n", "utf-8")
    print(len(tasks), "tasks ->", out.name)


if __name__ == "__main__":
    write("fsae-2027-depth.json", "Arcadia-depth FSAE 2027 tasks for experiment 4 (bare agent + MCP, with and without "
          "skills, Sonnet and Haiku). Scored by native checks (if any) and method checks in harness-comparison/grade.groovy.",
          TASKS)
    write("fsae-2027-depth-5.json", "Arcadia-depth FSAE 2027 tasks for experiment 5 (the second batch of depth files). "
          "Scored as fsae-2027-depth.json.", TASKS_5)
    write("fsae-2027-depth-6.json", "API-gap FSAE 2027 tasks for experiment 6 (scenarios, chains, physical paths, "
          "transitions). Scored as fsae-2027-depth.json.", TASKS_6)
