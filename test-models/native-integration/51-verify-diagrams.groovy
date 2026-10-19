import org.eclipse.sirius.business.api.query.DRepresentationQuery
assert session.sessionResource.URI.toString().toLowerCase().contains('capella-ai-native-integration')
def diagrams = api.diagram.list().findAll { it.name?.startsWith('AI Native Probe ') }
assert !diagrams.isEmpty()
def rows = diagrams.collect { diagram ->
    def descriptor = new DRepresentationQuery(diagram).representationDescriptor
    assert descriptor != null && descriptor.name == diagram.name
    assert diagram.target != null && diagram.target.eResource() != null && !diagram.target.eIsProxy()
    diagram.ownedDiagramElements.each { element ->
        assert element.target != null && element.target.eResource() != null && !element.target.eIsProxy()
    }
    [uid: diagram.uid, name: diagram.name, descriptor: diagram.description.name,
     target: api.id(diagram.target), views: diagram.ownedDiagramElements.size()]
}
return ([phase: 'verify-diagrams', session: session.sessionResource.URI.toString(), diagrams: rows])
