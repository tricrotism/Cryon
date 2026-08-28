# Paper node "paper2" (server prison), backend port 25567. many-nodes + Redis.
$ErrorActionPreference = "Stop"
$java = "C:\Program Files\Amazon Corretto\jdk25.0.3_9\bin\java.exe"
$env:CRYON_NODE = "paper2"
$env:CRYON_SERVER = "prison"
$env:CRYON_EXPECT = "many-nodes"
Set-Location "$PSScriptRoot\paper2"
& $java "-Xms2G" "-Xmx2G" "-Dcom.mojang.eula.agree=true" "-jar" "paper.jar" "--nogui"
