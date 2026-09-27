# CWBridge Helper - GUI smoke test
#
# The helper window cannot be tested the usual way: the interesting part is a
# WinForms form, and the script finishes by blocking inside ShowDialog() until a
# human closes it. So this test:
#
#   1. strips ONLY the message-loop line, leaving every other line untouched;
#   2. runs the rest for real, which builds the actual window and calls the actual
#      Set-Layout / Fill-Devices / Fill-Status / Set-Working;
#   3. asserts on the resulting controls: do they exist, do they stay inside the
#      window at both the default and the minimum size, and do they get the right
#      colour when a device is healthy versus broken.
#
# It runs no adb command and needs no device and no network, so it is safe anywhere.
#
#   powershell -ExecutionPolicy Bypass -File .\scripts\test-helper-gui.ps1
#
# Exit code 0 = every check passed, 1 = at least one failed.
#
# Windows only: WinForms does not exist in PowerShell on Linux, and the build
# workflows run on ubuntu, so this is NOT wired into CI. Run it locally, or add a
# windows-latest job if you want it enforced.
#
# ASCII-only on purpose, same reason as the GUI script: Windows PowerShell 5.1
# reads a BOM-less UTF-8 file as ANSI.
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'

# WinForms wants an STA thread. powershell.exe already is one, pwsh is not; re-launch
# on Windows PowerShell instead of reporting a pass that means nothing.
if ([System.Threading.Thread]::CurrentThread.GetApartmentState() -ne 'STA') {
  $exe = Join-Path $PSHOME 'powershell.exe'
  if (Test-Path -LiteralPath $exe) {
    $p = Start-Process -FilePath $exe -Wait -PassThru -ArgumentList @(
      '-NoProfile', '-ExecutionPolicy', 'Bypass', '-STA', '-File', $PSCommandPath)
    exit $p.ExitCode
  }
  Write-Host 'test-helper-gui: needs an STA host; run it as powershell.exe -STA.' -ForegroundColor Yellow
  exit 1
}

$guiFile = Join-Path $PSScriptRoot 'cwbridge-helper-gui.ps1'
if (-not (Test-Path -LiteralPath $guiFile)) {
  Write-Host ('test-helper-gui: cannot find ' + $guiFile) -ForegroundColor Red
  exit 1
}

$src = [System.IO.File]::ReadAllText($guiFile, [System.Text.Encoding]::UTF8)
if ($src.Length -gt 0 -and [int]$src[0] -eq 0xFEFF) { $src = $src.Substring(1) }

# Strip exactly one line: the modal message loop. Everything else must survive,
# including the three $dlg.ShowDialog() file pickers behind Browse / Save log /
# Install local APK. A regex that swallowed those would neuter the window while this
# test still passed, so the survivor count is asserted rather than assumed.
$loopPattern = '(?m)^[ \t]*\$null = \$script:Form\.ShowDialog\(\)[^\r\n]*(?:\r?\n|$)'
$loopHits = ([regex]::Matches($src, $loopPattern)).Count
$dlgHits = ([regex]::Matches($src, '\$dlg\.ShowDialog\(\)')).Count

Write-Host ''
Write-Host 'CWBridge Helper - GUI smoke test'
Write-Host ('  building the real window from ' + (Split-Path -Leaf $guiFile))

$stripOk = $true
if ($loopHits -ne 1) {
  Write-Host ('  FAIL  expected exactly 1 message-loop line to strip, found ' + $loopHits) -ForegroundColor Red
  Write-Host '        the end of the GUI script changed - update $loopPattern to match.'
  $stripOk = $false
}
if ($dlgHits -lt 3) {
  Write-Host ('  FAIL  expected at least 3 $dlg.ShowDialog() file pickers, found ' + $dlgHits) -ForegroundColor Red
  $stripOk = $false
}
if (-not $stripOk) { exit 1 }

$src = [regex]::Replace($src, $loopPattern, '')

$global:SmokePass = 0
$global:SmokeFail = 0

$assertBody = @'
# ---------------------------------------------------------------------------
# Checks. This runs in the same scope as the window, so $script:* is visible.
# ---------------------------------------------------------------------------
function Check($name, $ok) {
  if ($ok) { $global:SmokePass++; Write-Host ('  PASS  ' + $name) }
  else     { $global:SmokeFail++; Write-Host ('  FAIL  ' + $name) -ForegroundColor Red }
}

Check 'the window is built with the expected title' ($script:Form.Text -eq 'CWBridge Helper')
Check 'the window opens at a usable size' ($script:Form.ClientSize.Width -ge 940 -and $script:Form.ClientSize.Height -ge 620)
Check 'the window has its controls' ($script:Form.Controls.Count -ge 30)

# Regression for the [switch] trap: PowerShell leaves [switch] parameters out of
# positional binding, so a switch declared before a positional shifts every later
# argument by one. That is exactly what silently dropped this label's colour once.
$probe = New-Lbl 'probe' 9 $script:Col.Red -Bold
Check 'New-Lbl keeps its colour' ($probe.ForeColor -eq $script:Col.Red)
Check 'New-Lbl keeps its bold flag' ($probe.Font.Bold)
Check 'New-Lbl falls back to the default ink' ((New-Lbl 'plain').ForeColor -eq $script:Col.Fg)

# --- the ten action buttons
$acts = @($script:BtnCheck, $script:BtnUpdate, $script:BtnLocal, $script:BtnGrant,
          $script:BtnLaunch, $script:BtnShizuku, $script:BtnWeb, $script:BtnDiag,
          $script:BtnClear, $script:BtnSave)
Check 'ten action buttons exist' ($script:Buttons.Count -eq 10)
Check 'every action button is placed on the form' (@($acts | Where-Object { $null -eq $_ }).Count -eq 0)
Check 'every action button has a caption' (@($acts | Where-Object { -not $_.Text }).Count -eq 0)
Check 'the primary action is styled as primary' ($script:BtnUpdate.Tag -eq 'p' -and $script:BtnCheck.Tag -eq 'n')

# --- status grid
Check 'six status rows exist' ($script:StatRows.Count -eq 6)
Check 'every status row has a caption' (@($script:StatRows | Where-Object { -not $_[0].Text }).Count -eq 0)

# --- layout: nothing may sit outside the client area
$w = $script:Form.ClientSize.Width
$h = $script:Form.ClientSize.Height
$out = @($script:Form.Controls | Where-Object { $_.Left -lt 0 -or $_.Top -lt 0 -or $_.Right -gt $w -or $_.Bottom -gt $h })
Check 'nothing lays out outside the window at the default size' ($out.Count -eq 0)

# the smallest window a user can drag it down to
$script:Form.ClientSize = New-Object System.Drawing.Size(940, 620)
Set-Layout
$w = $script:Form.ClientSize.Width
$h = $script:Form.ClientSize.Height
$out = @($script:Form.Controls | Where-Object { $_.Left -lt 0 -or $_.Top -lt 0 -or $_.Right -gt $w -or $_.Bottom -gt $h })
Check 'nothing lays out outside the window at the minimum size' ($out.Count -eq 0)
Check 'the log pane keeps a usable width at the minimum size' ($script:Rtb.Width -ge 220)

$script:Form.ClientSize = New-Object System.Drawing.Size(1140, 740)
Set-Layout

# --- device picker
Fill-Devices @(
  [pscustomobject]@{ Serial = 'unauth01'; State = 'unauthorized'; Model = 'Pixel 7' },
  [pscustomobject]@{ Serial = 'gooddev1'; State = 'device';       Model = 'Pixel 7' })
Check 'the device list is filled' ($script:DevBox.Items.Count -eq 2)
Check 'a usable device is auto-picked over an unauthorized one' ($script:Serial -eq 'gooddev1')

Fill-Devices @(
  [pscustomobject]@{ Serial = 'otherdev'; State = 'device'; Model = 'Pixel 7' },
  [pscustomobject]@{ Serial = 'gooddev1'; State = 'device'; Model = 'Pixel 7' })
Check 'the current pick survives a refresh' ($script:Serial -eq 'gooddev1')

Fill-Devices @(
  [pscustomobject]@{ Serial = 'unauth01'; State = 'unauthorized'; Model = 'Pixel 7' })
Check 'a lone unauthorized device is still selectable' ($script:Serial -eq 'unauth01')

# --- status grid colouring
function New-Status($installed, $logs, $a11y, $shizuku) {
  [pscustomobject]@{
    Model = 'Pixel 7'; Android = '14'; Sdk = '34'; WlanIp = '192.168.1.5'
    Installed = $installed; Version = '2.10.0'
    ReadLogs = $logs; A11y = $a11y; Shizuku = $shizuku
  }
}

Fill-Status (New-Status $true $true $true $true)
Check 'an installed app reads green' ($script:SApp.ForeColor -eq $script:Col.Green)
Check 'granted READ_LOGS reads green' ($script:SLogs.ForeColor -eq $script:Col.Green)
Check 'an enabled accessibility service reads green' ($script:SA11y.ForeColor -eq $script:Col.Green)
Check 'an installed Shizuku reads green' ($script:SShiz.ForeColor -eq $script:Col.Green)
Check 'the device row shows model, Android and SDK' ($script:SDevice.Text -like '*Pixel 7*' -and $script:SDevice.Text -like '*SDK 34*')
Check 'the wlan ip row is filled' ($script:SIp.Text -eq '192.168.1.5')

Fill-Status (New-Status $false $false $false $false)
Check 'a missing app warns in yellow' ($script:SApp.ForeColor -eq $script:Col.Yellow)
Check 'a missing app says so in words' ($script:SApp.Text -like '*not installed*')
Check 'revoked READ_LOGS reads red' ($script:SLogs.ForeColor -eq $script:Col.Red)
Check 'a disabled accessibility service reads red' ($script:SA11y.ForeColor -eq $script:Col.Red)
Check 'a missing Shizuku stays dim, not red' ($script:SShiz.ForeColor -eq $script:Col.Dim)

# --- log pane
$before = $script:Rtb.TextLength
Write-Log 'smoke line' 'info'
Check 'the log pane receives text' ($script:Rtb.TextLength -gt $before -and $script:Rtb.Text -like '*smoke line*')

# --- in-flight guard: this is what stops an action double-firing
Set-Working $true
Check 'a running job disables every action button' (@($script:Buttons | Where-Object { $_.Enabled }).Count -eq 0)
Check 'the progress bar goes marquee while busy' ([string]$script:Bar.Style -eq 'Marquee')
Start-Core @{ Op = 'devices' }
Check 'Start-Core refuses a second job while one is in flight' ($null -eq $script:Job)
Set-Working $false
Check 'finishing a job re-enables every button' (@($script:Buttons | Where-Object { -not $_.Enabled }).Count -eq 0)
Check 'the progress bar resets when idle' ([string]$script:Bar.Style -eq 'Continuous' -and $script:Bar.Value -eq 0)

# --- the timer that drains the core queue
Check 'the ui timer polls the core every 120 ms' ($script:Timer.Interval -eq 120)

$script:Form.Dispose()
'@

$body = $src + [Environment]::NewLine + $assertBody
try {
  & ([scriptblock]::Create($body))
} catch {
  Write-Host ('  ERROR while building the window: ' + $_.Exception.Message) -ForegroundColor Red
  Write-Host ('        ' + $_.InvocationInfo.PositionMessage)
  exit 1
}

Write-Host ''
if ($global:SmokeFail -eq 0) {
  Write-Host ('  ' + $global:SmokePass + ' passed, 0 failed') -ForegroundColor Green
  exit 0
}
Write-Host ('  ' + $global:SmokePass + ' passed, ' + $global:SmokeFail + ' FAILED') -ForegroundColor Red
exit 1
