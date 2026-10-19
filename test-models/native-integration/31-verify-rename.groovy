assert session.sessionResource.URI.toString().toLowerCase().contains('capella-ai-native-integration')
def original = api.find('LogicalFunction', 'AI Native Probe LA Acquire')
def renamed = api.find('LogicalFunction', 'AI Native Probe LA Acquire renamed')
assert (original == null) != (renamed == null): 'Exactly one name must exist after native rename undo/redo'
return ([phase: 'verify-rename', name: (original ?: renamed).name, id: api.id(original ?: renamed)])
