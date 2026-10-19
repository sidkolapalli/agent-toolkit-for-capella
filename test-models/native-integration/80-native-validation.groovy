import org.eclipse.core.runtime.Platform
import org.eclipse.core.runtime.NullProgressMonitor

assert session.sessionResource.URI.toString().toLowerCase().contains('capella-ai-native-integration')
def validationBundle = Platform.getBundle('org.polarsys.capella.core.validation')
assert validationBundle != null: 'Native Capella validation bundle must be installed'
def adapter = validationBundle.loadClass('org.polarsys.capella.core.validation.CapellaValidatorAdapter').newInstance()
def validator = adapter.validator
validator.setIncludeLiveConstraints(true)
def projects = session.semanticResources.collectMany { it.contents }.findAll { it.eClass().name == 'Project' }
assert projects.size() == 1
def status = validator.validate(projects[0], new NullProgressMonitor())
def leaves = []
def collect
collect = { current ->
    if (current.multiStatus) {
        current.children.each { collect(it) }
    } else if (!current.isOK()) {
        leaves << [severity: current.severity, message: current.message, code: current.code, plugin: current.plugin]
    }
}
collect(status)
def errors = leaves.count { it.severity >= 4 }
def warnings = leaves.count { it.severity == 2 }
log.info('Native Capella batch validation severity=' + status.severity + ', errors=' + errors +
    ', warnings=' + warnings + ', issues=' + leaves.size())
leaves.eachWithIndex { issue, index ->
    log.info('VALIDATION[' + (index + 1) + '] severity=' + issue.severity + ', code=' + issue.code +
        ', plugin=' + issue.plugin + ': ' + issue.message)
}
return [phase: 'native-validation', session: session.sessionResource.URI.toString(), severity: status.severity,
    errors: errors, warnings: warnings,
    issues: leaves.take(100), issueCount: leaves.size()]
