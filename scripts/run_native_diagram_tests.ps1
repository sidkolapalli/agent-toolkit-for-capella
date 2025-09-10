Param(
    [string]$JavaServiceUrl = "http://localhost:8080/api/v1"
)

Write-Host "Starting Native Diagram Service tests against $JavaServiceUrl"
if (-not (Test-Path "test_gmhcd_copy\GMHCD.aird")) {
    Write-Error "GMHCD.aird not found under test_gmhcd_copy/"
    exit 1
}

if (-not (Get-Command python -ErrorAction SilentlyContinue)) {
    Write-Error "Python not found in PATH. Please install Python 3.9+ and pytest."
    exit 1
}

$env:JAVA_DIAGRAM_SERVICE = $JavaServiceUrl
pytest -q tests/integration/test_java_native_diagram_service.py

