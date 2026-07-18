# Paper instance "paper2" (family prison), backend port 25567. Instanced mode + Redis.
$ErrorActionPreference = "Stop"
$java = "C:\Program Files\Amazon Corretto\jdk25.0.3_9\bin\java.exe"
$env:CRYON_INSTANCE_ID = "paper2"
$env:CRYON_SERVER_FAMILY = "prison"
$env:CRYON_NETWORK_MODE = "instanced"
Set-Location "$PSScriptRoot\paper2"
& $java "-Xms2G" "-Xmx2G" "-Dcom.mojang.eula.agree=true" "-jar" "paper.jar" "--nogui"
