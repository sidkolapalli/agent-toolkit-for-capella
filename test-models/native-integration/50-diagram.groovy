import org.eclipse.sirius.business.api.query.DRepresentationQuery
import org.eclipse.sirius.viewpoint.DSemanticDecorator

assert session.sessionResource.URI.toString().toLowerCase().contains('capella-ai-native-integration')
def layer = '__LAYER__'
def kind = '__KIND__'
assert ['OA', 'SA', 'LA', 'PA', 'EPBS'].contains(layer)
assert ['architecture', 'flow'].contains(kind)
assert layer != 'EPBS' || kind != 'flow': 'EPBS has no functional data-flow diagram'
def prefix = 'AI Native Probe '
def descriptor = kind == 'flow' ? [OA:'OAIB', SA:'SDFB', LA:'LDFB', PA:'PDFB'][layer] :
    [OA:'OAB', SA:'SAB', LA:'LAB', PA:'PAB', EPBS:'EPBS Architecture Blank'][layer]
def root = kind == 'flow' ? api.container(layer, 'root-function') :
    (layer == 'OA' ? api.container(layer, 'component') : api.layer(layer).system)
assert root != null
def title = prefix + layer + ' ' + kind + ' diagram'
assert api.diagram.list().every { it.name != title && it.name != title + ' renamed' }
def diagram = api.diagram.createSafe(descriptor, title, root)
assert diagram != null
assert diagram.target == root: 'The native semantic diagram target must preserve the requested root'
def displayed = []
def elements
if (layer == 'EPBS') {
    elements = ['Product', 'Software', 'Hardware'].collect { api.find('ConfigurationItem', prefix + 'EPBS ' + it) }
} else if (kind == 'flow') {
    def type = [OA:'OperationalActivity', SA:'SystemFunction', LA:'LogicalFunction', PA:'PhysicalFunction'][layer]
    elements = [api.find(type, prefix + layer + ' Acquire'), api.find(type, prefix + layer + ' Process')]
} else {
    def type = [OA:'Entity', SA:'SystemComponent', LA:'LogicalComponent', PA:'PhysicalComponent'][layer]
    elements = [api.find(type, prefix + layer + ' Producer'), api.find(type, prefix + layer + ' Consumer')]
}
assert elements.every { it != null }: 'Create fixture before running diagram tests'
elements.each { element ->
    def target = kind == 'flow' || layer == 'OA' ? element : api.ensurePart(element, root)
    assert target != null
    def existing = diagram.eAllContents().any { it instanceof DSemanticDecorator && it.target == target }
    if (!existing) assert api.diagram.add(diagram, target): 'Failed to display ' + element.name
    displayed << [element: api.id(element), target: api.id(target)]
}
api.diagram.refresh(diagram)
assert api.diagram.layout(diagram, 'grid')
assert api.diagram.rename(diagram, title + ' renamed')
def representationDescriptor = new DRepresentationQuery(diagram).representationDescriptor
assert representationDescriptor != null
assert representationDescriptor.name == title + ' renamed': 'Native descriptor name must match the visible diagram title'
assert diagram.name == title + ' renamed'
def viewTarget = kind == 'flow' || layer == 'OA' ? elements[0] : api.ensurePart(elements[0], root)
assert api.diagram.remove(diagram, viewTarget)
assert api.findById(api.id(elements[0])) == elements[0]: 'Removing a diagram view must preserve its semantic element'
assert api.diagram.add(diagram, viewTarget)
api.diagram.refresh(diagram)
return ([phase: 'diagram', layer: layer, kind: kind, uid: diagram.uid,
    name: diagram.name, descriptor: diagram.description.name, descriptorName: representationDescriptor.name,
    target: api.id(diagram.target), displayed: displayed,
    diagramElements: diagram.ownedDiagramElements.size()])
