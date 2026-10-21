# Property values

**Rule.** Non-functional data about an element (safety class, reaction time, mass budget) is a typed
**property value** in a named **group** owned by that element, not prose in its description. Pick the
type of the value: `BooleanPropertyValue`, `IntegerPropertyValue`, `FloatPropertyValue`,
`StringPropertyValue`, `EnumerationPropertyValue`. Put the unit in the value's name when it has one.

```groovy
def g = api.create('PropertyValueGroup', 'Safety', component)
def p = api.create('BooleanPropertyValue', 'Programmable', g); api.set(p, 'value', false)
def r = api.create('IntegerPropertyValue', 'Reaction time ms', g); api.set(r, 'value', 500)
```

**Check.** Each element the task names owns the group, and each value has the right type and value.
