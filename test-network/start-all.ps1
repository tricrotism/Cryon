# Opens Redis, both Paper backends, and Velocity each in its own PowerShell window, in order.
# Close a window (or Ctrl+C in it) to stop that process. Use stop-all.ps1 to kill everything.
$root = $PSScriptRoot

function Launch($title, $script)
{
    Start-Process pwsh -ArgumentList @(
        "-NoExit", "-Command",
        "`$host.UI.RawUI.WindowTitle = '$title'; & '$root\$script'"
    )
}

Write-Host "Starting Postgres (port 5433) ..." -ForegroundColor Cyan
Launch "cryon-postgres" "start-postgres.ps1"
Start-Sleep -Seconds 2

Write-Host "Starting Redis ..." -ForegroundColor Cyan
Launch "cryon-redis" "start-redis.ps1"
Start-Sleep -Seconds 2

Write-Host "Starting paper1 ..." -ForegroundColor Cyan
Launch "cryon-paper1" "start-paper1.ps1"
Write-Host "Starting paper2 ..." -ForegroundColor Cyan
Launch "cryon-paper2" "start-paper2.ps1"

# Give the backends a head start so they register before the proxy seeds its list.
Start-Sleep -Seconds 8
Write-Host "Starting Velocity ..." -ForegroundColor Cyan
Launch "cryon-velocity" "start-velocity.ps1"

Write-Host ""
Write-Host "All four windows launched. Connect a Minecraft client to localhost:25565." -ForegroundColor Green
Write-Host "Stop everything with: .\stop-all.ps1" -ForegroundColor Yellow
