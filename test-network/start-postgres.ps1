# Test Postgres cluster on port 5433 (5432 is left for any pre-existing local Postgres).
# Superuser 'cryon', database 'cryon', trust auth on loopback (no password) — dev only.
# Enables cross-restart persistence for feature flags / player language and clears the
# "instanced but database.enabled is false" boot banner. Runs in the foreground; Ctrl+C to stop.
$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
& "$root\postgres\bin\postgres.exe" -D "$root\postgres\data" -p 5433
