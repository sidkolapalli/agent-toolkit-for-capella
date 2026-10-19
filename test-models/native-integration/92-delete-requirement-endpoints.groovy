assert session.sessionResource.URI.toString().toLowerCase().contains('capella-ai-native-integration')
def req = api.requirements()
def source = req.find('AI-NATIVE-REQ:OA:source')
def owner = api.find('SystemComponent','AI Native Requirement SA Owner')
def verification = api.find('Constraint','AI Native Requirement LA Verification obligation')
assert source != null && owner != null && verification != null
def removed = [source:api.id(source),owner:api.id(owner),verification:api.id(verification)]
api.batch('Delete native requirement design and verification endpoints') {
    assert req.delete(source)
    assert api.delete(owner)
    assert api.delete(verification)
}
return [phase:'requirements-delete-endpoints',removed:removed]
