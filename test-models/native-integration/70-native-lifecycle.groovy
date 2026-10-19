// Run only in the marker workspace. Keep the model idle until the worker finishes.
import java.util.concurrent.atomic.AtomicReference
import org.eclipse.core.runtime.NullProgressMonitor
import org.eclipse.sirius.business.api.query.DRepresentationQuery
import org.eclipse.sirius.business.api.dialect.DialectManager
import org.polarsys.capella.ai.session.SessionSafeguards

def nativeSession = session
def workspace = org.eclipse.core.resources.ResourcesPlugin.workspace.root.location.toString().replace('\\', '/')
assert workspace.equalsIgnoreCase('<local-path>')
assert nativeSession.sessionResource.URI.toString().startsWith('platform:/resource/capella-ai-native-integration/')

def worker = new Thread({
    def checks = []
    def onUI = { Closure action ->
        def value = new AtomicReference()
        SessionSafeguards.runOnUiThread({ value.set(action.call()) } as Runnable)
        value.get()
    }
    def nativeApi = new CapellaAPI(nativeSession)
    def domain = nativeSession.transactionalEditingDomain
    def prefix = 'AI Native Probe '
    def check = { String name, Closure action ->
        try { action.call(); checks << [name: name, success: true] }
        catch (Throwable error) { checks << [name: name, success: false, error: error.toString()]; throw error }
    }
    def run = { String code, Map extras = [:] ->
        def result = GroovyExecutor.execute(nativeSession, code, 20000L, extras)
        assert result.success(): result.message()
        result
    }
    def save = { onUI { nativeSession.save(new NullProgressMonitor()) } }
    try {
        onUI { domain.commandStack.flush() }
        check('native_function_rename_undo_redo') {
            def function = onUI { nativeApi.find('LogicalFunction', prefix + 'LA Acquire') }
            assert function != null
            run("assert api.rename(target, 'AI Native Probe LA Acquire renamed')", [target: function])
            onUI {
                assert function.name == prefix + 'LA Acquire renamed'
                domain.commandStack.undo()
                assert function.name == prefix + 'LA Acquire'
                assert !domain.commandStack.canUndo()
                domain.commandStack.redo()
                assert function.name == prefix + 'LA Acquire renamed'
                domain.commandStack.undo()
            }
            save()
            onUI { domain.commandStack.flush() }
        }
        check('native_representation_delete_undo_redo_preserves_semantics') {
            def diagram = onUI { nativeApi.diagram.find(prefix + 'LA architecture diagram renamed') }
            assert diagram != null
            def descriptor = onUI { new DRepresentationQuery(diagram).representationDescriptor }
            def producer = onUI { nativeApi.find('LogicalComponent', prefix + 'LA Producer') }
            run('assert api.diagram.delete(target)', [target: diagram])
            onUI {
                assert !DialectManager.INSTANCE.getAllRepresentationDescriptors(nativeSession).contains(descriptor)
                assert producer.eResource() != null
                domain.commandStack.undo()
                assert DialectManager.INSTANCE.getAllRepresentationDescriptors(nativeSession).contains(descriptor)
                assert descriptor.representation == diagram
                domain.commandStack.redo()
                assert !DialectManager.INSTANCE.getAllRepresentationDescriptors(nativeSession).contains(descriptor)
                domain.commandStack.undo()
                assert descriptor.representation == diagram
            }
            save()
            onUI { domain.commandStack.flush() }
        }
        check('native_all_five_layers_delete_undo_redo') {
            def targets = onUI {
                def rows = []
                ['OA':'OperationalActivity', 'SA':'SystemFunction', 'LA':'LogicalFunction', 'PA':'PhysicalFunction'].each { layer, type ->
                    def f = nativeApi.find(type, prefix + layer + ' Acquire')
                    def ctype = ['OA':'Entity', 'SA':'SystemComponent', 'LA':'LogicalComponent', 'PA':'PhysicalComponent'][layer]
                    def c = nativeApi.find(ctype, prefix + layer + ' Consumer')
                    def e = nativeApi.find('FunctionalExchange', prefix + layer + ' Data')
                    def ce = nativeApi.find(layer == 'OA' ? 'CommunicationMean' : 'ComponentExchange', prefix + layer + ' Transport')
                    assert [f, c, e, ce].every { it != null && it.eResource() != null }
                    rows << [function: f, component: c, exchange: e, transport: ce]
                }
                def ci = nativeApi.find('ConfigurationItem', prefix + 'EPBS Software')
                assert ci != null && ci.ownedPhysicalArtifactRealizations.size() == 1
                [rows: rows, ci: ci, realization: ci.ownedPhysicalArtifactRealizations[0]]
            }
            run('''
                targets.rows.each { row ->
                    assert api.delete(row.function)
                    assert api.delete(row.component)
                    assert row.exchange.eResource() == null && row.transport.eResource() == null
                }
                assert api.delete(targets.ci)
                assert targets.realization.eResource() == null
            ''', [targets: targets])
            def removed = { ->
                assert targets.rows.every { row -> [row.function, row.component, row.exchange, row.transport].every { it.eResource() == null } }
                assert targets.ci.eResource() == null && targets.realization.eResource() == null
            }
            def restored = { ->
                assert targets.rows.every { row -> [row.function, row.component, row.exchange, row.transport].every { it.eResource() != null } }
                assert targets.ci.eResource() != null && targets.realization.eContainer() == targets.ci
            }
            onUI {
                removed.call()
                domain.commandStack.undo()
                restored.call()
                assert !domain.commandStack.canUndo(): 'Deletion must be a single native command'
                domain.commandStack.redo()
                removed.call()
            }
            save()
        }
    } catch (Throwable ignored) {
        // The failing check is recorded; do not retry a partially observed mutation.
    } finally {
        def escaped = { value -> String.valueOf(value).replace('\\', '\\\\').replace('"', '\\"').replace('\n', '\\n').replace('\r', '\\r') }
        def entries = checks.collect { item -> '{"name":"' + escaped(item.name) + '","success":' + item.success +
            (item.error != null ? ',"error":"' + escaped(item.error) + '"' : '') + '}' }.join(',')
        def report = '{"success":' + (checks.size() == 3 && checks.every { it.success }) + ',"checks":[' + entries + ']}'
        assert new FileSystemAPI().write('workspace/native-model-lifecycle-results.json', report).success()
    }
} as Runnable, 'CapellaAI-NativeLifecycleProbe')
worker.daemon = true
worker.start()
return 'Native lifecycle worker started; keep the disposable model idle until its report appears.'
