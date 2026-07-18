# Kills the test network: both Paper backends, the Velocity proxy, and Redis.
# Matches java processes launched from this test-network folder plus redis-server.exe.
$root = $PSScriptRoot

Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" |
        Where-Object { $_.CommandLine -and $_.CommandLine -match [regex]::Escape($root) } |
        ForEach-Object {
            Write-Host "Stopping java PID $( $_.ProcessId )" -ForegroundColor Yellow
            Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
        }

Get-Process redis-server -ErrorAction SilentlyContinue | ForEach-Object {
    Write-Host "Stopping redis-server PID $( $_.Id )" -ForegroundColor Yellow
    Stop-Process -Id $_.Id -Force -ErrorAction SilentlyContinue
}

# Only our test cluster on 5433 — never touches a Postgres on the default 5432.
& "$root\postgres\bin\pg_ctl.exe" -D "$root\postgres\data" -m fast stop 2> $null
if ($LASTEXITCODE -ne 0)
{
    Get-CimInstance Win32_Process -Filter "Name = 'postgres.exe'" |
            Where-Object { $_.CommandLine -and $_.CommandLine -match [regex]::Escape("$root\postgres\data") } |
            ForEach-Object {
                Write-Host "Stopping postgres PID $( $_.ProcessId )" -ForegroundColor Yellow
                Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
            }
}

Write-Host "Done." -ForegroundColor Green
