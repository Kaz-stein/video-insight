param(
    [string]$EnvFile = (Join-Path $PSScriptRoot '..\.env.local')
)

$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Set-Location $root

if (Test-Path -LiteralPath $EnvFile) {
    Get-Content -LiteralPath $EnvFile | ForEach-Object {
        $line = $_.Trim()
        if ($line -and -not $line.StartsWith('#')) {
            $idx = $line.IndexOf('=')
            if ($idx -gt 0) {
                $name = $line.Substring(0, $idx).Trim()
                $value = $line.Substring($idx + 1).Trim().Trim('"').Trim("'")
                Set-Item -Path "Env:$name" -Value $value
            }
        }
    }
}

if (-not $env:JAVA_HOME) {
    $env:JAVA_HOME = 'D:\IntelliJ IDEA 2026.1\jbr'
}
$env:Path = "C:\Windows\System32;C:\Windows;" + $env:Path

if (-not $env:MQ_ENABLED) { $env:MQ_ENABLED = 'true' }
if (-not $env:LLM_PROVIDER) { $env:LLM_PROVIDER = 'fake' }
if (-not $env:RATE_LIMIT_MODE) { $env:RATE_LIMIT_MODE = 'memory' }
if (-not $env:FFMPEG_PATH) {
    $env:FFMPEG_PATH = 'C:\Users\pence\Downloads\ffmpeg-master-latest-win64-gpl-shared\ffmpeg-master-latest-win64-gpl-shared\bin\ffmpeg.exe'
}
if (-not $env:YTDLP_PATH) {
    $env:YTDLP_PATH = 'C:\Users\pence\Downloads\ytdlp-interface\yt-dlp.exe'
}

& (Join-Path $env:JAVA_HOME 'bin\java.exe') -jar 'target\video-insight-0.0.1-SNAPSHOT.jar'
