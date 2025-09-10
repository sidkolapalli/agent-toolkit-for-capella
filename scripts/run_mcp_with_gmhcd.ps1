$env:PYTHONPATH = "src"
$model = Resolve-Path "test_gmhcd_copy/GMHCD.aird" -ErrorAction SilentlyContinue
if (-not $model) { Write-Error "GMHCD.aird not found under test_gmhcd_copy/"; exit 1 }
$env:CAPELLA_MODEL_PATH = $model.Path
if (-not (Get-Command python -ErrorAction SilentlyContinue)) { Write-Error "Python not found in PATH"; exit 1 }
python -m capella_mcp

