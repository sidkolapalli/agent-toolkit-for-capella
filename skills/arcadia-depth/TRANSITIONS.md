# Transitions between perspectives

**Rule.** Carry elements down a perspective with Capella's own transition, never by recreating
them: it creates the lower element and the realization link that justifies it. LA to PA:
components (`LA2PA`) and functions (`functional`); PA to EPBS: `PA2EPBS`. Data, exchange items,
interfaces and state machines have their own kinds.

```groovy
api.ops.transition([logicalFunction], 'functional')
api.ops.transition([logicalComponent], 'LA2PA')
```

**Check.** Each new lower element realizes its upper one (`ComponentRealization`,
`FunctionRealization`) and the lower function is allocated to the lower component.
