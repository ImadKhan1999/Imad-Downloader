<#
  Builds, tests and publishes an Imad Downloader release, then points the in-app updaters at it.

    powershell -ExecutionPolicy Bypass -File release.ps1 -Changelog "What changed"

  Steps: every test suite (engine, Windows, Android unit and emulator device tests, browser
  extension end-to-end, installed-app HTTPS smoke test) -> MSI/EXE/APK/extension zip ->
  GitHub release with the files -> update/version.json (versions, URLs, SHA-256) pushed to main,
  which is what the apps' "Check for updates" reads. Nothing is published if a test fails.
  The GitHub token comes from Git's credential manager; it is never written anywhere.
#>
param(
    [Parameter(Mandatory = $true)][string]$Changelog,
    [switch]$Mandatory,
    [switch]$SkipDeviceTests
)
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot
$repo = "ImadKhan1999/Imad-Downloader"
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"

$version = ([regex]::Match((Get-Content gradle\libs.versions.toml -Raw), 'app = "([^"]+)"')).Groups[1].Value
$code = [int]([regex]::Match((Get-Content androidApp\build.gradle.kts -Raw), 'versionCode = (\d+)')).Groups[1].Value
"Releasing $version (Android versionCode $code)"

function Step($name, [scriptblock]$block) {
    "== $name"
    & $block
    if ($LASTEXITCODE -and $LASTEXITCODE -ne 0) { throw "$name failed (exit $LASTEXITCODE)" }
}

# ---------------------------------------------------------------- tests and builds
if (-not $SkipDeviceTests) {
    $booted = (& $adb shell getprop sys.boot_completed 2>$null) -eq "1"
    if (-not $booted) {
        Start-Process -WindowStyle Hidden "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe" -ArgumentList "-avd imad_test -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -no-snapshot"
        & $adb wait-for-device
        $deadline = (Get-Date).AddMinutes(5)
        while ((& $adb shell getprop sys.boot_completed 2>$null) -ne "1" -and (Get-Date) -lt $deadline) { Start-Sleep 3 }
    }
}
$tasks = @(":core:test", ":desktop:test", ":androidApp:testDebugUnitTest", ":desktop:packageMsi", ":desktop:packageExe", ":desktop:createDistributable", ":androidApp:assembleRelease")
if (-not $SkipDeviceTests) { $tasks += ":androidApp:connectedDebugAndroidTest" }
Step "Gradle tests and builds" { & .\gradlew.bat @tasks --console=plain }
$exe = "desktop\build\compose\binaries\main\app\Imad Downloader\Imad Downloader.exe"
Step "Browser extension end-to-end" { $env:APP_EXE = (Resolve-Path $exe).Path; node extension\test\e2e.mjs; Remove-Item Env:APP_EXE }
Step "Installed app HTTPS smoke test" { powershell -NoProfile -ExecutionPolicy Bypass -File desktop\test-installed.ps1 -Exe (Resolve-Path $exe).Path -Url "https://proof.ovh.net/files/10Mb.dat" }

# ---------------------------------------------------------------- release files
New-Item -ItemType Directory -Force dist | Out-Null
Get-ChildItem dist -File | ForEach-Object { [IO.File]::Delete($_.FullName) }
$msiName = "Imad.Downloader-$version.msi"; $exeName = "Imad.Downloader-$version.exe"; $apkName = "ImadDownloader-$version.apk"; $zipName = "ImadDownloader-extension-$version.zip"
Copy-Item "desktop\build\compose\binaries\main\msi\Imad Downloader-$version.msi" "dist\$msiName"
Copy-Item "desktop\build\compose\binaries\main\exe\Imad Downloader-$version.exe" "dist\$exeName"
Copy-Item "androidApp\build\outputs\apk\release\androidApp-release.apk" "dist\$apkName"
Compress-Archive -Path extension\manifest.json, extension\background.js, extension\content.js, extension\content.css, extension\popup.html, extension\popup.css, extension\popup.js, extension\icons -DestinationPath "dist\$zipName" -Force

# ---------------------------------------------------------------- GitHub release
$tmp = [IO.Path]::GetTempFileName(); [IO.File]::WriteAllText($tmp, "protocol=https`nhost=github.com`n`n")
$cred = cmd /c "git credential fill < `"$tmp`""; [IO.File]::Delete($tmp)
$token = ($cred | Where-Object { $_ -like "password=*" }) -replace '^password=', ''
if (-not $token) { throw "No GitHub credentials in Git's credential manager" }
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$h = @{ Authorization = "Bearer $token"; Accept = "application/vnd.github+json"; "User-Agent" = "imad-release" }
$body = @{ tag_name = "v$version"; target_commitish = "main"; name = "Imad Downloader $version"; body = $Changelog } | ConvertTo-Json
$rel = Invoke-RestMethod -Method Post -Headers $h -ContentType "application/json" -Body $body "https://api.github.com/repos/$repo/releases"
$upload = $rel.upload_url -replace '\{.*\}', ''
foreach ($f in Get-ChildItem dist -File) {
    for ($try = 1; $try -le 4; $try++) {
        try {
            $ct = if ($f.Extension -eq ".apk") { "application/vnd.android.package-archive" } elseif ($f.Extension -eq ".zip") { "application/zip" } else { "application/octet-stream" }
            $a = Invoke-RestMethod -Method Post -Headers $h -ContentType $ct -InFile $f.FullName -TimeoutSec 1800 "$upload`?name=$($f.Name)"
            "uploaded $($a.name)"; break
        } catch {
            "upload of $($f.Name) failed (try $try): $($_.Exception.Message)"
            $existing = (Invoke-RestMethod -Headers $h "https://api.github.com/repos/$repo/releases/$($rel.id)/assets") | Where-Object { $_.name -eq $f.Name }
            foreach ($e in $existing) { Invoke-RestMethod -Method Delete -Headers $h $e.url }
            if ($try -eq 4) { throw }
            Start-Sleep -Seconds (2 * $try)
        }
    }
}

# ---------------------------------------------------------------- update manifest (what the apps read)
$dl = "https://github.com/$repo/releases/download/v$version"
function Sha($p) { (Get-FileHash $p -Algorithm SHA256).Hash.ToLower() }
$manifest = [ordered]@{
    android = [ordered]@{ version = $version; versionCode = $code; url = "$dl/$apkName"; sha256 = (Sha "dist\$apkName"); size = (Get-Item "dist\$apkName").Length; changelog = $Changelog; mandatory = [bool]$Mandatory }
    windows = [ordered]@{ version = $version; url = "$dl/$msiName"; sha256 = (Sha "dist\$msiName"); size = (Get-Item "dist\$msiName").Length; changelog = $Changelog; mandatory = [bool]$Mandatory }
}
New-Item -ItemType Directory -Force update | Out-Null
[IO.File]::WriteAllText("$PSScriptRoot\update\version.json", ($manifest | ConvertTo-Json -Depth 4), (New-Object Text.UTF8Encoding $false))
git add update/version.json
git commit -q -m "Publish update manifest for $version"
git push -q
"Released ${version}: $($rel.html_url)"
