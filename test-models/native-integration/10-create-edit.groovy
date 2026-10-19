
assert session.sessionResource.URI.toString().toLowerCase().contains('capella-ai-native-integration')
def prefix = 'AI Native Probe '
assert api.find('LogicalFunction', prefix + 'LA Acquire') == null: 'Fixture already exists; verify or delete it instead of rerunning create'
def layers = ['OA', 'SA', 'LA', 'PA']
def functionTypes = ['OperationalActivity', 'SystemFunction', 'LogicalFunction', 'PhysicalFunction']
def componentTypes = ['Entity', 'SystemComponent', 'LogicalComponent', 'PhysicalComponent']
nativeIntegrationState = [prefix: prefix, session: session.sessionResource.URI.toString(), layers: [:], traces: [], diagrams: [:]]

api.batch('Native five-perspective integration fixture') {
    layers.eachWithIndex { layer, index ->
        def architecture = api.layer(layer)
        def functionRoot = api.container(layer, 'root-function')
        def parent = layer in ['OA', 'SA'] ? api.container(layer, 'component') : architecture.system
        def source = api.create(layer, functionTypes[index], prefix + layer + ' Acquire')
        def target = api.create(layer, functionTypes[index], prefix + layer + ' Process')
        assert source.eContainer() == functionRoot
        assert target.eContainer() == functionRoot
        assert source.eContainingFeature().name == 'ownedFunctions'
        def producer = layer == 'SA' ? api.create('SA', 'SystemActor', prefix + layer + ' Producer') :
            api.create(componentTypes[index], prefix + layer + ' Producer', parent)
        def consumer = layer == 'SA' ? api.create('SA', 'SystemActor', prefix + layer + ' Consumer') :
            api.create(componentTypes[index], prefix + layer + ' Consumer', parent)
        assert !['ownedMigratedElements', 'ownedExtensions'].contains(producer.eContainingFeature().name)
        assert api.set(producer, 'summary', 'Native create/edit/persist integration test')
        if (layer == 'PA') {
            assert api.set(producer, 'nature', PhysicalComponentNature.BEHAVIOR)
            assert api.set(consumer, 'nature', PhysicalComponentNature.BEHAVIOR)
            assert api.set(producer, 'kind', PhysicalComponentKind.SOFTWARE_EXECUTION_UNIT)
            assert api.set(consumer, 'kind', PhysicalComponentKind.SOFTWARE_EXECUTION_UNIT)
        }
        assert api.allocate(source, producer)
        assert api.allocate(source, producer)
        assert api.allocate(target, consumer)
        assert producer.ownedFunctionalAllocation.size() == 1
        def exchange = api.link(source, target, 'FunctionalExchange')
        assert api.rename(exchange, prefix + layer + ' Data')
        def componentExchange = api.link(producer, consumer, 'ComponentExchange')
        assert api.rename(componentExchange, prefix + layer + ' Transport')
        if (layer == 'OA') {
            assert exchange.source == source && exchange.target == target
            assert componentExchange instanceof CommunicationMean
            assert componentExchange.source == producer && componentExchange.target == consumer
        } else {
            assert exchange.source instanceof FunctionOutputPort
            assert exchange.target instanceof FunctionInputPort
            assert exchange.source.eContainer() == source && exchange.target.eContainer() == target
            assert componentExchange.source instanceof ComponentPort
            assert componentExchange.target instanceof ComponentPort
            assert componentExchange.source.eContainer() == producer
            assert componentExchange.target.eContainer() == consumer
            def producerPart = api.ensurePart(producer, parent)
            def consumerPart = api.ensurePart(consumer, parent)
            assert producerPart != null && consumerPart != null
            assert ['ownedFeatures', 'ownedParts'].contains(producerPart.eContainingFeature().name)
            assert producerPart.abstractType == producer
            assert api.ensurePart(producer, parent) == producerPart
        }
        nativeIntegrationState.layers[layer] = [source: api.id(source), target: api.id(target),
            producer: api.id(producer), consumer: api.id(consumer), exchange: api.id(exchange),
            componentExchange: api.id(componentExchange), functionType: functionTypes[index],
            componentType: componentTypes[index]]
        log.info('Created, edited, allocated and linked ' + layer)
    }
    for (int index = 1; index < layers.size(); index++) {
        def lower = nativeIntegrationState.layers[layers[index]]
        def higher = nativeIntegrationState.layers[layers[index - 1]]
        nativeIntegrationState.traces << api.id(api.link(api.findById(lower.source), api.findById(higher.source), 'realization'))
        nativeIntegrationState.traces << api.id(api.link(api.findById(lower.producer), api.findById(higher.producer), 'realization'))
    }
    def physicalRoot = api.layer('PA').system
    def node = api.create('PhysicalComponent', prefix + 'PA Computer node', physicalRoot)
    assert api.set(node, 'nature', PhysicalComponentNature.NODE)
    assert api.set(node, 'kind', PhysicalComponentKind.HARDWARE_COMPUTER)
    assert api.ensurePart(node, physicalRoot) != null
    def ciRoot = api.epbs().system
    def product = api.create('ConfigurationItem', prefix + 'EPBS Product', ciRoot)
    def software = api.create('ConfigurationItem', prefix + 'EPBS Software', product)
    def hardwarePkg = api.create('ConfigurationItemPkg', prefix + 'EPBS Equipment', product)
    def hardware = api.create('ConfigurationItem', prefix + 'EPBS Hardware', hardwarePkg)
    assert api.set(product, 'kind', ConfigurationItemKind.PRIME_ITEM_CI)
    assert api.set(software, 'kind', ConfigurationItemKind.CSCI)
    assert api.set(software, 'itemIdentifier', 'AI-NATIVE-SW-001')
    assert api.set(hardware, 'kind', ConfigurationItemKind.HWCI)
    assert api.set(hardware, 'itemIdentifier', 'AI-NATIVE-HW-001')
    def softwareTrace = api.link(software, api.findById(nativeIntegrationState.layers.PA.producer), 'PhysicalArtifactRealization')
    def hardwareTrace = api.link(hardware, node, 'realization')
    assert softwareTrace.sourceElement == software
    assert softwareTrace.targetElement == api.findById(nativeIntegrationState.layers.PA.producer)
    assert hardwareTrace.sourceElement == hardware && hardwareTrace.targetElement == node
    nativeIntegrationState.epbs = [product: api.id(product), software: api.id(software), hardware: api.id(hardware),
        node: api.id(node), softwareTrace: api.id(softwareTrace), hardwareTrace: api.id(hardwareTrace)]
    assert api.ensurePart(product, ciRoot) != null
    assert api.ensurePart(software, product) != null
    assert api.ensurePart(hardware, product) != null
    log.info('Created and edited EPBS CI hierarchy with native PA artifact realizations')
}
return ([phase: 'create-edit', state: nativeIntegrationState])
