// Scores the open model for one task: the task's native checks, its method checks, native validation, integrity
// and the drawing quality of every diagram the run created.
// capella.py substitutes TASK_JSON and PRISTINE_DIAGRAMS before posting this to /execute.
import com.sidkolapalli.capella.ai.agent.EvalRunner
import com.sidkolapalli.capella.ai.agent.ModelPostconditions
import org.eclipse.emf.ecore.util.EcoreUtil
import org.eclipse.sirius.business.api.dialect.DialectManager
import org.eclipse.sirius.viewpoint.DSemanticDecorator
import org.eclipse.sirius.diagram.DDiagram
import org.eclipse.sirius.diagram.DEdge
import org.eclipse.sirius.diagram.ui.business.api.view.SiriusGMFHelper
import org.eclipse.gmf.runtime.notation.Node
import org.eclipse.gmf.runtime.notation.Bounds
import org.eclipse.gmf.runtime.notation.Location
import org.eclipse.gmf.runtime.notation.RelativeBendpoints
import com.sidkolapalli.capella.ai.agent.AgentJson

def session = api.session
def taskJson = AgentJson.parse(TASK_JSON, 2_000_000).tasks[0]
def report = taskJson.checks ? ModelPostconditions.check(session, EvalRunner.parse(TASK_JSON)[0].checks()) : null
def validation = api.validateNative()
def resources = session.transactionalEditingDomain.resourceSet
int proxies = EcoreUtil.UnresolvedProxyCrossReferencer.find(resources).size()
int dangling = 0
for (r in session.allSessionResources) for (o in r.allContents)
    if (o instanceof DSemanticDecorator && (o.target == null || o.target.eIsProxy() || o.target.eResource() == null)) dangling++

// ---- Model navigation (Capella metamodel through EMF, so no layer-specific imports) ----
def LAYERS = [OperationalAnalysis: 'OA', SystemAnalysis: 'SA', LogicalArchitecture: 'LA', PhysicalArchitecture: 'PA', EPBSArchitecture: 'EPBS']
def layerOf = { o -> for (def c = o; c != null; c = c.eContainer()) if (LAYERS[c.eClass().name]) return LAYERS[c.eClass().name]; null }
def find = { sel ->
    if (sel.id) return api.findById(sel.id) ?: { throw new IllegalStateException("no element ${sel.id}") }()
    def hits = api.findAll(sel.type).findAll { it.name == sel.name && (!sel.layer || layerOf(it) == sel.layer) }
    if (hits.size() != 1) throw new IllegalStateException("${hits.size()} matches for ${sel.type} '${sel.name}'")
    hits[0]
}
def kind = { o -> o.eClass().name }
def endFunction = { p -> p == null ? null : kind(p).endsWith('Port') ? p.eContainer() : p }
def endComponent = { p -> p == null ? null : kind(p) == 'Part' ? p.abstractType : kind(p).endsWith('Port') ? p.eContainer() : p }
def allocators = { f -> f.componentFunctionalAllocations*.sourceElement.findAll { it != null } }
// Neither findAll nor for-in walks an EMF TreeIterator here, so drain it by hand.
def descendants = { o -> def out = [], all = o.eAllContents(); while (all.hasNext()) out << all.next(); out }
def subtree = { c -> [c] + descendants(c).findAll { kind(it) == kind(c) } }
def ancestors = { c -> def out = []; for (def a = c; a != null && kind(a) == kind(c); a = a.eContainer()) out << a; out }
def isLeafComponent = { c -> !c.eContents().any { kind(it) == kind(c) } }
def functionsOf = { comp -> def scope = subtree(comp) as Set; api.findAll('LogicalFunction').findAll { f -> allocators(f).any { scope.contains(it) } } }
def leavesUnder = { f -> f.ownedFunctions ? descendants(f).findAll { kind(it) == kind(f) && !it.ownedFunctions } : [] }
def realizers = { f -> f.incomingTraces.findAll { kind(it) == 'FunctionRealization' }*.sourceElement.findAll { it != null } }
def laExchanges = { api.findAll('FunctionalExchange').findAll { layerOf(it) == 'LA' } }
def laComponentExchanges = { api.findAll('ComponentExchange').findAll { layerOf(it) == 'LA' } }
def owner = { f -> def a = allocators(f); a.size() == 1 ? a[0] : null }
// Functional exchanges between functions of two different components, with at least one end inside scope.
def crossing = { scope ->
    def inside = subtree(scope) as Set
    laExchanges().findAll { fe ->
        def s = owner(endFunction(fe.source)), t = owner(endFunction(fe.target))
        s != null && t != null && s != t && (inside.contains(s) || inside.contains(t))
    }
}
def functionSet = { spec ->
    if (spec instanceof List) return spec.collect { find(it) }
    if (spec.functions_of) return functionsOf(find(spec.functions_of))
    if (spec.realizers_of) return leavesUnder(find(spec.realizers_of)).collectMany { realizers(it) }
    [find(spec)]
}
def names = { list, n = 6 -> list.take(n)*.name.join('; ') + (list.size() > n ? " (+${list.size() - n})" : '') }

// ---- Drawing quality of one diagram, read from the stored GMF notation ----
// HEADER: ELK's title band, for absolute positions. TITLE: the band Sirius actually draws, for clipping.
// Calibrated on the shutdown-sources diagrams of experiment 1 (A judged clean by eye; B and C broken).
def HEADER = 30, TITLE = 20, LABEL_SLACK = 80
def boundsOf = { v -> v instanceof Node && v.layoutConstraint instanceof Bounds ? v.layoutConstraint : null }
def sized = { b -> b != null && b.width > 0 && b.height > 0 }
def isPort = { v -> v.element?.eContainingFeature()?.name == 'ownedBorderedNodes' }
def absolute = { v ->
    int x = 0, y = 0
    for (def n = v; n instanceof Node; n = n.eContainer()) {
        def b = boundsOf(n)
        if (b) { x += b.x; y += b.y } else if (n != v && n.eContainer() instanceof Node) y += HEADER   // a compartment
    }
    [x, y]
}
def centre = { v -> def a = absolute(v), b = boundsOf(v); sized(b) ? [a[0] + b.width / 2, a[1] + b.height / 2] : a }
def segDistance = { p, route ->
    double best = Double.MAX_VALUE
    for (int i = 1; i < route.size(); i++) {
        double ax = route[i - 1][0], ay = route[i - 1][1], dx = route[i][0] - ax, dy = route[i][1] - ay
        double t = dx == 0 && dy == 0 ? 0 : Math.max(0, Math.min(1, ((p[0] - ax) * dx + (p[1] - ay) * dy) / (dx * dx + dy * dy)))
        best = Math.min(best, Math.hypot(p[0] - (ax + dx * t), p[1] - (ay + dy * t)))
    }
    route.size() == 1 ? Math.hypot(p[0] - route[0][0], p[1] - route[0][1]) : best
}
// Sirius places a centre label at an offset from the route's half-length point, rotated into that segment.
def labelCentre = { route, Location at ->
    double total = 0
    for (int i = 1; i < route.size(); i++) total += Math.hypot(route[i][0] - route[i - 1][0], route[i][1] - route[i - 1][1])
    double remaining = total / 2
    for (int i = 1; i < route.size(); i++) {
        double dx = route[i][0] - route[i - 1][0], dy = route[i][1] - route[i - 1][1], length = Math.hypot(dx, dy)
        if (length == 0) continue
        if (remaining <= length || i == route.size() - 1) {
            double t = Math.min(1, remaining / length), cos = dx / length, sin = dy / length
            double rx = route[i - 1][0] + dx * t, ry = route[i - 1][1] + dy * t
            return [rx + at.x * cos - at.y * sin, ry + at.x * sin + at.y * cos]
        }
        remaining -= length
    }
    [route[0][0] + at.x, route[0][1] + at.y]
}
def overlap = { a, ab, b, bb -> a[0] < b[0] + bb.width && b[0] < a[0] + ab.width && a[1] < b[1] + bb.height && b[1] < a[1] + ab.height }
def quality = { dd ->
    def gmf = SiriusGMFHelper.getGmfDiagram(dd)
    def q = [gmf: gmf != null, nodes: 0, unsized: 0, overlaps: 0, port_overlaps: 0, ports_off_border: 0, clipped: 0,
             edges: 0, far_labels: 0, worst_label_px: 0, offenders: []]
    if (gmf == null) return q
    def visit
    visit = { view ->
        def shapes = [], ports = []
        for (child in view.children) if (child instanceof Node) {
            def b = boundsOf(child)
            if (child.element instanceof DSemanticDecorator && b != null) {
                q.nodes++
                if (!sized(b)) q.unsized++
                else (isPort(child) ? ports : shapes) << child
            }
            visit(child)
        }
        for (list in [shapes, ports]) for (int i = 0; i < list.size(); i++) for (int j = 0; j < i; j++)
            if (overlap([boundsOf(list[i]).x, boundsOf(list[i]).y], boundsOf(list[i]), [boundsOf(list[j]).x, boundsOf(list[j]).y], boundsOf(list[j])))
                list.is(shapes) ? q.overlaps++ : q.port_overlaps++
        def frame = boundsOf(view)
        if (sized(frame)) for (p in ports) {
            def b = boundsOf(p)
            boolean onX = b.x <= 0 && 0 <= b.x + b.width || b.x <= frame.width && frame.width <= b.x + b.width
            boolean onY = b.y <= 0 && 0 <= b.y + b.height || b.y <= frame.height && frame.height <= b.y + b.height
            boolean within = b.x + b.width >= 0 && b.x <= frame.width && b.y + b.height >= 0 && b.y <= frame.height
            if (!(within && (onX || onY))) q.ports_off_border++
        }
        // Shapes in a container's compartment must fit inside the container below its title band.
        def container = view.eContainer() instanceof Node ? boundsOf(view.eContainer()) : null
        if (frame == null && sized(container)) for (s in shapes) {
            def b = boundsOf(s)
            if (b.x < 0 || b.y < 0 || b.x + b.width > container.width || b.y + b.height + TITLE > container.height) {
                q.clipped++
                if (q.offenders.size() < 4) q.offenders << "clipped ${s.element.name} ${b.x},${b.y} ${b.width}x${b.height} in ${container.width}x${container.height}".toString()
            }
        }
    }
    visit(gmf)
    for (edge in gmf.edges) {
        q.edges++
        def label = edge.children.find { it instanceof Node && it.layoutConstraint instanceof Location }
        if (label == null || edge.source == null || edge.target == null) continue
        def from = centre(edge.source), to = centre(edge.target)
        def route = edge.bendpoints instanceof RelativeBendpoints ?
            edge.bendpoints.points.collect { [from[0] + it.sourceX, from[1] + it.sourceY] } : []
        if (route.size() < 2 || route.every { it == route[0] }) route = [from, to]   // only anchors stored
        double far = segDistance(labelCentre(route, label.layoutConstraint), route)
        q.worst_label_px = Math.max(q.worst_label_px, Math.round(far) as int)
        if (far > LABEL_SLACK) {
            q.far_labels++
            if (q.offenders.size() < 4) q.offenders << "label ${edge.element?.name} ${Math.round(far)} px".toString()
        }
    }
    q
}
// Architecture diagrams show a component through its Part.
def shownTargets = { dd -> dd.diagramElements.findAll { !(it instanceof DEdge) }*.target.collectMany { it != null && kind(it) == 'Part' ? [it, it.abstractType] : [it] } as Set }
def shownEdges = { dd -> dd.diagramElements.findAll { it instanceof DEdge }*.target as Set }

// ---- Elicitation: the text of every Capella element (names, descriptions, constraint bodies, property values) ----
// Requirements (Requirements Viewpoint) are left out: they quote the rules, not the team's decisions.
def corpus = null
def texts = {
    if (corpus == null) {
        corpus = []
        for (r in session.semanticResources) {
            def all = r.allContents
            while (all.hasNext()) {
                def o = all.next()
                if (!o.eClass().EPackage.nsURI.contains('/capella/core/')) continue
                def text = o.eClass().EAllAttributes.findAll { !(it.name in ['id', 'sid']) }
                    .collect { a -> def v = o.eGet(a); v instanceof Iterable ? v.join(' ') : v }
                    .findAll { it instanceof String || it instanceof Number }.join(' ')
                if (text) corpus << [o, text]
            }
        }
    }
    corpus
}
def matching = { c -> def p = java.util.regex.Pattern.compile(c.pattern); texts().findAll { p.matcher(it[1]).find() }*.getAt(0) }
def labels = { list -> list.take(3).collect { kind(it) + (it.respondsTo('getName') && it.name ? " '${it.name}'" : '') }.join('; ') }

// ---- Method checks: each returns [passed, detail] ----
def CHECKS = [
    mentions: { c -> def hits = matching(c); [!hits.isEmpty(), "${hits.size()} elements match /${c.pattern}/: ${labels(hits)}"] },
    absent: { c -> def hits = matching(c); [hits.isEmpty(), "${hits.size()} elements match /${c.pattern}/: ${labels(hits)}"] },
    children_at_least: { c -> def n = find(c.parent).ownedFunctions.size(); [n >= c.min, "${n} children"] },
    leaves_realized: { c ->
        def leaves = leavesUnder(find(c.parent)), missing = leaves.findAll { realizers(it).isEmpty() }
        [leaves && !missing, "${leaves.size()} leaves, unrealized: ${names(missing)}"] },
    realizers_allocated_once: { c ->
        def within = subtree(find(c.within)) as Set
        def fns = leavesUnder(find(c.parent)).collectMany { realizers(it) }.collectMany { it.ownedFunctions ? leavesUnder(it) : [it] }.unique()
        def bad = fns.findAll { f -> def a = allocators(f); a.size() != 1 || !within.contains(a[0]) || !isLeafComponent(a[0]) }
        [fns && !bad, "${fns.size()} realizing functions, misallocated: ${names(bad)}"] },
    connected: { c ->
        def from = functionSet(c.from) as Set, to = functionSet(c.to) as Set
        def links = laExchanges().findAll { fe -> def s = endFunction(fe.source), t = endFunction(fe.target)
            from.contains(s) && to.contains(t) || to.contains(s) && from.contains(t) }
        [!links.isEmpty(), "${links.size()} exchanges: ${names(links, 3)}"] },
    chain_path: { c ->
        def start = find(c.start), end = find(c.end), found = []
        for (chain in api.findAll('FunctionalChain').findAll { layerOf(it) == 'LA' }) {
            def inv = chain.ownedFunctionalChainInvolvements
            def fns = inv.findAll { kind(it) == 'FunctionalChainInvolvementFunction' }*.involved as Set
            def next = [:].withDefault { [] }
            inv.findAll { kind(it) == 'FunctionalChainInvolvementLink' && kind(it.involved) == 'FunctionalExchange' }.each { l ->
                def s = endFunction(l.involved.source), t = endFunction(l.involved.target)
                if (fns.contains(s) && fns.contains(t)) next[s] << t
            }
            def seen = [start] as Set, todo = [start]
            while (todo) { def f = todo.pop(); next[f].each { if (seen.add(it)) todo << it } }
            if (fns.contains(start) && seen.contains(end)) found << chain
        }
        [!found.isEmpty(), found ? "via ${names(found, 2)}" : "no LA chain runs from '${start.name}' to '${end.name}'"] },
    crossing_allocated: { c ->
        def all = crossing(find(c.scope))
        def bad = all.findAll { fe ->
            def s = ancestors(owner(endFunction(fe.source))) as Set, t = ancestors(owner(endFunction(fe.target))) as Set
            !fe.allocatingComponentExchanges.any { ce -> def a = endComponent(ce.source), b = endComponent(ce.target)
                s.contains(a) && t.contains(b) || s.contains(b) && t.contains(a) }
        }
        [all && !bad, "${all.size()} crossing exchanges, not on a component exchange between their components: ${names(bad)}"] },
    ce_used: { c ->
        def inside = subtree(find(c.scope)) as Set
        def ces = laComponentExchanges().findAll { inside.contains(endComponent(it.source)) || inside.contains(endComponent(it.target)) }
        def empty = ces.findAll { it.allocatedFunctionalExchanges.isEmpty() }
        [ces && !empty, "${ces.size()} component exchanges, carrying nothing: ${names(empty)}"] },
    transport_names: { c ->
        def inside = subtree(find(c.scope)) as Set
        def ces = laComponentExchanges().findAll { it.name?.endsWith(' transport') &&
            (inside.contains(endComponent(it.source)) || inside.contains(endComponent(it.target))) }
        def bad = ces.findAll { ce -> !ce.allocatedFunctionalExchanges.any { it.name == ce.name - ~/ transport$/ } }
        [ces && !bad, "${ces.size()} named transports, not carrying their exchange: ${names(bad)}"] },
    items_on: { c ->
        def all = crossing(find(c.scope)), bare = all.findAll { it.exchangedItems.isEmpty() }
        [all && !bare, "${all.size()} crossing exchanges, without an exchange item: ${names(bare)}"] },
    interface_carries: { c ->
        def items = crossing(find(c.scope)).collectMany { it.exchangedItems } as Set
        def faces = api.findAll('Interface').findAll { it.name == c.name && layerOf(it) == 'LA' }
        def carried = faces.collectMany { it.ownedExchangeItemAllocations*.allocatedItem } as Set
        def missing = items.findAll { !carried.contains(it) }
        [faces.size() == 1 && items && !missing, "${faces.size()} interfaces named so; ${items.size()} items, not carried: ${names(missing as List)}"] },
    leaf_allocation: { c ->
        def inside = subtree(find(c.scope)) as Set
        def fns = api.findAll('LogicalFunction').findAll { f -> allocators(f).any { inside.contains(it) } }
        def bad = fns.findAll { f -> def a = allocators(f); f.ownedFunctions || a.size() != 1 || !isLeafComponent(a[0]) }
        [fns && !bad, "${fns.size()} allocated functions, not leaf-on-leaf exactly once: ${names(bad)}"] },
    // ---- Experiment 4: Arcadia depth ----
    machine_modes: { c ->
        def comp = find(c.component)
        def machines = comp.ownedStateMachines.findAll { sm -> descendants(sm).count { kind(it) == 'Mode' } >= c.min }
        if (machines.size() != 1) return [false, "${machines.size()} state machines with >= ${c.min} modes on '${comp.name}'"]
        def all = descendants(machines[0]), modes = all.findAll { kind(it) == 'Mode' }
        def transitions = all.findAll { kind(it) == 'StateTransition' }
        def next = [:].withDefault { [] }
        transitions.each { next[it.source] << it.target }
        def initial = all.findAll { kind(it) == 'InitialPseudoState' }
        def seen = initial as Set, todo = initial.collect()
        while (todo) { def s = todo.pop(); next[s].each { if (seen.add(it)) todo << it } }
        def unreachable = modes.findAll { !seen.contains(it) }
        def untriggered = transitions.findAll { kind(it.source) == 'Mode' && !it.triggers && !it.triggerDescription?.trim() && it.guard == null }
        def states = all.findAll { kind(it) == 'State' }
        [initial && !unreachable && !untriggered && !states,
         "${modes.size()} modes, ${states.size()} states, ${initial.size()} initial; unreachable: ${names(unreachable)}; transitions without trigger or guard: ${untriggered.size()}"] },
    available_only: { c ->
        def f = find(c.function), in_ = f.availableInStates
        [in_.size() == 1 && kind(in_[0]) == 'Mode' && in_[0].name =~ c.pattern, "available in: ${in_.collect { kind(it) + ' ' + it.name }.join('; ')}"] },
    item_kind: { c ->
        def items = find(c.exchange).exchangedItems
        [items && items.every { it.exchangeMechanism.toString() in c.mechanisms }, "items: ${items.collect { it.name + ' ' + it.exchangeMechanism }.join('; ')}"] },
    item_typed: { c ->
        def leaves = find(c.exchange).exchangedItems.collectMany { it.ownedElements*.abstractType }.findAll { it != null }
            .collectMany { t -> kind(t) == 'Class' ? t.ownedFeatures.findAll { kind(it) == 'Property' }*.abstractType.findAll { it != null } : [t] }
        def quantity = leaves.any { kind(it) == 'PhysicalQuantity' && it.unit != null }, flag = leaves.any { kind(it) == 'BooleanType' }
        [quantity && flag, "leaf types: ${leaves.collect { kind(it) + ' ' + it.name + (kind(it) == 'PhysicalQuantity' ? ' [' + it.unit?.name + ']' : '') }.join('; ')}"] },
    transported: { c ->
        def fe = find(c.exchange)
        def s = ancestors(owner(endFunction(fe.source))) as Set, t = ancestors(owner(endFunction(fe.target))) as Set
        def ok = fe.allocatingComponentExchanges.findAll { ce -> def a = endComponent(ce.source), b = endComponent(ce.target)
            s.contains(a) && t.contains(b) || s.contains(b) && t.contains(a) }
        [!ok.isEmpty(), "on component exchanges: ${names(fe.allocatingComponentExchanges)}"] },
    interface_contract: { c ->
        def faces = api.findAll('Interface').findAll { it.name == c.name && layerOf(it) == 'LA' }
        if (faces.size() != 1) return [false, "${faces.size()} interfaces named '${c.name}'"]
        def face = faces[0], items = find(c.exchange).exchangedItems
        def carried = face.ownedExchangeItemAllocations*.allocatedItem as Set
        def ports = { comp -> subtree(comp).collectMany { it.ownedFeatures.findAll { kind(it) == 'ComponentPort' } } }
        def provides = { comp -> subtree(comp).any { it.ownedInterfaceImplementations*.implementedInterface.contains(face) } || ports(comp).any { it.providedInterfaces.contains(face) } }
        def requires = { comp -> subtree(comp).any { it.ownedInterfaceUses*.usedInterface.contains(face) } || ports(comp).any { it.requiredInterfaces.contains(face) } }
        def p = find(c.provider), r = find(c.consumer)
        boolean ok = items && carried.containsAll(items) && provides(p) && requires(r) && !requires(p) && !provides(r)
        [ok, "carries ${items.count { carried.contains(it) }}/${items.size()} items; provider provides ${provides(p)}, requires ${requires(p)}; consumer requires ${requires(r)}, provides ${provides(r)}"] },
    missions: { c ->
        def missions = api.findAll('Mission').findAll { layerOf(it) == 'SA' }
        def caps = api.findAll('Capability').findAll { layerOf(it) == 'SA' }
        def used = missions.collectMany { it.ownedCapabilityExploitations*.capability } as Set
        def idle = missions.findAll { !it.ownedCapabilityExploitations }, unused = caps.findAll { !used.contains(it) }
        [missions.size() >= c.min && !idle && !unused, "${missions.size()} missions, without exploitation: ${names(idle)}; capabilities not exploited: ${names(unused)}"] },
    control_function: { c ->
        def source = find(c.source), consumers = c.consumers.collect { find(it) }
        def into = { f -> laExchanges().findAll { endFunction(it.target) == f } }, outOf = { f -> laExchanges().findAll { endFunction(it.source) == f } }
        def hits = api.findAll('LogicalFunction').findAll { it.kind.toString() == c.function_kind && into(it).any { endFunction(it.source) == source } &&
            consumers.every { k -> outOf(it).any { endFunction(it.target) == k } } }
        def bypass = outOf(source).findAll { consumers.contains(endFunction(it.target)) }
        [hits && !bypass, "${hits.size()} ${c.function_kind} functions from the source to every consumer; direct source-to-consumer exchanges: ${names(bypass)}"] },
    property_group: { c ->
        def e = find(c.element)
        def groups = (e.ownedPropertyValueGroups + e.appliedPropertyValueGroups).findAll { it.name =~ c.group }
        def values = groups.collectMany { it.ownedPropertyValues }
        def missing = c.values.findAll { w -> !values.any { v -> v.name =~ w.name && (w.type == 'number' ? kind(v) in ['IntegerPropertyValue', 'FloatPropertyValue'] && v.value == w.value : kind(v) == w.type && v.value == w.value) } }
        [groups && !missing, "${groups.size()} groups; values: ${values.collect { kind(it) + ' ' + it.name + '=' + it.value }.join('; ')}; missing: ${missing*.name.join(', ')}"] },
    category: { c ->
        def cats = api.findAll('ExchangeCategory').findAll { it.name == c.name && layerOf(it) == 'LA' }
        def want = laExchanges().findAll { endFunction(it.target) == find(c.into) }
        def got = cats.collectMany { it.exchanges } as Set
        def missing = want.findAll { !got.contains(it) }
        [cats.size() == 1 && want && !missing, "${cats.size()} categories named so; ${want.size()} exchanges in, not grouped: ${names(missing)}"] },
    // ---- Experiment 5: Arcadia depth, second batch ----
    role: { c ->
        def roles = api.findAll('Role').findAll { it.name == c.name && layerOf(it) == 'OA' }
        if (roles.size() != 1) return [false, "${roles.size()} roles named '${c.name}'"]
        def got = roles[0].activityAllocations*.activity.findAll { it != null } as Set, want = c.activities.collect { find(it) } as Set
        def entity = find(c.entity)
        [got == want && entity.allocatedRoles.contains(roles[0]),
         "role activities ${got.size()} (want ${want.size()}), missing: ${names((want - got) as List)}, extra: ${names((got - want) as List)}; played by entity: ${entity.allocatedRoles.contains(roles[0])}"] },
    communication_mean: { c ->
        def a = find(c.a), b = find(c.b), pair = [a, b] as Set
        def entityOf = { f -> allocators(f).find { it != null } }
        def between = api.findAll('FunctionalExchange').findAll { layerOf(it) == 'OA' &&
            [entityOf(endFunction(it.source)), entityOf(endFunction(it.target))] as Set == pair }
        def cms = api.findAll('CommunicationMean').findAll { it.name == c.name && [endComponent(it.source), endComponent(it.target)] as Set == pair }
        def carried = cms.collectMany { it.allocatedFunctionalExchanges } as Set
        def missing = between.findAll { !carried.contains(it) }
        [cms.size() == 1 && between && !missing, "${cms.size()} communication means named so between the two; ${between.size()} interactions, not carried: ${names(missing)}"] },
    capability_relation: { c ->
        def from = find(c.from), to = find(c.to)
        def targets = [include: { x -> x.includedAbstractCapabilities }, extend: { x -> x.extendedAbstractCapabilities }, generalization: { x -> x.super }][c.relation]
        boolean forward = targets(from).contains(to), backward = targets(to).contains(from)
        [forward && !backward, "${c.relation} '${from.name}' -> '${to.name}': ${forward}; reversed: ${backward}"] },
    // ---- Experiment 6: the API gaps ----
    scenario_messages: { c ->
        def capability = find(c.capability), want = c.exchanges.collect { find(it) }
        def instance = { end -> end.coveredInstanceRoles[0]?.representedInstance }
        def ok = capability.ownedScenarios.findAll { it.kind.toString() == c.scenario_kind }.findAll { s ->
            def ops = s.ownedMessages.collect { m -> m.sendingEnd?.event?.operation }
            int at = 0
            for (m in s.ownedMessages) if (at < want.size() && m.sendingEnd?.event?.operation == want[at]
                    && instance(m.sendingEnd) == endFunction(want[at].source) && instance(m.receivingEnd) == endFunction(want[at].target)) at++
            at == want.size()
        }
        [!ok.isEmpty(), "${capability.ownedScenarios.size()} scenarios (${capability.ownedScenarios.collect { it.kind.toString() + ' ' + it.ownedMessages.size() + ' msgs' }.join('; ')}); with the messages in order on the right lifelines: ${ok.size()}"] },
    chain_exchanges: { c ->
        def want = c.exchanges.collect { find(it) } as Set
        def chains = api.findAll('FunctionalChain').findAll { it.name == c.name && layerOf(it) == 'LA' }
        if (chains.size() != 1) return [false, "${chains.size()} LA chains named '${c.name}'"]
        def links = chains[0].ownedFunctionalChainInvolvements.findAll { kind(it) == 'FunctionalChainInvolvementLink' }
        def good = links.findAll { l -> l.source?.involved == endFunction(l.involved.source) && l.target?.involved == endFunction(l.involved.target) }*.involved as Set
        def missing = want.findAll { !good.contains(it) }
        [!missing, "${links.size()} links, ${good.size()} with matching ends; missing: ${names(missing as List)}"] },
    path_carries: { c ->
        def ce = find(c.exchange), stops = c.stops.collect { find(it) }
        def paths = api.findAll('PhysicalPath').findAll { it.allocatedComponentExchanges.contains(ce) }
        def nodesOf = { p ->
            def inv = p.ownedPhysicalPathInvolvements
            def first = inv.find { i -> !inv.any { it.nextInvolvements.contains(i) } }, seq = []
            for (def s = first; s != null; s = s.nextInvolvements ? s.nextInvolvements[0] : null) if (kind(s.involved) == 'Part') seq << s.involved.abstractType
            seq }
        def ok = paths.findAll { def seq = nodesOf(it); seq == stops || seq == stops.reverse() }
        [!ok.isEmpty(), "${paths.size()} paths carry it; node sequences: ${paths.collect { nodesOf(it)*.name }}"] },
    realized_in_pa: { c ->
        def lcomp = find(c.component), lfun = find(c.function)
        def pcs = lcomp.incomingTraces.findAll { kind(it) == 'ComponentRealization' }*.sourceElement.findAll { it != null && layerOf(it) == 'PA' }
        def pfs = realizers(lfun).findAll { layerOf(it) == 'PA' }
        def allocated = pfs.findAll { f -> allocators(f).any { pcs.contains(it) } }
        [pcs && pfs && allocated, "realizing PA components: ${names(pcs)}; realizing PA functions: ${names(pfs)}; allocated to them: ${allocated.size()}"] },
    diagram_quality: { c ->
        def matches = DialectManager.INSTANCE.getAllRepresentationDescriptors(session).findAll { it.name == c.diagram }
        if (matches.size() != 1) return [false, "${matches.size()} diagrams named '${c.diagram}'"]
        def dd = matches[0].representation
        def want = c.elements.collectMany { functionSet(it) }
        def fnSet = want as Set
        def wantEdges = c.edges == 'between' ? laExchanges().findAll { fnSet.contains(endFunction(it.source)) && fnSet.contains(endFunction(it.target)) } : []
        def shown = shownTargets(dd), edges = shownEdges(dd)
        def missing = want.findAll { !shown.contains(it) }, missingEdges = wantEdges.findAll { !edges.contains(it) }
        def q = quality(dd)
        boolean clean = q.gmf && q.overlaps == 0 && q.port_overlaps == 0 && q.ports_off_border == 0 && q.clipped == 0 && q.far_labels == 0
        [!missing && !missingEdges && clean, "missing ${missing.size()}/${want.size()} elements, ${missingEdges.size()}/${wantEdges.size()} edges; ${q}"] },
]
def method = (taskJson.method ?: []).collect { c ->
    try { def r = CHECKS[c.kind](c); [kind: c.kind, passed: r[0], detail: r[1].toString().take(600)] }
    catch (Exception e) { [kind: c.kind, passed: false, detail: 'unavailable: ' + e.message?.take(300)] }
}

// New diagrams: drawing quality, whatever the task asked.
def pristine = PRISTINE_DIAGRAMS as Set
def fresh = []
for (d in DialectManager.INSTANCE.getAllRepresentationDescriptors(session)) {
    if (pristine.contains(d.name) || !(d.representation instanceof DDiagram)) continue
    fresh << [name: d.name, elements: d.representation.ownedDiagramElements.size()] + quality(d.representation)
}
boolean nativePassed = report == null || report.allPassed()
def out = AgentJson.write([
    id: taskJson.id, passed: nativePassed && method.every { it.passed }, native_passed: nativePassed,
    checks: report?.markdownSummary() ?: '', method: method,
    errors: validation.errors, warnings: validation.warnings,
    unresolved_proxies: proxies, dangling_diagram_elements: dangling, new_diagrams: fresh,
    // Each raises DWF_D_51; the API cannot set cardinalities, so experiment 4 discounts these errors.
    eie_without_min_card: api.findAll('ExchangeItemElement').count { it.ownedMinCard == null },
    // Experiment 7: elements whose text marks an open decision; compare with the pristine model's count.
    tbd_elements: texts().count { it[1] =~ /\bTBD\b/ }])
// /execute caps a returned value at 200 characters; log lines carry up to 500.
out.toList().collate(400)*.join().each { log.info('G|' + it) }
return out.length()
