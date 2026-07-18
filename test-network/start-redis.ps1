# Redis — the shared transport. Everything cross-server (registry, routing, handoff, maintenance,
# flag sync) rides on this. Start it FIRST. Runs in the foreground; Ctrl+C to stop.
$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
Set-Location "$root\redis"
Write-Host "Starting Redis on localhost:6379 ..." -ForegroundColor Cyan
& "$root\redis\redis-server.exe" --port 6379 --save "" --appendonly no
