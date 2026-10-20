param(
    [ValidateSet('inspect', 'create-edit', 'repair-sa', 'verify', 'rename', 'verify-rename', 'delete', 'verify-deleted', 'diagram', 'verify-diagrams', 'delete-diagram', 'native-validation')]
    [string[]]$Phases = @('inspect'),
    [string]$Endpoint = 'http://127.0.0.1:8765',
    # Written by the running dev server; CAPELLA_AI_DEVSERVER_TOKEN overrides it.
    [string]$Token = $env:CAPELLA_AI_DEVSERVER_TOKEN,
    [string]$Conversation = 'capella-ai-native-integration',
    [ValidateSet('OA', 'SA', 'LA', 'PA', 'EPBS')]
    [string]$DiagramLayer = 'LA',
    [ValidateSet('architecture', 'flow')]
    [string]$DiagramKind = 'architecture',
    [string]$ReportDirectory = '<local-path>'
)

$ErrorActionPreference = 'Stop'
$taskScripts = @{
    inspect = '00-inspect.groovy'
    'create-edit' = '10-create-edit.groovy'
    'repair-sa' = '12-repair-sa-black-box.groovy'
    verify = '20-verify.groovy'
    rename = '30-rename.groovy'
    'verify-rename' = '31-verify-rename.groovy'
    delete = '40-delete.groovy'
    'verify-deleted' = '41-verify-deleted.groovy'
    diagram = '50-diagram.groovy'
    'verify-diagrams' = '51-verify-diagrams.groovy'
    'delete-diagram' = '60-delete-diagram.groovy'
    'native-validation' = '80-native-validation.groovy'
}
if (!$Token) { $Token = (Get-Content -LiteralPath (Join-Path $HOME ".capella-ai/devserver-$(([uri]$Endpoint).Port).token") -Raw).Trim() }
New-Item -ItemType Directory -Path $ReportDirectory -Force | Out-Null
foreach ($taskPhase in $Phases) {
    $taskSource = Get-Content -LiteralPath (Join-Path $PSScriptRoot $taskScripts[$taskPhase]) -Raw
    if ($taskPhase -eq 'diagram') {
        $taskSource = $taskSource.Replace("'__LAYER__'", "'$DiagramLayer'").Replace("'__KIND__'", "'$DiagramKind'")
    }
    $taskResponse = Invoke-WebRequest -Uri ($Endpoint.TrimEnd('/') + '/execute') -Method Post `
        -ContentType 'text/plain; charset=utf-8' -Body ([System.Text.Encoding]::UTF8.GetBytes($taskSource)) `
        -Headers @{ 'X-Conversation-Id' = $Conversation; Authorization = "Bearer $Token" } -TimeoutSec 60 -SkipHttpErrorCheck
    $taskResult = $taskResponse.Content | ConvertFrom-Json
    $taskStamp = Get-Date -Format 'yyyyMMdd-HHmmss-fff'
    $taskLabel = if ($taskPhase -eq 'diagram') { "$taskPhase-$DiagramLayer-$DiagramKind" } else { $taskPhase }
    [System.IO.File]::WriteAllText((Join-Path $ReportDirectory "$taskStamp-$taskLabel.json"), $taskResponse.Content)
    if (!$taskResult.success) {
        $taskResult | ConvertTo-Json -Depth 15
        throw "Native phase '$taskLabel' failed. Inspect saved response before rerunning a mutation."
    }
    Write-Output "PASS $taskLabel"
    if ($taskResult.value) {
        $taskResult.value
    }
}
