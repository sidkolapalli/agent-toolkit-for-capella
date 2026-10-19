import org.eclipse.sirius.business.api.query.DRepresentationQuery
import org.eclipse.sirius.business.api.dialect.DialectManager
assert session.sessionResource.URI.toString().toLowerCase().contains('capella-ai-native-integration')
def name = 'AI Native Probe LA architecture diagram renamed'
def diagram = api.diagram.find(name)
assert diagram != null
def descriptor = new DRepresentationQuery(diagram).representationDescriptor
assert descriptor != null
def producer = api.find('LogicalComponent', 'AI Native Probe LA Producer')
def consumer = api.find('LogicalComponent', 'AI Native Probe LA Consumer')
assert producer != null && consumer != null
def uid = diagram.uid
assert api.diagram.delete(diagram)
assert api.diagram.list().every { it.name != name }
assert !DialectManager.INSTANCE.getAllRepresentationDescriptors(session).contains(descriptor)
assert api.findById(api.id(producer)) == producer && api.findById(api.id(consumer)) == consumer
return ([phase: 'delete-diagram', deleted: uid, semanticElementsPreserved: true])
