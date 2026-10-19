<#
    本地一键启动脚本（Windows / PowerShell 7）

    常见的"无法执行"基本就三种，这里都兜住了：
      1) 在 cmd 里直接敲 .\scripts\run-local.ps1   -> 用 run-local.cmd，或者 pwsh -File ...
      2) 执行策略禁止运行脚本                       -> run-local.cmd 已带 -ExecutionPolicy Bypass
      3) 8080 还被上一次的 java 占着                 -> 加 -Restart 自动停旧进程再起

    用法：
        .\scripts\run-local.cmd                 # 最简单，cmd / 双击都能用
        .\scripts\run-local.cmd -Restart        # 重启
        pwsh -NoProfile -ExecutionPolicy Bypass -File .\scripts\run-local.ps1
#>
param(
    [string]$EnvFile = (Join-Path $PSScriptRoot '..\.env.local'),
    [switch]$Restart,
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'

$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Set-Location $root

# 让重定向到文件的日志也是 UTF-8，避免 cmd 里看中文乱码
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

function Write-Step($text) { Write-Host "==> $text" -ForegroundColor Cyan }
function Write-Fail($text) { Write-Host "[x] $text" -ForegroundColor Red; exit 1 }

<#
    判断 jar 是否"可启动"。

    只比时间戳不够：mvn 会先产出普通 jar，再做 repackage 加上启动入口。
    如果 repackage 失败（Windows 上旧进程锁着 jar 时就会这样），
    磁盘上就留下一个"时间很新但没有 Main-Class"的残次品。
#>
function Test-JarReady($path) {
    if (-not (Test-Path -LiteralPath $path)) { return $false }
    try {
        Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction SilentlyContinue
        $zip = [System.IO.Compression.ZipFile]::OpenRead($path)
        try {
            $entry = $zip.Entries | Where-Object { $_.FullName -eq 'META-INF/MANIFEST.MF' } | Select-Object -First 1
            if (-not $entry) { return $false }
            $reader = New-Object System.IO.StreamReader($entry.Open())
            try { $manifest = $reader.ReadToEnd() } finally { $reader.Dispose() }
            return ($manifest -match 'Main-Class:')
        } finally {
            $zip.Dispose()
        }
    } catch {
        return $false
    }
}

# ---------- 1. 读取 .env.local ----------
# 只做 KEY=VALUE 解析，支持注释、export 前缀、成对引号
if (Test-Path -LiteralPath $EnvFile) {
    Get-Content -LiteralPath $EnvFile | ForEach-Object {
        $line = $_.Trim()
        if (-not $line -or $line.StartsWith('#')) { return }
        if ($line.StartsWith('export ')) { $line = $line.Substring(7).Trim() }
        $idx = $line.IndexOf('=')
        if ($idx -le 0) { return }
        $name = $line.Substring(0, $idx).Trim()
        $value = $line.Substring($idx + 1).Trim()
        if ($value.Length -ge 2) {
            $first = $value.Substring(0, 1)
            $last = $value.Substring($value.Length - 1, 1)
            if (($first -eq '"' -and $last -eq '"') -or ($first -eq "'" -and $last -eq "'")) {
                $value = $value.Substring(1, $value.Length - 2)
            }
        }
        Set-Item -Path "Env:$name" -Value $value
    }
    Write-Step "已加载 $EnvFile"
} else {
    Write-Host "[!] 没找到 $EnvFile，将使用 application.yml 里的默认配置" -ForegroundColor Yellow
}

# ---------- 2. 定位 JDK ----------
$javaHomeCandidates = @(
    $env:JAVA_HOME,
    'D:\IntelliJ IDEA 2026.1\jbr',
    'C:\Program Files\Java\jdk-25'
) | Where-Object { $_ -and (Test-Path (Join-Path $_ 'bin\java.exe')) }

if (-not $javaHomeCandidates) {
    Write-Fail '找不到 JDK，请先设置 JAVA_HOME 指向 JDK 目录'
}
$env:JAVA_HOME = @($javaHomeCandidates)[0]
$java = Join-Path $env:JAVA_HOME 'bin\java.exe'
Write-Step "JAVA_HOME=$env:JAVA_HOME"

# 部分终端 PATH 被裁剪，补上系统目录，避免 java 找不到运行库
$env:Path = "C:\Windows\System32;C:\Windows;" + $env:Path

# ---------- 3. 缺省配置 ----------
if (-not $env:MQ_ENABLED) { $env:MQ_ENABLED = 'true' }
if (-not $env:LLM_PROVIDER) { $env:LLM_PROVIDER = 'fake' }
if (-not $env:RATE_LIMIT_MODE) { $env:RATE_LIMIT_MODE = 'memory' }
if (-not $env:FFMPEG_PATH) {
    $env:FFMPEG_PATH = 'C:\Users\pence\Downloads\ffmpeg-master-latest-win64-gpl-shared\ffmpeg-master-latest-win64-gpl-shared\bin\ffmpeg.exe'
}
if (-not $env:YTDLP_PATH) {
    $env:YTDLP_PATH = 'C:\Users\pence\Downloads\ytdlp-interface\yt-dlp.exe'
}

# 填了 key 却忘了改 provider 时自动纠正，否则会出现"key 填了但笔记还是抄原句"的困惑
if ($env:LLM_API_KEY -and $env:LLM_PROVIDER -eq 'fake') {
    $env:LLM_PROVIDER = 'http'
    Write-Host "[i] 检测到 LLM_API_KEY 非空，自动把 LLM_PROVIDER 从 fake 切到 http" -ForegroundColor Yellow
}

# ---------- 4. 判断是否需要重新打包 ----------
$jar = Join-Path $root 'target\video-insight-0.0.1-SNAPSHOT.jar'
$needBuild = -not (Test-JarReady $jar)

if (-not $needBuild) {
    $jarTime = (Get-Item $jar).LastWriteTimeUtc
    $watch = @((Join-Path $root 'src'), (Join-Path $root 'pom.xml')) |
        Where-Object { Test-Path $_ }
    $newer = Get-ChildItem -Path $watch -Recurse -File -ErrorAction SilentlyContinue |
        Where-Object { $_.LastWriteTimeUtc -gt $jarTime } |
        Select-Object -First 1
    if ($newer) {
        $needBuild = $true
        Write-Host "[!] 源码比 jar 新：$($newer.Name)" -ForegroundColor Yellow
    }
}

# ---------- 5. 端口占用检查（必须放在打包之前） ----------
# Windows 会锁住正在运行的 jar，mvn repackage 时改名会直接失败。
# 所以"要重新打包"和"要重启"一样，都得先把旧实例停掉。
$port = 8080
$listener = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue |
    Select-Object -First 1

if ($listener) {
    $owner = $listener.OwningProcess
    $proc = Get-CimInstance Win32_Process -Filter "ProcessId=$owner" -ErrorAction SilentlyContinue
    $cmdLine = if ($proc) { [string]$proc.CommandLine } else { '' }

    if ($cmdLine -like '*video-insight*') {
        if ($Restart -or $needBuild) {
            $why = if ($Restart) { '-Restart' } else { '源码有更新，需要重新打包' }
            Write-Step "停止旧实例 PID=$owner（$why）"
            Stop-Process -Id $owner -Force
            for ($i = 0; $i -lt 10; $i++) {
                Start-Sleep -Milliseconds 400
                if (-not (Get-Process -Id $owner -ErrorAction SilentlyContinue)) { break }
            }
        } else {
            Write-Host "[!] 8080 已经被本项目的旧实例占用（PID=$owner），没有再启动一份。" -ForegroundColor Yellow
            Write-Host "    直接打开： http://localhost:8080" -ForegroundColor Yellow
            Write-Host "    要重启请用： .\scripts\run-local.cmd -Restart" -ForegroundColor Yellow
            exit 0
        }
    } else {
        $pname = if ($proc) { $proc.Name } else { '未知进程' }
        Write-Fail "8080 被其他程序占用：PID=$owner $pname，请先处理它"
    }
}

# ---------- 6. 打包 ----------
if ($needBuild -and -not $SkipBuild) {
    $mvn = $null
    $mvnCmd = Get-Command mvn -ErrorAction SilentlyContinue
    if ($mvnCmd) { $mvn = $mvnCmd.Source }
    if (-not $mvn) {
        $bundled = 'D:\IntelliJ IDEA 2026.1\plugins\maven\lib\maven3\bin\mvn.cmd'
        if (Test-Path $bundled) { $mvn = $bundled }
    }
    if (-not $mvn) { Write-Fail '需要重新打包，但找不到 mvn。请先手动执行 mvn -B -DskipTests package' }

    Write-Step "开始打包：$mvn"
    & $mvn -B -DskipTests package
    if ($LASTEXITCODE -ne 0) { Write-Fail "打包失败（退出码 $LASTEXITCODE）" }
    if (-not (Test-JarReady $jar)) {
        Write-Fail "打包结束了，但 jar 里没有启动入口（repackage 没成功），请检查上面的 Maven 输出"
    }
}

if (-not (Test-JarReady $jar)) {
    Write-Fail "jar 不存在或不可启动：$jar。请执行 mvn -B -DskipTests package"
}

# ---------- 7. 启动 ----------
Write-Step "启动 http://localhost:8080   (Ctrl+C 停止)"
& $java -jar $jar
