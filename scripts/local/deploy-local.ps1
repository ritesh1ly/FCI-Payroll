<#
.SYNOPSIS
  Deploys FCILekha Payroll on this Windows machine the way it is deployed on a dev or UAT server,
  without Docker, then smoke-tests it:
    - the shared database: one H2 TCP server (in-memory database, loopback only);
    - the backend WAR twice, in two Tomcat 11 instances on JDK 25 from ONE installation:
        tomcat-online  profile online (REST API, login)       ports 8080 / 8081 (management)
        tomcat-batch   profile batch  (job poller, schedules) ports 8090 / 8091 (management)
    - the Next.js frontend as its standalone SSR server (node server.js) on 127.0.0.1:3000;
    - NGINX terminating TLS: "/" to Next.js, "/api/" to the online instance.

.DESCRIPTION
  The installed Tomcat and NGINX are used as they are and never modified. As on a server:
    - the Tomcat installation is CATALINA_HOME; each mode is its own instance (CATALINA_BASE) with
      the reviewed server.xml, context descriptor and setenv.bat from config/tomcat;
    - NGINX runs with its own prefix directory holding the site from config/nginx.
  Configuration is the server configuration from config/ (backend application.yaml, secrets as
  files, TLS); only Windows paths and localhost ports are substituted, plus a small local overlay
  (e-mails go to the log, a higher per-IP sign-in limit because every local browser is 127.0.0.1).

  Profiles uat,demo plus the mode: the strict start-up guards of uat apply as on a server, and demo
  adds the obviously fake seed users of docs/rbac/SEED_USERS.md (password Fci@Demo2026), with OTP
  codes in the online log. -NoDemo deploys uat alone (one System Administrator from V009).
  The database is H2 IN MEMORY in the H2 server process: every deploy starts from an empty schema
  that the online instance migrates (Flyway) before the batch instance starts.

  Layout (everything under -Runtime, git-ignored):
    h2\                            H2 server classpath (h2 jar from the WAR + fci-h2-triggers.jar)
    tomcat-online\, tomcat-batch\  CATALINA_BASEs: conf\, bin\setenv.bat, webapps-fci\, logs\, ...
    nginx-prefix\                  NGINX prefix: conf\nginx.conf, conf\fci\..., logs\, temp\
    config\backend\                application.yaml shared by both instances
    config\secrets\backend\        generated once by scripts/GenerateSecrets.java
    config\tls\                    self-signed certificate for localhost
    apps\current\fci-payroll.war   the deployed WAR
    web\current\                   the deployed Next.js standalone server
    logs\{h2,online,batch,web}\    process and application logs

  URLs: https://localhost:8443/ (application),
        http://127.0.0.1:8081/api/actuator/health (online), http://127.0.0.1:8091/... (batch).

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts\local\deploy-local.ps1
.EXAMPLE
  powershell -File scripts\local\deploy-local.ps1 -SkipBuild        # redeploy existing build output
.EXAMPLE
  powershell -File scripts\local\deploy-local.ps1 -SkipBuild -Jpda   # attach a debugger: 8000 online, 8001 batch
.EXAMPLE
  powershell -File scripts\local\deploy-local.ps1 -SkipBuild -Pwa  # with the service worker (installable PWA)
.EXAMPLE
  powershell -File scripts\local\deploy-local.ps1 -Action stop
.EXAMPLE
  powershell -File scripts\local\deploy-local.ps1 -Action web       # frontend change: rebuild and restart Next.js only
.EXAMPLE
  powershell -File scripts\local\deploy-local.ps1 -Action backend   # backend change: restart the two Tomcats only; H2 and its data stay
.EXAMPLE
  powershell -File scripts\local\deploy-local.ps1 -PortOffset 200   # beside another stack: https://localhost:8643/
.EXAMPLE
  powershell -File scripts\local\deploy-local.ps1 -OtpRequired Y    # ask the OTP of critical activities, as UAT / prod
#>
[CmdletBinding()]
param(
  [ValidateSet('deploy', 'stop', 'status', 'web', 'backend')]
  [string]$Action = 'deploy',
  # Default: scripts\local\runtime (set below: Windows PowerShell 5.1 has no $PSScriptRoot here).
  [string]$Runtime = '',
  # The installed Tomcat 11 (CATALINA_HOME) and NGINX; neither is modified.
  [string]$TomcatHome = 'C:\Users\user\apache-tomcat-11.0.27',
  [string]$NginxHome = 'C:\Users\user\nginx-1.30.5',
  # JDK 25 (baseline); used when JAVA_HOME is not a JDK 25 or later.
  [string]$Jdk25 = 'C:\Program Files\Java\jdk-25.0.4.1',
  # Use the WAR and the frontend build already there.
  [switch]$SkipBuild,
  # uat only, without the demo seed (first-deployment System Administrator instead).
  [switch]$NoDemo,
  # Adds the 50,000-employee volume data set (profile volume, docs/perf/VOLUME_DATA.md) for volume and
  # performance runs only; never for the normal tests or the E2E suite.
  [switch]$Volume,
  # Adds the edge-case data set (profile edge, docs/test-data/EDGE_DATA.md): rows for every page and section that
  # exercise the edge cases; demo only, never with -Volume (H2, or Oracle schema FCI_PAYROLL).
  [switch]$EdgeData,
  # Adds the screen demo data set for the FCI evaluation (profile screens, OD-141 D-screens, db/demo-screens,
  # docs/quality/demo-data-coverage.md): rows that give every wireframe screen its states on top of the demo data;
  # demo only, never with -EdgeData or -Volume (H2, or an Oracle schema of its own, never one holding the edge set).
  [switch]$ScreensData,
  # Resilience runs (owner 2026-10-27): adds the chaos-monkey profile (Chaos Monkey for Spring Boot, asleep until
  # scripts/resilience/chaos-run.mjs wakes it). The WAR must be built with -Pfci.chaos=true; demo stacks only.
  [switch]$Chaos,
  # Start both Tomcat instances with a JDWP (JPDA) port on loopback, not suspended:
  # online 127.0.0.1:8000, batch 127.0.0.1:8001 (IDE: "Attach: Tomcat online/batch").
  [switch]$Jpda,
  # Enable the service worker (FCI_PWA_ENABLED=true on the Next.js server): installable PWA, offline
  # page. Without it any registered worker is removed (docs/architecture/RESPONSIVE_AND_PWA.md).
  [switch]$Pwa,
  # Added to every port (application, management, H2, Next.js, NGINX, JPDA) so a second checkout can
  # run beside a stack already on the default ports; 0 keeps the documented ports.
  [ValidateRange(0, 1000)]
  [int]$PortOffset = 0,
  # OTP verification of the critical activities (fci.api.otp.required): N on a local machine by default
  # (project owner, 2026-10-23); Y with -NoDemo (a UAT rehearsal refuses N) and for the full E2E suite,
  # whose OTP specs prove the Y behaviour.
  [ValidateSet('', 'Y', 'N')]
  [string]$OtpRequired = '',
  # OD-130: 'Y' for a run of e2e/01-sign-in-and-access/security-offence.spec.ts; demo stacks default to 'N'.
  [string]$SecurityOffence = ''
  ,
  # Start-up speed (local stacks only, never UAT or production): the JVMs compile with C1 only
  # (-XX:TieredStopAtLevel=1), which cuts the Spring start-up markedly on a busy machine at a small
  # cost in peak speed. -FullJit keeps the normal tiered compiler (profiling, performance runs).
  [switch]$FullJit,
  # Hibernate checks every mapped entity against the schema at start-up (spring.jpa.hibernate.ddl-auto=validate,
  # about 15 s). A local stack skips it: Flyway owns the schema and the integration tests, UAT and production keep
  # the check, so drift is still caught there. -ValidateSchema keeps it on this stack too (owner 2026-10-26).
  [switch]$ValidateSchema,
  # Extra JVM options for both backends, for start-up experiments (e.g. '-Dspring.data.jpa.repositories.bootstrap-mode=lazy').
  [string]$ExtraJavaOpts = '',
  # Extra Spring profiles added to both backends, e.g. 'hrms-mock' (Payroll calls the HRMS mock server at
  # FCI_HRMS_BASE_URL with FCI_HRMS_API_KEY, FCI_HRMS_USERNAME and FCI_HRMS_PASSWORD from the environment).
  [string]$ExtraProfiles = '',
  # Adds the worker clones of the demo users (profile e2e-parallel, frontend/e2e/README.md "Parallel lanes"), so
  # Playwright lanes run in parallel, each worker as its own user. H2 demo stacks only (StartupGuards).
  [switch]$E2EParallelUsers,
  # Records a Java Flight Recorder profile of each instance's first five minutes into
  # logs\<mode>\startup-<pid>.jfr, to find what makes the start-up slow (jfr print / JMC).
  [switch]$ProfileStartup,
  # The path the pages are served under (next.config.ts basePath): /nginx, as on the shared server
  # (owner 2026-10-31: https://localhost:8443/nginx). The API stays at /api/; every other path is sent
  # to the pages (config/nginx/conf.d/fci-payroll.conf). Another name only for an experiment.
  [ValidatePattern('^/[A-Za-z0-9_-]+$')]
  [string]$BasePath = '/nginx'
  ,
  # The database (owner 2026-11-01, feature/Oracle). H2: the in-memory H2 TCP server of this stack, empty at
  # every deploy - every test suite and Playwright run uses it. Oracle: the application on an Oracle database
  # (profile oracle, docs/db/ORACLE_LOCAL.md): no H2 server; the schema and its data persist between
  # deploys, a deploy applies only the new migrations. Never for the Playwright suite.
  [ValidateSet('H2', 'Oracle')]
  [string]$Database = 'H2',
  # H2 only (owner 2026-11-09): build the schema and its data from the install kit in its one order (1 DROP,
  # 2 DDL, 3 DML reference + demo, then edge or screens, 4 COMPILE INVALID; db/h2-setup/fcipay_h2_setup_<set>.sql, OD-140)
  # instead of letting Flyway migrate an empty schema. Flyway then finds its history loaded and applies only
  # what the kit leaves to it (V009, docs/db/INSTALL_KIT.md 3.6). Regenerate the kit first after a migration.
  [switch]$SchemaFromKit,
  # -Database Oracle: the wallet folder (tnsnames.ora, sqlnet.ora, cwallet.sso) and its alias to use.
  [string]$OracleWalletDir = $env:FCI_ORACLE_WALLET_DIR,
  [string]$OracleTnsAlias = 'fcipay_medium',
  # -Database Oracle: the schema owner and the folder of its password (docs/db/ORACLE_LOCAL.md). Two data
  # sets that cannot share a schema (both write the cycles of the same offices and months):
  #   FCI_PAYROLL     demo + edge cases (use -EdgeData)      secrets\oracle
  #   FCI_PAYROLL_VOL demo + 50K volume (use -Volume)        secrets\oracle-vol
  #   (the screen demo set, -ScreensData, needs a schema without the edge set: a fresh owner or a demo-only one)
  [ValidatePattern('^[A-Z][A-Z0-9_]{0,29}$')]
  [string]$OracleUser = 'FCI_PAYROLL',
  [string]$OracleSecretsDir = ''
)

$ErrorActionPreference = 'Stop'
# The prefix of every page URL ('/nginx'); also the pages' home (Next.js serves a base path without
# a trailing slash and redirects '/nginx/' there with a 308).
$PagePath = $BasePath
$PageRoot = $BasePath
# What this run (re)starts. deploy: everything, fresh in-memory database. web: the Next.js server only (a frontend
# change). backend: the two Tomcats only, on the running H2 (its data stays; new migrations apply on top).
$doDb = $Action -eq 'deploy'
$oracle = $Database -eq 'Oracle'
# The schema owner's password: <repo>\secrets\oracle\spring.datasource.password (git-ignored, ADR-0013).
$OracleSecrets = if ($OracleSecretsDir) { $OracleSecretsDir } else {
  Join-Path (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path $(if ($OracleUser -eq 'FCI_PAYROLL_VOL') { 'secrets\oracle-vol' } else { 'secrets\oracle' })
}
if ($oracle -and $Action -in 'deploy', 'backend') {
  if (-not $OracleWalletDir -or -not (Test-Path (Join-Path $OracleWalletDir 'tnsnames.ora'))) {
    throw '-Database Oracle needs -OracleWalletDir (or FCI_ORACLE_WALLET_DIR): the folder with tnsnames.ora and cwallet.sso'
  }
  if (-not (Test-Path (Join-Path $OracleSecrets 'spring.datasource.password'))) {
    throw "-Database Oracle needs the schema password in $OracleSecrets\spring.datasource.password (docs/db/ORACLE_LOCAL.md)"
  }
}
$doBackend = $Action -in 'deploy', 'backend'
$doWeb = $Action -in 'deploy', 'web'
$ProgressPreference = 'SilentlyContinue'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

if (-not $Runtime) { $Runtime = Join-Path $PSScriptRoot 'runtime' }
$Repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$HttpsPort = 8443 + $PortOffset
$PlainPort = 8088 + $PortOffset
$H2Port = 9092 + $PortOffset
$WebPort = 3000 + $PortOffset
# Mode -> application port, management port, heap. Local stacks only: the online heap is production's setenv.sh default
# (2g); a parallel Playwright run of 8 workers signs in with Argon2 at once and ran short of 1g (owner 2026-11-08).
# Override per run with FCI_LOCAL_ONLINE_HEAP / FCI_LOCAL_BATCH_HEAP / FCI_LOCAL_H2_HEAP.
$OnlineHeap = if ($env:FCI_LOCAL_ONLINE_HEAP) { $env:FCI_LOCAL_ONLINE_HEAP } else { '2g' }
$BatchHeap = if ($env:FCI_LOCAL_BATCH_HEAP) { $env:FCI_LOCAL_BATCH_HEAP } else { '1g' }
$H2Heap = if ($env:FCI_LOCAL_H2_HEAP) { $env:FCI_LOCAL_H2_HEAP } else { '1g' }
$Instances = [ordered]@{
  online = @{ Http = 8080 + $PortOffset; Mgmt = 8081 + $PortOffset; Heap = $OnlineHeap; Jpda = 8000 + $PortOffset }
  batch  = @{ Http = 8090 + $PortOffset; Mgmt = 8091 + $PortOffset; Heap = $BatchHeap; Jpda = 8001 + $PortOffset }
}

New-Item -ItemType Directory -Force $Runtime | Out-Null
$Runtime = (Resolve-Path $Runtime).Path
$H2Dir = Join-Path $Runtime 'h2'
$Nginx = Join-Path $Runtime 'nginx-prefix'
$nginxExe = Join-Path $NginxHome 'nginx.exe'
$Config = Join-Path $Runtime 'config'
$Apps = Join-Path $Runtime 'apps'
$Web = Join-Path $Runtime 'web'
$Logs = Join-Path $Runtime 'logs'

function Step([string]$Message) { Write-Host "==> $Message" -ForegroundColor Cyan }
function Fwd([string]$Path) { $Path -replace '\\', '/' }

# Windows PowerShell 5.1 turns any stderr line of a native program into an error record, which
# $ErrorActionPreference = 'Stop' would make fatal (nginx -t, Gradle and npm all write to stderr).
# Native programs report failure through $LASTEXITCODE instead.
function Invoke-Native([scriptblock]$Block) {
  $previous = $ErrorActionPreference
  $ErrorActionPreference = 'Continue'
  try { & $Block } finally { $ErrorActionPreference = $previous }
}

# config/ files are UTF-8 without BOM; Windows PowerShell would read them as ANSI and write a BOM.
$Utf8 = New-Object System.Text.UTF8Encoding $false
function Read-Text([string]$Path) { [IO.File]::ReadAllText($Path, $Utf8) }
function Write-Text([string]$Path, [string]$Text) { [IO.File]::WriteAllText($Path, $Text, $Utf8) }

# Removes a directory only if it lies inside the runtime directory (never anything else).
function Remove-RuntimeDir([string]$Path) {
  $full = [IO.Path]::GetFullPath($Path)
  if (-not $full.StartsWith($Runtime + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw "refusing to delete ${full}: outside $Runtime"
  }
  if (Test-Path $full) { Remove-Item -Recurse -Force $full }
}

function Test-Ready([string]$Url) {
  try { (Invoke-WebRequest -UseBasicParsing -Uri $Url -TimeoutSec 3 -MaximumRedirection 0).StatusCode -eq 200 } catch { $false }
}

function Wait-Ready([string]$Name, [string]$Url, [string]$Log, [int]$Minutes = 4) {
  $deadline = (Get-Date).AddMinutes($Minutes)
  while (-not (Test-Ready $Url)) {
    if ((Get-Date) -gt $deadline) {
      if ($Log -and (Test-Path $Log)) { Get-Content -Tail 40 $Log }
      throw "$Name did not become ready ($Url); logs: $Logs"
    }
    Start-Sleep -Seconds 2
  }
  Write-Host "    $Name ready"
}

# Waits until the online instance's Flyway has migrated the schema: a line written to its JSON log
# after $FromLine (the log is appended across deploys) saying the migrations were applied or the
# schema is up to date. The batch instance (Flyway disabled) only needs the schema, not a ready
# online instance, so it can start from here and the two start-ups overlap.
function Wait-Migrated([string]$ApiLog, [long]$FromByte, [string]$Out, [int]$Minutes = 4) {
  $deadline = (Get-Date).AddMinutes($Minutes)
  while ($true) {
    if (Test-Path $ApiLog) {
      $stream = [IO.File]::Open($ApiLog, 'Open', 'Read', 'ReadWrite')
      try {
        # A log rotated since the deploy began is shorter than before: read it from its start.
        $from = if ($stream.Length -lt $FromByte) { 0 } else { $FromByte }
        [void]$stream.Seek($from, 'Begin')
        $new = (New-Object IO.StreamReader($stream)).ReadToEnd()
      } finally { $stream.Dispose() }
      if ($new -match 'Successfully applied \d+ migration|Schema .* is up to date') { break }
    }
    if ((Get-Date) -gt $deadline) {
      if ($Out -and (Test-Path $Out)) { Get-Content -Tail 40 $Out }
      throw "backend (online) did not migrate the schema; logs: $Logs"
    }
    Start-Sleep -Seconds 2
  }
  Write-Host '    schema migrated (online)'
}

# Stops the process listening on $Port, but only if it belongs to this runtime (its command line
# names a path under $Runtime); anything else on the port is an error, never killed.
function Stop-Port([int]$Port, [string]$Name) {
  $listener = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
  if (-not $listener) { return }
  $process = Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwningProcess)"
  if ($process.CommandLine -like "*$Runtime*" -or $process.CommandLine -like "*$(Fwd $Runtime)*") {
    Step "Stopping $Name"
    Stop-Process -Id $process.ProcessId -Force
  } else {
    throw "port $Port is used by another program (PID $($process.ProcessId)): $($process.Name). Stop it first."
  }
}

function Stop-Local {
  # NGINX first (no traffic), then the frontend, the batch and online instances, the database last.
  $ours = Get-CimInstance Win32_Process -Filter "Name='nginx.exe'" |
    Where-Object { $_.CommandLine -like "*$(Fwd $Runtime)*" -or $_.ExecutablePath -like "$Runtime*" }
  if ($ours) {
    Step 'Stopping NGINX'
    $ours | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
  }
  Stop-Port $WebPort 'Next.js server'
  Stop-Port $Instances.batch.Http 'Tomcat (batch)'
  Stop-Port $Instances.online.Http 'Tomcat (online)'
  Stop-Port $H2Port 'H2 database server'
  Start-Sleep -Seconds 1
}

function Show-Status {
  foreach ($mode in $Instances.Keys) {
    $ready = Test-Ready "http://127.0.0.1:$($Instances[$mode].Mgmt)/api/actuator/health/readiness"
    Write-Host ("  {0,-7} readiness: {1}" -f $mode, $(if ($ready) { 'UP' } else { 'DOWN' }))
  }
  $h2 = [bool](Get-NetTCPConnection -LocalPort $H2Port -State Listen -ErrorAction SilentlyContinue)
  Write-Host "  h2      tcp://127.0.0.1:${H2Port}: $(if ($h2) { 'UP' } else { 'DOWN' })"
  $next = try { (Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$WebPort/" -TimeoutSec 3).StatusCode } catch { 'unreachable' }
  Write-Host "  next    http://127.0.0.1:${WebPort}/: $next"
  $web = try { (Invoke-WebRequest -UseBasicParsing -Uri "https://localhost:$HttpsPort/" -TimeoutSec 3).StatusCode } catch { 'unreachable' }
  Write-Host "  nginx   https://localhost:$HttpsPort/: $web"
}

if ($Action -eq 'stop') { Stop-Local; return }
if ($Action -eq 'status') {
  # PowerShell 5.1 rejects the self-signed certificate; status only needs an answer.
  [Net.ServicePointManager]::ServerCertificateValidationCallback = { $true }
  Show-Status; return
}

# ------------------------------------------------------------------ prerequisites
# The JDK is chosen by its specification version, not by its folder name.
function Get-JavaFeature([string]$JdkHome) {
  # The JDK's own release file (JAVA_VERSION="25.0.4.1"): no process is started, nothing on stderr.
  $release = Join-Path $JdkHome 'release'
  if (-not (Test-Path (Join-Path $JdkHome 'bin\java.exe')) -or -not (Test-Path $release)) { return 0 }
  $m = Select-String -Path $release -Pattern '^JAVA_VERSION="(\d+)' | Select-Object -First 1
  if (-not $m) { return 0 }
  return [int]$m.Matches[0].Groups[1].Value
}
if (-not $env:JAVA_HOME -or (Get-JavaFeature $env:JAVA_HOME) -lt 25) {
  if ((Get-JavaFeature $Jdk25) -lt 25) { throw "JDK 25 not found at $Jdk25 (pass -Jdk25 or set JAVA_HOME to a JDK 25)" }
  $env:JAVA_HOME = $Jdk25
}
$java = Join-Path $env:JAVA_HOME 'bin\java.exe'
$node = (Get-Command node -ErrorAction SilentlyContinue).Source
if (-not $node) { throw 'Node.js (22.12 or later) is required for the Next.js server' }
if (-not (Test-Path (Join-Path $TomcatHome 'bin\catalina.bat'))) { throw "Tomcat not found at $TomcatHome (pass -TomcatHome)" }
if (-not (Test-Path $nginxExe)) { throw "nginx.exe not found in $NginxHome (pass -NginxHome)" }
$bash = @("$env:ProgramFiles\Git\bin\bash.exe", "${env:ProgramFiles(x86)}\Git\bin\bash.exe") | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $bash) { throw 'Git for Windows (bash, openssl, curl) is required for the TLS certificate and the smoke test' }
$gitUsr = Join-Path (Split-Path (Split-Path $bash)) 'usr\bin'
$openssl = @((Join-Path $gitUsr 'openssl.exe'), (Join-Path (Split-Path (Split-Path $bash)) 'mingw64\bin\openssl.exe')) | Where-Object { Test-Path $_ } | Select-Object -First 1

# ------------------------------------------------------------------ build (no tests)
$War = Join-Path $Repo 'backend\build\libs\fci-payroll.war'
$Triggers = Join-Path $Repo 'backend\build\libs\fci-h2-triggers.jar'
$Standalone = Join-Path $Repo 'frontend\.next\standalone'
if (-not $SkipBuild -and $doBackend) {
  Step 'Building the backend WAR and the H2 trigger jar (JDK 25, Spring Boot 4)'
  # An H2 stack needs H2 in the WAR (-Pfci.h2=true, a local build); an Oracle stack runs the release WAR (no H2).
  $h2Flag = if ($oracle) { '-Pfci.h2=false' } else { '-Pfci.h2=true' }
  Invoke-Native { & (Join-Path $Repo 'gradlew.bat') -p $Repo :backend:bootWar :backend:h2Triggers $h2Flag --quiet }
  if ($LASTEXITCODE -ne 0) { throw 'Gradle build failed' }
}
if (-not $SkipBuild -and $doWeb) {
  Step 'Building the frontend (Next.js standalone server, SSR)'
  Push-Location (Join-Path $Repo 'frontend')
  try {
    if (-not (Test-Path 'node_modules')) {
      Invoke-Native { npm ci --no-audit --no-fund }
      if ($LASTEXITCODE -ne 0) { throw 'npm ci failed' }
    }
    # The pages under -BasePath (/nginx, as next.config.ts defaults to).
    # '/' means the root: PowerShell 5.1 removes a variable set to ''.
    $env:FCI_BASE_PATH = $BasePath
    try {
      Invoke-Native { npm run build --silent }
    } finally { Remove-Item Env:FCI_BASE_PATH -ErrorAction SilentlyContinue }
    if ($LASTEXITCODE -ne 0) { throw 'frontend build failed' }
  } finally { Pop-Location }
}
foreach ($required in @($(if ($doBackend) { $War, $Triggers }) + $(if ($doWeb) { (Join-Path $Standalone 'server.js') }))) {
  if (-not (Test-Path $required)) { throw "missing ${required}: run without -SkipBuild" }
}

# Every port of this offset, checked before anything is stopped or started: a port held by a
# program outside this runtime (another checkout's stack, any other server) stops the deploy with
# its name and the next offset whose ports are all free, instead of failing halfway through startup.
function Get-StackPorts([int]$Offset) {
  @(8443, 8088, 9092, 3000, 8080, 8081, 8000, 8090, 8091, 8001) | ForEach-Object { $_ + $Offset }
}
function Get-ForeignHolder([int]$Port) {
  $listener = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
  if (-not $listener) { return $null }
  $process = Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwningProcess)"
  if ($process -and ($process.CommandLine -like "*$Runtime*" -or $process.CommandLine -like "*$(Fwd $Runtime)*" -or
      $process.ExecutablePath -like "$Runtime*")) { return $null }
  return "port $Port held by PID $($listener.OwningProcess) ($(if ($process) { $process.Name } else { 'unknown' }))"
}
$foreign = @(Get-StackPorts $PortOffset | ForEach-Object { Get-ForeignHolder $_ } | Where-Object { $_ })
if ($foreign.Count -gt 0) {
  $free = (100..1000 | Where-Object { $_ % 100 -eq 0 -and $_ -ne $PortOffset } | Where-Object {
      $candidate = $_
      -not (Get-StackPorts $candidate | Where-Object { Get-NetTCPConnection -LocalPort $_ -State Listen -ErrorAction SilentlyContinue })
    } | Select-Object -First 1)
  throw ("ports of offset $PortOffset are in use by another program: " + ($foreign -join '; ') +
    $(if ($free) { ". Use -PortOffset $free (all its ports are free) with its own -Runtime." } else { '.' }))
}

if ($doDb) {
  Stop-Local
} else {
  if ($doBackend -and -not $oracle -and -not (Get-NetTCPConnection -LocalPort $H2Port -State Listen -ErrorAction SilentlyContinue)) {
    throw "-Action backend needs the running H2 server of this stack (tcp://127.0.0.1:$H2Port): run a full deploy first"
  }
  if ($doWeb) { Stop-Port $WebPort 'Next.js server' }
  if ($doBackend) {
    Stop-Port $Instances.batch.Http 'Tomcat (batch)'
    Stop-Port $Instances.online.Http 'Tomcat (online)'
  }
  Start-Sleep -Seconds 1
}

# ------------------------------------------------------------------ secrets (generated once)
if (-not (Test-Path (Join-Path $Config 'secrets\backend'))) {
  Step 'Generating secrets (scripts/GenerateSecrets.java)'
  $handover = Invoke-Native { & $java (Join-Path $Repo 'scripts\GenerateSecrets.java') --out $Config }
  if ($LASTEXITCODE -ne 0) { throw 'GenerateSecrets failed' }
  # Local machine only: keep the one-time values (metrics password) next to the secrets.
  Write-Text (Join-Path $Config 'HANDOVER.txt') (($handover | Out-String))
}
# NTFS equivalent of 0700/0400: only the current user. Grant on the directory (inherited by
# everything inside), then make every file inherit that ACL.
$secrets = Join-Path $Config 'secrets'
Invoke-Native {
  & icacls $secrets /inheritance:r /grant:r "$($env:USERNAME):(OI)(CI)F" /Q | Out-Null
  & icacls "$secrets\*" /reset /T /Q | Out-Null
}
$tls = Join-Path $Config 'tls'
if (-not (Test-Path (Join-Path $tls 'dev.crt'))) {
  if (-not $openssl) { throw 'openssl (Git for Windows) not found: cannot create the localhost certificate' }
  Step 'Creating a self-signed TLS certificate for localhost'
  New-Item -ItemType Directory -Force $tls | Out-Null
  $env:MSYS_NO_PATHCONV = '1'
  Invoke-Native {
    & $openssl req -x509 -newkey rsa:3072 -sha256 -days 397 -nodes -keyout (Join-Path $tls 'dev.key') -out (Join-Path $tls 'dev.crt') `
      -subj '/CN=localhost/O=FCI development' -addext 'subjectAltName=DNS:localhost,IP:127.0.0.1' 2>&1 | Out-Null
  }
  if ($LASTEXITCODE -ne 0) { throw 'openssl failed' }
}

# ------------------------------------------------------------------ backend configuration
Step 'Writing the backend configuration (config/app/backend + local overlay)'
New-Item -ItemType Directory -Force (Join-Path $Config 'backend') | Out-Null
# The server template configures the on-premises Oracle of an environment (profile oracle). A local stack
# overrides the database in its overlay: the stack's H2 server (tests), or the cloud ADB through its wallet
# (profile oracle-adb, local only).
$yaml = Read-Text (Join-Path $Repo 'config\app\backend\application.yaml')
if (-not $OtpRequired) { $OtpRequired = if ($NoDemo) { 'Y' } else { 'N' } }
$overlay = "fci.api.notification.mode: log                   # no SMTP server locally: e-mails are logged`n" +
           "fci.api.rate-limits.login-per-ip-per-minute: 600  # every local browser is 127.0.0.1`n" +
           "fci.api.otp.required: '$OtpRequired'                       # OTP of critical activities (Y in UAT / prod)`n" +
           $(if ($SecurityOffence) { "fci.security.offence.enabled: '$SecurityOffence'`n" } else { '' }) +
           "fci.local-stack: true                            # a local stack: H2 and the cloud wallet allowed (StartupGuards)`n"
if ($oracle) {
  # Profiles oracle,oracle-adb: the wallet, its alias and the schema owner. The password goes where a server
  # keeps it: the stack's secrets\backend\spring.datasource.password (in place of H2's).
  $overlay += "fci.oracle.wallet-dir: '$(Fwd (Resolve-Path $OracleWalletDir).Path)'`n" +
              "fci.oracle.tns-alias: '$OracleTnsAlias'`n" +
              "fci.oracle.username: $OracleUser`n"
  Copy-Item (Join-Path $OracleSecrets 'spring.datasource.password') (Join-Path $Config 'secrets\backend\spring.datasource.password') -Force
} else {
  # The stack's shared H2 TCP server (in memory; tests and Playwright).
  $overlay += "spring.datasource.url: 'jdbc:h2:tcp://127.0.0.1:$H2Port/mem:fcilekha;MODE=Oracle;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1'`n" +
              "spring.datasource.username: fci_payroll`n"
}
Write-Text (Join-Path $Config 'backend\application.yaml') ($yaml + "---`n# LOCAL OVERLAY (deploy-local.ps1), never on a server:`n" + $overlay)

# ------------------------------------------------------------------ release: WAR, H2 classpath, web
Step 'Deploying fci-payroll.war, the H2 server classpath and the Next.js server'
$current = Join-Path $Apps 'current'
New-Item -ItemType Directory -Force $current, $Logs | Out-Null
if ($doBackend) { Copy-Item $War $current -Force }
if ($doDb -and -not $oracle) {
# The H2 server must run the exact H2 version the WAR uses, and needs the trigger classes.
Remove-RuntimeDir $H2Dir
New-Item -ItemType Directory -Force $H2Dir | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::OpenRead($War)
try {
  $entry = $zip.Entries | Where-Object { $_.FullName -match '^WEB-INF/lib/h2-[0-9.]+\.jar$' } | Select-Object -First 1
  if (-not $entry) { throw 'no H2 jar in the WAR' }
  [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, (Join-Path $H2Dir $entry.Name), $true)
} finally { $zip.Dispose() }
Copy-Item $Triggers $H2Dir -Force
}
if ($doWeb) {
# Next.js standalone output: server.js plus the static assets it serves (.next/static).
Remove-RuntimeDir (Join-Path $Web 'current')
Copy-Item -Recurse $Standalone (Join-Path $Web 'current')
Copy-Item -Recurse (Join-Path $Repo 'frontend\.next\static') (Join-Path $Web 'current\.next\static')
$publicDir = Join-Path $Repo 'frontend\public'
if (Test-Path $publicDir) { Copy-Item -Recurse $publicDir (Join-Path $Web 'current\public') }
}

# ------------------------------------------------------------------ Tomcat instances (CATALINA_BASE)
foreach ($mode in $(if ($doBackend) { $Instances.Keys } else { @() })) {
  $base = Join-Path $Runtime "tomcat-$mode"
  Step "Preparing the Tomcat instance $base (CATALINA_HOME $TomcatHome)"
  foreach ($dir in 'conf', 'bin', 'logs', 'temp', 'work', 'webapps-fci') { New-Item -ItemType Directory -Force (Join-Path $base $dir) | Out-Null }
  # The stock files every Tomcat instance needs come from the installation; the rest from config/.
  # Tomcat 11 has no Security Manager and ships no catalina.policy (ADR-0025).
  foreach ($file in 'web.xml', 'catalina.properties', 'context.xml', 'logging.properties') {
    Copy-Item (Join-Path $TomcatHome "conf\$file") (Join-Path $base "conf\$file") -Force
  }
  $localhostConf = Join-Path $base 'conf\Catalina\localhost'
  New-Item -ItemType Directory -Force $localhostConf | Out-Null
  Copy-Item (Join-Path $Repo 'config\tomcat\conf\server.xml') (Join-Path $base 'conf\server.xml') -Force
  Copy-Item (Join-Path $Repo 'config\tomcat\conf\Catalina\localhost\api.xml') $localhostConf -Force
  Copy-Item (Join-Path $Repo 'config\tomcat\bin\setenv.bat') (Join-Path $base 'bin\setenv.bat') -Force
  # Expanded copy of the previous WAR: Tomcat must unpack the new one.
  Remove-RuntimeDir (Join-Path $base 'webapps-fci\api')
  Remove-RuntimeDir (Join-Path $base 'work\Catalina')
  New-Item -ItemType Directory -Force (Join-Path $Logs $mode) | Out-Null
}

# ------------------------------------------------------------------ NGINX configuration
if ($doDb) {
Step 'Writing the NGINX configuration from config/nginx'
$conf = Join-Path $Nginx 'conf'
$fci = Join-Path $conf 'fci'
foreach ($dir in 'conf.d', 'snippets') { New-Item -ItemType Directory -Force (Join-Path $fci $dir) | Out-Null }
New-Item -ItemType Directory -Force (Join-Path $Nginx 'logs'), (Join-Path $Nginx 'temp'), (Join-Path $Nginx 'temp\fci-cache') | Out-Null  # proxy_cache_path parent: nginx creates only the last level
Copy-Item (Join-Path $NginxHome 'conf\mime.types') (Join-Path $conf 'mime.types') -Force
# Windows NGINX: no "user" directive, local pid and log paths.
Write-Text (Join-Path $conf 'nginx.conf') ((Read-Text (Join-Path $Repo 'config\nginx\nginx.conf')) `
  -replace '(?m)^user nginx;\r?\n', '' `
  -replace 'pid /run/nginx.pid;', 'pid logs/nginx.pid;' `
  -replace '/var/log/nginx/', 'logs/' `
  -replace '/var/cache/fci-nginx/', 'temp/fci-cache/')
Remove-Item (Join-Path $fci 'snippets\*.conf'), (Join-Path $fci 'conf.d\*.conf') -ErrorAction SilentlyContinue
Copy-Item (Join-Path $Repo 'config\nginx\snippets\*.conf') (Join-Path $fci 'snippets') -Force
Write-Text (Join-Path $fci 'conf.d\upstream.conf') ((Read-Text (Join-Path $Repo 'config\nginx\conf.d\upstream.conf')) `
  -replace 'server 127\.0\.0\.1:8080;', "server 127.0.0.1:$($Instances.online.Http);" `
  -replace 'server 127\.0\.0\.1:3000;', "server 127.0.0.1:${WebPort};")
Write-Text (Join-Path $fci 'snippets\server-name.conf') "server_name localhost;`n"
# The site: local ports and certificate; no IPv6 listeners on Windows.
Write-Text (Join-Path $fci 'conf.d\fci-payroll.conf') ((Read-Text (Join-Path $Repo 'config\nginx\conf.d\fci-payroll.conf')) `
  -replace '(?m)^\s*listen \[::\].*\r?\n', '' `
  -replace 'listen 80( default_server)?;', "listen $PlainPort`$1;" `
  -replace 'listen 443 ssl', "listen $HttpsPort ssl" `
  -replace '/etc/nginx/tls/fci-payroll.crt', (Fwd (Join-Path $tls 'dev.crt')) `
  -replace '/etc/nginx/tls/fci-payroll.key', (Fwd (Join-Path $tls 'dev.key')) `
  -replace '/var/log/nginx/', 'logs/')
if ($PagePath -ne '/nginx') {
  # The template publishes the pages under /nginx; another -BasePath renames that prefix.
  $site = Join-Path $fci 'conf.d\fci-payroll.conf'
  Write-Text $site ((Read-Text $site) -replace '/nginx(?=[/ $;])', $PagePath)
}
}

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'

# ------------------------------------------------------------------ 1. H2 database server
if ($doDb -and -not $oracle) {
Step "Starting the H2 database server (tcp://127.0.0.1:$H2Port, in-memory, loopback only)"
New-Item -ItemType Directory -Force (Join-Path $Logs 'h2') | Out-Null
$h2Classpath = "$H2Dir\*"
Start-Process -FilePath $java -ArgumentList "-Xmx$H2Heap", '-cp', "`"$h2Classpath`"", 'org.h2.tools.Server', '-tcp', '-tcpPort', $H2Port, '-ifNotExists' `
  -WindowStyle Hidden -RedirectStandardOutput (Join-Path $Logs "h2\h2-$stamp.out.log") -RedirectStandardError (Join-Path $Logs "h2\h2-$stamp.err.log")
$deadline = (Get-Date).AddSeconds(30)
while (-not (Get-NetTCPConnection -LocalPort $H2Port -State Listen -ErrorAction SilentlyContinue)) {
  if ((Get-Date) -gt $deadline) { Get-Content (Join-Path $Logs "h2\h2-$stamp.err.log"); throw 'H2 server did not start' }
  Start-Sleep -Seconds 1
}
Write-Host '    h2 ready'
if ($SchemaFromKit) {
  if ($NoDemo -or $Volume) { throw '-SchemaFromKit loads the demo, edge or screens data set: drop -NoDemo / -Volume' }
  if ($EdgeData -and $ScreensData) { throw '-EdgeData and -ScreensData are separate data sets: choose one' }
  $kitSet = if ($EdgeData) { 'edge' } elseif ($ScreensData) { 'screens' } else { 'demo' }
  $setup = Join-Path $Repo "db\h2-setup\fcipay_h2_setup_$kitSet.sql"
  if (-not (Test-Path $setup)) { throw "no ${setup}: python db/tools/oracle/build_setup_script.py" }
  Step "Loading the install kit ($kitSet) in its order: drop, DDL, DML, compile invalid"
  # The steps are taken from the setup script (the one order) and run client-side, so the paths resolve here.
  $kitSteps = Select-String -Path $setup -Pattern "^RUNSCRIPT FROM '([^']+)'" | ForEach-Object { $_.Matches[0].Groups[1].Value }
  if (-not $kitSteps) { throw "no RUNSCRIPT step in $setup" }
  $kitPw = (Get-Content -Raw (Join-Path $Config 'secrets\backend\spring.datasource.password')).Trim()
  $kitUrl = "jdbc:h2:tcp://127.0.0.1:$H2Port/mem:fcilekha;MODE=Oracle;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
  foreach ($kitStep in $kitSteps) {
    Write-Host "    $kitStep"
    $kitFile = Join-Path $Repo ($kitStep -replace '/', '\')
    # The password goes on command line of a local child process only (loopback H2), never to a log.
    & $java -cp "$H2Dir\*" org.h2.tools.RunScript -url $kitUrl -user fci_payroll -password $kitPw -script $kitFile
    if ($LASTEXITCODE -ne 0) { throw "install kit step $kitStep failed (exit $LASTEXITCODE)" }
  }
  Remove-Variable kitPw
  Write-Host '    kit loaded'
}
}

# ------------------------------------------------------------------ 2./3. Tomcat online, then batch
$profiles = if ($NoDemo) { 'uat' } else { 'uat,demo' }
if ($Volume) {
  if ($NoDemo) { throw '-Volume needs the demo data set: drop -NoDemo' }
  $profiles = "$profiles,volume"
}
if ($EdgeData) {
  if ($NoDemo -or $Volume) { throw '-EdgeData needs the demo data set and is never combined with -Volume' }
  $profiles = "$profiles,edge"
}
if ($ScreensData) {
  if ($NoDemo -or $Volume -or $EdgeData) { throw '-ScreensData needs the demo data set and is never combined with -Volume or -EdgeData' }
  $profiles = "$profiles,screens"
}
if ($ExtraProfiles) { $profiles = "$profiles,$ExtraProfiles" }
if ($E2EParallelUsers) { $profiles = "$profiles,e2e-parallel" }
if ($oracle) { $profiles = "$profiles,oracle,oracle-adb" }  # oracle-adb: the cloud wallet, local only
if ($Chaos) {
  if ($NoDemo) { throw '-Chaos runs on a local demo stack only: drop -NoDemo' }
  $profiles = "$profiles,chaos-monkey"
}
$onlineApiLog = Join-Path $Logs 'online\api.json'
$onlineLogStart = if (Test-Path $onlineApiLog) { (Get-Item $onlineApiLog).Length } else { 0 }
$starting = @{}
foreach ($mode in $(if ($doBackend) { $Instances.Keys } else { @() })) {
  $i = $Instances[$mode]
  Step "Starting Tomcat 11 ($mode) on JDK 25: profiles $profiles,$mode, ports $($i.Http)/$($i.Mgmt)"
  $env:CATALINA_HOME = $TomcatHome
  $env:CATALINA_BASE = Join-Path $Runtime "tomcat-$mode"
  $env:FCI_MODE = $mode
  $env:FCI_PROFILE = $profiles
  $env:FCI_CONFIG_DIR = Fwd $Config
  $env:FCI_APPS_DIR = Fwd $Apps
  $env:FCI_LOG_DIR = Fwd (Join-Path $Logs $mode)
  $env:FCI_HTTP_PORT = "$($i.Http)"
  $env:FCI_MGMT_PORT = "$($i.Mgmt)"
  $env:FCI_HEAP = $i.Heap
  # setenv.bat appends its own options to CATALINA_OPTS: these only add start-up settings.
  $opts = @()
  if (-not $FullJit) { $opts += '-XX:TieredStopAtLevel=1' }
  # A local Oracle stack has no Flyway (owner 2026-11-09, OD-151: its schema comes from the install kit), so
  # Hibernate's schema check stays on there: it is the only drift check left on that stack.
  if (-not $ValidateSchema -and -not $oracle) { $opts += '-Dspring.jpa.hibernate.ddl-auto=none' }
  if ($ExtraJavaOpts) { $opts += $ExtraJavaOpts }
  if ($ProfileStartup) {
    $opts += "-XX:StartFlightRecording=duration=300s,settings=profile,filename=$(Fwd (Join-Path $Logs "$mode\startup-%p.jfr"))"
  }
  $env:CATALINA_OPTS = $opts -join ' '
  $catalinaArgs = @('run')
  if ($Jpda) {
    # catalina.bat jpda run: JDWP on loopback only, the instance does not wait for a debugger.
    $env:JPDA_TRANSPORT = 'dt_socket'
    $env:JPDA_ADDRESS = "127.0.0.1:$($i.Jpda)"
    $env:JPDA_SUSPEND = 'n'
    $catalinaArgs = @('jpda', 'run')
    Write-Host "    JPDA debugger port 127.0.0.1:$($i.Jpda)"
  }
  $out = Join-Path $Logs "$mode\catalina-$stamp.out.log"
  Start-Process -FilePath (Join-Path $TomcatHome 'bin\catalina.bat') -ArgumentList $catalinaArgs -WindowStyle Hidden `
    -RedirectStandardOutput $out -RedirectStandardError (Join-Path $Logs "$mode\catalina-$stamp.err.log")
  $starting[$mode] = $out
  # Online migrates the schema; batch (Flyway disabled) validates it, so batch starts once the
  # migration is done and the rest of the two start-ups overlap (about half a minute saved).
  # A backend-only restart runs on the existing schema: batch need not wait (about half a minute more saved).
  # Oracle: the first deploy creates the schema and loads the seeds over the WAN (about ten minutes).
  # A local Oracle stack runs no Flyway (OD-151): nothing to wait for, the kit built the schema beforehand.
  if ($mode -eq 'online' -and $doDb -and -not $oracle) { Wait-Migrated $onlineApiLog $onlineLogStart $out 4 }
}
foreach ($name in 'FCI_MODE', 'FCI_HTTP_PORT', 'FCI_MGMT_PORT', 'FCI_HEAP', 'CATALINA_BASE', 'CATALINA_OPTS', 'JPDA_TRANSPORT', 'JPDA_ADDRESS', 'JPDA_SUSPEND') { Remove-Item "env:$name" -ErrorAction SilentlyContinue }

# ------------------------------------------------------------------ 4. Next.js server (SSR)
# Started while the two backends start (it needs them only per request), not after: about half a minute saved.
if ($doWeb) {
Step "Starting the Next.js server (http://127.0.0.1:$WebPort, SSR, BACKEND_URL -> online)"
New-Item -ItemType Directory -Force (Join-Path $Logs 'web') | Out-Null
$env:NODE_ENV = 'production'
$env:HOSTNAME = '127.0.0.1'
$env:PORT = "$WebPort"
# Idle keep-alive longer than NGINX's upstream keepalive_timeout (15 s, config/nginx/conf.d/upstream.conf): with Node's
# default 5 s the server closes a connection NGINX still reuses and a page request ends in 502 (run 2026-11-09).
$env:KEEP_ALIVE_TIMEOUT = '20000'
$env:BACKEND_URL = "http://127.0.0.1:$($Instances.online.Http)/api"
$env:FCI_PWA_ENABLED = if ($Pwa) { 'true' } else { 'false' }
$webOut = Join-Path $Logs "web\next-$stamp.out.log"
Start-Process -FilePath $node -ArgumentList "`"$(Join-Path $Web 'current\server.js')`"" -WorkingDirectory (Join-Path $Web 'current') -WindowStyle Hidden `
  -RedirectStandardOutput $webOut -RedirectStandardError (Join-Path $Logs "web\next-$stamp.err.log")
foreach ($name in 'NODE_ENV', 'HOSTNAME', 'PORT', 'KEEP_ALIVE_TIMEOUT', 'BACKEND_URL', 'FCI_PWA_ENABLED') { Remove-Item "env:$name" -ErrorAction SilentlyContinue }
}
foreach ($mode in $(if ($doBackend) { $Instances.Keys } else { @() })) {
  Wait-Ready "backend ($mode)" "http://127.0.0.1:$($Instances[$mode].Mgmt)/api/actuator/health/readiness" $starting[$mode]
}
if ($doWeb) { Wait-Ready 'Next.js server' "http://127.0.0.1:$WebPort$PageRoot" $webOut 1 }
if (-not $doDb) {
  # NGINX and H2 kept running: a partial restart needs no smoke test of the whole stack.
  Write-Host "FCILekha Payroll ($Action restarted) is running: https://localhost:$HttpsPort$PageRoot" -ForegroundColor Green
  return
}

# ------------------------------------------------------------------ 5. NGINX
Step "Starting NGINX ($nginxExe, prefix $Nginx) on https://localhost:$HttpsPort/"
Push-Location $Nginx
try {
  Invoke-Native { & $nginxExe -t -q -p "$(Fwd $Nginx)/" 2>&1 | ForEach-Object { "$_" } | Where-Object { $_ -notmatch 'ssl_stapling' } | ForEach-Object { Write-Host "    $_" } }
  if ($LASTEXITCODE -ne 0) { throw 'nginx configuration test failed' }
  Start-Process -FilePath $nginxExe -ArgumentList '-p', "$(Fwd $Nginx)/" -WorkingDirectory $Nginx -WindowStyle Hidden
} finally { Pop-Location }
# Wait until NGINX answers through TLS (curl -k: the certificate is self-signed) rather than a fixed
# pause: a smoke test started too early fails every check with "000".
$curl = Join-Path (Split-Path (Split-Path $bash)) 'mingw64\bin\curl.exe'
if (-not (Test-Path $curl)) { $curl = 'curl.exe' }
$deadline = (Get-Date).AddSeconds(60)
do {
  Start-Sleep -Seconds 1
  $code = Invoke-Native { & $curl -sk -o NUL -w '%{http_code}' "https://localhost:$HttpsPort$PageRoot" }
} while ($code -ne '200' -and (Get-Date) -lt $deadline)
Write-Host "    nginx ready ($code)"

# ------------------------------------------------------------------ verify
Step 'Smoke test (scripts/smoke-test.sh)'
$env:SMOKE_CURL_OPTS = '-k'
$env:SMOKE_PAGE_PATH = $PagePath
$handoverFile = Join-Path $Config 'HANDOVER.txt'
if (Test-Path $handoverFile) {
  $metrics = Select-String -Path $handoverFile -Pattern 'metrics \(Prometheus\) password : (\S+)' | Select-Object -First 1
  if ($metrics) { $env:SMOKE_METRICS_PASSWORD = $metrics.Matches[0].Groups[1].Value }
}
Invoke-Native { & $bash (Join-Path $Repo 'scripts\smoke-test.sh') "https://localhost:$HttpsPort" `
  "http://127.0.0.1:$($Instances.online.Mgmt)" "http://127.0.0.1:$($Instances.batch.Mgmt)" "http://127.0.0.1:$($Instances.batch.Http)" }
$smoke = $LASTEXITCODE
Remove-Item env:SMOKE_METRICS_PASSWORD -ErrorAction SilentlyContinue
if ($smoke -ne 0) { throw 'smoke test failed' }

Write-Host ''
Write-Host "FCILekha Payroll is running: https://localhost:$HttpsPort$PageRoot (self-signed certificate)" -ForegroundColor Green
if ($NoDemo) {
  Write-Host "  Sign in as sysadmin@fci.gov.in (System Admin, HQ). Initial password: $Config\secrets\backend\fci.api.bootstrap.admin-initial-password"
} else {
  Write-Host "  Demo users: docs\rbac\SEED_USERS.md, password Fci@Demo2026 (e.g. maker.delhi@fci-demo.test as Payroll at DH001)."
  Write-Host "  OTP codes (sensitive actions, password reset) are written to $Logs\online\api.json (batch: $Logs\batch\batch.json)"
}
Write-Host "  Online: http://127.0.0.1:$($Instances.online.Mgmt)/api/actuator/health   Batch: http://127.0.0.1:$($Instances.batch.Mgmt)/api/actuator/health"
Write-Host "  Logs:   $Logs"
Write-Host "  Stop:   powershell -File scripts\local\deploy-local.ps1 -Action stop"
