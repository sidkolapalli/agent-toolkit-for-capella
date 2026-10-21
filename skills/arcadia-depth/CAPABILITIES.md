# Capability include, extend and generalization

**Rule.** Capabilities relate like use cases, and the direction is the method:
- **include**: the base capability always performs the included one. The base owns the include.
- **extend**: the extending capability optionally adds behaviour to a base. The extending one owns the
  extend; `extended` is the base.
- **generalization**: the specific capability is a kind of the general one. The specific one owns the
  generalization; `super` is the general one.

Use these relations, not involvements, realizations or traces, to say how capabilities depend on each
other.

```groovy
def inc = api.create('AbstractCapabilityInclude', null, base); api.set(inc, 'included', included)
def ext = api.create('AbstractCapabilityExtend', null, extending); api.set(ext, 'extended', base)
def gen = api.create('AbstractCapabilityGeneralization', null, specific); api.set(gen, 'super', general)
```

**Check.** `base.includedAbstractCapabilities`, `extending.extendedAbstractCapabilities` and
`specific.super` name the intended capabilities, and no relation runs the other way.
