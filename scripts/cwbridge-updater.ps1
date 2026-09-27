# CWBridge Updater for Windows
# Installs / updates the CWBridge Android app on a USB-connected device.
# Mirrors the Android OTG helper (helper/src/main/java/com/cwbridge/helper).
#
#   .\cwbridge-updater.ps1                  # interactive
#   .\cwbridge-updater.ps1 -Status          # read-only device report
#   .\cwbridge-updater.ps1 -Serial <id>     # target a specific device
#   .\cwbridge-updater.ps1 -SkipGrant -SkipLaunch
#   .\cwbridge-updater.ps1 -Logs            # tail logcat, then exit
param(
  [string]$Adb,
  [string]$Serial,
  [string]$Apk,
  [switch]$Status,
  [switch]$Logs,
  [switch]$SkipGrant,
  [switch]$SkipLaunch,
  [switch]$SkipShizuku
)

$ErrorActionPreference = 'Stop'
$Owner      = 'angamer234k'
$Repo       = 'cwbridge-androidfork'
$Api        = "https://api.github.com/repos/$Owner/$Repo/releases/latest"
$TargetPkg  = 'com.cwbridge.android.debug'
$ShizukuPkg = 'moe.shizuku.privileged.api'
$ReadLogs   = 'android.permission.READ_LOGS'
$CacheDir   = Join-Path $env:TEMP 'cwbridge-updater'

function Write-Step($m) { Write-Host "==> $m" -ForegroundColor Cyan }
function Write-Info($m) { Write-Host "    $m" }
function Write-Good($m) { Write-Host "OK   $m" -ForegroundColor Green }
function Write-Warn2($m) { Write-Host "WARN $m" -ForegroundColor Yellow }
function Write-Err2($m) { Write-Host "ERR  $m" -ForegroundColor Red }
function Die($m) { Write-Err2 $m; exit 1 }

function Find-Adb {
  if ($Adb) {
    if (Test-Path $Adb) { return $Adb }
    Die "-Adb path not found: $Adb"
  }
  $cmd = Get-Command adb -ErrorAction SilentlyContinue
  if ($cmd) { return $cmd.Source }
  # @() is required: Where-Object unrolls a single result to a string, and
  # $cands[0] on a string would return the first CHARACTER, not the path.
  $cands = @(@(
    "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
    "$env:USERPROFILE\AppData\Local\Android\Sdk\platform-tools\adb.exe",
    "$env:ANDROID_HOME\platform-tools\adb.exe",
    "$env:ANDROID_SDK_ROOT\platform-tools\adb.exe",
    'C:\Android\platform-tools\adb.exe',
    'D:\Android\platform-tools\adb.exe',
    "$env:ProgramFiles\Android\platform-tools\adb.exe"
  ) | Where-Object { $_ -and (Test-Path $_) })
  if ($cands.Count -gt 0) { return [string]$cands[0] }
  Die "adb.exe not found. Install Android platform-tools, or pass -Adb <path>."
}

$script:AdbExe = $null
$script:AdbBase = @()

function Invoke-Adb {
  param([Parameter(ValueFromRemainingArguments = $true)][string[]]$AdbArgs)
  # adb writes progress and warnings to stderr even on success ("* daemon not
  # running", "adb server version mismatch"). With $ErrorActionPreference='Stop'
  # those become terminating errors, so relax it for the duration of the call
  # and treat the exit code / output text as the real signal.
  $prev = $ErrorActionPreference
  $ErrorActionPreference = 'Continue'
  try {
    $out = & $script:AdbExe @($script:AdbBase) @AdbArgs 2>&1
    return ($out | ForEach-Object { if ($_ -is [System.Management.Automation.ErrorRecord]) { $_.ToString() } else { "$_" } } | Out-String).Trim()
  } finally {
    $ErrorActionPreference = $prev
  }
}

function Get-Devices {
  $raw = Invoke-Adb 'devices'
  $list = @()
  foreach ($line in ($raw -split "`r?`n")) {
    $t = $line.Trim()
    if (-not $t -or $t -like 'List of devices*' -or $t -like '*daemon*') { continue }
    if ($t -match '^(\S+)\s+(device|offline|unauthorized|recovery)(?:\s+.*)?$') {
      $list += [pscustomobject]@{ Serial = $Matches[1]; State = $Matches[2] }
    }
  }
  return $list
}

function Select-Device {
  # @() guards against PowerShell unrolling: returning a one-element array from
  # Get-Devices yields a bare object, and .Count on that is unreliable.
  $devs = @(Get-Devices)
  if ($devs.Count -eq 0) { Die "No devices. Connect USB, enable USB debugging, accept the prompt." }
  if ($Serial) {
    $d = $devs | Where-Object { $_.Serial -eq $Serial } | Select-Object -First 1
    if (-not $d) { Die "Device '$Serial' not found. Seen: $(($devs | ForEach-Object { $_.Serial }) -join ', ')" }
    return $d
  }
  if ($devs.Count -eq 1) { return $devs[0] }
  Write-Step "Multiple devices:"
  for ($i = 0; $i -lt $devs.Count; $i++) {
    Write-Host ("    [{0}] {1}  ({2})" -f ($i + 1), $devs[$i].Serial, $devs[$i].State) -ForegroundColor Gray
  }
  $choice = Read-Host "Pick a device [1]"
  $idx = 1
  if ($choice) { if (-not [int]::TryParse($choice, [ref]$idx)) { Die "Not a number: $choice" } }
  if ($idx -lt 1 -or $idx -gt $devs.Count) { Die "Out of range" }
  return $devs[$idx - 1]
}

function Get-Prop([string]$name) {
  $v = Invoke-Adb 'shell' 'getprop' $name
  if ($v -match '^\s*$') { return '' }
  return ($v -split "`r?`n")[0].Trim()
}

function Test-PkgInstalled([string]$pkg) {
  return ((Invoke-Adb 'shell' 'pm' 'path' $pkg) -match 'package:')
}

function Get-InstalledVersion([string]$pkg) {
  $m = [regex]::Match((Invoke-Adb 'shell' 'dumpsys' 'package' $pkg), 'versionName=(\S+)')
  if ($m.Success) { return $m.Groups[1].Value }
  return ''
}

function Get-LatestRelease {
  $headers = @{ 'User-Agent' = 'CWBridge-Updater'; 'Accept' = 'application/vnd.github+json' }
  try {
    return Invoke-RestMethod -Uri $Api -Headers $headers -UseBasicParsing -TimeoutSec 30
  } catch {
    Die "Could not reach GitHub releases: $($_.Exception.Message)"
  }
}

function Select-ApkAsset($release) {
  # The release ships the main app AND the helper. Never pick the helper: it is
  # a different package and would install the wrong app on the device.
  $apks  = @($release.assets | Where-Object { $_.name -like '*.apk' })
  $main  = $apks | Where-Object { $_.name -notlike '*helper*' }
  if ($main.Count -eq 0) { Die "No main-app APK asset on release $($release.tag_name)" }
  $android = $main | Where-Object { $_.name -like '*android*' }
  if ($android) { return $android[0] }
  return $main[0]
}

function Get-Apk {
  param($Release, $Asset)
  New-Item -ItemType Directory -Force -Path $CacheDir | Out-Null
  $dest = Join-Path $CacheDir $Asset.name
  $meta = "$dest.tag"
  $remoteSize = [int64]$Asset.size

  if (Test-Path $dest) {
    $len = (Get-Item $dest).Length
    if ($remoteSize -le 0 -or $len -eq $remoteSize) {
      if (-not (Test-Path $meta)) { Set-Content -Path $meta -Value $Release.tag_name }
      Write-Info "cached: $dest"
      return $dest
    }
    Write-Info "stale cache ($len vs $remoteSize bytes) - re-downloading"
  }

  $tmp = "$dest.part"
  if (Test-Path $tmp) { Remove-Item $tmp -Force }
  Write-Info "downloading $($Asset.name) ($remoteSize bytes)..."

  # Streamed download. Invoke-WebRequest buffers the whole body in memory and
  # can stall for minutes on big assets, so use HttpWebRequest + a copy loop.
  $job = Start-Job -ScriptBlock {
    param($url, $target)
    $req = [System.Net.HttpWebRequest]::Create($url)
    $req.UserAgent = 'CWBridge-Updater'
    $req.Timeout = 120000
    $req.ReadWriteTimeout = 120000
    $resp = $req.GetResponse()
    $in = $resp.GetResponseStream()
    $out = [System.IO.File]::Create($target)
    try {
      $buf = New-Object byte[] 65536
      while (($n = $in.Read($buf, 0, $buf.Length)) -gt 0) { $out.Write($buf, 0, $n) }
    } finally { $out.Close(); $in.Close(); $resp.Close() }
  } -ArgumentList $Asset.browser_download_url, $tmp

  $sw = [System.Diagnostics.Stopwatch]::StartNew()
  while ($job.State -eq 'Running') {
    $have = if (Test-Path $tmp) { (Get-Item $tmp).Length } else { 0 }
    $pct = if ($remoteSize -gt 0) { [math]::Round(100 * $have / $remoteSize) } else { 0 }
    Write-Host ("`r    {0,3}%  {1} / {2} bytes" -f $pct, $have, $remoteSize) -NoNewline -ForegroundColor DarkGray
    Start-Sleep -Milliseconds 700
  }
  Write-Host ""
  $jobFailed = $job.State -ne 'Completed'
  Receive-Job $job -ErrorAction SilentlyContinue | Out-Null
  Remove-Job $job -Force -ErrorAction SilentlyContinue
  if ($jobFailed -or -not (Test-Path $tmp)) {
    if (Test-Path $tmp) { Remove-Item $tmp -Force -ErrorAction SilentlyContinue }
    Die "download failed after $([int]$sw.Elapsed.TotalSeconds)s - partial file removed."
  }

  if ($remoteSize -gt 0 -and (Get-Item $tmp).Length -ne $remoteSize) {
    $got = (Get-Item $tmp).Length
    Remove-Item $tmp -Force -ErrorAction SilentlyContinue
    Die "download size mismatch (got $got, expected $remoteSize) - file removed, retry."
  }
  if (Test-Path $dest) { Remove-Item $dest -Force }
  Move-Item $tmp $dest
  Set-Content -Path $meta -Value $Release.tag_name
  Write-Info "saved: $dest"
  return $dest
}

$script:AdbExe = Find-Adb
Write-Info "adb: $script:AdbExe"
$ver = (Invoke-Adb 'version') -split "`r?`n"
if ($ver) { Write-Info $ver[0].Trim() }

$dev = Select-Device
$script:AdbBase = @('-s', $dev.Serial)
if ($dev.State -ne 'device') { Die "Device state is '$($dev.State)'. Accept the USB debugging prompt and retry." }
Write-Good "device $($dev.Serial)"

$android   = Get-Prop 'ro.build.version.release'
$sdk       = Get-Prop 'ro.build.version.sdk'
$installed = Test-PkgInstalled $TargetPkg
$instVer   = if ($installed) { Get-InstalledVersion $TargetPkg } else { '' }
$shiz      = Test-PkgInstalled $ShizukuPkg
$a11y      = Invoke-Adb 'shell' 'settings' 'get' 'secure' 'enabled_accessibility_services'

Write-Step "Device"
Write-Info "Android $android (SDK $sdk)"
Write-Info "CWBridge installed: $installed $(if ($instVer) { "($instVer)" })"
Write-Info "Shizuku installed: $shiz"
if ($a11y -like "*$TargetPkg*") {
  Write-Good "CWBridge Tap accessibility service is enabled"
} else {
  Write-Warn2 "CWBridge Tap is NOT enabled - turn it on in the device's Accessibility settings"
}

if ($Logs) {
  Write-Step "logcat (Ctrl+C to stop)"
  & $script:AdbExe @($script:AdbBase) logcat -v threadtime '*:E' 'CWBridge:V' 'Roblox:V' '*:S'
  exit 0
}
if ($Status) { exit 0 }

$apkPath = $Apk
if ($apkPath) {
  if (-not (Test-Path $apkPath)) { Die "APK not found: $apkPath" }
  $apkPath = (Resolve-Path $apkPath).Path
  Write-Info "using local APK: $apkPath"
} else {
  Write-Step "Checking GitHub releases"
  $release = Get-LatestRelease
  $asset   = Select-ApkAsset $release
  Write-Info "latest release: $($release.tag_name)"
  Write-Info "asset: $($asset.name)"
  if ($instVer) { Write-Info "installed version: $instVer" }
  $apkPath = Get-Apk $release $asset
}

Write-Step "Installing / updating"
$installOut = Invoke-Adb 'install' '-r' '-g' $apkPath
($installOut -split "`r?`n" | Select-Object -Last 2) | ForEach-Object { Write-Info $_.Trim() }

if ($installOut -notmatch 'Success') {
  Write-Err2 "install failed"
  Write-Host $installOut
  Write-Host ""
  Write-Host "Common causes:" -ForegroundColor Yellow
  Write-Host "  INSTALL_FAILED_UPDATE_INCOMPATIBLE - a differently-signed build is installed." -ForegroundColor Yellow
  Write-Host "    Fix: adb -s $($dev.Serial) uninstall $TargetPkg" -ForegroundColor Yellow
  Write-Host "  INSTALL_FAILED_VERSION_DOWNGRADE - install a build with an equal or higher versionCode." -ForegroundColor Yellow
  exit 1
}
Write-Good "install succeeded"
$newVer = Get-InstalledVersion $TargetPkg
Write-Good "now installed: $(if ($newVer) { $newVer } else { 'version unknown' })"

if (-not $SkipGrant) {
  Write-Step "Granting READ_LOGS"
  $g = Invoke-Adb 'shell' 'pm' 'grant' $TargetPkg $ReadLogs
  if ($g -match 'Operation not allowed|Exception|SecurityException') {
    Write-Warn2 "grant failed: $g"
    Write-Warn2 "the bridge cannot read console output without READ_LOGS"
  } else {
    Write-Good "READ_LOGS granted"
  }
}

if (-not $SkipShizuku) {
  Write-Step "Shizuku"
  if (-not $shiz) {
    Write-Warn2 "not installed - Ctrl+T and Restart Roblox will not work"
  } else {
    $line = (Invoke-Adb 'shell' 'pm' 'path' $ShizukuPkg) -split "`r?`n" |
            Where-Object { $_ -like 'package:*' } | Select-Object -First 1
    if ($line) {
      $dir  = ($line -replace '^package:', '').TrimEnd('/')
      $found = $false
      foreach ($abi in @('arm64-v8a', 'armeabi-v7a', 'x86_64', 'x86')) {
        $so = "$dir/lib/$abi/libshizuku.so"
        if ((Invoke-Adb 'shell' "test -f $so && echo Y || echo N") -match 'Y') {
          Invoke-Adb 'shell' $so | Out-Null
          Write-Good "Shizuku started ($abi)"
          $found = $true
          break
        }
      }
      if (-not $found) { Write-Warn2 "libshizuku.so not found - start Shizuku manually on the device" }
    }
  }
}

if (-not $SkipLaunch) {
  Write-Step "Launching CWBridge"
  $l = Invoke-Adb 'shell' 'monkey' '-p' $TargetPkg '-c' 'android.intent.category.LAUNCHER' '1'
  if ($l -match 'No activities found|Error') { Write-Warn2 "launch: $l" }
  else { Write-Good "launched" }
}

Write-Host ""
Write-Good "done"
