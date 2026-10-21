# Physical links and paths

**Rule.** In PA a component exchange between behaviour components travels on the physical means
between the nodes they are deployed on: one **physical link**, or a **physical path** of contiguous
links when it passes through other nodes. Deploy the behaviours first; the allocation is refused
when the carrier's ends are not their nodes.

```groovy
def first = api.arcadia.physicalLink('Harness ES-LV', esNodePart, lvNodePart)
def second = api.arcadia.physicalLink('Harness LV-SD', lvNodePart, sdNodePart)
def path = api.arcadia.physicalPath('Battery status route', [first, second])   // in order
api.arcadia.allocateOnLink(componentExchange, path)    // or a single link
```

**Check.** The component exchange is in the carrier's `allocatedComponentExchanges`, and the path
runs node to node from one behaviour's node to the other's.
