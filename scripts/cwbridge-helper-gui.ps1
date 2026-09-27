# CWBridge Helper - Windows GUI
#
# Installs, updates and diagnoses the CWBridge Android app on a USB-connected
# device. Same job as scripts\cwbridge-updater.ps1 (the CLI) and the Android
# OTG helper (helper/src/main/java/com/cwbridge/helper), but with a window.
#
#   powershell -ExecutionPolicy Bypass -File .\scripts\cwbridge-helper-gui.ps1
#   powershell -ExecutionPolicy Bypass -File .\scripts\cwbridge-helper-gui.ps1 -Adb C:\adb\adb.exe
#   powershell -ExecutionPolicy Bypass -File .\scripts\cwbridge-helper-gui.ps1 -SelfTest
#
# Needs Android platform-tools (adb). Auto-detected, or point at it with -Adb or
# the Browse button. The bridge itself stays Android-only: AccessibilityService,
# READ_LOGS, the overlay and Shizuku have no Windows equivalent. This tool only
# installs, grants and reports.
#
# This file is deliberately ASCII-only. Windows PowerShell 5.1 reads a BOM-less
# UTF-8 file as ANSI, and one stray byte would mangle every string in the window.
[CmdletBinding()]
param(
  [string]$Adb,
  [switch]$SelfTest
)

$ErrorActionPreference = 'Stop'

# ---------------------------------------------------------------------------
# Core
#
# Everything that shells out to adb or GitHub lives in this one scriptblock, and
# it is used twice from the exact same text:
#   * -SelfTest invokes it inline, synchronously;
#   * the GUI invokes it inside a background runspace, because adb blocks for
#     seconds and would otherwise freeze the window.
#
# Log lines travel back through $Ctx.Log, a thread-safe queue that the UI drains
# on a timer, so no ScriptBlock ever has to cross a runspace boundary. $Ctx is
# plain data in and plain data out, which is what makes the self-test possible.
# ---------------------------------------------------------------------------
$script:CoreText = @'
param($Ctx)

$Owner      = 'angamer234k'
$Repo       = 'cwbridge-androidfork'
$ApiUrl     = if ($Ctx.Api) { $Ctx.Api } else { "https://api.github.com/repos/$Owner/$Repo/releases/latest" }
$CacheDir   = if ($Ctx.CacheDir) { $Ctx.CacheDir } else { Join-Path $env:TEMP 'cwbridge-helper' }
$TargetPkg  = 'com.cwbridge.android.debug'
$ShizukuPkg = 'moe.shizuku.privileged.api'
$ReadLogs   = 'android.permission.READ_LOGS'

function Log($level, $text) { $null = $Ctx.Log.Enqueue(@{ L = $level; T = $text }) }
function Log-Progress($pct, $text) { $null = $Ctx.Log.Enqueue(@{ L = 'step'; T = $text; P = $pct }) }
function Write-Step($m)  { Log 'step' $m }
function Write-Info($m)  { Log 'info' $m }
function Write-Good($m)  { Log 'ok'   $m }
function Write-Warn2($m) { Log 'warn' $m }
function Write-Err2($m)  { Log 'err'  $m }

function Invoke-Adb {
  param([Parameter(ValueFromRemainingArguments = $true)][string[]]$AdbArgs)
  # adb writes progress and warnings to stderr even on success ("* daemon not
  # running", "adb server version mismatch"). Under $ErrorActionPreference='Stop'
  # those become terminating errors, so relax it for the duration of the call
  # and treat the exit code / output text as the real signal.
  $ErrorActionPreference = 'Continue'
  $out = & $Ctx.AdbExe @($Ctx.AdbBase) @AdbArgs 2>&1
  return ($out | ForEach-Object { if ($_ -is [System.Management.Automation.ErrorRecord]) { $_.ToString() } else { "$_" } } | Out-String).Trim()
}


function Get-LatestRelease {
  $headers = @{ 'User-Agent' = 'CWBridge-Helper-Win'; 'Accept' = 'application/vnd.github+json' }
  return Invoke-RestMethod -Uri $ApiUrl -Headers $headers -UseBasicParsing -TimeoutSec 30
}

function Select-ApkAsset($release) {
  # The release ships the main app AND the OTG helper. Never pick the helper:
  # it is a different package and would install the wrong app on the device.
  $apks  = @($release.assets | Where-Object { $_.name -like '*.apk' })
  $main  = @($apks | Where-Object { $_.name -notlike '*helper*' })
  if ($main.Count -eq 0) { throw "No main-app APK asset on release $($release.tag_name)" }
  $named = @($main | Where-Object { $_.name -like '*cwbridge*' })
  if ($named.Count -gt 0) { return $named[0] }
  $android = @($main | Where-Object { $_.name -like '*android*' })
  if ($android.Count -gt 0) { return $android[0] }
  return $main[0]
}

function Get-Apk {
  param($Release, $Asset)
  New-Item -ItemType Directory -Force -Path $CacheDir | Out-Null
  $dest = Join-Path $CacheDir $Asset.name
  $meta = "$dest.tag"
  $remoteSize = [int64]$Asset.size

  if (Test-Path -LiteralPath $dest) {
    $len = (Get-Item -LiteralPath $dest).Length
    if ($remoteSize -le 0 -or $len -eq $remoteSize) {
      if (-not (Test-Path -LiteralPath $meta)) { Set-Content -Path $meta -Value $Release.tag_name }
      Write-Info "cached: $dest"
      return $dest
    }
    Write-Info "stale cache ($len vs $remoteSize bytes) - re-downloading"
  }

  $tmp = "$dest.part"
  if (Test-Path -LiteralPath $tmp) { Remove-Item -LiteralPath $tmp -Force }

  Write-Info "downloading $($Asset.name) ($remoteSize bytes)"
  # Streamed, reporting progress as it goes. Invoke-WebRequest buffers the whole
  # body in memory and can stall for minutes on a large asset. The core already
  # runs off the UI thread, so the Start-Job the CLI script needs is not needed.
  # WebRequest rather than HttpWebRequest, so a file:// URL works too: that is
  # how the self-test drives this function with no network at all. The timeout
  # knobs only exist on the HTTP flavour.
  $req = [System.Net.WebRequest]::Create($Asset.browser_download_url)
  if ($req -is [System.Net.HttpWebRequest]) {
    $req.UserAgent = 'CWBridge-Helper-Win'
    $req.Timeout = 120000
    $req.ReadWriteTimeout = 120000
  }
  $resp = $null
  try {
    $resp = $req.GetResponse()
    $in = $resp.GetResponseStream()
    $out = [System.IO.File]::Create($tmp)
    try {
      $buf = New-Object byte[] 65536
      $have = 0L
      while (($n = $in.Read($buf, 0, $buf.Length)) -gt 0) {
        $out.Write($buf, 0, $n)
        $have += $n
        $pct = if ($remoteSize -gt 0) { [int][math]::Min(100, [math]::Floor(100 * $have / $remoteSize)) } else { 0 }
        Log-Progress $pct ("  {0,3}%  {1} / {2} bytes" -f $pct, $have, $remoteSize)
        Start-Sleep -Milliseconds 300
      }
    } finally { $out.Close(); $in.Close() }
  } catch {
    if (Test-Path -LiteralPath $tmp) { Remove-Item -LiteralPath $tmp -Force -ErrorAction SilentlyContinue }
    throw "download failed: $($_.Exception.Message)"
  } finally { if ($resp) { $resp.Close() } }

  if ($remoteSize -gt 0 -and (Get-Item -LiteralPath $tmp).Length -ne $remoteSize) {
    $got = (Get-Item -LiteralPath $tmp).Length
    Remove-Item -LiteralPath $tmp -Force -ErrorAction SilentlyContinue
    throw "download size mismatch (got $got, expected $remoteSize) - file removed, retry."
  }
  if (Test-Path -LiteralPath $dest) { Remove-Item -LiteralPath $dest -Force }
  Move-Item -LiteralPath $tmp -Destination $dest
  Set-Content -Path $meta -Value $Release.tag_name
  Write-Info "saved: $dest"
  return $dest
}

function Invoke-Install {
  param([string]$ApkPath)
  Write-Step 'Installing / updating'
  $out = Invoke-Adb 'install' '-r' '-g' $ApkPath
  foreach ($l in ($out -split "`r?`n")) { if ($l.Trim()) { Write-Info $l.Trim() } }
  if ($out -notmatch 'Success') {
    Write-Err2 'install failed'
    Write-Warn2 'Common causes:'
    Write-Warn2 '  INSTALL_FAILED_UPDATE_INCOMPATIBLE - a differently-signed build is installed.'
    Write-Warn2 "    Fix: adb -s $($Ctx.Serial) uninstall $TargetPkg   (this clears app data)"
    Write-Warn2 '  INSTALL_FAILED_VERSION_DOWNGRADE - install a build with an equal or higher versionCode.'
    throw 'install failed'
  }
  Write-Good 'install succeeded'
}

function Start-ShizukuOnDevice {
  Write-Step 'Shizuku'
  $line = (Invoke-Adb 'shell' 'pm' 'path' $ShizukuPkg) -split "`r?`n" |
          Where-Object { $_ -like 'package:*' } | Select-Object -First 1
  if (-not $line) { throw 'Shizuku is not installed (optional, but Ctrl+T and Restart Roblox need it).' }
  $dir = ($line -replace '^package:', '').TrimEnd('/')
  foreach ($abi in @('arm64-v8a', 'armeabi-v7a', 'x86_64', 'x86')) {
    $so = "$dir/lib/$abi/libshizuku.so"
    if ((Invoke-Adb 'shell' "test -f $so && echo Y || echo N") -match 'Y') {
      Invoke-Adb 'shell' $so | Out-Null
      Write-Good "Shizuku started ($abi)"
      return $true
    }
  }
  Write-Warn2 'libshizuku.so not found - start Shizuku manually on the device'
  return $false
}

function Start-Cwbridge {
  Write-Step 'Launching CWBridge'
  $l = Invoke-Adb 'shell' 'monkey' '-p' $TargetPkg '-c' 'android.intent.category.LAUNCHER' '1'
  if ($l -match 'No activities found|Error') { Write-Warn2 "launch: $l"; return $false }
  Write-Good 'launched'
  return $true
}


function Find-Adb {
  if ($Ctx.AdbPath) {
    if (Test-Path -LiteralPath $Ctx.AdbPath) { return (Resolve-Path -LiteralPath $Ctx.AdbPath).Path }
    throw "adb path not found: $($Ctx.AdbPath)"
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
  ) | Where-Object { $_ -and (Test-Path -LiteralPath $_) })
  if ($cands.Count -gt 0) { return [string]$cands[0] }
  throw 'adb.exe not found. Install Android platform-tools, then press Browse and pick adb.exe.'
}

# Pure parser, so it can be unit tested without adb installed.
function ConvertTo-DeviceList {
  param([string]$Raw)
  $list = New-Object System.Collections.ArrayList
  foreach ($line in ($Raw -split "`r?`n")) {
    $t = $line.Trim()
    if (-not $t) { continue }
    if ($t -like 'List of devices*') { continue }
    if ($t -like '*daemon*') { continue }
    if ($t -match '^(\S+)\s+(device|offline|unauthorized|recovery|bootloader|no permissions)(?:\s+.*)?$') {
      $null = $list.Add([pscustomobject]@{ Serial = $Matches[1]; State = $Matches[2] })
    }
  }
  return $list.ToArray()
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

function Test-ReadLogsGranted {
  return ((Invoke-Adb 'shell' 'dumpsys' 'package' $TargetPkg) -match 'READ_LOGS:\s*granted=true')
}

function Test-A11yEnabled {
  return ((Invoke-Adb 'shell' 'settings' 'get' 'secure' 'enabled_accessibility_services') -like "*$TargetPkg*")
}

function Get-DeviceIp {
  # The first IP on the wlan0 route line is the GATEWAY, not this device.
  # `src` is the address the device actually uses.
  $route = Invoke-Adb 'shell' 'ip' 'route'
  $m = [regex]::Match($route, 'dev\s+wlan0.*?src\s+(\d{1,3}(?:\.\d{1,3}){3})')
  if ($m.Success) { return $m.Groups[1].Value }
  $m = [regex]::Match($route, 'src\s+(\d{1,3}(?:\.\d{1,3}){3})')
  if ($m.Success) { return $m.Groups[1].Value }
  foreach ($p in @('dhcp.wlan0.ipaddress', 'dhcp.eth0.ipaddress')) {
    $v = Get-Prop $p
    if ($v -match '^\d{1,3}(\.\d{1,3}){3}$') { return $v }
  }
  return ''
}

function Get-DeviceStatus {
  $installed = Test-PkgInstalled $TargetPkg
  return [ordered]@{
    Serial    = if ($Ctx.AdbBase.Count -ge 2) { [string]$Ctx.AdbBase[1] } else { '' }
    Model     = Get-Prop 'ro.product.model'
    Android   = Get-Prop 'ro.build.version.release'
    Sdk       = Get-Prop 'ro.build.version.sdk'
    Installed = $installed
    Version   = if ($installed) { Get-InstalledVersion $TargetPkg } else { '' }
    ReadLogs  = (Test-ReadLogsGranted)
    A11y      = (Test-A11yEnabled)
    Shizuku   = (Test-PkgInstalled $ShizukuPkg)
    WlanIp    = (Get-DeviceIp)
  }
}

# Dot-sourcing this text with $Ctx.FunctionsOnly = $true defines every helper
# above in the caller's scope and returns straight away. That is how the
# self-test reaches the pure helpers (ConvertTo-DeviceList, Select-ApkAsset,
# Get-Apk, Invoke-Install) without running a whole operation.
if ($Ctx.FunctionsOnly) { return }

# --------------------------------------------------------------------------
# Operations. One entry point, dispatched on $Ctx.Op, so the window and the
# self-test drive exactly the same code paths.
# --------------------------------------------------------------------------
$Ctx.AdbBase = @()
$result = [ordered]@{ Ok = $true; Op = [string]$Ctx.Op; Status = $null; Devices = $null; Message = '' }

try {
  $Ctx.AdbExe = Find-Adb
  Write-Info "adb: $($Ctx.AdbExe)"
  $vline = (Invoke-Adb 'version') -split "`r?`n" | Where-Object { $_.Trim() } | Select-Object -First 1
  if ($vline) { Write-Info $vline.Trim() }

  if ($Ctx.NeedDevice) {
    if (-not $Ctx.Serial) { throw 'No device selected. Press Refresh and pick one.' }
    $dev = @(ConvertTo-DeviceList (Invoke-Adb 'devices')) |
           Where-Object { $_.Serial -eq $Ctx.Serial } | Select-Object -First 1
    if (-not $dev) { throw "Device '$($Ctx.Serial)' is not connected. Press Refresh." }
    if ($dev.State -ne 'device') {
      throw "Device state is '$($dev.State)'. Accept the USB debugging prompt on the device, then press Refresh."
    }
    $Ctx.AdbBase = @('-s', $dev.Serial)
    Write-Good "device $($dev.Serial)"
  }

  $op = [string]$Ctx.Op

  if ($op -eq 'devices') {
    Write-Step 'Scanning for devices'
    $result.Devices = @(ConvertTo-DeviceList (Invoke-Adb 'devices'))
    foreach ($d in $result.Devices) { Write-Info ("  {0}  ({1})" -f $d.Serial, $d.State) }
    if ($result.Devices.Count -eq 0) {
      Write-Warn2 'No devices. Plug in USB, turn on USB debugging, accept the prompt.'
    }
    $result.Message = 'devices'

  } elseif ($op -eq 'status') {
    Write-Step 'Reading device state'

  } elseif ($op -eq 'grant-logs') {
    Write-Step 'Granting READ_LOGS'
    $g = Invoke-Adb 'shell' 'pm' 'grant' $TargetPkg $ReadLogs
    if ($g -match 'Operation not allowed|Exception|SecurityException') {
      Write-Warn2 "grant failed: $g"
      Write-Warn2 'the bridge cannot read console output without READ_LOGS'
      $result.Ok = $false
      $result.Message = 'grant failed'
    } else {
      Write-Good 'READ_LOGS granted'
      $result.Message = 'READ_LOGS granted'
    }

  } elseif ($op -eq 'launch') {
    $result.Ok = (Start-Cwbridge)
    if (-not $result.Ok) { $result.Message = 'launch failed' }

  } elseif ($op -eq 'shizuku') {
    try {
      $result.Ok = (Start-ShizukuOnDevice)
      if (-not $result.Ok) { $result.Message = 'libshizuku.so not found' }
    } catch {
      $result.Ok = $false
      $result.Message = $_.Exception.Message
      Write-Warn2 $result.Message
    }

  } elseif ($op -eq 'web') {
    Write-Step 'Forwarding the web panel to this PC'
    $fw = Invoke-Adb 'forward' 'tcp:8765' 'tcp:8765'
    if ($fw -match 'cannot bind|error|failed') { Write-Warn2 "forward: $fw" }
    else { Write-Good 'forwarded  127.0.0.1:8765 -> device:8765' }
    $ip = Get-DeviceIp
    if ($ip) { Write-Info "LAN address: http://$($ip):8765" }
    Write-Info 'Then on the device: CWBridge -> Server -> Start server'
    $result.Message = 'http://127.0.0.1:8765'


  } elseif ($op -eq 'diagnostics') {
    Write-Step 'Diagnostics'
    Write-Info "host      : $env:COMPUTERNAME"
    Write-Info "os        : $([System.Environment]::OSVersion.VersionString)"
    Write-Info "powershell: $($PSVersionTable.PSVersion)  64-bit=$([System.Environment]::Is64BitProcess)"
    Write-Info "adb       : $($Ctx.AdbExe)"
    Write-Info "serial    : $($Ctx.Serial)"
    $devs = @(ConvertTo-DeviceList (Invoke-Adb 'devices'))
    Write-Info "devices   : $($devs.Count)"
    foreach ($d in $devs) { Write-Info ("  {0}  ({1})" -f $d.Serial, $d.State) }
    Write-Info ("get-state : {0}" -f (Invoke-Adb 'get-state'))
    Write-Info ("sdk       : {0}" -f (Get-Prop 'ro.build.version.sdk'))
    Write-Info ("model     : {0}" -f (Get-Prop 'ro.product.model'))
    Write-Info ("wlan ip   : {0}" -f (Get-DeviceIp))
    Write-Info "cwbridge  : $(if (Test-PkgInstalled $TargetPkg) { 'installed ' + (Get-InstalledVersion $TargetPkg) } else { 'NOT installed' })"
    Write-Info "read_logs : $(if (Test-ReadLogsGranted) { 'granted' } else { 'NOT granted' })"
    Write-Info "a11y      : $(if (Test-A11yEnabled) { 'CWBridge Tap enabled' } else { 'CWBridge Tap NOT enabled' })"
    Write-Info "shizuku   : $(if (Test-PkgInstalled $ShizukuPkg) { 'installed' } else { 'not installed' })"
    Write-Info "cache dir : $CacheDir"
    $result.Message = 'diagnostics done'

  } elseif ($op -eq 'push' -or $op -eq 'install-local') {
    if ($op -eq 'install-local') {
      if (-not $Ctx.Apk) { throw 'No APK selected.' }
      if (-not (Test-Path -LiteralPath $Ctx.Apk)) { throw "APK not found: $($Ctx.Apk)" }
      $apkPath = (Resolve-Path -LiteralPath $Ctx.Apk).Path
      Write-Info "local APK: $apkPath ($((Get-Item -LiteralPath $apkPath).Length) bytes)"
    } else {
      Write-Step 'Checking GitHub releases'
      $rel = Get-LatestRelease
      $asset = Select-ApkAsset $rel
      Write-Info "latest release: $($rel.tag_name)"
      Write-Info "asset         : $($asset.name) ($($asset.size) bytes)"
      $apkPath = Get-Apk $rel $asset
    }

    Invoke-Install $apkPath

    if (-not $Ctx.SkipGrant) {
      Write-Step 'Granting READ_LOGS'
      $g = Invoke-Adb 'shell' 'pm' 'grant' $TargetPkg $ReadLogs
      if ($g -match 'Operation not allowed|Exception|SecurityException') {
        Write-Warn2 "grant failed: $g"
        Write-Warn2 'the bridge cannot read console output without READ_LOGS'
      } else {
        Write-Good 'READ_LOGS granted'
      }
    }
    if (-not $Ctx.SkipShizuku) { $null = Start-ShizukuOnDevice }
    if (-not $Ctx.SkipLaunch)   { $null = Start-Cwbridge }
    $result.Message = 'done'

  } else {
    throw "Unknown operation: $op"
  }

  # Only worth probing when a device is actually targeted.
  if ($Ctx.NeedDevice) { $result.Status = Get-DeviceStatus }

} catch {
  # Report the failure in the log, but still refresh the status panel so the
  # window shows real device state instead of stale values.
  $result.Ok = $false
  $result.Message = $_.Exception.Message
  Write-Err2 $_.Exception.Message
  try { if ($Ctx.NeedDevice) { $result.Status = Get-DeviceStatus } } catch { $null = $true }
}

return $result
'@


# ---------------------------------------------------------------------------
# Self-test
#
# Runs the core against a fake adb and a loopback HTTP server, so every code
# path can be exercised on a machine with no phone and no real adb. The fake
# adb is a genuine .exe, so process invocation, argument quoting, exit codes and
# stderr are all exercised too. -SelfTest doubles as the regression test.
# ---------------------------------------------------------------------------

# Answers the handful of commands the core issues, and records every
# invocation so the tests can assert on the exact argument list.
function New-FakeAdb {
  param([string]$Dir)
  $cs = @'
using System;
using System.IO;

class FakeAdb {
  static string Sc() {
    string v = Environment.GetEnvironmentVariable("FAKE_ADB_SCENARIO");
    return v == null ? "ok" : v;
  }

  static void Record(string[] a) {
    string log = Environment.GetEnvironmentVariable("FAKE_ADB_LOG");
    if (log == null || log.Length == 0) return;
    try { File.AppendAllText(log, string.Join(" ", a) + "\n"); } catch { }
  }

  static int Main(string[] argv) {
    Record(argv);
    int i = 0;
    if (argv.Length >= 2 && argv[0] == "-s") i = 2;
    if (i >= argv.Length) { Console.WriteLine("adb: no command"); return 1; }
    string cmd = argv[i];

    if (cmd == "version") {
      Console.WriteLine("Android Debug Bridge version 1.0.41");
      Console.WriteLine("Version 35.0.2-12147458");
      return 0;
    }
    if (cmd == "devices") {
      Console.WriteLine("List of devices attached");
      string sc = Sc();
      if (sc != "no-device") {
        if (sc == "unauthorized") Console.WriteLine("FAKE0123\tunauthorized");
        else if (sc == "offline") Console.WriteLine("FAKE0123\toffline");
        else {
          Console.WriteLine("FAKE0123\tdevice");
          if (sc == "two") Console.WriteLine("FAKE9999\tdevice");
        }
      }
      return 0;
    }
    if (cmd == "get-state") { Console.WriteLine("device"); return 0; }
    if (cmd == "forward") { return 0; }
    if (cmd == "install") {
      string p = argv[argv.Length - 1];
      if (p.Contains("FAIL") || Sc() == "install-fail") {
        Console.WriteLine("adb: failed to install " + p + ": Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package signatures do not match]");
        return 1;
      }
      Console.WriteLine("Performing Streamed Install");
      Console.WriteLine("Success");
      return 0;
    }
    if (cmd == "shell") {
      string s = string.Join(" ", argv, i + 1, argv.Length - i - 1);
      if (s.StartsWith("getprop ro.build.version.release")) { Console.WriteLine(Sc() == "old" ? "9" : "13"); return 0; }
      if (s.StartsWith("getprop ro.build.version.sdk")) { Console.WriteLine(Sc() == "old" ? "28" : "33"); return 0; }
      if (s.StartsWith("getprop ro.product.model")) { Console.WriteLine("Pixel 6"); return 0; }
      if (s.StartsWith("getprop dhcp.wlan0.ipaddress")) { Console.WriteLine("192.168.1.50"); return 0; }
      if (s.StartsWith("ip route")) { Console.WriteLine("192.168.1.0/24 dev wlan0 proto kernel scope link src 192.168.1.50"); return 0; }
      if (s.StartsWith("pm path")) {
        string pkg = s.Substring(s.LastIndexOf(' ') + 1);
        if (pkg.Contains("cwbridge") && Sc() == "not-installed") return 0;
        if (pkg.Contains("shizuku") && Sc() == "no-shizuku") return 0;
        Console.WriteLine("package:/data/app/~~x==" + pkg + "/base.apk");
        return 0;
      }
      if (s.StartsWith("dumpsys package")) {
        if (Sc() == "not-installed") { Console.WriteLine("Unable to find package"); return 0; }
        Console.WriteLine("  Package [com.cwbridge.android.debug] (a1b2c3):");
        Console.WriteLine("    versionName=2.9.2");
        if (Sc() == "no-readlogs") Console.WriteLine("    android.permission.READ_LOGS: granted=false");
        else Console.WriteLine("    android.permission.READ_LOGS: granted=true");
        return 0;
      }
      if (s.StartsWith("settings get secure enabled_accessibility_services")) {
        if (Sc() == "no-a11y") Console.WriteLine("com.android.talkback/com.android.talkback.TalkBackService");
        else Console.WriteLine("com.cwbridge.android.debug/com.cwbridge.android.TapService");
        return 0;
      }
      if (s.StartsWith("pm grant")) {
        if (Sc() == "grant-fail") Console.WriteLine("Operation not allowed: java.lang.SecurityException: Permission denial");
        return 0;
      }
      if (s.StartsWith("monkey")) { Console.WriteLine(Sc() == "no-launch" ? "No activities found" : "Events injected: 1"); return 0; }
      if (s.StartsWith("test -f")) { Console.WriteLine(Sc() == "shizuku-so" ? "Y" : "N"); return 0; }
      return 0;
    }
    return 0;
  }
}
'@
  if (-not (Test-Path -LiteralPath $Dir)) { New-Item -ItemType Directory -Force -Path $Dir | Out-Null }
  $exe = Join-Path $Dir 'adb.exe'
  if (Test-Path -LiteralPath $exe) { Remove-Item -LiteralPath $exe -Force }
  Add-Type -TypeDefinition $cs -OutputAssembly $exe -OutputType ConsoleApplication
  return $exe
}

function Test-Case {
  param([string]$Name, [bool]$Ok, [string]$Detail = '')
  if ($Ok) {
    $script:ST.Pass++
    Write-Host ('  PASS  ' + $Name) -ForegroundColor Green
  } else {
    $script:ST.Fail++
    Write-Host ('  FAIL  ' + $Name) -ForegroundColor Red
    if ($Detail) { Write-Host ('        ' + $Detail.Trim()) -ForegroundColor DarkGray }
  }
}

function Test-Skip {
  param([string]$Name, [string]$Why)
  $script:ST.Skip++
  Write-Host ('  SKIP  ' + $Name + '   (' + $Why + ')') -ForegroundColor Yellow
}

function Invoke-SelfTest {
  $ErrorActionPreference = 'Stop'
  $script:ST = @{ Pass = 0; Fail = 0; Skip = 0 }
  $root = Join-Path $env:TEMP ('cwbridge-selftest-' + [guid]::NewGuid().ToString('N').Substring(0, 8))
  $cache = Join-Path $root 'cache'
  New-Item -ItemType Directory -Force -Path $root | Out-Null
  $env:FAKE_ADB_LOG = Join-Path $root 'adb.log'
  $env:FAKE_ADB_SCENARIO = 'ok'

  Write-Host ''
  Write-Host 'CWBridge helper - self test' -ForegroundColor Cyan
  Write-Host ('  work dir: ' + $root) -ForegroundColor DarkGray
  Write-Host ''

  try {
    # ---------- a real .exe standing in for adb ----------
    $fakeAdb = New-FakeAdb (Join-Path $root 'bin')
    $ver = (& $fakeAdb version 2>&1 | Out-String)
    Test-Case 'fake adb.exe compiles and runs' ($ver -like '*Debug Bridge*') $ver

    # ---------- pure helpers, no adb needed ----------
    $coreSB = [ScriptBlock]::Create($script:CoreText)
    $sbQ = New-Object 'System.Collections.Concurrent.ConcurrentQueue[object]'
    . $coreSB @{ FunctionsOnly = $true; Log = $sbQ }
    $CacheDir = $cache
    $Ctx = @{ Log = $sbQ; AdbExe = $fakeAdb; AdbBase = @('-s', 'FAKE0123'); AdbPath = $fakeAdb; CacheDir = $cache; Serial = 'FAKE0123' }

    $raw = "List of devices attached`nFAKE0123`tdevice`n* daemon not running *`nFAKE9999`toffline"
    $devs = @(ConvertTo-DeviceList $raw)
    Test-Case 'device parser: keeps states, drops adb noise' `
             ($devs.Count -eq 2 -and $devs[0].Serial -eq 'FAKE0123' -and $devs[0].State -eq 'device' -and $devs[1].State -eq 'offline') `
             ($devs | Out-String)
    Test-Case 'device parser: no devices when none attached' `
             (@(ConvertTo-DeviceList "List of devices attached`n").Count -eq 0)

    $relMock = [pscustomobject]@{ tag_name = 'v1'; assets = @(
        [pscustomobject]@{ name = 'cwbridge-helper-debug.apk'; size = 1 },
        [pscustomobject]@{ name = 'cwbridge-android-debug.apk'; size = 2 }) }
    $picked = (Select-ApkAsset $relMock).name
    Test-Case 'asset picker never picks the OTG helper APK' ($picked -eq 'cwbridge-android-debug.apk') $picked

    # ---------- download + install, with no network and no phone ----------
    $payload = New-Object byte[] 65536
    (New-Object Random 7).NextBytes($payload)
    $apkSrc = Join-Path $root 'src.apk'
    [System.IO.File]::WriteAllBytes($apkSrc, $payload)
    $fileUri = 'file:///' + ($apkSrc -replace '\\', '/')

    $asset = [pscustomobject]@{ name = 'cwbridge-android-debug.apk'; size = $payload.Length; browser_download_url = $fileUri }
    $relDl = [pscustomobject]@{ tag_name = 'v9.9.9-selftest'; assets = @($asset) }
    $got = Get-Apk $relDl $asset
    $gotLen = if (Test-Path -LiteralPath $got) { (Get-Item -LiteralPath $got).Length } else { -1 }
    Test-Case 'download: streams to the cache and matches the advertised size' `
             ($gotLen -eq $payload.Length -and (Test-Path -LiteralPath "$got.tag")) `
             ("got=$got len=$gotLen expected=$($payload.Length)")

    $again = Get-Apk $relDl $asset
    Test-Case 'download: a repeat run is served from cache' ($again -eq $got)

    $badAsset = [pscustomobject]@{ name = 'wrong-size.apk'; size = ($payload.Length + 99); browser_download_url = $fileUri }
    $sizeErr = ''
    try { $null = Get-Apk ([pscustomobject]@{ tag_name = 'v1'; assets = @($badAsset) }) $badAsset }
    catch { $sizeErr = $_.Exception.Message }
    Test-Case 'download: size mismatch is rejected, not cached' `
             ($sizeErr -like '*size mismatch*' -and -not (Test-Path -LiteralPath (Join-Path $cache 'wrong-size.apk'))) `
             $sizeErr

    Invoke-Install $got
    $adbLog = Get-Content -LiteralPath $env:FAKE_ADB_LOG -Raw
    Test-Case 'install: shells out to adb install -r -g' `
             ($adbLog -match 'install -r -g .*cwbridge-android-debug\.apk') `
             (($adbLog -split "`r?`n" | Where-Object { $_ -like '*install*' }) -join ' | ')

    $env:FAKE_ADB_SCENARIO = 'install-fail'
    $installErr = ''
    try { Invoke-Install $got } catch { $installErr = $_.Exception.Message }
    Test-Case 'install: failure is raised, not swallowed' ($installErr -like '*install failed*') $installErr
    $env:FAKE_ADB_SCENARIO = 'ok'

    # ---------- whole operations, through the dispatcher ----------
    # -Async is exactly what the window does: same text, separate runspace.
    function Run-Core {
      param([hashtable]$Op, [switch]$Async)
      $q = New-Object 'System.Collections.Concurrent.ConcurrentQueue[object]'
      $c = @{ Log = $q; AdbPath = $fakeAdb; CacheDir = $cache }
      foreach ($k in $Op.Keys) { $c[$k] = $Op[$k] }
      if ($Async) {
        $rs = [runspacefactory]::CreateRunspace()
        $rs.Open()
        $ps = [powershell]::Create()
        $ps.Runspace = $rs
        $null = $ps.AddScript($script:CoreText).AddArgument($c)
        $h = $ps.BeginInvoke()
        while (-not $h.IsCompleted) { Start-Sleep -Milliseconds 10 }
        $out = $ps.EndInvoke($h)
        $ps.Dispose()
        $rs.Dispose()
      } else {
        $out = & $coreSB $c
      }
      $lines = @()
      $e = $null
      while ($q.TryDequeue([ref]$e)) { $lines += ,$e }
      $res = @($out) | Where-Object { $_ -is [System.Collections.Specialized.OrderedDictionary] } | Select-Object -First 1
      return [pscustomobject]@{ Result = $res; Lines = $lines; Text = (($lines | ForEach-Object { $_.T }) -join "`n") }
    }

    $st = Run-Core @{ Op = 'status'; Serial = 'FAKE0123'; NeedDevice = $true }
    $s = $st.Result.Status
    Test-Case 'status: a healthy device reports everything' `
             ($st.Result.Ok -and $s.Android -eq '13' -and $s.Sdk -eq '33' -and $s.Model -eq 'Pixel 6' -and $s.Installed -and $s.Version -eq '2.9.2' -and $s.ReadLogs -and $s.A11y -and $s.Shizuku -and $s.WlanIp -eq '192.168.1.50') `
             $st.Text

    $sta = Run-Core @{ Op = 'status'; Serial = 'FAKE0123'; NeedDevice = $true } -Async
    Test-Case 'status: identical through a background runspace (the GUI path)' `
             ($sta.Result.Ok -and $sta.Result.Status.Version -eq '2.9.2' -and $sta.Lines.Count -gt 3) `
             $sta.Text

    $env:FAKE_ADB_SCENARIO = 'not-installed'
    $st = Run-Core @{ Op = 'status'; Serial = 'FAKE0123'; NeedDevice = $true }
    Test-Case 'status: CWBridge reported missing' `
             (-not $st.Result.Status.Installed -and $st.Result.Status.Version -eq '') $st.Text

    $env:FAKE_ADB_SCENARIO = 'no-a11y'
    $st = Run-Core @{ Op = 'status'; Serial = 'FAKE0123'; NeedDevice = $true }
    Test-Case 'status: accessibility reported off' (-not $st.Result.Status.A11y) $st.Text

    $env:FAKE_ADB_SCENARIO = 'unauthorized'
    $st = Run-Core @{ Op = 'status'; Serial = 'FAKE0123'; NeedDevice = $true }
    Test-Case 'unauthorized device is refused with a useful message' `
             (-not $st.Result.Ok -and $st.Result.Message -like '*unauthorized*') $st.Result.Message

    $env:FAKE_ADB_SCENARIO = 'no-device'
    $st = Run-Core @{ Op = 'status'; Serial = 'FAKE0123'; NeedDevice = $true }
    Test-Case 'unplugged device is refused' `
             (-not $st.Result.Ok -and $st.Result.Message -like '*not connected*') $st.Result.Message

    $env:FAKE_ADB_SCENARIO = 'ok'
    $st = Run-Core @{ Op = 'status'; NeedDevice = $true }
    Test-Case 'no device selected is refused' `
             (-not $st.Result.Ok -and $st.Result.Message -like '*No device selected*') $st.Result.Message

    $st = Run-Core @{ Op = 'status'; Serial = 'FAKE0123'; NeedDevice = $true; AdbPath = (Join-Path $root 'nope\adb.exe') }
    Test-Case 'a bad adb path is reported clearly' `
             (-not $st.Result.Ok -and $st.Result.Message -like '*adb path not found*') $st.Result.Message

    $env:FAKE_ADB_SCENARIO = 'grant-fail'
    $st = Run-Core @{ Op = 'grant-logs'; Serial = 'FAKE0123'; NeedDevice = $true }
    Test-Case 'grant: permission denial is surfaced' `
             (-not $st.Result.Ok -and $st.Text -like '*cannot read console output*') $st.Text

    $env:FAKE_ADB_SCENARIO = 'ok'
    $st = Run-Core @{ Op = 'web'; Serial = 'FAKE0123'; NeedDevice = $true }
    Test-Case 'web: forwards port 8765 and reports the LAN address' `
             ($st.Result.Ok -and $st.Result.Message -eq 'http://127.0.0.1:8765' -and $st.Text -like '*192.168.1.50:8765*') $st.Text

    $st = Run-Core @{ Op = 'diagnostics'; Serial = 'FAKE0123'; NeedDevice = $true }
    Test-Case 'diagnostics: runs and reports the device' `
             ($st.Result.Ok -and $st.Text -like '*Pixel 6*' -and $st.Text -like '*cwbridge*') $st.Text

    $env:FAKE_ADB_SCENARIO = 'two'
    $st = Run-Core @{ Op = 'devices' }
    $found = @($st.Result.Devices)
    Test-Case 'devices: lists what adb reports, no device needed' `
             ($st.Result.Ok -and $found.Count -eq 2 -and $found[0].Serial -eq 'FAKE0123' -and $found[1].Serial -eq 'FAKE9999') `
             $st.Text

    # ---------- the real GitHub API, when this machine happens to be online ----------
    try {
      $live = Invoke-RestMethod -Uri 'https://api.github.com/repos/angamer234k/cwbridge-androidfork/releases/latest' `
                                -Headers @{ 'User-Agent' = 'CWBridge-Helper-SelfTest' } -TimeoutSec 15
      $liveAsset = Select-ApkAsset $live
      Test-Case 'live GitHub release: the main APK is selectable' `
               ($liveAsset.name -like '*.apk' -and $liveAsset.name -notlike '*helper*') `
               ("tag=$($live.tag_name) asset=$($liveAsset.name)")
    } catch {
      Test-Skip 'live GitHub release' $_.Exception.Message
    }
  } finally {
    Remove-Item -Recurse -Force $root -ErrorAction SilentlyContinue
    $env:FAKE_ADB_LOG = $null
    $env:FAKE_ADB_SCENARIO = $null
  }

  Write-Host ''
  $tone = if ($script:ST.Fail) { 'Red' } else { 'Green' }
  Write-Host ('  ' + $script:ST.Pass + ' passed, ' + $script:ST.Fail + ' failed, ' + $script:ST.Skip + ' skipped') -ForegroundColor $tone
  Write-Host ''
  return $script:ST
}

# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------
if ($SelfTest) {
  $r = Invoke-SelfTest
  if ($r.Fail -gt 0) { exit 1 }
  exit 0
}

# ---------------------------------------------------------------------------
# GUI
#
# Everything above is headless and testable. From here on it is WinForms: a dark
# window with a device picker, a status grid and a log pane. All adb work still
# goes through Invoke-Core below, i.e. a background runspace, so the window
# never freezes and the progress bar keeps moving while an APK downloads.
# ---------------------------------------------------------------------------
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
[System.Windows.Forms.Application]::EnableVisualStyles()
[System.Windows.Forms.Application]::SetCompatibleTextRenderingDefault($false)

# WinForms wants an STA thread. powershell.exe is STA by default, but pwsh and
# some hosts are not, and without STA the clipboard and some dialogs misbehave.
if ([System.Threading.Thread]::CurrentThread.GetApartmentState() -ne 'STA') {
  $hostExe = Join-Path $PSHOME 'powershell.exe'
  if (-not (Test-Path -LiteralPath $hostExe)) { $hostExe = 'powershell.exe' }
  $fwd = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-STA', '-File', $PSCommandPath)
  if ($Adb) { $fwd += @('-Adb', $Adb) }
  Start-Process -FilePath $hostExe -ArgumentList $fwd
  return
}

$script:Col = @{
  Bg     = [System.Drawing.Color]::FromArgb(10, 11, 13)
  Panel  = [System.Drawing.Color]::FromArgb(20, 22, 27)
  Panel2 = [System.Drawing.Color]::FromArgb(30, 33, 41)
  Fg     = [System.Drawing.Color]::FromArgb(222, 226, 232)
  Dim    = [System.Drawing.Color]::FromArgb(139, 144, 154)
  Blue   = [System.Drawing.Color]::FromArgb(155, 184, 232)
  Gold   = [System.Drawing.Color]::FromArgb(196, 165, 116)
  Green  = [System.Drawing.Color]::FromArgb(130, 200, 145)
  Yellow = [System.Drawing.Color]::FromArgb(226, 200, 120)
  Red    = [System.Drawing.Color]::FromArgb(226, 125, 125)
}

$script:LogQ = New-Object 'System.Collections.Concurrent.ConcurrentQueue[object]'
$script:Job = $null
$script:Busy = $false
$script:Serial = ''
$script:AdbPath = $Adb
$script:Buttons = @()

function New-Btn {
  param([string]$Text, [int]$Width = 150, [switch]$Primary)
  $b = New-Object System.Windows.Forms.Button
  $b.Text = $Text
  $b.Size = New-Object System.Drawing.Size($Width, 30)
  $b.Margin = New-Object System.Windows.Forms.Padding(0, 0, 8, 6)
  $b.FlatStyle = 'Flat'
  $b.FlatAppearance.BorderSize = 0
  $b.UseVisualStyleBackColor = $false
  $b.Font = New-Object System.Drawing.Font('Segoe UI', 9)
  $b.Tag = if ($Primary) { 'p' } else { 'n' }
  if ($Primary) { $b.BackColor = $script:Col.Blue; $b.ForeColor = $script:Col.Bg }
  else { $b.BackColor = $script:Col.Panel2; $b.ForeColor = $script:Col.Fg }
  $b.Add_MouseEnter({ $this.BackColor = [System.Drawing.Color]::FromArgb(58, 64, 77) })
  $b.Add_MouseLeave({
    if ($this.Tag -eq 'p') { $this.BackColor = $script:Col.Blue } else { $this.BackColor = $script:Col.Panel2 }
  })
  return $b
}

function New-Lbl {
  param([string]$Text, [double]$Size = 9, [switch]$Bold, $Ink)
  $l = New-Object System.Windows.Forms.Label
  $l.Text = $Text
  $l.AutoSize = $false
  $l.ForeColor = if ($Ink) { $Ink } else { $script:Col.Fg }
  $style = if ($Bold) { [System.Drawing.FontStyle]::Bold } else { [System.Drawing.FontStyle]::Regular }
  $l.Font = New-Object System.Drawing.Font('Segoe UI', $Size, $style)
  return $l
}

function Write-Log {
  param([string]$Text, [string]$Level = 'info')
  $col = switch ($Level) {
    'step' { $script:Col.Blue }
    'ok'   { $script:Col.Green }
    'warn' { $script:Col.Yellow }
    'err'  { $script:Col.Red }
    'tip'  { $script:Col.Gold }
    default { $script:Col.Dim }
  }
  $script:Rtb.SelectionColor = $col
  $script:Rtb.AppendText($Text + [Environment]::NewLine)
  $script:Rtb.SelectionStart = $script:Rtb.TextLength
  $script:Rtb.ScrollToCaret()
}
