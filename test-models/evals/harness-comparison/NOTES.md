# Working on this Capella model with capellambse

- The project is `FSAE-2027-Electric-Arcadia/`: `FSAE-2027-Electric-Arcadia.aird` (entry point and Sirius
  diagrams) and `FSAE-2027-Electric-Arcadia.capella` (the semantic Arcadia model, Capella 7.0). It uses the
  Requirements Viewpoint (`org.polarsys.capella.vp.requirements`).
- Python with capellambse 0.8.1 installed: `PYTHON` below. Its source is in `<venv>/Lib/site-packages/capellambse/`;
  read it when you need an API detail.

```python
import capellambse
m = capellambse.MelodyModel("FSAE-2027-Electric-Arcadia/FSAE-2027-Electric-Arcadia.aird")
m.oa, m.sa, m.la, m.pa, m.epbs                # Arcadia layers
m.la.all_functions, m.la.all_components, m.la.all_functional_chains, m.la.all_requirements
m.search("LogicalComponent")                  # every element of a type
m.by_uuid("...")                              # one element by id
m.la.all_components.by_name("...")            # by name
f = m.la.root_function.functions.create(name="New function")   # ElementList.create(typehint, **attrs)
m.save()                                      # writes the .capella/.aird files in place
```

- Requirements and their trace relations: `capellambse.extensions.reqif` (`Requirement`,
  `CapellaOutgoingRelation`, `requirements_relations` on elements).
- The saved files are opened in Capella 7.0 afterwards and checked there, so keep them loadable.
