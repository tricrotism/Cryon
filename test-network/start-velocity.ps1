# Velocity proxy — players connect here on localhost:25565. Start it AFTER Redis (it reads the
# shared registry to discover backends and perform routing/handoff).
$ErrorActionPreference = "Stop"
$java = "C:\Program Files\Amazon Corretto\jdk25.0.3_9\bin\java.exe"
Set-Location "$PSScriptRoot\velocity"
& $java "-Xms512M" "-Xmx512M" "-jar" "velocity.jar"
