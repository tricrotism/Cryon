# Geyser Standalone. Bedrock clients connect here on UDP localhost:19132 and are translated into
# Java connections to Velocity on 25565. Start it AFTER Velocity: it pings the proxy for the MOTD
# and player count, and the login itself goes through the proxy.
# The jar is passed by absolute path so stop-all.ps1 can match this process by folder.
$ErrorActionPreference = "Stop"
$java = "C:\Program Files\Amazon Corretto\jdk25.0.3_9\bin\java.exe"
Set-Location "$PSScriptRoot\geyser"
& $java "-Xms512M" "-Xmx512M" "-jar" "$PSScriptRoot\geyser\geyser.jar"
