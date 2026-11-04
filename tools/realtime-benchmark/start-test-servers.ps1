param(
    [ValidateSet('stomp', 'native')][string]$Mode = 'native',
    [switch]$NoFrontend
)

$ErrorActionPreference = 'Stop'

$backendRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$workspaceRoot = Split-Path $backendRoot -Parent
$socketRoot = Join-Path $workspaceRoot 'wooms-socket'
$frontendRoot = Join-Path $workspaceRoot 'wooms-frontend'
$javaHome = 'C:\Users\enssels\.jdks\temurin-22.0.2'
$java = Join-Path $javaHome 'bin\java.exe'
$node = (Get-Command node.exe -ErrorAction Stop).Source
$ports = [System.Collections.Generic.List[int]]::new()
$ports.Add(8080)
if ($Mode -eq 'native') { $ports.Add(8090) }
if (-not $NoFrontend) { $ports.Add(5173) }
$ports = $ports.ToArray()
$busy = @(Get-NetTCPConnection -State Listen -LocalPort $ports -ErrorAction SilentlyContinue)
if ($busy.Count -gt 0) {
    $busy | Select-Object LocalPort, OwningProcess | Format-Table | Out-String | Write-Error
    throw 'A requested UI/test port is already occupied; no process was started.'
}

$runId = [guid]::NewGuid().ToString('N')
$tempRoot = $env:TEMP
$fixtureFile = Join-Path $tempRoot "realtimecompare-fixtures-$runId.json"
$classpathFile = Join-Path $tempRoot "realtimecompare-classpath-$runId.txt"
$backendArgFile = Join-Path $tempRoot "realtimecompare-backend-$runId.args"
$backendOut = Join-Path $tempRoot "realtimecompare-backend-$runId.out.log"
$backendErr = Join-Path $tempRoot "realtimecompare-backend-$runId.err.log"
$socketOut = Join-Path $tempRoot "realtimecompare-netty-$runId.out.log"
$socketErr = Join-Path $tempRoot "realtimecompare-netty-$runId.err.log"
$frontendOut = Join-Path $tempRoot "realtimecompare-frontend-$runId.out.log"
$frontendErr = Join-Path $tempRoot "realtimecompare-frontend-$runId.err.log"
$metadataFile = Join-Path $tempRoot "realtimecompare-launch-$runId.json"
$started = [System.Collections.Generic.List[System.Diagnostics.Process]]::new()

$oldJavaHome = $env:JAVA_HOME
$oldPath = $env:PATH
$oldFixtureFile = $env:REALTIME_FIXTURE_FILE
$oldSocketTransport = $env:VITE_SOCKET_TRANSPORT
$oldNettyUrl = $env:VITE_NETTY_WS_URL
$oldStompUrl = $env:VITE_STOMP_WS_URL
$env:JAVA_HOME = $javaHome
$env:PATH = "$javaHome\bin;$oldPath"
$env:REALTIME_FIXTURE_FILE = $fixtureFile
$env:VITE_SOCKET_TRANSPORT = if ($Mode -eq 'native') { 'netty' } else { 'stomp' }
$env:VITE_NETTY_WS_URL = 'ws://localhost:8090/ws'
$env:VITE_STOMP_WS_URL = 'ws://localhost:8080/ws'

try {
    $gradleWrapper = Join-Path $backendRoot 'gradlew.bat'
    $initScript = Join-Path $PSScriptRoot 'fixture.init.gradle'
    Push-Location $backendRoot
    try {
        & $gradleWrapper -I $initScript --no-daemon "-PrealtimeFixtureClasspathFile=$classpathFile" prepareRealtimeFixtureClasspath --console=plain
        if ($LASTEXITCODE -ne 0) { throw "Fixture test-main compilation failed with exit code $LASTEXITCODE" }
    } finally { Pop-Location }

    $classpath = [IO.File]::ReadAllText($classpathFile).Replace('\', '/')
    if ([string]::IsNullOrWhiteSpace($classpath)) { throw 'Fixture test classpath was not generated.' }
    $serviceKeyBytes = New-Object byte[] 32
    $serviceKeyGenerator = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $serviceKeyGenerator.GetBytes($serviceKeyBytes) }
    finally { $serviceKeyGenerator.Dispose() }
    $env:WOOMS_REALTIME_INTERNAL_SECRET = [Convert]::ToBase64String($serviceKeyBytes)
    $backendMaxHeapMiB = if ($Mode -eq 'stomp') { 1536 } else { 1024 }
    $backendArguments = @(
        "-Xmx${backendMaxHeapMiB}m",
        '-cp',
        "`"$classpath`"",
        'com.ee06.wooms.benchmark.RealtimeFixtureServerMain',
        '--server.port=8080',
        '--spring.datasource.url=jdbc:h2:mem:realtimecompare;DB_CLOSE_DELAY=-1',
        '--spring.datasource.driver-class-name=org.h2.Driver',
        '--spring.datasource.username=sa',
        '--spring.datasource.password=',
        '--spring.jpa.hibernate.ddl-auto=create-drop',
        '--logging.level.root=WARN',
        '--logging.level.com.ee06.wooms=WARN',
        '--logging.level.http.request=WARN',
        '--logging.level.business.event=WARN',
        '--logging.level.org.springframework.web=WARN',
        '--logging.level.org.hibernate.SQL=WARN',
        '--logging.level.org.hibernate.type.descriptor.sql.BasicBinder=WARN'
    ) -join "`r`n"
    [IO.File]::WriteAllText($backendArgFile, $backendArguments, [Text.UTF8Encoding]::new($false))

    $backendProcess = Start-Process -FilePath $java -ArgumentList "@$backendArgFile" -WorkingDirectory $backendRoot `
        -WindowStyle Hidden -PassThru -RedirectStandardOutput $backendOut -RedirectStandardError $backendErr
    $started.Add($backendProcess)
    $backendDeadline = (Get-Date).AddMinutes(2)
    while ((Get-Date) -lt $backendDeadline -and -not (Test-Path $fixtureFile)) {
        if ($backendProcess.HasExited) { throw "Fixture backend exited; inspect TEMP logs: $backendOut / $backendErr" }
        Start-Sleep -Milliseconds 500
    }
    if (-not (Test-Path $fixtureFile)) { throw "Fixture backend did not finish seeding; inspect TEMP logs: $backendOut / $backendErr" }

    $socketProcess = $null
    if ($Mode -eq 'native') {
        $socketJar = Join-Path $socketRoot 'build\libs\mychat-0.0.1-SNAPSHOT.jar'
        $socketProcess = Start-Process -FilePath $java -ArgumentList @(
            '-Xmx512m', '-jar', $socketJar,
            '--wooms.socket.host=127.0.0.1', '--wooms.socket.port=8090', '--wooms.socket.path=/ws',
            '--wooms.socket.backend-url=http://localhost:8080',
            '--logging.level.root=WARN', '--logging.level.com.ee06.mychat=WARN',
            '--logging.level.org.springframework.web=WARN'
        ) -WorkingDirectory $socketRoot -WindowStyle Hidden -PassThru `
            -RedirectStandardOutput $socketOut -RedirectStandardError $socketErr
        $started.Add($socketProcess)
    }

    $frontendProcess = $null
    if (-not $NoFrontend) {
        $viteScript = Join-Path $frontendRoot 'node_modules\vite\bin\vite.js'
        $frontendProcess = Start-Process -FilePath $node -ArgumentList @(
            $viteScript, '--host', '127.0.0.1', '--port', '5173', '--strictPort'
        ) -WorkingDirectory $frontendRoot -WindowStyle Hidden -PassThru `
            -RedirectStandardOutput $frontendOut -RedirectStandardError $frontendErr
        $started.Add($frontendProcess)
    }

    $deadline = (Get-Date).AddMinutes(2)
    $ready = @{ 8080 = $false; 8090 = $false; 5173 = $false }
    $notReady = @($ports | Where-Object { -not $ready[$_] })
    while ((Get-Date) -lt $deadline -and $notReady.Count -gt 0) {
        foreach ($port in $ports) {
            if (-not $ready[$port]) {
                $client = [System.Net.Sockets.TcpClient]::new()
                try { $client.Connect('127.0.0.1', $port); $ready[$port] = $true } catch {} finally { $client.Dispose() }
            }
        }
        foreach ($process in $started) {
            if ($process.HasExited) { throw "A test server exited during startup; logs are in TEMP." }
        }
        $notReady = @($ports | Where-Object { -not $ready[$_] })
        if ($notReady.Count -gt 0) { Start-Sleep -Milliseconds 500 }
    }
    if ($notReady.Count -gt 0) { throw 'Timed out waiting for one or more requested test/UI ports.' }
    if (-not $NoFrontend) {
        try {
            $null = Invoke-WebRequest -Uri 'http://127.0.0.1:5173/' -TimeoutSec 10
        } catch { throw 'Frontend port is open but Vite did not return its page.' }
    }

    $safeMetadata = [ordered]@{
        runId = $runId
        mode = $Mode
        maxJavaHeapMiB = 1536
        frontendDisabled = [bool]$NoFrontend
        backend = [ordered]@{ pid = $backendProcess.Id; port = 8080; logOut = $backendOut; logErr = $backendErr; database = 'jdbc:h2:mem:realtimecompare;DB_CLOSE_DELAY=-1' }
        netty = if ($null -ne $socketProcess) { [ordered]@{ pid = $socketProcess.Id; port = 8090; logOut = $socketOut; logErr = $socketErr; backendUrl = 'http://localhost:8080'; maxHeapMiB = 512 } } else { $null }
        frontend = if ($null -ne $frontendProcess) { [ordered]@{ pid = $frontendProcess.Id; port = 5173; logOut = $frontendOut; logErr = $frontendErr; transport = $env:VITE_SOCKET_TRANSPORT } } else { $null }
        fixture = [ordered]@{ file = $fixtureFile; rooms = 100; users = 1000 }
    }
    [IO.File]::WriteAllText($metadataFile, ($safeMetadata | ConvertTo-Json -Depth 5), [Text.UTF8Encoding]::new($false))
    [pscustomobject]@{
        Ready = $true
        Mode = $Mode
        BackendPid = $backendProcess.Id
        BackendMaxHeapMiB = $backendMaxHeapMiB
        SocketMaxHeapMiB = if ($Mode -eq 'native') { 512 } else { 0 }
        NettyPid = if ($null -ne $socketProcess) { $socketProcess.Id } else { $null }
        FrontendPid = if ($null -ne $frontendProcess) { $frontendProcess.Id } else { $null }
        Ports = ($ports -join ', ')
        FixtureRooms = 100
        FixtureUsers = 1000
        FixtureFile = $fixtureFile
        MetadataFile = $metadataFile
        BackendLogs = "$backendOut | $backendErr"
        NettyLogs = "$socketOut | $socketErr"
        FrontendLogs = "$frontendOut | $frontendErr"
    } | Format-List
} catch {
    foreach ($process in $started) {
        $current = Get-CimInstance Win32_Process -Filter "ProcessId=$($process.Id)" -ErrorAction SilentlyContinue
        if ($current) { Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue }
    }
    throw
} finally {
    if ($null -eq $oldJavaHome) { Remove-Item Env:\JAVA_HOME -ErrorAction SilentlyContinue } else { $env:JAVA_HOME = $oldJavaHome }
    $env:PATH = $oldPath
    if ($null -eq $oldFixtureFile) { Remove-Item Env:\REALTIME_FIXTURE_FILE -ErrorAction SilentlyContinue } else { $env:REALTIME_FIXTURE_FILE = $oldFixtureFile }
    if ($null -eq $oldSocketTransport) { Remove-Item Env:\VITE_SOCKET_TRANSPORT -ErrorAction SilentlyContinue } else { $env:VITE_SOCKET_TRANSPORT = $oldSocketTransport }
    if ($null -eq $oldNettyUrl) { Remove-Item Env:\VITE_NETTY_WS_URL -ErrorAction SilentlyContinue } else { $env:VITE_NETTY_WS_URL = $oldNettyUrl }
    if ($null -eq $oldStompUrl) { Remove-Item Env:\VITE_STOMP_WS_URL -ErrorAction SilentlyContinue } else { $env:VITE_STOMP_WS_URL = $oldStompUrl }
    Remove-Item Env:\WOOMS_REALTIME_INTERNAL_SECRET -ErrorAction SilentlyContinue
}
