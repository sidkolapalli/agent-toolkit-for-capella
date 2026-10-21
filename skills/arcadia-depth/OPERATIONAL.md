# Operational roles and communication means

**Rule.** In OA a **role** is a coherent set of operational activities that one or more entities or
actors play as a whole; the role owns an activity allocation per activity, and each entity that plays
it owns a role allocation. Interactions between activities of two different entities travel on a
**communication means** between those entities; the communication means carries each interaction.
`role`, `activity` and `entity` on these allocations are read-only: set `sourceElement` and
`targetElement`.

```groovy
def role = api.create('Role', 'Inspection presenter', api.layer('OA').ownedRolePkg)
activities.each { a -> def aa = api.create('ActivityAllocation', null, role); api.set(aa, 'sourceElement', role); api.set(aa, 'targetElement', a) }
def ra = api.create('RoleAllocation', null, entity); api.set(ra, 'sourceElement', entity); api.set(ra, 'targetElement', role)

def cm = api.link(entityA, entityB, 'CommunicationMean')
interactions.each { i -> def c = api.create('ComponentExchangeFunctionalExchangeAllocation', null, cm); api.set(c, 'sourceElement', cm); api.set(c, 'targetElement', i) }
```

**Check.** `role.activityAllocations*.activity` is exactly the role's activities and the entity's
`allocatedRoles` holds the role; every interaction between the two entities' activities is in some
communication means' `allocatedFunctionalExchanges`.
