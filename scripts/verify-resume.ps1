<#
    断点续传验收脚本（对着本地已启动的服务跑）

    它模拟的正是"传到一半断网"：
      1. 建上传会话，只传前 1 个分片，然后"假装断网"（不再往下传）
      2. 重新连上：先 GET 会话状态，拿到服务端真实已收到的分片列表
      3. 只补传缺失分片，不重传已完成的
      4. 再故意重传一次第 0 片，验证幂等（不会多算、不会写坏）

    用法：pwsh -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify-resume.ps1
#>
param(
    [string]$BaseUrl = 'http://localhost:8080',
    [int]$SizeMb = 17,
    [int]$ChunkMb = 8
)

$ErrorActionPreference = 'Stop'
$total = [long]$SizeMb * 1MB
$chunkSize = [int]$ChunkMb * 1MB
$totalChunks = [int][Math]::Ceiling($total / $chunkSize)

Write-Host "文件大小: $SizeMb MB / 分片: $ChunkMb MB / 分片数: $totalChunks" -ForegroundColor Cyan

# 用固定内容填充，稍后要按分片校验 sha256
$bytes = New-Object byte[] $total
(New-Object Random 42).NextBytes($bytes)

function Get-Chunk([int]$index) {
    $start = [long]$index * $chunkSize
    $len = [int][Math]::Min([long]$chunkSize, $total - $start)
    $buf = New-Object byte[] $len
    [Array]::Copy($bytes, $start, $buf, 0, $len)
    return ,$buf
}

function Get-Sha256([byte[]]$data) {
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($sha.ComputeHash($data)) -replace '-', '').ToLowerInvariant() }
    finally { $sha.Dispose() }
}

function Send-Chunk([string]$uploadId, [int]$index) {
    $data = Get-Chunk $index
    $resp = Invoke-RestMethod -Method Put `
        -Uri "$BaseUrl/api/uploads/$uploadId/chunks/$index" `
        -ContentType 'application/octet-stream' `
        -Headers @{ 'X-Chunk-SHA256' = (Get-Sha256 $data) } `
        -Body $data -TimeoutSec 120
    return $resp
}

function Get-Status([string]$uploadId) {
    return Invoke-RestMethod -Uri "$BaseUrl/api/uploads/$uploadId" -TimeoutSec 30
}

# ---- 1. 建会话 ----
$created = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/uploads" `
    -ContentType 'application/json' `
    -Headers @{ 'X-User-Id' = '1' } `
    -Body (@{
        fileName   = "resume-check-$SizeMb" + "mb.mp4"
        totalSize  = $total
        totalChunks = $totalChunks
        chunkSize  = $chunkSize
    } | ConvertTo-Json)
$uploadId = $created.uploadId
Write-Host "1) 会话已建: $uploadId" -ForegroundColor Green

# ---- 2. 只传第 0 片，然后"断网" ----
Send-Chunk $uploadId 0 | Out-Null
Write-Host "2) 只传了分片 0，然后模拟断网" -ForegroundColor Green

# ---- 3. 重连：向服务端要真实进度 ----
$status = Get-Status $uploadId
$uploaded = @($status.uploadedChunks)
Write-Host "3) 重连后服务端报已收到: [$($uploaded -join ',')] 共 $($uploaded.Count)/$totalChunks" -ForegroundColor Green
if ($uploaded.Count -ne 1 -or $uploaded[0] -ne 0) {
    throw "进度不对：期望只有分片 0"
}

# ---- 4. 只补缺失分片 ----
$missing = @(0..($totalChunks - 1) | Where-Object { $uploaded -notcontains $_ })
Write-Host "4) 需要补传: [$($missing -join ',')]" -ForegroundColor Green
foreach ($i in $missing) { Send-Chunk $uploadId $i | Out-Null }

$status2 = Get-Status $uploadId
Write-Host "   补传后: $($status2.uploadedChunks.Count)/$totalChunks" -ForegroundColor Green
if ($status2.uploadedChunks.Count -ne $totalChunks) { throw "分片没齐" }

# ---- 5. 幂等：再传一次第 0 片 ----
Send-Chunk $uploadId 0 | Out-Null
$status3 = Get-Status $uploadId
Write-Host "5) 重传分片 0 之后仍是: $($status3.uploadedChunks.Count)/$totalChunks（幂等 OK）" -ForegroundColor Green
if ($status3.uploadedChunks.Count -ne $totalChunks) { throw "重传把计数写坏了" }

# ---- 6. 校验和错误的请求应被拒绝 ----
$bad = Get-Chunk 1
$rejected = $false
try {
    Invoke-RestMethod -Method Put -Uri "$BaseUrl/api/uploads/$uploadId/chunks/1" `
        -ContentType 'application/octet-stream' `
        -Headers @{ 'X-Chunk-SHA256' = ('0' * 64) } `
        -Body $bad -TimeoutSec 60 | Out-Null
} catch {
    $rejected = $true
}
Write-Host "6) 错误校验和是否被拒绝: $rejected" -ForegroundColor Green
if (-not $rejected) { throw "坏分片没被拒绝" }

# ---- 7. 整文件哈希不符时，complete 必须拒绝 ----
$hashRejected = $false
try {
    Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/uploads/$uploadId/complete" `
        -ContentType 'application/json' `
        -Headers @{ 'X-User-Id' = '1' } `
        -Body (@{ title = 'hash-mismatch'; expectedSha256 = ('0' * 64) } | ConvertTo-Json) | Out-Null
} catch {
    $hashRejected = $true
}
Write-Host "7) 整文件哈希不符是否被拒绝: $hashRejected" -ForegroundColor Green
if (-not $hashRejected) { throw "整文件哈希不符没被拒绝" }

# ---- 8. 正向对照：哈希正确时能合并出任务 ----
$srt = @"
1
00:00:00,000 --> 00:00:05,000
Hello world, this is a resume test.

2
00:00:05,000 --> 00:00:10,000
The second cue keeps the pipeline happy.
"@
$srtBytes = [System.Text.Encoding]::UTF8.GetBytes($srt)
$srtCreated = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/uploads" `
    -ContentType 'application/json' `
    -Headers @{ 'X-User-Id' = '1' } `
    -Body (@{
        fileName    = 'resume-positive-check.srt'
        totalSize   = $srtBytes.Length
        totalChunks = 1
        chunkSize   = 1MB
    } | ConvertTo-Json)
$srtId = $srtCreated.uploadId

Invoke-RestMethod -Method Put -Uri "$BaseUrl/api/uploads/$srtId/chunks/0" `
    -ContentType 'application/octet-stream' `
    -Headers @{ 'X-Chunk-SHA256' = (Get-Sha256 $srtBytes) } `
    -Body $srtBytes -TimeoutSec 60 | Out-Null

$task = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/uploads/$srtId/complete" `
    -ContentType 'application/json' `
    -Headers @{ 'X-User-Id' = '1' } `
    -Body (@{ title = 'resume-positive-check'; expectedSha256 = (Get-Sha256 $srtBytes) } | ConvertTo-Json)
Write-Host "8) 哈希正确时合并成功，生成任务 #$($task.id)" -ForegroundColor Green

Write-Host "`n全部通过：断点续传、幂等重传、坏分片拦截、整文件哈希校验都正常。" -ForegroundColor Cyan
Write-Host "（第 8 步会在任务列表里留下一条 resume-positive-check，可忽略）" -ForegroundColor DarkGray
