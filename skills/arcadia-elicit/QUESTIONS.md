# Arcadia question bank

Decisions to settle, by branch of the design tree. Ask only the branches the request reaches, and
only what the model does not already answer. Each branch opens once the one above it is settled.

## Need (OA)

- Who are the stakeholders and operational entities, and which matter most?
- What operational capabilities must they achieve, and in what situations (missions, phases)?
- Which operational activities and interactions realise each capability?
- What does failure look like for each stakeholder?

## Boundary (SA)

- What is inside the system and what is an external actor?
- Which capabilities does the system support, and which stay with the actors?
- What does the system exchange with each actor (data, energy, material, commands)?
- Which modes and states does the system have, and what triggers each transition?
- Which constraints are given: regulations, rules, budgets, interfaces fixed by others?

## Behaviour (SA functions)

- Which top-level system functions realise each capability, and how deep should they decompose?
- Which functional chains carry the critical paths (safety, performance, timing)?
- Which scenarios must the model show: nominal, degraded, failure?
- Which non-functional requirements attach to which function or chain?

## Logical (LA)

- What decomposition principle drives the logical components: function, safety segregation,
  supplier, reuse?
- Which functions must stay apart (independence, redundancy) or together (latency, cohesion)?
- Which component exchanges and interfaces are needed, and which exchange items do they carry?
- Which alternatives are worth comparing, and by which criteria?

## Physical (PA)

- Which technologies or equipment are already chosen, and which stay open (TBD)?
- How do behaviour components deploy onto node components?
- Which physical links carry which component exchanges?
- Which budgets apply per node: mass, power, cost, thermal?
