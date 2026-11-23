# Builds the mod jar, then runs the headless self-test server. Exit 0 only on LIGHTBORN SELFTEST PASS.
# -Visual also runs the client game tests in a real game window (screenshots land in build\run\clientGameTest\screenshots).
# -Only <Names> runs just those client tests (comma separated simple names, e.g. -Only 'HomeClientTest,FlightClientTest')
#   and skips the self-test. The one entry point, lightborn.test.Suite, reads the list from LIGHTBORN_CLIENT_TESTS.
# -Shots <scenes> runs just those VisualTest scenes (e.g. -Shots 'cage,flight'; implies -Only VisualTest). An unknown
#   name fails the run and lists every scene. -Lean leaves the companion mods out of the client run: boot drops from
#   about 2.5 minutes to well under one, for shots of Lightborn's own things (a companion-only scene skips itself).
# Every run is watched (README.md, Testing rules): one heavy run at a time on this PC, game processes at below-normal
# priority, a hard time limit per run, and a client heartbeat; a run that stalls or overruns gets a thread dump in
# build\test-hang.txt, is killed with every child process, and the script fails saying where it stuck.
param([switch]$Visual, [string]$Only, [string]$Shots, [switch]$Lean)
if ($Shots -and -not $Only) { $Only = 'VisualTest' }
if ($Only) { $Visual = $true }
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

# Limits, in seconds. Measured 2026-11-17/18: self-test about 90 s after the build; client boot about 150 s with the
# companion mods; a fresh test world about 13 s; the whole client suite about 18 minutes.
$SelfTestLimit = 420
$ClientLimit = if ($Only) { 900 } else { 2400 }
$BootLimit = 420     # launch to the first heartbeat
$StallLimit = 180    # longest silence between heartbeats once the tests run

function Remove-TestDirectory([string]$relative) {
    $target = [IO.Path]::GetFullPath((Join-Path $root $relative))
    $boundary = [IO.Path]::GetFullPath($root) + [IO.Path]::DirectorySeparatorChar
    if (-not $target.StartsWith($boundary, [StringComparison]::OrdinalIgnoreCase)) { throw 'Test directory escaped the project' }
    if (Test-Path -LiteralPath $target) { Remove-Item -LiteralPath $target -Recurse -Force }
}

# Every process below $id, from one process snapshot.
function Get-Descendants([int]$id, $all) {
    $kids = @($all | Where-Object { $_.ParentProcessId -eq $id })
    foreach ($k in $kids) { $k; Get-Descendants $k.ProcessId $all }
}

# Java processes of this project's game or test runs that are not ours: another session's test, a leftover, or the
# owner playing. Any of them means a second heavy run would starve the PC.
function Get-ForeignGameJava([int[]]$ours) {
    $projectGame = [regex]::Escape((Join-Path $root 'Game'))
    Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" |
        Where-Object { $ours -notcontains $_.ProcessId } |
        Where-Object { $_.CommandLine -match 'clientGameTest|run-selftest|runSelftest|runClientGameTest' -or $_.CommandLine -match $projectGame }
}

# Runs gradlew with the given arguments under the watchdog. Returns the exit code; throws on a stall or overrun.
function Invoke-Watched([string]$name, [string[]]$gradleArgs, [string]$log, [int]$limit, [string]$heartbeat) {
    $started = Get-Date
    $proc = Start-Process -FilePath (Join-Path $root 'gradlew.bat') -ArgumentList $gradleArgs -WorkingDirectory $root `
        -RedirectStandardOutput $log -RedirectStandardError "$log.err" -NoNewWindow -PassThru
    $null = $proc.Handle  # keeps the exit code readable after the process ends
    $why = $null
    while (-not $proc.WaitForExit(5000)) {
        $all = Get-CimInstance Win32_Process
        foreach ($d in @(Get-Descendants $proc.Id $all)) {
            # Below-normal priority: the owner's desktop and games stay responsive while a test runs.
            try { $gp = Get-Process -Id $d.ProcessId -ErrorAction Stop; if ($gp.PriorityClass -ne 'BelowNormal' -and $gp.PriorityClass -ne 'Idle') { $gp.PriorityClass = 'BelowNormal' } } catch {}
        }
        $elapsed = ((Get-Date) - $started).TotalSeconds
        if ($elapsed -gt $limit) { $why = "ran past its $limit s limit"; break }
        if ($heartbeat) {
            if (Test-Path -LiteralPath $heartbeat) {
                $quiet = ((Get-Date) - (Get-Item -LiteralPath $heartbeat).LastWriteTime).TotalSeconds
                if ($quiet -gt $StallLimit) { $why = "stalled: no heartbeat for $([int]$quiet) s"; break }
            } elseif ($elapsed -gt $BootLimit) { $why = "never reached the tests in $BootLimit s (stuck while booting)"; break }
        }
    }
    if ($why) {
        $where = if ($heartbeat -and (Test-Path -LiteralPath $heartbeat)) { 'last at ' + (Get-Content -LiteralPath $heartbeat -Raw).Trim() } else { 'no heartbeat' }
        $dump = Join-Path $root 'build\test-hang.txt'
        $jcmd = Join-Path $env:JAVA_HOME 'bin\jcmd.exe'
        $all = Get-CimInstance Win32_Process
        Set-Content -LiteralPath $dump "$name $why ($where), $(Get-Date -Format s)"
        foreach ($j in @(Get-Descendants $proc.Id $all | Where-Object { $_.Name -like 'java*' })) {
            Add-Content -LiteralPath $dump "`n===== pid $($j.ProcessId): $($j.CommandLine.Substring(0, [Math]::Min(300, $j.CommandLine.Length)))"
            & $jcmd $j.ProcessId Thread.print 2>&1 | Out-String | Add-Content -LiteralPath $dump
        }
        & taskkill.exe /PID $proc.Id /T /F 2>&1 | Out-Null
        throw "$name $why ($where). Killed it with every child process; thread dump in $dump"
    }
    # Host, not output: the function's only output is the exit code.
    Write-Host ("{0} finished in {1} s" -f $name, [int]((Get-Date) - $started).TotalSeconds)
    return [int]$proc.ExitCode
}

$env:JAVA_HOME = (Get-ChildItem (Join-Path $root '..\..\..\Pipelines\vendor\jdk25') -Directory | Select-Object -First 1).FullName
$lock = Join-Path $root 'build\test-run.lock'
Push-Location $root
try {
    # One heavy run at a time on this PC (owner, 2026-11-18: overlapping runs froze the game window).
    New-Item -ItemType Directory -Force build | Out-Null
    if (Test-Path -LiteralPath $lock) {
        $holder = [int](Get-Content -LiteralPath $lock -Raw)
        if ($holder -ne $PID -and (Get-Process -Id $holder -ErrorAction SilentlyContinue)) { throw "another build/test run (PowerShell pid $holder) is still going: wait for it, never run two" }
    }
    Set-Content -LiteralPath $lock $PID
    $foreign = @(Get-ForeignGameJava @())
    if ($foreign) {
        throw ("a Lightborn game or test is already running (pid " + (($foreign | ForEach-Object ProcessId) -join ', ') +
            "). If it is the owner playing, ask before testing; if it is a leftover test, stop it first.")
    }

    # Restore/verify every pin, including a partially populated cache. Building never changes Game/mods.
    & py -3.14 -I tools\companion_mods.py fetch --cache-only
    if ($LASTEXITCODE -ne 0) { throw "companion mod fetch failed" }
    # Format first (Palantir via Spotless, see build.gradle): every build leaves the sources in house style.
    & .\gradlew.bat spotlessApply --no-daemon -q
    if ($LASTEXITCODE -ne 0) { throw "spotlessApply failed" }
    & .\gradlew.bat build --no-daemon -q
    if ($LASTEXITCODE -ne 0) { throw "gradle build failed" }

    if (-not $Only) {
        New-Item -ItemType Directory -Force run-selftest | Out-Null
        Set-Content run-selftest\eula.txt 'eula=true'
        # FakePlayer checks are not online players. The dedicated test world must not auto-pause mid-suite.
        $properties = Join-Path $root 'run-selftest\server.properties'
        $settings = if (Test-Path -LiteralPath $properties) { @(Get-Content -LiteralPath $properties | Where-Object { $_ -notmatch '^(pause-when-empty-seconds|max-tick-time)=' }) } else { @() }
        # The synchronous, forced-chunk arena setup can exceed vanilla's one-minute tick limit under load.
        [IO.File]::WriteAllLines($properties, [string[]]@($settings + 'pause-when-empty-seconds=0' + 'max-tick-time=300000'), [Text.UTF8Encoding]::new($false))
        # Fresh world every run: the spawn-hut checks need a brand-new world, and old test state must not leak in.
        Remove-TestDirectory 'run-selftest\world'
        $log = Join-Path $root 'run-selftest\selftest.log'
        $code = Invoke-Watched 'self-test' @('runSelftest', '--no-daemon', '-q') $log $SelfTestLimit $null
        $line = Select-String -Path $log -Pattern 'LIGHTBORN SELFTEST (PASS|FAIL).*' | Select-Object -Last 1
        if (-not $line) {
            Get-Content -LiteralPath "$log.err" -ErrorAction SilentlyContinue |
                Select-String -Pattern 'error:|What went wrong|FAILED' | Select-Object -First 12 | ForEach-Object { $_.Line }
            throw "self-test printed no result (exit $code); see $log and $log.err"
        }
        [regex]::Match($line.Line, 'LIGHTBORN SELFTEST (?:PASS|FAIL): \d+ checks').Value
        # Match the verdict itself: check details on the same line can contain the word PASS.
        if ($line.Line -notmatch 'LIGHTBORN SELFTEST PASS') { exit 1 }
    }

    if ($Visual) {
        # Apply production defaults before client mods initialize, rather than midway through a test.
        Remove-TestDirectory 'build\run\clientGameTest'
        & py -3.14 -I tools\prepare_game.py --game-dir build\run\clientGameTest --music-dir .preview\test-music
        if ($LASTEXITCODE -ne 0) { throw 'client test preparation failed' }
        $shotsDir = 'build\run\clientGameTest\screenshots'
        Remove-TestDirectory $shotsDir
        $vlog = Join-Path $root 'build\clientgametest.log'
        $env:LIGHTBORN_CLIENT_TESTS = $Only
        $env:LIGHTBORN_SHOTS = $Shots
        $gradleArgs = @('runClientGameTest', '-x', 'deleteGameTestRunDir', '--no-daemon', '-q')
        if ($Lean) { $gradleArgs += '-Plean' }
        try { $code = Invoke-Watched 'client tests' $gradleArgs $vlog $ClientLimit (Join-Path $root 'build\run\clientGameTest\heartbeat.txt') }
        finally { Remove-Item Env:LIGHTBORN_CLIENT_TESTS, Env:LIGHTBORN_SHOTS -ErrorAction SilentlyContinue }
        # Every verdict, time and failed check, whatever the outcome.
        Select-String -Path $vlog -Pattern 'LIGHTBORN CLIENT (TEST|CHECK|SHOT)' | ForEach-Object { $_.Line -replace '^.*?(LIGHTBORN CLIENT)', '$1' }
        if ($code -ne 0) {
            # Gradle writes compile errors and build failures to stderr, kept beside the log: show them here.
            Get-Content -LiteralPath "$vlog.err" -ErrorAction SilentlyContinue |
                Select-String -Pattern 'error:|What went wrong|FAILED' | Select-Object -First 12 | ForEach-Object { $_.Line }
            throw "client game test failed; see $vlog and $vlog.err"
        }
        Get-ChildItem $shotsDir -Filter *.png | ForEach-Object { $_.FullName }
    }
} finally {
    if ((Test-Path -LiteralPath $lock) -and (Get-Content -LiteralPath $lock -Raw).Trim() -eq "$PID") { Remove-Item -LiteralPath $lock -Force }
    Pop-Location
}
