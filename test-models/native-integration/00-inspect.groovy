
def marker = 'capella-ai-native-integration'
def uri = session.sessionResource.URI.toString()
assert uri.toLowerCase().contains(marker): 'Open only the disposable native integration project: ' + uri
def rows = ['OA', 'SA', 'LA', 'PA', 'EPBS'].collect { layer ->
    def architecture = api.layer(layer)
    assert architecture != null: 'Missing native architecture ' + layer
    def componentPkg = api.container(layer, 'component')
    assert componentPkg != null: 'Missing native component package ' + layer
    def function = layer == 'EPBS' ? null : api.container(layer, 'root-function')
    assert layer == 'EPBS' || function != null: 'Missing native root function ' + layer
    def system = layer == 'OA' ? null : architecture.system
    assert layer == 'OA' || system != null: 'Missing native root component ' + layer
    [layer: layer, architecture: architecture.eClass().name,
     rootFunction: function?.name, rootComponent: system?.name,
     componentPackage: componentPkg.eClass().name,
     diagramDescriptors: api.diagram.listAvailable(layer)]
}
log.info('Native disposable model verified: ' + uri)
return ([phase: 'inspect', session: uri, layers: rows])
