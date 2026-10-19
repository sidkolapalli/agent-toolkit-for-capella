import java.util.concurrent.atomic.AtomicReference
import org.eclipse.core.runtime.NullProgressMonitor
import org.polarsys.capella.ai.session.SessionSafeguards

// Run after phase90 and91 in the disposable marker model. Keep the model idle until the report appears.
def nativeSession = session
def workspacePath = org.eclipse.core.resources.ResourcesPlugin.workspace.root.location.toString().replace('\\','/')
assert workspacePath.equalsIgnoreCase('<local-path>')
assert nativeSession.sessionResource.URI.toString().startsWith('platform:/resource/capella-ai-native-integration/')
def reportPath = 'workspace/native-requirements-lifecycle-results.json'
assert api.fs.write(reportPath,'{"success":false,"status":"started","checks":[]}').success()
def worker = new Thread({
    def checks = []
    def onUI = { Closure action ->
        def value = new AtomicReference()
        SessionSafeguards.runOnUiThread({ value.set(action.call()) } as Runnable)
        value.get()
    }
    def nativeApi = new CapellaAPI(nativeSession)
    def req = nativeApi.requirements()
    def domain = nativeSession.transactionalEditingDomain
    def check = { String name,Closure action ->
        try { action.call(); checks << [name:name,success:true] }
        catch (Throwable error) { checks << [name:name,success:false,error:error.toString()]; throw error }
    }
    def verifyPresent = { ->
        assert !SessionSafeguards.isInWriteContext()
        nativeApi = new CapellaAPI(nativeSession)
        req = nativeApi.requirements()
        ['OA','SA','LA','PA','EPBS'].each { layer ->
            def prefix = 'AI-NATIVE-REQ:'+layer+':'
            def source = req.find(prefix+'source')
            def derived = req.find(prefix+'derived')
            def module = req.find(prefix+'module')
            assert source != null && derived != null && module != null
            assert module.eContainer() == nativeApi.layer(layer)
            assert source.ReqIFLongName == layer+' Edited native requirement'
            assert source.ReqIFText == 'Edited short source paraphrase'
            assert req.attributes(source).reviewed == true
            assert req.attributes(source).coverage_status == 'not_modeled'
            assert req.attributes(source).verification_status == 'not_verified'
            def ownerType = [OA:'Entity',SA:'SystemComponent',LA:'LogicalComponent',PA:'PhysicalComponent',EPBS:'ConfigurationItem'][layer]
            def owner = nativeApi.find(ownerType,'AI Native Requirement '+layer+' Owner')
            assert owner != null
            def outgoing = owner.ownedExtensions.find { it.eClass().name == 'CapellaOutgoingRelation' && it.target == source }
            def incoming = source.ownedRelations.find { it.eClass().name == 'CapellaIncomingRelation' }
            def internal = derived.ownedRelations.find { it.eClass().name == 'InternalRelation' }
            assert outgoing != null && outgoing.source == owner && outgoing.target == source
            assert incoming != null && incoming.source == source && incoming.target.eResource() != null
            assert internal != null && internal.source == derived && internal.target == source
            [outgoing,incoming,internal].each { relation ->
                assert relation.eResource() != null && relation.relationType.eResource() != null
                assert !relation.source.eIsProxy() && !relation.target.eIsProxy()
            }
        }
    }
    def verifyRemoved = { ->
        assert !SessionSafeguards.isInWriteContext()
        nativeApi = new CapellaAPI(nativeSession)
        req = nativeApi.requirements()
        assert req.find('AI-NATIVE-REQ:OA:source') == null
        assert nativeApi.find('SystemComponent','AI Native Requirement SA Owner') == null
        assert nativeApi.find('Constraint','AI Native Requirement LA Verification obligation') == null
        def oaDerived = req.find('AI-NATIVE-REQ:OA:derived')
        def oaOwner = nativeApi.find('Entity','AI Native Requirement OA Owner')
        def laSource = req.find('AI-NATIVE-REQ:LA:source')
        assert oaDerived != null && oaOwner != null && laSource != null
        assert !oaDerived.ownedRelations.any { it.eClass().name == 'InternalRelation' }
        assert !oaOwner.ownedExtensions.any { it.eClass().name == 'CapellaOutgoingRelation' }
        assert !laSource.ownedRelations.any { it.eClass().name == 'CapellaIncomingRelation' }
        assert req.find('AI-NATIVE-REQ:SA:source') != null
        assert req.find('AI-NATIVE-REQ:PA:source') != null
        assert req.find('AI-NATIVE-REQ:EPBS:source') != null
        nativeSession.semanticResources.each { resource ->
            def contents = resource.allContents
            while (contents.hasNext()) {
                def relation = contents.next()
                if (relation.eClass().name in ['CapellaIncomingRelation','CapellaOutgoingRelation','InternalRelation']) {
                    assert relation.source != null && relation.target != null
                    assert relation.source.eResource() != null && relation.target.eResource() != null
                    assert !relation.source.eIsProxy() && !relation.target.eIsProxy()
                }
            }
        }
    }
    try {
        onUI { assert !SessionSafeguards.isInWriteContext(); domain.commandStack.flush() }
        check('all_five_perspectives_native_requirement_links_present') { onUI { verifyPresent.call() } }
        check('delete_three_native_endpoint_types_and_close_relations') {
            def result = GroovyExecutor.execute(nativeSession,'''
                assert session.sessionResource.URI.toString().startsWith('platform:/resource/capella-ai-native-integration/')
                api.batch('Delete native requirement design and verification endpoints') {
                    assert api.requirements().delete(api.requirements().find('AI-NATIVE-REQ:OA:source'))
                    assert api.delete(api.find('SystemComponent','AI Native Requirement SA Owner'))
                    assert api.delete(api.find('Constraint','AI Native Requirement LA Verification obligation'))
                }
            ''',60000L,[:])
            assert result.success(): result.message()
            onUI { verifyRemoved.call() }
        }
        check('native_undo_restores_all_reqif_endpoint_directions') {
            onUI {
                assert domain.commandStack.canUndo()
                domain.commandStack.undo()
                verifyPresent.call()
                assert !domain.commandStack.canUndo(): 'Endpoint deletion must occupy one native undo command'
                assert domain.commandStack.canRedo()
            }
        }
        check('native_redo_removes_endpoints_without_dangling_reqif_links') {
            onUI {
                domain.commandStack.redo()
                verifyRemoved.call()
                nativeSession.save(new NullProgressMonitor())
            }
        }
    } catch (Throwable ignored) {
        // Report the observed failure; never rerun a mutation or erase a partially created fixture.
    } finally {
        def escaped = { value -> String.valueOf(value).replace('\\','\\\\').replace('"','\\"').replace('\n','\\n').replace('\r','\\r') }
        def entries = checks.collect { item -> '{"name":"'+escaped(item.name)+'","success":'+item.success+
            (item.error != null ? ',"error":"'+escaped(item.error)+'"' : '')+'}' }.join(',')
        def report = '{"success":'+(checks.size() == 4 && checks.every { it.success })+
            ',"status":"finished","session":"'+escaped(nativeSession.sessionResource.URI)+'","checks":['+entries+']}'
        assert new FileSystemAPI().write(reportPath,report).success()
    }
} as Runnable,'CapellaAI-NativeRequirementsLifecycle')
worker.daemon = true
worker.start()
return 'Native requirement lifecycle worker queued; read workspace/native-requirements-lifecycle-results.json before changing the disposable model.'
