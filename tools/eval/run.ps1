param(
    [string]$BaseUrl = 'http://127.0.0.1:8080',
    [int]$TimeoutSeconds = 60
)

$ErrorActionPreference = 'Stop'
$fixtures = Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'fixtures') -Filter '*.srt' | Sort-Object Name
if ($fixtures.Count -eq 0) {
    throw "没有找到评测素材: $PSScriptRoot\fixtures"
}

$results = @()
foreach ($fixture in $fixtures) {
    $form = @{
        file  = $fixture
        title = $fixture.BaseName
    }
    $task = Invoke-RestMethod -Uri "$BaseUrl/api/tasks" -Method Post -Form $form `
        -Headers @{ 'X-User-Id' = 'eval-runner' } -TimeoutSec 30

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $current = $task
    while ((Get-Date) -lt $deadline) {
        $current = Invoke-RestMethod -Uri "$BaseUrl/api/tasks/$($task.id)" -TimeoutSec 10
        if ($current.status -in @('DONE', 'FAILED')) {
            break
        }
        Start-Sleep -Seconds 1
    }

    $results += [pscustomobject]@{
        fixture = $fixture.Name
        taskId  = $task.id
        status  = $current.status
        stage   = $current.stage
    }
    Write-Host ("{0} -> task {1}: {2}" -f $fixture.Name, $task.id, $current.status)
}

$metrics = Invoke-RestMethod -Uri "$BaseUrl/api/eval" -TimeoutSec 10
Write-Host ''
$metrics | ConvertTo-Json
$results | Format-Table -AutoSize
