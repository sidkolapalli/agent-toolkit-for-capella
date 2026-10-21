# Exchange categories

**Rule.** A category groups exchanges that belong together for the reader (all trips, all power
flows) so diagrams can filter and collapse them; it changes no semantics. An exchange category for
functional exchanges lives in the layer's function package.

```groovy
def cat = api.create('ExchangeCategory', 'Shutdown trips', api.layer('LA').containedLogicalFunctionPkg)
api.set(cat, 'exchanges', exchanges)       // the full list; each exchange then lists it in `categories`
```

**Check.** The category holds exactly the exchanges the task describes.
