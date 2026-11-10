# Samples CPU % (of the whole machine) and working-set memory of the stack's processes every few seconds into a CSV,
# for reading a performance-test run against host resources (owner 2026-10-27: evaluate memory and CPU).
# Processes: this checkout's online and batch Tomcats (by their catalina.base), the H2 server (by its TCP port),
# the node load generator. Stops after -Seconds.
# Usage: powershell -File scripts/perf/sample-processes.ps1 -Out docs/perf/s7-resources.csv -Seconds 600 [-Every 5]
param(
  [Parameter(Mandatory = $true)][string]$Out,
  [int]$Seconds = 600,
  [int]$Every = 5,
  [int]$DbPort = 9392
)
$ErrorActionPreference = 'Stop'
$runtime = (Resolve-Path (Join-Path $PSScriptRoot '..\local\runtime-b')).Path
$cores = (Get-CimInstance Win32_ComputerSystem).NumberOfLogicalProcessors
function Find-Roles {
  $roles = @{}
  foreach ($p in Get-CimInstance Win32_Process -Filter "Name='java.exe' or Name='node.exe'") {
    $cmd = [string]$p.CommandLine
    if ($cmd -like "*$runtime\tomcat-online*") { $roles['online'] = $p.ProcessId }
    elseif ($cmd -like "*$runtime\tomcat-batch*") { $roles['batch'] = $p.ProcessId }
    elseif ($cmd -like '*org.h2.tools.Server*' -and $cmd -like "*-tcpPort $DbPort*") { $roles['h2'] = $p.ProcessId }
    elseif ($cmd -like '*perf-test.mjs*') { $roles['loadgen'] = $p.ProcessId }
  }
  return $roles
}
"time,role,pid,cpuPercentOfMachine,workingSetMB,threads" | Out-File -Encoding utf8 $Out
$last = @{}
$end = (Get-Date).AddSeconds($Seconds)
while ((Get-Date) -lt $end) {
  $roles = Find-Roles
  $now = Get-Date
  foreach ($role in $roles.Keys) {
    $id = $roles[$role]
    $proc = Get-Process -Id $id -ErrorAction SilentlyContinue
    if (-not $proc) { continue }
    $cpu = $proc.TotalProcessorTime.TotalSeconds
    if ($last.ContainsKey($id)) {
      $dt = ($now - $last[$id].at).TotalSeconds
      $pct = [math]::Round(100 * ($cpu - $last[$id].cpu) / ($dt * $cores), 1)
      "{0},{1},{2},{3},{4},{5}" -f $now.ToString('HH:mm:ss'), $role, $id, $pct, [math]::Round($proc.WorkingSet64 / 1MB), $proc.Threads.Count |
        Out-File -Encoding utf8 -Append $Out
    }
    $last[$id] = @{ cpu = $cpu; at = $now }
  }
  Start-Sleep -Seconds $Every
}
