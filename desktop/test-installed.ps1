# Smoke test for the packaged Windows app: starts it with a throwaway profile, downloads a
# real file over HTTPS through the local API and checks the result.
#   powershell -File desktop\test-installed.ps1 [-Exe <path>] [-Url <https url>]
param(
    [string]$Exe = "$PSScriptRoot\build\compose\binaries\main\app\Imad Downloader\Imad Downloader.exe",
    [string]$Url = "https://github.com/ImadKhan1999/Imad-Downloader/archive/refs/heads/main.zip"
)
$ErrorActionPreference = "Stop"
$work = Join-Path $env:TEMP ("imad-smoke-" + [guid]::NewGuid().ToString("N").Substring(0, 8))
$data = Join-Path $work "data"; $dl = Join-Path $work "downloads"
New-Item -ItemType Directory -Force $data | Out-Null
@{ saveDir = $dl; categorySubfolders = $false; showStartDialog = $false; showProgressWindow = $false; showCompleteDialog = $false; monitorClipboard = $false; notifications = $false; soundOnComplete = $false } |
    ConvertTo-Json | Out-File -Encoding ascii (Join-Path $data "settings.json")
$env:IMAD_DATA_DIR = $data
$proc = Start-Process -FilePath $Exe -PassThru
try {
    $port = $null
    $deadline = (Get-Date).AddSeconds(60)
    while (-not $port -and (Get-Date) -lt $deadline) {
        foreach ($p in 9614..9617) {
            try { Invoke-WebRequest -UseBasicParsing -Method Post "http://127.0.0.1:$p/api/ping" -Headers @{ "X-Imad-Client" = "smoke" } -Body "{}" -TimeoutSec 2 | Out-Null; $port = $p; break } catch {}
        }
        if (-not $port) { [Threading.Thread]::Sleep(1000) }
    }
    if (-not $port) { throw "app did not start" }
    $body = @{ url = $Url; mode = "silent" } | ConvertTo-Json
    Invoke-WebRequest -UseBasicParsing -Method Post "http://127.0.0.1:$port/api/download" -Headers @{ "X-Imad-Client" = "smoke" } -ContentType "application/json" -Body $body | Out-Null
    $state = Join-Path $data "downloads.json"
    $deadline = (Get-Date).AddSeconds(120)
    $item = $null
    while ((Get-Date) -lt $deadline) {
        [Threading.Thread]::Sleep(1500)
        if (Test-Path $state) {
            $item = (Get-Content $state -Raw | ConvertFrom-Json).downloads | Select-Object -First 1
            if ($item.status -in "COMPLETED", "FAILED") { break }
        }
    }
    if ($item.status -ne "COMPLETED") { throw "download did not complete: $($item.status) $($item.error)" }
    $file = Join-Path $item.saveDir $item.fileName
    "PASS: $($item.fileName) $((Get-Item $file).Length) bytes over HTTPS via the packaged app"
} finally {
    Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 1
    [IO.Directory]::Delete($work, $true)
}
