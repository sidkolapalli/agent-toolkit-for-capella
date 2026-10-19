assert session.sessionResource.URI.toString().toLowerCase().contains('capella-ai-native-integration')
def source = api.find('LogicalFunction', 'AI Native Probe LA Acquire')
assert source != null
assert api.rename(source, 'AI Native Probe LA Acquire renamed')
return ([phase: 'rename', id: api.id(source), name: source.name])
