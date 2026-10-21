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

if __name__ == "__main__":
    doc = {"description": "Arcadia-depth FSAE 2027 tasks for experiment 4 (bare agent + MCP, with and without skills, "
                          "Sonnet and Haiku). Scored by native checks (if any) and method checks in "
                          "harness-comparison/grade.groovy.",
           "requires": "The FSAE-2027-Electric-Arcadia model, the same pristine copy as fsae-2027.json.",
           "tasks": TASKS}
    out = pathlib.Path(__file__).with_name("fsae-2027-depth.json")
    out.write_text(json.dumps(doc, indent=1, ensure_ascii=False) + "\n", "utf-8")
    print(len(TASKS), "tasks ->", out.name)
