# Serves the client resource pack over HTTP on port 8085.
#
# PackModule hands the client a URL, and a client will only take one over http(s). A file:// path or
# a local directory is not something it can be pointed at. So even an entirely local test network
# needs something listening, and this is it.
#
# The zip is built by:
#   ./gradlew :cryon-pixelmon-pack-tools:clientPack -Ppack=<converted dir> -Pcobblemon=<clone> -Pzip=<zip>
# which prints the sha1 that has to match `resource-pack.sha1` in each instance's plugins/Cryon/pack.yml.
$ErrorActionPreference = "Stop"
$root = "$PSScriptRoot\pack-host"
$zip = "$root\cryon-pixelmon-pack.zip"

if (-not (Test-Path $zip))
{
    Write-Host "No pack zip at $zip. Run the clientPack task first." -ForegroundColor Red
    exit 1
}

$sha1 = (Get-FileHash -Algorithm SHA1 $zip).Hash.ToLower()
$size = [math]::Round((Get-Item $zip).Length / 1MB, 1)
Write-Host "Serving $size MB on port 8085" -ForegroundColor Cyan
Write-Host "  sha1 $sha1" -ForegroundColor DarkGray
Write-Host "  a LAN client needs this box's LAN address, not 127.0.0.1" -ForegroundColor DarkGray

# Bound to 0.0.0.0 so a client on the LAN can reach it; single-threaded, which is fine for a test
# network and would not be for a real one.
python -m http.server 8085 --bind 0.0.0.0 --directory $root
