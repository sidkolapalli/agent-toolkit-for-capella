# Modes and states

**Rule.** A **mode** is a behaviour the system is put in by choice (ready to drive, charging); a
**state** is a condition it undergoes (overheated, faulted). Use one kind per machine. The machine
belongs to the element whose behaviour it describes (the layer's root component for the whole system).
Every mode is reachable from the initial pseudo-state, every transition says what fires it (trigger
or guard), and each function says in which modes it is available; a function with no availability
is available everywhere.

```groovy
def sm = api.create('StateMachine', 'Operating modes', api.layer('LA').system)
def region = api.create('Region', 'Default', sm)
def off = api.create('Mode', 'Off', region), on = api.create('Mode', 'On', region)
def init = api.create('InitialPseudoState', 'Initial', region)
def go = { s, d, why -> def t = api.create('StateTransition', why, region)
    api.set(t, 'source', s); api.set(t, 'target', d); api.set(t, 'triggerDescription', why); t }
go(init, off, 'power up'); go(off, on, 'switch on'); go(on, off, 'switch off')
api.set(someFunction, 'availableInStates', [on])   // list every mode the function runs in
```

**Check.** `region.ownedStates` holds only `Mode` (or only `State`); each mode is the target of a
transition; each transition from a mode has a `triggerDescription` or guard; the functions the task
names list their modes in `availableInStates`.
