# Exchange items and the data model

**Rule.** An exchange item says *what* a functional exchange conveys, and its **mechanism** says
*how*: `EVENT` for a discrete occurrence (a trip, a command), `FLOW` for continuous material, energy
or a sampled signal, `SHARED_DATA` for a value read when needed, `OPERATION` for a call and its reply.
Type each element of an item: a physical value by a `PhysicalQuantity` with its `Unit`, a flag by a
`BooleanType`, a structure by a `Class`. Data lives in the layer's `ownedDataPkg`.

```groovy
def pkg = api.layer('LA').ownedDataPkg
def item = api.create('ExchangeItem', 'Trip', pkg)
api.set(item, 'exchangeMechanism', 'EVENT')
api.arcadia.exchangeItem(functionalExchange, item)          // the exchange now conveys the item

def volt = api.create('Unit', 'V', pkg)
def voltage = api.create('PhysicalQuantity', 'Voltage', pkg); api.set(voltage, 'unit', volt)
def valid = api.create('BooleanType', 'Validity', pkg)
def reading = api.create('ExchangeItem', 'Reading', pkg); api.set(reading, 'exchangeMechanism', 'FLOW')
def e = api.create('ExchangeItemElement', 'value', reading); api.set(e, 'abstractType', voltage)

def pack = api.create('Class', 'Pack status', pkg)           // a structure: a class with typed properties
def p = api.create('Property', 'cellVoltage', pack); api.set(p, 'abstractType', voltage)
```

New properties and exchange item elements start at cardinality [1..1], which native validation
(DWF_D_51) requires; leave it unless the task says otherwise.

**Check.** Each exchange the task names has `exchangedItems` of the intended mechanism; each element
has an `abstractType`, and a physical quantity has its `unit`.
