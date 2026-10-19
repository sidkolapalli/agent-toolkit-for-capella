assert session.sessionResource.URI.toString().toLowerCase().contains('capella-ai-native-integration')
def prefix = 'AI Native Probe '
def layers = ['OA', 'SA', 'LA', 'PA']
def functionTypes = ['OperationalActivity', 'SystemFunction', 'LogicalFunction', 'PhysicalFunction']
def componentTypes = ['Entity', 'SystemComponent', 'LogicalComponent', 'PhysicalComponent']
def deleted = []
api.batch('Native integration delete dependent structure') {
    layers.eachWithIndex { layer, index ->
        def source = api.find(functionTypes[index], prefix + layer + ' Acquire')
        if (source == null && layer == 'LA') source = api.find(functionTypes[index], prefix + 'LA Acquire renamed')
        def consumer = api.find(componentTypes[index], prefix + layer + ' Consumer')
        def exchange = api.find('FunctionalExchange', prefix + layer + ' Data')
        def componentExchange = api.find(layer == 'OA' ? 'CommunicationMean' : 'ComponentExchange', prefix + layer + ' Transport')
        assert source != null && consumer != null && exchange != null && componentExchange != null
        def ids = [api.id(source), api.id(consumer), api.id(exchange), api.id(componentExchange)]
        assert api.delete(source)
        assert api.delete(consumer)
        assert ids.every { api.findById(it) == null }: 'Dependent references/exchanges remain after ' + layer + ' deletion'
        def producer = api.find(componentTypes[index], prefix + layer + ' Producer')
        assert producer != null && producer.ownedFunctionalAllocation.isEmpty()
        deleted.addAll(ids)
        log.info('Deleted function/component and dependent relationships in ' + layer)
    }
    def software = api.find('ConfigurationItem', prefix + 'EPBS Software')
    assert software != null
    def ids = [api.id(software)] + software.ownedPhysicalArtifactRealizations.collect { api.id(it) }
    assert api.delete(software)
    assert ids.every { api.findById(it) == null }
    deleted.addAll(ids)
    assert api.find('PhysicalComponent', prefix + 'PA Producer') != null
    assert api.find('ConfigurationItem', prefix + 'EPBS Hardware') != null
}
return ([phase: 'delete', removed: deleted])
