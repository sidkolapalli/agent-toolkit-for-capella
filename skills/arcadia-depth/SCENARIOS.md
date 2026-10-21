# Scenarios

**Rule.** A scenario illustrates one capability, one case at a time: nominal and degraded cases are
separate scenarios. Pick the kind by what the lifelines are: a **functional scenario** (FS) when
they are functions, an **exchange scenario** (ES) when they are components and actors, an
operational scenario in OA. Every lifeline is involved in the capability first; each message is an
existing exchange, in the order it happens. Conditions are text constraints, not logic.

```groovy
fns.each { api.arcadia.involveFunction(capability, it) }
def fs = api.arcadia.functionalScenario(capability, 'BMS trip opens the shutdown path',
    [bmsTrip, openRequest, tripCause], 'A cell fault is detected', 'The shutdown path is open')
// ES: components involved, then all functional exchanges or all component exchanges
def es = api.arcadia.exchangeScenario(capability, name, orderedExchanges, pre, post)
api.diagram.create('FS', fs.name, fs)
```

**Check.** The scenario sits in the capability with the right kind; its messages invoke the named
exchanges in order, each from its source's lifeline to its target's.
