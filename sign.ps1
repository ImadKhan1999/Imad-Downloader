<#
  Code-signs Windows files (Authenticode) so Windows shows the verified publisher instead of
  "Unknown publisher". release.ps1 calls it for the MSI and EXE installers; it can also be run alone:

    powershell -ExecutionPolicy Bypass -File sign.ps1 -Files dist\iDownloader-1.8.1.msi

  Signing is switched on by a signing.json file next to this script (git-ignored, never pushed).
  Without that file nothing is signed and the script just says so. One of:

    { "mode": "store", "thumbprint": "<certificate SHA-1 thumbprint>" }
        A certificate in the Windows certificate store, e.g. on a Certum / Sectigo / SSL.com USB token.
    { "mode": "pfx", "pfx": "E:\\path\\to\\cert.pfx" }
        A .pfx file; its password comes from the IDL_SIGN_PFX_PASSWORD environment variable.
    { "mode": "azure", "dlib": "C:\\...\\Azure.CodeSigning.Dlib.dll", "metadata": "E:\\...\\metadata.json" }
        Azure Trusted Signing (Microsoft's signing service). Sign in first with "az login".

  Optional keys: "timestamp" (RFC 3161 server, default DigiCert's), "signtool" (path to signtool.exe).
  The publisher name Windows shows is the name on the certificate; it cannot be set here.
  If signing is configured but fails, the script stops, so an unsigned build is never published by mistake.
#>
param(
    [Parameter(Mandatory = $true)][string[]]$Files,
    [string]$Config
)
$ErrorActionPreference = "Stop"
# Windows PowerShell 5.1 leaves $PSScriptRoot empty in param defaults under "powershell -File",
# and -File passes "a.msi,b.exe" as one string; handle both here.
if (-not $Config) { $Config = Join-Path (Split-Path -Parent $MyInvocation.MyCommand.Path) "signing.json" }
$Files = @($Files -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ })

if (-not (Test-Path $Config)) {
    "Code signing: no signing.json, files left unsigned (Windows will show 'Unknown publisher')."
    return
}
$cfg = Get-Content $Config -Raw | ConvertFrom-Json

$signtool = $cfg.signtool
if (-not $signtool) {
    # Windows SDK (or the "Microsoft.Windows.SDK.BuildTools" NuGet package) puts signtool here.
    $signtool = Get-ChildItem "${env:ProgramFiles(x86)}\Windows Kits\10\bin\*\x64\signtool.exe" -ErrorAction SilentlyContinue |
        Sort-Object FullName -Descending | Select-Object -First 1 -ExpandProperty FullName
}
if (-not $signtool -or -not (Test-Path $signtool)) {
    throw "Code signing is configured but signtool.exe was not found. Install the Windows SDK signing tools or set ""signtool"" in signing.json."
}
$timestamp = if ($cfg.timestamp) { $cfg.timestamp } else { "http://timestamp.digicert.com" }

$common = @("sign", "/fd", "SHA256", "/tr", $timestamp, "/td", "SHA256", "/d", "iDownloader", "/du", "https://t.me/ImadLabs")
switch ($cfg.mode) {
    "store" { $auth = @("/sha1", $cfg.thumbprint) }
    "pfx" {
        if (-not $env:IDL_SIGN_PFX_PASSWORD) { throw "Set IDL_SIGN_PFX_PASSWORD for the .pfx certificate." }
        $auth = @("/f", $cfg.pfx, "/p", $env:IDL_SIGN_PFX_PASSWORD)
    }
    "azure" { $auth = @("/dlib", $cfg.dlib, "/dmdf", $cfg.metadata) }
    default { throw "signing.json: mode must be store, pfx or azure (got '$($cfg.mode)')." }
}

foreach ($f in $Files) {
    $path = (Resolve-Path $f).Path
    # Timestamp servers sometimes time out; try a few times before giving up.
    for ($try = 1; ; $try++) {
        & $signtool @common @auth $path
        if ($LASTEXITCODE -eq 0) { break }
        if ($try -ge 3) { throw "Signing $path failed (signtool exit $LASTEXITCODE)" }
        Start-Sleep -Seconds (5 * $try)
    }
    $sig = Get-AuthenticodeSignature $path
    if (-not $sig.SignerCertificate) { throw "$path has no signature after signing" }
    "signed $([IO.Path]::GetFileName($path)): $($sig.SignerCertificate.Subject) [$($sig.Status)]"
}
