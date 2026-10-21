# Control functions in a dataflow

**Rule.** When one output must reach several consumers unchanged, or flows must be split, merged,
routed or selected, model it with a control function of that **kind** (`DUPLICATE`, `SPLIT`,
`GATHER`, `ROUTE`, `SELECT`) instead of parallel exchanges from the source. The control function is
allocated like any function (usually to the source's component), and the direct source-to-consumer
exchanges it replaces are removed.

```groovy
def dup = api.create('LA', 'LogicalFunction', 'Duplicate torque request')
api.set(dup, 'kind', 'DUPLICATE')
api.allocate(dup, sourceComponent)
api.link(source, dup, 'FunctionalExchange'); api.link(dup, consumerA, 'FunctionalExchange'); api.link(dup, consumerB, 'FunctionalExchange')
api.delete(oldDirectExchange)
```

**Check.** The control function has its kind, one incoming exchange from the source, one outgoing per
consumer, and no direct exchange from the source to those consumers remains.
