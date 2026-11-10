<#
.SYNOPSIS
  Generates development and test secrets for running both WARs in a local Tomcat (ADR-0013).
.DESCRIPTION
  Same layout as production (scripts/GenerateSecrets.java) plus, when openssl is on PATH
  (Git for Windows ships one), a self-signed TLS certificate for NGINX on localhost.
  Nothing generated is ever committed (scripts/local/generated/ is ignored).
.EXAMPLE
  powershell -File scripts/local/generate-dev-keys.ps1
#>
param(
  # Default: scripts\local\generated (set below: Windows PowerShell 5.1 has no $PSScriptRoot here).
  [string]$Out = ''
)

$ErrorActionPreference = 'Stop'
if (-not $Out) { $Out = Join-Path $PSScriptRoot 'generated' }
if (-not (Get-Command java -ErrorAction SilentlyContinue)) { throw 'java (JDK 25) is required' }

& java (Join-Path $PSScriptRoot '..\scripts\GenerateSecrets.java') --out $Out --force
if ($LASTEXITCODE -ne 0) { throw "GenerateSecrets failed ($LASTEXITCODE)" }

# NTFS: only the current user may read the secrets and keys. Grant on the directories, then let
# the files inherit (an (OI)(CI) grant applied to files with /T leaves them unreadable).
foreach ($dir in 'secrets', 'keys') {
  $path = Join-Path $Out $dir
  & icacls $path /inheritance:r /grant:r "$($env:USERNAME):(OI)(CI)F" /Q | Out-Null
  & icacls "$path\*" /reset /T /Q | Out-Null
}

$openssl = Get-Command openssl -ErrorAction SilentlyContinue
if ($openssl) {
  $tls = Join-Path $Out 'tls'
  New-Item -ItemType Directory -Force $tls | Out-Null
  & $openssl.Source req -x509 -newkey rsa:3072 -sha256 -days 397 -nodes `
    -keyout (Join-Path $tls 'dev.key') -out (Join-Path $tls 'dev.crt') `
    -subj '/CN=localhost/O=FCI development' -addext 'subjectAltName=DNS:localhost,IP:127.0.0.1' 2>$null
  Write-Output "TLS certificate for NGINX: $tls\dev.crt (self-signed, localhost only)"
} else {
  Write-Warning 'openssl not found: no NGINX TLS certificate generated'
}
