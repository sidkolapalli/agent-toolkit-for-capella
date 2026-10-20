// Scores the open model for one task: the task's native checks, native validation, and integrity.
// grade.py substitutes TASK_JSON and PRISTINE_DIAGRAMS before posting this to /execute.
import org.polarsys.capella.ai.agent.EvalRunner
import org.polarsys.capella.ai.agent.ModelPostconditions
import org.eclipse.emf.ecore.util.EcoreUtil
import org.eclipse.sirius.business.api.dialect.DialectManager
import org.eclipse.sirius.viewpoint.DSemanticDecorator
import org.eclipse.sirius.diagram.DDiagram
import org.eclipse.sirius.diagram.ui.business.api.view.SiriusGMFHelper
import org.eclipse.gmf.runtime.notation.Node
import org.eclipse.gmf.runtime.notation.Bounds
import org.polarsys.capella.ai.agent.AgentJson

def session = api.session
def task = EvalRunner.parse(TASK_JSON)[0]
def report = ModelPostconditions.check(session, task.checks())
def validation = api.validateNative()
def resources = session.transactionalEditingDomain.resourceSet
int proxies = EcoreUtil.UnresolvedProxyCrossReferencer.find(resources).size()
int dangling = 0
for (r in session.allSessionResources) for (o in r.allContents)
    if (o instanceof DSemanticDecorator && (o.target == null || o.target.eIsProxy() || o.target.eResource() == null)) dangling++

// New diagrams: sibling node overlaps, edges, and whether any layout was stored at all.
def pristine = PRISTINE_DIAGRAMS as Set
def fresh = []
for (d in DialectManager.INSTANCE.getAllRepresentationDescriptors(session)) {
    if (pristine.contains(d.name) || !(d.representation instanceof DDiagram)) continue
    def gmf = SiriusGMFHelper.getGmfDiagram(d.representation)
    int nodes = 0, overlaps = 0, edges = 0
    if (gmf != null) {
        def visit
        visit = { view ->
            def boxes = []
            for (child in view.children) if (child instanceof Node) {
                if (child.layoutConstraint instanceof Bounds && child.layoutConstraint.width > 0 && child.layoutConstraint.height > 0) {
                    def b = child.layoutConstraint
                    for (o in boxes) if (b.x < o.x + o.width && o.x < b.x + b.width && b.y < o.y + o.height && o.y < b.y + b.height) overlaps++
                    boxes << b
                }
                nodes++
                visit(child)
            }
        }
        visit(gmf)
        edges = gmf.edges.size()
    }
    fresh << [name: d.name, elements: d.representation.ownedDiagramElements.size(), gmf: gmf != null, nodes: nodes, overlaps: overlaps, edges: edges]
}
def out = AgentJson.write([
    id: task.id(), passed: report.allPassed(), checks: report.markdownSummary(),
    errors: validation.errors, warnings: validation.warnings,
    unresolved_proxies: proxies, dangling_diagram_elements: dangling, new_diagrams: fresh])
// /execute caps a returned value at 200 characters; log lines carry up to 500.
out.toList().collate(400)*.join().each { log.info('G|' + it) }
return out.length()
