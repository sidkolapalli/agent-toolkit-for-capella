import org.eclipse.emf.ecore.util.EcoreUtil

assert session.sessionResource.URI.toString().toLowerCase().contains('capella-ai-native-integration')
def prefix = 'AI Native Probe '
def layers = ['OA', 'SA', 'LA', 'PA']
def functionTypes = ['OperationalActivity', 'SystemFunction', 'LogicalFunction', 'PhysicalFunction']
def componentTypes = ['Entity', 'SystemComponent', 'LogicalComponent', 'PhysicalComponent']
def rows = []
layers.eachWithIndex { layer, index ->
    def source = api.find(functionTypes[index], prefix + layer + ' Acquire')
    if (source == null && layer == 'LA') source = api.find(functionTypes[index], prefix + 'LA Acquire renamed')
    def target = api.find(functionTypes[index], prefix + layer + ' Process')
    def producer = api.find(componentTypes[index], prefix + layer + ' Producer')
    def consumer = api.find(componentTypes[index], prefix + layer + ' Consumer')
    def exchange = api.find('FunctionalExchange', prefix + layer + ' Data')
    def componentExchange = api.find(layer == 'OA' ? 'CommunicationMean' : 'ComponentExchange', prefix + layer + ' Transport')
    assert [source, target, producer, consumer, exchange, componentExchange].every { it != null && it.eResource() != null }
    assert producer.summary == 'Native create/edit/persist integration test'
    assert producer.ownedFunctionalAllocation.size() == 1
    assert producer.ownedFunctionalAllocation[0].targetElement == source
    assert producer.allocatedFunctions.contains(source): 'Native allocation view must resolve the stored function'
    if (layer == 'OA') {
        assert exchange.source == source && exchange.target == target
        assert componentExchange.source == producer && componentExchange.target == consumer
    } else {
        assert exchange.source.eContainer() == source && exchange.target.eContainer() == target
        assert componentExchange.source.eContainer() == producer && componentExchange.target.eContainer() == consumer
        def part = producer.representingParts.find { it.abstractType == producer }
        assert part != null && part.abstractType == producer
        assert ['ownedParts', 'ownedFeatures'].contains(part.eContainingFeature().name)
    }
    if (layer == 'PA') {
        assert producer.nature == PhysicalComponentNature.BEHAVIOR
        assert producer.kind == PhysicalComponentKind.SOFTWARE_EXECUTION_UNIT
    }
    rows << [layer: layer, function: api.id(source), component: api.id(producer),
        exchange: api.id(exchange), componentExchange: api.id(componentExchange), nativeAllocationView: true]
}
def software = api.find('ConfigurationItem', prefix + 'EPBS Software')
def hardware = api.find('ConfigurationItem', prefix + 'EPBS Hardware')
def node = api.find('PhysicalComponent', prefix + 'PA Computer node')
assert software != null && hardware != null && node != null
assert software.itemIdentifier == 'AI-NATIVE-SW-001'
assert hardware.itemIdentifier == 'AI-NATIVE-HW-001'
assert software.kind == ConfigurationItemKind.CSCI && hardware.kind == ConfigurationItemKind.HWCI
assert software.ownedPhysicalArtifactRealizations.size() == 1
assert hardware.ownedPhysicalArtifactRealizations.size() == 1
def trace = software.ownedPhysicalArtifactRealizations[0]
assert trace.realizingConfigurationItem == software
assert trace.realizedPhysicalArtifact == api.find('PhysicalComponent', prefix + 'PA Producer')
assert software.allocatedPhysicalArtifacts.contains(trace.targetElement)
assert hardware.allocatedPhysicalArtifacts.contains(node)
def invalid = []
session.semanticResources.each { resource ->
    resource.allContents.each { element ->
        element.eClass().EAllReferences.findAll { !it.containment && !it.derived && !it.transient && it.changeable }.each { reference ->
            def value = element.eGet(reference, true)
            def values = reference.many ? value : [value]
            values.findAll { it instanceof EObject }.each { referenced ->
                if (referenced.eIsProxy() || referenced.eResource() == null) {
                    invalid << [owner: EcoreUtil.getURI(element).toString(), feature: reference.name]
                }
            }
        }
    }
}
assert invalid.isEmpty(): 'Dangling/unresolved stored references: ' + invalid
return ([phase: 'verify', session: session.sessionResource.URI.toString(), layers: rows,
    epbs: [software: api.id(software), hardware: api.id(hardware), nativeArtifactViews: true],
    storedReferencesValid: true, validation: api.validate()])
