# Paper node "paper1" (server prison), backend port 25566. many-nodes + Redis.
$ErrorActionPreference = "Stop"
$java = "C:\Program Files\Amazon Corretto\jdk25.0.3_9\bin\java.exe"
$env:CRYON_NODE = "paper1"
$env:CRYON_SERVER = "prison"
$env:CRYON_EXPECT = "many-nodes"
Set-Location "$PSScriptRoot\paper1"
& $java "-Xms2G" "-Xmx2G" "-Dcom.mojang.eula.agree=true" "-jar" "paper.jar" "--nogui"
