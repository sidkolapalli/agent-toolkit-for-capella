# Standalone LLM Test Script
# Usage: .\test-llm.ps1 -ApiKey "sk-ant-..."
# Or set ANTHROPIC_API_KEY environment variable first

param(
    [string]$ApiKey = $env:ANTHROPIC_API_KEY,
    [string]$Model = "claude-sonnet-4-20250514",
    [string]$BaseUrl = "https://api.anthropic.com"
)

if (-not $ApiKey) {
    Write-Host "ERROR: No API key provided. Use -ApiKey parameter or set ANTHROPIC_API_KEY environment variable" -ForegroundColor Red
    exit 1
}

Write-Host "Testing Anthropic API connection..." -ForegroundColor Cyan
Write-Host "Model: $Model" -ForegroundColor Gray
Write-Host "Base URL: $BaseUrl" -ForegroundColor Gray

$headers = @{
    "Content-Type" = "application/json"
    "x-api-key" = $ApiKey
    "anthropic-version" = "2023-06-01"
}

$body = @{
    model = $Model
    max_tokens = 256
    temperature = 0.7
    messages = @(
        @{
            role = "user"
            content = @(
                @{
                    type = "text"
                    text = "Reply with exactly one word: PONG"
                }
            )
        }
    )
} | ConvertTo-Json -Depth 10

try {
    Write-Host "`nSending test request..." -ForegroundColor Yellow
    $response = Invoke-RestMethod -Uri "$BaseUrl/v1/messages" -Method Post -Headers $headers -Body $body

    Write-Host "`n=== SUCCESS ===" -ForegroundColor Green
    Write-Host "Response ID: $($response.id)"
    Write-Host "Model: $($response.model)"
    Write-Host "Stop Reason: $($response.stop_reason)"
    Write-Host "Input Tokens: $($response.usage.input_tokens)"
    Write-Host "Output Tokens: $($response.usage.output_tokens)"
    Write-Host "`nContent:" -ForegroundColor Cyan
    foreach ($block in $response.content) {
        if ($block.type -eq "text") {
            Write-Host $block.text
        }
    }

    # Test 2: TOOL_PLAN generation
    Write-Host "`n`n=== Testing TOOL_PLAN Generation ===" -ForegroundColor Cyan
    $toolPlanBody = @{
        model = $Model
        max_tokens = 1024
        temperature = 0.5
        system = @"
You are a Capella AI assistant. When asked to create model elements, respond with a TOOL_PLAN JSON block like:

```TOOL_PLAN
{
  "actions": [
    { "create_component": { "layer": "LA", "name": "ComponentName" } }
  ]
}
```

Only output the TOOL_PLAN block, nothing else.
"@
        messages = @(
            @{
                role = "user"
                content = @(
                    @{
                        type = "text"
                        text = "Create a logical component called 'SensorUnit'"
                    }
                )
            }
        )
    } | ConvertTo-Json -Depth 10

    $toolResponse = Invoke-RestMethod -Uri "$BaseUrl/v1/messages" -Method Post -Headers $headers -Body $toolPlanBody

    Write-Host "TOOL_PLAN Response:" -ForegroundColor Green
    foreach ($block in $toolResponse.content) {
        if ($block.type -eq "text") {
            Write-Host $block.text
        }
    }

    Write-Host "`n=== ALL TESTS PASSED ===" -ForegroundColor Green
    exit 0
}
catch {
    Write-Host "`n=== ERROR ===" -ForegroundColor Red
    Write-Host "Status: $($_.Exception.Response.StatusCode.value__)" -ForegroundColor Red
    Write-Host "Error: $($_.Exception.Message)" -ForegroundColor Red
    if ($_.ErrorDetails.Message) {
        Write-Host "Details: $($_.ErrorDetails.Message)" -ForegroundColor Red
    }
    exit 1
}
