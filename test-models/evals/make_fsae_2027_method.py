"""Writes fsae-2027-method.json: method-heavy FSAE 2027 tasks for experiment 2 (harness-comparison/PROTOCOL.md).

Prompts name only elements fsae-2027.json already names; the other elements are found by the agent and selected by id
in the checks. Method checks are scored by harness-comparison/grade.groovy.
"""
import json
import pathlib

LA = "LA"


def lf(name):
    return {"type": "LogicalFunction", "name": name, "layer": LA}


def lc(name):
    return {"type": "LogicalComponent", "name": name, "layer": LA}


ID = {
    "sa_charging": "67214fbc-4ec0-465e-9287-c761cc23cfa8", "sa_thermal": "dcad0e9b-6c38-4351-928d-026c62c5cb09",
    "la_charging": "10bf2bf9-86c9-4437-b167-7d085279bf69", "la_thermal": "93dd97cb-42b1-4cfb-b31c-e098e7dd34cf",
    "la_shutdown": "0ef29217-83cf-457f-8378-c86463c9c2be", "la_driver_demand": "0ad7250e-9610-449c-b155-617d11fa7be6",
    "la_energy_storage": "0bd1be2a-d9a0-455b-bca4-c877ee2246c5",
    "f_glv": "4a7a58e2-1786-33f7-928e-8599440b1398", "f_manual": "a2c09551-b43f-3835-986d-84ee4bdddf3e",
    "f_interlock": "a4d31a0a-bb20-392c-939b-10032b213075", "f_bots": "1df38e00-4761-393e-8268-289d053ed4b6",
}


def i(key):
    return {"id": ID[key]}


BMS = lc("FSAE2027:SD Independent BMS Logical monitor")
IMD = lc("FSAE2027:SD Independent IMD Logical monitor")
BSPD = lc("FSAE2027:DD Independent nonprogrammable BSPD Logical component")
PATH = lc("FSAE2027:SD Hardwired shutdown current path Logical subsystem")
OPEN = lf("FSAE2027:SD Open high-side shutdown current path")
IMD_F = lf("FSAE2027:SD Independently detect insulation and IMD operational faults")
READABLE = ("Make it readable: no overlapping shapes or ports, every port on its shape's border, shapes inside their "
            "containers, and each exchange's label next to its own line.")


def shows(diagram, elements):
    return {"kind": "native_structure", "check": {"kind": "diagram_shows", "diagram": diagram, "elements": elements}}


def derive(area, extra):
    return (f"In the system analysis, the {area} area's top function has no sub-functions yet. Following the Arcadia "
            f"method, decompose it into at least three leaf system functions {extra} Realize every new system function "
            f"with a logical function, allocate each of those logical functions to a leaf logical component inside the "
            f"{area} logical subsystem (create components there as needed), and connect them with functional exchanges to ")


TASKS = [
    {"id": "fsae-m-charging-derive",
     "prompt": derive("charging", "that cover safe accumulator charging for this car: power transfer from the charger, "
                                  "accumulator monitoring while charging, and opening the shutdown circuit on a charging "
                                  "fault.")
               + "the battery monitoring functions of 'FSAE2027:SD Independent BMS Logical monitor' and to the functions of "
                 "the shutdown logical subsystem.",
     "checks": [],
     "method": [{"kind": "children_at_least", "parent": i("sa_charging"), "min": 3},
                {"kind": "leaves_realized", "parent": i("sa_charging")},
                {"kind": "realizers_allocated_once", "parent": i("sa_charging"), "within": i("la_charging")},
                {"kind": "connected", "from": {"realizers_of": i("sa_charging")}, "to": {"functions_of": BMS}},
                {"kind": "connected", "from": {"realizers_of": i("sa_charging")}, "to": {"functions_of": i("la_shutdown")}}]},
    {"id": "fsae-m-thermal-derive",
     "prompt": derive("thermal", "covering accumulator cooling, motor and motor controller cooling, and reporting "
                                 "over-temperature to the battery monitor.")
               + "the functions of 'FSAE2027:SD Independent BMS Logical monitor'.",
     "checks": [],
     "method": [{"kind": "children_at_least", "parent": i("sa_thermal"), "min": 3},
                {"kind": "leaves_realized", "parent": i("sa_thermal")},
                {"kind": "realizers_allocated_once", "parent": i("sa_thermal"), "within": i("la_thermal")},
                {"kind": "connected", "from": {"realizers_of": i("sa_thermal")}, "to": {"functions_of": BMS}}]},
    {"id": "fsae-m-chain-imd-path",
     "prompt": "In the logical architecture, create the functional chain 'FSAE2027:Chain insulation fault to battery current "
               "interruption' from 'FSAE2027:SD Independently detect insulation and IMD operational faults' to "
               "'FSAE2027:SD Interrupt all tractive battery current'. Find the path through the existing functional "
               "exchanges and involve every function and exchange on it; add an exchange only where the path is really "
               "missing.",
     "checks": [{"kind": "element_exists", "type": "FunctionalChain",
                 "name": "FSAE2027:Chain insulation fault to battery current interruption", "layer": LA}],
     "method": [{"kind": "chain_path", "start": IMD_F, "end": lf("FSAE2027:SD Interrupt all tractive battery current")}]},
    {"id": "fsae-m-chains-all-trips",
     "prompt": "Every protective trip source of the shutdown circuit needs its own functional chain in the logical "
               "architecture, running through the existing functional exchanges from the source function to "
               "'FSAE2027:SD Open high-side shutdown current path'. Find all trip sources: the functions whose output opens "
               "the shutdown current path, whether through 'FSAE2027:SD Accept independent shutdown trips' or directly. "
               "Create one chain per source.",
     "checks": [],
     "method": [{"kind": "chain_path", "start": source, "end": OPEN} for source in
                [IMD_F, lf("FSAE2027:SD Issue independent BMS trip for any monitored fault"),
                 lf("FSAE2027:DD BSPD latched shutdown request"), i("f_glv"), i("f_manual"), i("f_interlock"), i("f_bots")]]},
    {"id": "fsae-m-shutdown-component-exchanges",
     "prompt": "In the logical architecture, every functional exchange between functions of two different logical "
               "components, where at least one of them is in the shutdown logical subsystem, must be allocated to a "
               "component exchange between those two components (or their parent components). Most of them are not yet. "
               "Fix all of them, reusing a component exchange where one already connects the right components.",
     "checks": [],
     "method": [{"kind": "crossing_allocated", "scope": i("la_shutdown")}]},
    {"id": "fsae-m-driver-demand-transports",
     "prompt": "The component exchanges around the driver-demand logical subsystem were created without the functional "
               "exchanges they transport. Each component exchange named '<exchange> transport' carries the functional "
               "exchange of that name. Allocate every functional exchange that crosses logical components in the "
               "driver-demand subsystem to its component exchange, and create a component exchange only where none "
               "connects the two components.",
     "checks": [],
     "method": [{"kind": "crossing_allocated", "scope": i("la_driver_demand")},
                {"kind": "ce_used", "scope": i("la_driver_demand")},
                {"kind": "transport_names", "scope": i("la_driver_demand")}]},
    {"id": "fsae-m-bms-interface",
     "prompt": "Define the logical interface of 'FSAE2027:SD Independent BMS Logical monitor'. Every functional exchange "
               "between one of its functions and a function of another logical component must carry at least one exchange "
               "item naming the data or signal it conveys, and one interface 'FSAE2027:BMS monitor interface' must carry "
               "all of those exchange items.",
     "checks": [{"kind": "element_exists", "type": "Interface", "name": "FSAE2027:BMS monitor interface", "layer": LA}],
     "method": [{"kind": "items_on", "scope": BMS},
                {"kind": "interface_carries", "name": "FSAE2027:BMS monitor interface", "scope": BMS}]},
    {"id": "fsae-m-leaf-allocation",
     "prompt": "Arcadia allocates functions to leaf components. In the shutdown and energy-storage logical subsystems, "
               "some logical functions are allocated to a component that has sub-components. Move each of them to the "
               "sub-component that performs it (create a sub-component only when none fits), so that every function "
               "allocated in those two subsystems is a leaf function allocated exactly once, to a leaf component.",
     "checks": [],
     "method": [{"kind": "leaf_allocation", "scope": i("la_shutdown")},
                {"kind": "leaf_allocation", "scope": i("la_energy_storage")}]},
    {"id": "fsae-m-shutdown-flow-diagram",
     "prompt": "Create a Logical Data Flow Blank diagram 'FSAE2027:Shutdown trip flow' showing every logical function "
               "allocated in the shutdown logical subsystem (including its sub-components) and every functional exchange "
               "between those functions. " + READABLE,
     "checks": [shows("FSAE2027:Shutdown trip flow", [IMD_F, OPEN])],
     "method": [{"kind": "diagram_quality", "diagram": "FSAE2027:Shutdown trip flow",
                 "elements": [{"functions_of": i("la_shutdown")}], "edges": "between"}]},
    {"id": "fsae-m-trip-architecture-diagram",
     "prompt": "Create a Logical Architecture Blank diagram 'FSAE2027:Shutdown trip architecture' showing "
               "'FSAE2027:SD Independent IMD Logical monitor', 'FSAE2027:SD Independent BMS Logical monitor', "
               "'FSAE2027:DD Independent nonprogrammable BSPD Logical component' and 'FSAE2027:SD Hardwired shutdown "
               "current path Logical subsystem', the functions allocated to each, and every functional exchange between "
               "those functions. " + READABLE,
     "checks": [shows("FSAE2027:Shutdown trip architecture", [IMD, BMS, BSPD, PATH])],
     "method": [{"kind": "diagram_quality", "diagram": "FSAE2027:Shutdown trip architecture",
                 "elements": [IMD, BMS, BSPD, PATH] + [{"functions_of": c} for c in (IMD, BMS, BSPD, PATH)],
                 "edges": "between"}]},
    {"id": "fsae-m-driver-demand-flow-diagram",
     "prompt": "Create a Logical Data Flow Blank diagram 'FSAE2027:Driver demand data flow' showing every logical "
               "function allocated in the driver-demand logical subsystem (including its sub-components) and every "
               "functional exchange between those functions. " + READABLE,
     "checks": [shows("FSAE2027:Driver demand data flow", [lf("FSAE2027:DD Acquire separate analogue APPS channels A and B")])],
     "method": [{"kind": "diagram_quality", "diagram": "FSAE2027:Driver demand data flow",
                 "elements": [{"functions_of": i("la_driver_demand")}], "edges": "between"}]},
]

# The skill each task is meant to exercise (PROTOCOL.md, experiment 2 decision rule).
SKILL = {t["id"]: "capella-diagrams" if t["id"].endswith("-diagram") else "arcadia-method" for t in TASKS}
for task in TASKS:
    task["skill"] = SKILL[task["id"]]

if __name__ == "__main__":
    doc = {"description": "Method-heavy FSAE 2027 tasks for experiment 2 (bare agent + MCP, with and without skills). "
                          "Scored by native checks (if any) and method checks in harness-comparison/grade.groovy.",
           "requires": "The FSAE-2027-Electric-Arcadia model, the same pristine copy as fsae-2027.json.",
           "tasks": TASKS}
    out = pathlib.Path(__file__).with_name("fsae-2027-method.json")
    out.write_text(json.dumps(doc, indent=1, ensure_ascii=False) + "\n", "utf-8")
    print(len(TASKS), "tasks ->", out.name)
