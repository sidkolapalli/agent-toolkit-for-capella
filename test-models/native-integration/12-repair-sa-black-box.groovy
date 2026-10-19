import org.polarsys.capella.core.model.helpers.ComponentExchangeExt

assert session.sessionResource.URI.toString().toLowerCase().contains('capella-ai-native-integration')
def prefix = 'AI Native Probe '
def architecture = api.layer('SA')
def system = architecture.system
def componentPackage = api.container('SA', 'component')
def components = ['Producer', 'Consumer'].collect { api.find('SystemComponent', prefix + 'SA ' + it) }
assert components.every { it != null }: 'The original marked fixture must exist before repair'
def repaired = components.every { it.eContainer() == componentPackage && it.actor }
assert repaired || components.every { it.eContainer() == system && !it.actor }:
    'Repair supports only the original fixture or its already repaired state'
def parts = components.collect { component ->
    def represented = component.representingParts.findAll { it.abstractType == component }
    assert represented.size() == 1: 'Every fixture actor must have exactly one native Part'
    represented[0]
}
assert parts.every { repaired ? it.eContainer() == componentPackage : it.eContainer() == system }
def exchange = api.find('ComponentExchange', prefix + 'SA Transport')
assert exchange != null

if (!repaired) {
    api.batch('Repair native SA fixture to preserve the system black box') {
        components.eachWithIndex { component, index ->
            componentPackage.ownedSystemComponents.add(component)
            componentPackage.ownedParts.add(parts[index])
            assert api.set(component, 'actor', true)
        }
        assert ComponentExchangeExt.attachTo(exchange, componentPackage)
        api.diagram.list().findAll { it.name?.startsWith(prefix + 'SA ') }.each { api.diagram.refresh(it) }
    }
}
assert components.every { it.actor && it.eContainer() == componentPackage }
assert parts.every { it.eContainer() == componentPackage && it.eContainingFeature().name == 'ownedParts' }
assert system.ownedSystemComponents.isEmpty()
assert system.ownedFeatures.findAll { it instanceof Part }.isEmpty()
log.info('SA fixture uses two native actors and their Parts in SystemComponentPkg; the System remains a black box')
return [phase: 'repair-sa', changed: !repaired, actors: components.collect { api.id(it) },
    parts: parts.collect { api.id(it) }, exchange: api.id(exchange)]
