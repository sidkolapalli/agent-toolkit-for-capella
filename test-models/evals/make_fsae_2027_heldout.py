"""Writes fsae-2027-heldout.json: held-out Arcadia-depth tasks for experiment G1 (harness-comparison/PROTOCOL.md).

One task per construct of the 14 depth tasks of experiments 4 to 11, with the same method check kinds as the original,
on other subsystems and with element names, scenarios and wording that appear in no skill file and no other task
prompt (leak check kept outside the repository). Reference solutions are withheld so the tasks stay usable as a
benchmark. Model: FSAE-2027-Electric-Arcadia at tag v1.1.0, the same content as the zip every experiment used.
"""
import json
import pathlib


def el(kind, layer):
    return lambda name: {"type": kind, "name": name, "layer": layer}


lf, lc, fe = el("LogicalFunction", "LA"), el("LogicalComponent", "LA"), el("FunctionalExchange", "LA")
cap, ent, act, pc = el("Capability", "SA"), el("Entity", "OA"), el("OperationalActivity", "OA"), el("PhysicalComponent", "PA")

THERMAL = lc("FSAE2027:thermal Logical subsystem")
SENSING = lc("FSAE2027:DD Brake demand and engagement sensing Logical component")
GUARD = lc("FSAE2027:DD APPS BSE and brake conflict safety guard Logical component")
BSE_SIGNAL = fe("FSAE2027:DD LA BSE analogue signal")
BSE_INHIBIT = fe("FSAE2027:DD LA BSE failure power inhibit")
SPEED = fe("FSAE2027:DD LA Vehicle speed and validity")
RESPONDERS = ent("FSAE2027:Emergency responders Operational actor")
DRIVER = ent("FSAE2027:Driver Operational actor")
DRIVER_STOP = "FSAE2027:SD Initiate emergency stop and observe disabled tractive energy"
RECOVERY = ["FSAE2027:process Stop recover and respond to emergencies",
            "FSAE2027:SD Clear protective fault and reset locally with driver excluded"]
LOADS_IN = "FSAE2027:VP Carry occupant protection loads through primary structure"
BRAKING = ["FSAE2027:VP LA Single mechanical pedal effort", "FSAE2027:VP LA Mechanical drive to independent circuit A",
           "FSAE2027:VP LA Mechanical drive to independent circuit B",
           "FSAE2027:VP LA Circuit A pressure and effective wheel-braking output",
           "FSAE2027:VP LA Circuit B pressure and effective wheel-braking output"]
CONFLICT = ["FSAE2027:DD LA Mechanical pedal travel and zero return", "FSAE2027:DD LA Validated pedal travel",
            "FSAE2027:DD LA Latched brake conflict power inhibit"]
SENSORS = [("FSAE2027:DD APPS analogue sensor channel A", False, 10), ("FSAE2027:DD APPS analogue sensor channel B", True, 10),
           ("FSAE2027:DD Analogue brake pressure sensor", False, 5)]


def q(names):
    return ", ".join(f"'{n}'" for n in names)


def signal(name, inverted, ms):
    return {"kind": "property_group", "element": pc(name), "group": "(?i)signal",
            "values": [{"name": "(?i)invert", "type": "BooleanPropertyValue", "value": inverted},
                       {"name": "(?i)sampl|period", "type": "number", "value": ms}]}


TASKS = [
    {"id": "fsae-h-modes", "skill": "arcadia-method",
     "note": "The two functions are created by the task (the thermal subsystem owns only one); fsae-d-modes used existing functions.",
     "prompt": "The cooling loop has operating modes that the team and the driver select: off, coolant fill and bleed, "
               "track cooling, charge cooling, and post-run cool-down. Model them in the logical architecture on "
               "'FSAE2027:thermal Logical subsystem' as Arcadia intends, with the mode the loop enters first, the "
               "changes permitted between modes and what causes each change. Add two logical functions allocated to "
               "that subsystem, 'FSAE2027:Purge trapped air from the coolant circuit' and 'FSAE2027:Keep radiator fans "
               "running after the car stops', and tie them to the modes: the first may run only in coolant fill and "
               "bleed, the second only in post-run cool-down.",
     "checks": [],
     "method": [{"kind": "machine_modes", "component": THERMAL, "min": 5},
                {"kind": "available_only", "function": lf("FSAE2027:Purge trapped air from the coolant circuit"),
                 "pattern": "(?i)bleed"},
                {"kind": "available_only", "function": lf("FSAE2027:Keep radiator fans running after the car stops"),
                 "pattern": "(?i)cool-?\\s?down"}]},
    {"id": "fsae-h-exchange-items", "skill": "arcadia-method",
     "prompt": "Describe what two flows into 'FSAE2027:DD Driver bounded torque and power inhibit Logical component' "
               "convey in the logical architecture. 'FSAE2027:DD LA BSE failure power inhibit' is a one-off command "
               "raised at the moment the brake sensor is found faulty. 'FSAE2027:DD LA Vehicle speed and validity' is "
               "sampled all the time and gives the road speed, in kilometres per hour, together with a flag telling "
               "whether that reading can be trusted. Give each functional exchange the exchange item that matches how "
               "its information is conveyed, and define the speed data in the data model with proper types, unit "
               "included.",
     "checks": [],
     "method": [{"kind": "item_kind", "exchange": BSE_INHIBIT, "mechanisms": ["EVENT"]},
                {"kind": "item_kind", "exchange": SPEED, "mechanisms": ["FLOW", "SHARED_DATA"]},
                {"kind": "item_typed", "exchange": SPEED}]},
    {"id": "fsae-h-interface", "skill": "arcadia-method",
     "prompt": "Make the contract for the brake sensor signal explicit as an interface in the logical architecture. "
               "'FSAE2027:DD LA BSE analogue signal' goes from 'FSAE2027:DD Brake demand and engagement sensing Logical "
               "component' to 'FSAE2027:DD APPS BSE and brake conflict safety guard Logical component'. It has to be "
               "carried by a component exchange between those two components, what it conveys has to be an exchange "
               "item, and an interface 'FSAE2027:Brake sensor signal interface' has to hold that item, with each "
               "component on its proper side of the contract.",
     "note": "As in fsae-d-interface, a component exchange that `transported` accepts already joins the two sides. "
             "There it was the subsystem-level 'LA Battery safety status transport', which already carried another "
             "exchange; here it is the empty, leaf-level 'DD LA BSE analogue signal transport', named after the "
             "signal, so the transport step is easier to find. A second empty exchange ('DD LA Mechanical brake "
             "engagement transport') joins the same pair; either one passes.",
     "checks": [{"kind": "element_exists", "type": "Interface", "name": "FSAE2027:Brake sensor signal interface", "layer": "LA"}],
     "method": [{"kind": "transported", "exchange": BSE_SIGNAL},
                {"kind": "interface_contract", "name": "FSAE2027:Brake sensor signal interface", "exchange": BSE_SIGNAL,
                 "provider": SENSING, "consumer": GUARD}]},
    {"id": "fsae-h-missions", "skill": "arcadia-method",
     "prompt": "In the system analysis, record what the team builds the car for across a season: its missions, at "
               "least getting the car through scrutineering before it may run, and serving as the platform on which "
               "drivers are trained and the car is tested between competitions (add more if you see them). Connect "
               "each mission to the system capabilities it depends on, so that no system capability is left without a "
               "mission that relies on it.",
     "checks": [],
     "method": [{"kind": "missions", "min": 2}]},
    {"id": "fsae-h-duplicate", "skill": "arcadia-method",
     "note": "The second consumer already exists; fsae-d-duplicate created it.",
     "prompt": "The mechanical brake engagement that 'FSAE2027:DD Acquire brake demand and mechanical engagement' sends "
               "to 'FSAE2027:DD Latch brake accelerator conflict motor inhibit' must now also drive the brake light "
               "function 'FSAE2027:VP Indicate braking visibly to observers behind vehicle'. Both the latch and the "
               "brake light must receive the identical engagement signal. Make that copying explicit in the logical dataflow, as "
               "Arcadia represents it.",
     "checks": [],
     "method": [{"kind": "control_function", "function_kind": "DUPLICATE",
                 "source": lf("FSAE2027:DD Acquire brake demand and mechanical engagement"),
                 "consumers": [lf("FSAE2027:DD Latch brake accelerator conflict motor inhibit"),
                               lf("FSAE2027:VP Indicate braking visibly to observers behind vehicle")]}]},
    {"id": "fsae-h-property-values", "skill": "arcadia-method",
     "note": "Physical components (PA) rather than logical ones (LA).",
     "prompt": "Record the signal data of three driver-input sensors in the physical architecture as structured Capella "
               "property values, grouped identically on each so that a signal-integrity review can read them: whether "
               "the sensor output is inverted, and its sample period in milliseconds as a number. "
               + " ".join(f"'{n}': {'inverted' if inv else 'not inverted'}, {ms} ms." for n, inv, ms in SENSORS)
               + " Call the group 'Signal conditioning'.",
     "checks": [],
     "method": [signal(*s) for s in SENSORS]},
    {"id": "fsae-h-roles", "skill": "arcadia-method",
     "note": "Two activities rather than four; another actor performs them today. The check needs only the role, its activities and the entity playing it.",
     "prompt": "When a car stops on track with an electrical fault, one coherent part is played, 'FSAE2027:Recovery "
               f"marshal', made up of these operational activities, which the team carries out today: '{RECOVERY[0]}' "
               f"and '{RECOVERY[1]}'. Model that part in the operational "
               "analysis as Arcadia intends, so that other entities could take it on later, and record that "
               "'FSAE2027:Emergency responders Operational actor' plays it.",
     "checks": [],
     "method": [{"kind": "role", "name": "FSAE2027:Recovery marshal", "entity": RESPONDERS,
                 "activities": [act(a) for a in RECOVERY]}]},
    {"id": "fsae-h-communication", "skill": "arcadia-method",
     "prompt": "In the operational analysis, the driver and the emergency responders talk to each other at a stopped "
               "car. Add an operational activity 'FSAE2027:Attend a stopped car' performed by 'FSAE2027:Emergency "
               "responders Operational actor', an operational interaction 'FSAE2027:Driver condition report' from the "
               f"driver's activity '{DRIVER_STOP}' to it, and an operational interaction 'FSAE2027:Safe egress "
               "instruction' in the other direction. Then model what connects 'FSAE2027:Driver Operational actor' and "
               "the emergency responders so they can communicate, call it 'FSAE2027:Trackside voice and hand signals', "
               "and have every operational interaction between their activities go over it.",
     "note": "Unlike fsae-d-communication, the task first adds one activity and two interactions: no actor pair is "
             "usable without a leak (the driver's activity has no interactions and the responders perform no "
             "activities). Because the task creates the only interactions between the two actors, the check reduces "
             "to: the named communication means exists between Driver and Emergency responders and carries the two "
             "created interactions; the original's part, finding the existing interactions, is gone. The check also "
             "fails if 'Attend a stopped car' is not assigned to the responders, which the prompt's 'performed by' "
             "states.",
     "checks": [],
     "method": [{"kind": "communication_mean", "name": "FSAE2027:Trackside voice and hand signals", "a": DRIVER, "b": RESPONDERS}]},
    {"id": "fsae-h-capabilities", "skill": "arcadia-method",
     "prompt": "Record in the system analysis how three pairs of capabilities relate. Propelling the car cannot happen "
               "without distributing tractive energy, every time: 'FSAE2027:powertrain System capability' and "
               "'FSAE2027:tractive-distribution System capability'. Aerodynamic load can enhance the wheel and steering "
               "capability when the car carries wings, but need not: 'FSAE2027:aerodynamics System capability' and "
               "'FSAE2027:running-gear System capability'. And add a new capability 'FSAE2027:Liquid cooling System "
               "capability', a more specific variant of 'FSAE2027:thermal System capability'.",
     "checks": [{"kind": "element_exists", "type": "Capability", "name": "FSAE2027:Liquid cooling System capability", "layer": "SA"}],
     "method": [{"kind": "capability_relation", "relation": "include", "from": cap("FSAE2027:powertrain System capability"),
                 "to": cap("FSAE2027:tractive-distribution System capability")},
                {"kind": "capability_relation", "relation": "extend", "from": cap("FSAE2027:aerodynamics System capability"),
                 "to": cap("FSAE2027:running-gear System capability")},
                {"kind": "capability_relation", "relation": "generalization",
                 "from": cap("FSAE2027:Liquid cooling System capability"), "to": cap("FSAE2027:thermal System capability")}]},
    {"id": "fsae-h-categories", "skill": "arcadia-method",
     "note": "Three exchanges enter the function, from three sources; fsae-d-categories-2 had six.",
     "prompt": f"Group every functional exchange that arrives at '{LOADS_IN}', no matter which function sends it, as one "
               "family 'FSAE2027:Occupant protection load' in the logical architecture, so that all loads reaching the "
               "primary structure can be filtered and analysed together.",
     "checks": [],
     "method": [{"kind": "category", "name": "FSAE2027:Occupant protection load", "into": lf(LOADS_IN)}]},
    {"id": "fsae-h-fs-scenario", "skill": "arcadia-method",
     "prompt": "Show how a pedal conflict cuts motor power with a functional scenario 'FSAE2027:Pedal conflict removes "
               "motor power' in the logical capability realization 'FSAE2027:driver-demand Logical capability "
               f"realization': the accelerator pedal travel reaches the APPS channels ('{CONFLICT[0]}'), the validated "
               f"travel reaches the conflict latch ('{CONFLICT[1]}'), and the latch inhibits motor power "
               f"('{CONFLICT[2]}'), one after the other in that sequence.",
     "checks": [],
     "method": [{"kind": "scenario_messages",
                 "capability": {"type": "CapabilityRealization", "name": "FSAE2027:driver-demand Logical capability realization", "layer": "LA"},
                 "scenario_kind": "FUNCTIONAL", "exchanges": [fe(n) for n in CONFLICT]}]},
    {"id": "fsae-h-branching-chain", "skill": "arcadia-method",
     "note": "Five links, a split then a join, all named in the prompt; fsae-d-branching-chain-2 had seven, a join, named by the function they enter.",
     "prompt": "In the logical architecture, model one functional chain 'FSAE2027:Pedal effort reaches every wheel "
               "brake' that follows the driver's braking effort from 'FSAE2027:VP Transmit pedal force without "
               "obstructive component failure' to 'FSAE2027:VP Apply a single mechanical braking demand', splits there "
               "into both hydraulic circuits, and comes together again at 'FSAE2027:VP Brake all wheels and retain "
               f"effective two-wheel braking after failure'. It covers these functional exchanges: {q(BRAKING)}.",
     "checks": [],
     "method": [{"kind": "chain_exchanges", "name": "FSAE2027:Pedal effort reaches every wheel brake",
                 "exchanges": [fe(n) for n in BRAKING]}]},
    {"id": "fsae-h-physical-path", "skill": "arcadia-method",
     "prompt": "The cable for the validated torque demand ('FSAE2027:PA Validated torque demand transport') leaves "
               "'FSAE2027:driver-demand Equipment', is routed via 'FSAE2027:cockpit Equipment' and ends at "
               "'FSAE2027:powertrain Equipment'. Represent that physical route in the physical architecture and have "
               "the component exchange use it.",
     "checks": [],
     "method": [{"kind": "path_carries",
                 "exchange": {"type": "ComponentExchange", "name": "FSAE2027:PA Validated torque demand transport", "layer": "PA"},
                 "stops": [pc("FSAE2027:driver-demand Equipment"), pc("FSAE2027:cockpit Equipment"), pc("FSAE2027:powertrain Equipment")]}]},
    {"id": "fsae-h-transition", "skill": "arcadia-method",
     "prompt": "Add a logical component 'FSAE2027:Steering angle sensor' inside 'FSAE2027:running-gear Logical "
               "subsystem', with a logical function 'FSAE2027:Measure steering wheel angle' allocated to it. Then bring "
               "both down to the physical architecture with Capella's own mechanism for it, so that each physical "
               "element stays traced to the logical one it comes from.",
     "note": "Same flaw as fsae-d-transition, stated rather than fixed: realized_in_pa (the original check kind) also "
             "passes when the PA component, function, realizations and allocation are built by hand, so this task "
             "cannot show whether the transition operation was used. Pre-registered secondary measure, transition_used: the "
             "run's script or tool log shows the transition applied with 'functional' to the new function and 'LA2PA' "
             "to the new component; reported for both arms, not scored.",
     "checks": [{"kind": "element_exists", "type": "LogicalComponent", "name": "FSAE2027:Steering angle sensor", "layer": "LA"}],
     "method": [{"kind": "realized_in_pa", "component": lc("FSAE2027:Steering angle sensor"),
                 "function": lf("FSAE2027:Measure steering wheel angle")}]},
]

if __name__ == "__main__":
    doc = {"description": "Held-out Arcadia-depth FSAE 2027 tasks for experiment G1: one per construct of experiments 4 "
                          "to 11, same method check kinds, new subsystems and names. Scored as fsae-2027-depth.json by "
                          "harness-comparison/grade.groovy. A task's 'note', when present, says where it differs from "
                          "its original; the agent never sees it.",
           "requires": "The FSAE-2027-Electric-Arcadia model at tag v1.1.0 (the same content as the zip every experiment used).",
           "tasks": TASKS}
    out = pathlib.Path(__file__).with_name("fsae-2027-heldout.json")
    out.write_text(json.dumps(doc, indent=1, ensure_ascii=False) + "\n", "utf-8")
    print(len(TASKS), "tasks ->", out.name)
