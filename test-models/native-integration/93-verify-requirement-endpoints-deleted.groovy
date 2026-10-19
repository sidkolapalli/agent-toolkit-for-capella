assert session.sessionResource.URI.toString().toLowerCase().contains('capella-ai-native-integration')
def req = api.requirements()
assert req.find('AI-NATIVE-REQ:OA:source') == null
assert api.find('SystemComponent','AI Native Requirement SA Owner') == null
assert api.find('Constraint','AI Native Requirement LA Verification obligation') == null
def oaDerived = req.find('AI-NATIVE-REQ:OA:derived')
def oaOwner = api.find('Entity','AI Native Requirement OA Owner')
def saSource = req.find('AI-NATIVE-REQ:SA:source')
def laSource = req.find('AI-NATIVE-REQ:LA:source')
assert oaDerived != null && oaOwner != null && saSource != null && laSource != null
assert !oaDerived.ownedRelations.any { it.eClass().name == 'InternalRelation' }
assert !oaOwner.ownedExtensions.any { it.eClass().name == 'CapellaOutgoingRelation' }
assert !laSource.ownedRelations.any { it.eClass().name == 'CapellaIncomingRelation' }
assert req.find('AI-NATIVE-REQ:PA:source') != null && req.find('AI-NATIVE-REQ:EPBS:source') != null
def relationCount = 0
session.semanticResources.each { resource ->
    def contents = resource.allContents
    while (contents.hasNext()) {
        def object = contents.next()
        if (object.eClass().name in ['CapellaIncomingRelation','CapellaOutgoingRelation','InternalRelation']) {
            relationCount++
            assert object.source != null && object.target != null
            assert !object.source.eIsProxy() && !object.target.eIsProxy()
            assert object.source.eResource() != null && object.target.eResource() != null
        }
    }
}
return [phase:'requirements-deleted-verify',remaining_native_relations:relationCount]
