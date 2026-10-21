# Functional chains and operational processes

**Rule.** A functional chain is one path of interest through the dataflow (a safety reaction, an
end-to-end latency), not a copy of it. It may branch and join: list its exchanges and the chain
involves every function they connect. In OA the same construct is an **operational process**.
Chains belong to the architecture root function; involve them in the capability they illustrate.

```groovy
def chain = api.arcadia.chain(api.layer('LA').ownedFunctionPkg.ownedLogicalFunctions[0],
    'Any trip opens the shutdown path', tripExchanges + [openRequest])
api.arcadia.involveChain(capability, chain)
```

**Check.** Each exchange is a link of the chain between the involvements of its own source and
target functions; nothing outside the path of interest is in it.
