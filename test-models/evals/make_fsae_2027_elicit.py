"""Writes fsae-2027-elicit.json: underspecified FSAE 2027 tasks for experiment 3 (harness-comparison/PROTOCOL.md).

Each task's prompt leaves the team's decisions open. Its brief holds them; only the simulated stakeholder in run.py
reads the brief, and only the agent's questions reach it. Every brief fact has a `mentions` check and some have an
`absent` check for the usual wrong guess; both search the text of every Capella element (names, descriptions,
constraint bodies, property values), scored by harness-comparison/grade.groovy. None matches the pristine model.
"""
import json
import pathlib


def fact(text, pattern, guess=None):
    """A brief line, the pattern that shows it reached the model, and optionally the pattern of the usual wrong guess."""
    return text, pattern, guess


def celsius(n):
    """n °C however it is written: °C, degC, deg C, degrees C, Celsius or ℃."""
    return rf"\b{n}\s*(°|º|deg(rees?)?)?\s*(C\b|Celsius|℃)"


TASKS = [
    ("fsae-e-charging",
     "Model the car's charging subsystem in the logical architecture (LA): the functions and components involved in "
     "charging the accumulator, how they connect to the rest of the car, and the limits that apply.",
     [fact("The charger is an off-board commercial unit that stays in the paddock; no part of it is mounted on the car.",
           r"(?i)off[- ]?(board|car|vehicle)", r"(?i)on[- ]?board charger"),
      fact("The charger and the BMS talk over CAN: the BMS sends the current and voltage setpoints and can tell the "
           "charger to stop.", r"\bCAN\b"),
      fact("The maximum charge current is 15 A.", r"\b15\s*(A\b|amps?\b|amperes?\b)"),
      fact("Charging stops when any cell reaches 4.15 V.", r"4\.15\s*(V\b|volts?\b)", r"4\.2\d*\s*(V\b|volts?\b)"),
      # No wrong-guess check: the rules' 60 °C cell limit is already in the model.
      fact("Charging also stops when any cell temperature exceeds 45 °C.", celsius(45)),
      fact("The charging shutdown circuit runs through an HVIL loop in the charging connector: unplugging the connector "
           "opens it.", r"(?i)\bHVIL\b|high[- ]voltage interlock")]),
    ("fsae-e-thermal",
     "Model the cooling of the tractive system in the logical architecture (LA): what is cooled, how heat is moved "
     "and rejected, and the temperature limits that apply.",
     [fact("Motor and inverter share one liquid loop; the coolant is plain water.", r"(?i)\bwater\b", r"(?i)glycol"),
      fact("There is a single radiator, in the left sidepod.", r"(?i)left sidepod"),
      fact("The radiator fan switches on above 40 °C coolant temperature.", celsius(40)),
      fact("The inverter derates torque when its coolant inlet exceeds 50 °C.", celsius(50)),
      fact("The motor derates when its winding temperature reaches 120 °C.", celsius(120)),
      fact("The accumulator is not on the water loop: it is air-cooled by two fans pulling air through the segments.",
           r"(?i)air[- ]?cool", r"(?i)cold ?plate")]),
    ("fsae-e-drive",
     "Model the drive subsystem in the logical architecture (LA): how the driver's torque request becomes wheel torque, "
     "with the components and exchanges involved.",
     [fact("One motor drives the rear axle through a chain drive.", r"(?i)chain drive",
           r"(?i)hub motor|in-wheel|four[- ]wheel drive|\b4WD\b|\bAWD\b"),
      fact("The motor is an Emrax 228, an axial-flux permanent-magnet motor.", r"(?i)emrax|axial[- ]flux"),
      fact("The inverter is a Cascadia PM100.", r"(?i)\bPM100\b|cascadia"),
      fact("The VCU sends the torque request to the inverter over CAN every 10 ms.", r"\b10\s*(ms\b|milliseconds?\b)"),
      fact("The torque request is capped at 180 N·m at the motor.", r"\b180\s*N\s*[·.\-]?\s*m\b"),
      fact("There is no differential: the rear axle is a spool.", r"(?i)\bspool\b")]),
    ("fsae-e-charging-ops",
     "Model how the team charges the accumulator at competition, in the operational analysis (OA): who takes part, "
     "what each of them does, and where.",
     [fact("Charging happens only in the charging area of the paddock.", r"(?i)charging area"),
      fact("The accumulator is taken out of the car and charged on the team's charging cart.", r"(?i)charging cart",
           r"(?i)charg\w* (in|inside) the (car|vehicle)"),
      fact("Only the ESO starts charging, with a key switch on the charging cart.", r"(?i)key[- ]?switch"),
      fact("A second team member stands fire watch for the whole charge.", r"(?i)fire[- ]?watch"),
      fact("The ESO keeps a charging log, signed at the start and the end of each charge.", r"(?i)charg(ing|e) log(book)?\b")]),
]


def task(id, prompt, facts):
    method = [{"kind": "mentions", "fact": text, "pattern": pattern} for text, pattern, _ in facts]
    method += [{"kind": "absent", "fact": text, "pattern": guess} for text, _, guess in facts if guess]
    return {"id": id, "prompt": prompt, "brief": [text for text, _, _ in facts], "method": method}


if __name__ == "__main__":
    out = pathlib.Path(__file__).with_name("fsae-2027-elicit.json")
    out.write_text(json.dumps({"tasks": [task(*t) for t in TASKS]}, indent=1, ensure_ascii=False) + "\n", "utf-8")
    print(out, len(TASKS), "tasks")
