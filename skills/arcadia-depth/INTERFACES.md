# Interfaces

**Rule.** An interface is the contract a component exchange honours: it groups the exchange items the
functional exchanges carry. The **producer** side provides it, the **consumer** side requires it, set
on the component ports at each end of the component exchange (or on the components). Work in this
order: functional exchange → its exchange items ([DATA.md](DATA.md)) → a component exchange between
the components the functions are allocated to, with the functional exchange allocated to it → the
interface carrying those items → provided and required.

```groovy
def ce = api.link(producerComponent, consumerComponent, 'ComponentExchange')
api.arcadia.allocateExchange(ce, functionalExchange)
def iface = api.create('Interface', 'Trip interface', api.layer('LA').ownedInterfacePkg)
api.arcadia.allocateExchangeItem(iface, item)              // once per item
api.set(ce.source, 'providedInterfaces', [iface])          // producer's port
api.set(ce.target, 'requiredInterfaces', [iface])          // consumer's port
```

**Check.** The functional exchange is allocated to the component exchange; the interface carries every
item of that exchange; the producer side provides it and the consumer side requires it, never swapped.
