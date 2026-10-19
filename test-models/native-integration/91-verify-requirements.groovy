assert session.sessionResource.URI.toString().toLowerCase().contains('capella-ai-native-integration')
def req = api.requirements()
assert req.available() && req.viewpointEnabled()
def results = []
['OA','SA','LA','PA','EPBS'].each { layer ->
    def prefix = 'AI-NATIVE-REQ:' + layer + ':'
    def module = req.find(prefix+'module')
    def source = req.find(prefix+'source')
    def derived = req.find(prefix+'derived')
    assert module != null && source != null && derived != null
    assert module.eContainer() == api.layer(layer)
    assert module.eContainingFeature().name == 'ownedExtensions'
    assert source.eContainer().ReqIFIdentifier == prefix+'folder'
    assert source.ReqIFLongName == layer+' Edited native requirement'
    assert source.ReqIFText == 'Edited short source paraphrase'
    assert req.attributes(source).reviewed == true
    assert req.attributes(source).coverage_status == 'not_modeled'
    assert req.attributes(source).verification_status == 'not_verified'
    assert req.attributes(source).source_page == 1
    assert source.requirementType.eResource() != null
    def incoming = source.ownedRelations.find { it.eClass().name == 'CapellaIncomingRelation' }
    assert incoming != null && incoming.source == source && incoming.target.eResource() != null
    assert incoming.relationType.ReqIFLongName == 'verify'
    def internal = derived.ownedRelations.find { it.eClass().name == 'InternalRelation' }
    assert internal != null && internal.source == derived && internal.target == source
    def ownerType = [OA:'Entity',SA:'SystemComponent',LA:'LogicalComponent',PA:'PhysicalComponent',EPBS:'ConfigurationItem'][layer]
    def owner = api.find(ownerType,'AI Native Requirement '+layer+' Owner')
    assert owner != null
    def outgoing = owner.ownedExtensions.find { it.eClass().name == 'CapellaOutgoingRelation' && it.target == source }
    assert outgoing != null && outgoing.source == owner
    assert outgoing.relationType.ReqIFLongName == 'satisfy'
    [incoming,internal,outgoing].each { relation ->
        assert !relation.source.eIsProxy() && !relation.target.eIsProxy()
        assert relation.source.eResource() != null && relation.target.eResource() != null
    }
    results << [layer:layer,module:api.id(module),source:api.id(source),source_identifier:prefix+'source',relations:3]
}
return [phase:'requirements-verify',layers:results]
