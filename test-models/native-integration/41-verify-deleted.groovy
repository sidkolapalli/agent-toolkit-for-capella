import org.eclipse.emf.ecore.util.EcoreUtil
assert session.sessionResource.URI.toString().toLowerCase().contains('capella-ai-native-integration')
def prefix = 'AI Native Probe '
['OA':'OperationalActivity', 'SA':'SystemFunction', 'LA':'LogicalFunction', 'PA':'PhysicalFunction'].each { layer, type ->
    assert api.find(type, prefix + layer + ' Acquire') == null
    assert api.find(type, prefix + layer + ' Process') != null
    assert api.find('FunctionalExchange', prefix + layer + ' Data') == null
}
assert api.find('LogicalFunction', prefix + 'LA Acquire renamed') == null
['OA':'Entity', 'SA':'SystemComponent', 'LA':'LogicalComponent', 'PA':'PhysicalComponent'].each { layer, type ->
    assert api.find(type, prefix + layer + ' Consumer') == null
    assert api.find(type, prefix + layer + ' Producer') != null
    assert api.find(layer == 'OA' ? 'CommunicationMean' : 'ComponentExchange', prefix + layer + ' Transport') == null
}
assert api.find('ConfigurationItem', prefix + 'EPBS Software') == null
assert api.find('ConfigurationItem', prefix + 'EPBS Hardware') != null
def dangling = []
session.semanticResources.each { resource ->
    resource.allContents.each { owner ->
        owner.eClass().EAllReferences.findAll { !it.containment && !it.derived && !it.transient && it.changeable }.each { reference ->
            def current = owner.eGet(reference, true)
            (reference.many ? current : [current]).findAll { it instanceof EObject }.each { target ->
                if (target.eIsProxy() || target.eResource() == null) dangling << EcoreUtil.getURI(owner).toString() + ':' + reference.name
            }
        }
    }
}
assert dangling.isEmpty(): dangling
return ([phase: 'verify-deleted', session: session.sessionResource.URI.toString(), danglingReferences: dangling,
    preservedLayers: ['OA', 'SA', 'LA', 'PA', 'EPBS'].collect { api.layer(it).eClass().name }])
