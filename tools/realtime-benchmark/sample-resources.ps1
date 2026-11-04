param(
    [Parameter(Mandatory = $true)][string]$ServerPids,
    [Parameter(Mandatory = $true)][string]$WorkerPids,
    [Parameter(Mandatory = $true)][string]$StartSignalPath,
    [Parameter(Mandatory = $true)][string]$ReadyPath,
    [Parameter(Mandatory = $true)][int]$DurationSeconds,
    [Parameter(Mandatory = $true)][string]$OutputPath
)

$ErrorActionPreference = 'Stop'
$serverIds = @()
$workerIds = @()
if (-not [string]::IsNullOrWhiteSpace($ServerPids)) { $serverIds = @($ServerPids.Split(',') | ForEach-Object { [int]::Parse($_) }) }
if (-not [string]::IsNullOrWhiteSpace($WorkerPids)) { $workerIds = @($WorkerPids.Split(',') | ForEach-Object { [int]::Parse($_) }) }
$allIds = @($serverIds + $workerIds | Select-Object -Unique)
# Warm Windows process/WMI providers before the synchronized measurement window.
# The first CIM query can take several seconds on a cold PowerShell process.
foreach ($pidValue in $allIds) { try { Get-Process -Id $pidValue -ErrorAction Stop | Out-Null } catch { } }
try { Get-CimInstance -ClassName Win32_PerfFormattedData_PerfOS_Processor -Filter "Name='_Total'" | Out-Null } catch { }
$clock = [System.Diagnostics.Stopwatch]::StartNew()
Set-Content -LiteralPath $ReadyPath -Value 'ready' -NoNewline -Encoding ASCII

while (-not (Test-Path -LiteralPath $StartSignalPath)) { Start-Sleep -Milliseconds 100 }
$startEpochMs = [long](Get-Content -LiteralPath $StartSignalPath -Raw).Trim()
$start = [DateTimeOffset]::FromUnixTimeMilliseconds($startEpochMs).UtcDateTime
$end = $start.AddSeconds($DurationSeconds)

while ([DateTime]::UtcNow -lt $start) {
    $remaining = ($start - [DateTime]::UtcNow).TotalMilliseconds
    Start-Sleep -Milliseconds ([Math]::Max(10, [Math]::Min(100, [int]$remaining)))
}
$clock.Restart()

$previousCpu = @{}
$lastTickSeconds = 0.0
$sampleNumber = 0
while ([DateTime]::UtcNow -le $end) {
    $tick = $clock.Elapsed.TotalSeconds
    $elapsed = if ($sampleNumber -eq 0) { 1.0 } else { [Math]::Max(0.001, $tick - $lastTickSeconds) }
    $processes = @()
    foreach ($pidValue in $allIds) {
        try {
            $proc = Get-Process -Id $pidValue -ErrorAction Stop
            $cpuSeconds = $proc.TotalProcessorTime.TotalSeconds
            $deltaCpu = if ($previousCpu.ContainsKey($pidValue)) { [Math]::Max(0.0, $cpuSeconds - $previousCpu[$pidValue]) } else { 0.0 }
            $previousCpu[$pidValue] = $cpuSeconds
            $role = if ($serverIds -contains $pidValue) { 'server' } else { 'worker' }
            $processes += [pscustomobject][ordered]@{
                pid = $pidValue
                role = $role
                cpuSeconds = [Math]::Round($cpuSeconds, 4)
                cpuDeltaSeconds = [Math]::Round($deltaCpu, 4)
                cpuPercentOneCore = [Math]::Round(($deltaCpu / $elapsed) * 100.0, 2)
                workingSetBytes = [long]$proc.WorkingSet64
                privateBytes = [long]$proc.PrivateMemorySize64
            }
        } catch {
            $processes += [pscustomobject][ordered]@{ pid = $pidValue; role = if ($serverIds -contains $pidValue) { 'server' } else { 'worker' };
                cpuSeconds = 0.0; cpuDeltaSeconds = 0.0; cpuPercentOneCore = 0.0;
                workingSetBytes = 0L; privateBytes = 0L; missing = $true }
        }
    }

    $server = @($processes | Where-Object { $_.role -eq 'server' -and -not $_.missing })
    $workers = @($processes | Where-Object { $_.role -eq 'worker' -and -not $_.missing })
    $serverCpuDelta = ($server | Measure-Object -Property cpuDeltaSeconds -Sum).Sum
    $serverWorkingSet = ($server | Measure-Object -Property workingSetBytes -Sum).Sum
    $serverPrivate = ($server | Measure-Object -Property privateBytes -Sum).Sum
    $workerCpuDelta = ($workers | Measure-Object -Property cpuDeltaSeconds -Sum).Sum
    $workerWorkingSet = ($workers | Measure-Object -Property workingSetBytes -Sum).Sum
    $workerPrivate = ($workers | Measure-Object -Property privateBytes -Sum).Sum
    if ($null -eq $serverCpuDelta) { $serverCpuDelta = 0 }
    if ($null -eq $serverWorkingSet) { $serverWorkingSet = 0 }
    if ($null -eq $serverPrivate) { $serverPrivate = 0 }
    if ($null -eq $workerCpuDelta) { $workerCpuDelta = 0 }
    if ($null -eq $workerWorkingSet) { $workerWorkingSet = 0 }
    if ($null -eq $workerPrivate) { $workerPrivate = 0 }
    $hostCpu = $null
    try {
        $hostCpu = [double](Get-CimInstance -ClassName Win32_PerfFormattedData_PerfOS_Processor -Filter "Name='_Total'").PercentProcessorTime
    } catch { }
    $record = [ordered]@{
        timestampUtc = [DateTime]::UtcNow.ToString('o')
        epochMs = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
        sample = $sampleNumber
        intervalSeconds = [Math]::Round($elapsed, 4)
        logicalProcessors = [Environment]::ProcessorCount
        hostBusyPercentOfLogicalProcessors = if ($null -eq $hostCpu) { $null } else { [Math]::Round($hostCpu, 2) }
        hostBusyOneCoreEquivalentPercent = if ($null -eq $hostCpu) { $null } else { [Math]::Round($hostCpu * [Environment]::ProcessorCount, 2) }
        serverCpuDeltaSeconds = [Math]::Round([double]$serverCpuDelta, 4)
        serverWorkingSetBytesTotal = [long]$serverWorkingSet
        serverPrivateBytesTotal = [long]$serverPrivate
        workerCpuDeltaSeconds = [Math]::Round([double]$workerCpuDelta, 4)
        workerWorkingSetBytesTotal = [long]$workerWorkingSet
        workerPrivateBytesTotal = [long]$workerPrivate
        processes = $processes
    }
    Add-Content -LiteralPath $OutputPath -Value ($record | ConvertTo-Json -Depth 5 -Compress) -Encoding UTF8
    $sampleNumber++
    $lastTickSeconds = $tick
    $nextElapsed = $sampleNumber
    while ($clock.Elapsed.TotalSeconds -lt $nextElapsed -and [DateTime]::UtcNow -lt $end) {
        $remaining = ($nextElapsed - $clock.Elapsed.TotalSeconds) * 1000
        Start-Sleep -Milliseconds ([Math]::Max(10, [Math]::Min(100, [int]$remaining)))
    }
}
