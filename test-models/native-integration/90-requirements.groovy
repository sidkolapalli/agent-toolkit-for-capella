assert session.sessionResource.URI.toString().toLowerCase().contains('capella-ai-native-integration')
def req = api.requirements()
assert req.available() && req.viewpointEnabled(): 'Enable the native Requirements viewpoint before executing this phase'
assert req.find('AI-NATIVE-REQ:OA:source') == null: 'Requirements fixture already exists; use verification or deletion phases'
nativeRequirementsState = [session:session.sessionResource.URI.toString(), layers:[:]]
def layers = ['OA','SA','LA','PA','EPBS']
def componentTypes = ['Entity','SystemActor','LogicalComponent','PhysicalComponent','ConfigurationItem']
api.batch('Native ReqIF workflows in all five perspectives') {
    layers.eachWithIndex { layer,index ->
        def prefix = 'AI-NATIVE-REQ:' + layer + ':'
        def module = req.createModule(api.layer(layer), prefix + 'module', layer + ' Native requirements module')
        def folder = req.createFolder(module, prefix + 'folder', 'Source requirements')
        def source = req.create(folder, prefix + 'source', layer + ' Source requirement', 'Short authored source paraphrase')
        def derived = req.create(folder, prefix + 'derived', layer + ' Derived requirement', 'Short authored derived requirement')
        req.setAttribute(source,'source_document_id','native-integration-fixture')
        req.setAttribute(source,'source_rule_id','TEST.' + layer)
        req.setAttribute(source,'source_page',1)
        req.setAttribute(source,'reviewed',false)
        req.setAttribute(source,'coverage_status','not_modeled')
        req.setAttribute(source,'verification_status','not_verified')
        req.setAttribute(source,'reference_value',0.5d)
        def parent = layer in ['OA','SA'] ? api.container(layer,'component') : api.layer(layer).system
        def owner = layer == 'SA' ? api.create('SA','SystemActor','AI Native Requirement SA Owner') :
            api.create(componentTypes[index], 'AI Native Requirement ' + layer + ' Owner', parent)
        if (layer == 'PA') api.set(owner,'nature',PhysicalComponentNature.BEHAVIOR)
        def constraint = api.create('Constraint','AI Native Requirement ' + layer + ' Verification obligation',api.layer(layer))
        api.set(constraint,'summary','An unexecuted verification obligation, with no claim that the requirement passed')
        def expression = org.polarsys.capella.core.data.information.datavalue.DatavalueFactory.eINSTANCE.createOpaqueExpression()
        expression.languages.add('Text')
        expression.bodies.add('Verify the fixture requirement and record evidence. This obligation has not been executed.')
        constraint.ownedSpecification = expression
        def satisfy = req.satisfy(owner,source)
        def verify = req.verify(source,constraint)
        def derive = req.derive(derived,source)
        assert req.satisfy(owner,source) == satisfy
        assert req.verify(source,constraint) == verify
        assert req.derive(derived,source) == derive
        assert satisfy.source == owner && satisfy.target == source && satisfy.eContainer() == owner
        assert verify.source == source && verify.target == constraint && verify.eContainer() == source
        assert derive.source == derived && derive.target == source && derive.eContainer() == derived
        req.edit(source, layer + ' Edited native requirement','Edited short source paraphrase')
        req.setAttribute(source,'reviewed',true)
        assert req.attributes(source).reviewed == true
        assert req.requirements(module).size() == 2
        nativeRequirementsState.layers[layer] = [module:api.id(module),folder:api.id(folder),source:api.id(source),
            derived:api.id(derived),owner:api.id(owner),constraint:api.id(constraint),satisfy:api.id(satisfy),
            verify:api.id(verify),derive:api.id(derive),source_identifier:prefix+'source']
        log.info('Native ReqIF create/edit/link completed for ' + layer)
    }
}
return [phase:'requirements-create-edit',state:nativeRequirementsState]
