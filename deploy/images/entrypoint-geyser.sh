#!/usr/bin/env bash
# Places the mounted Geyser config and the shared Floodgate key, then starts Geyser Standalone.
set -euo pipefail
cd /geyser
mkdir -p config

if [ -f /geyser-config/config.yml ]; then
  cp /geyser-config/config.yml config/config.yml
fi

# The Floodgate key must be identical across Geyser, the proxies, and every backend.
if [ -f /floodgate/key.pem ]; then
  cp /floodgate/key.pem config/key.pem
fi

exec java ${JAVA_OPTS:-} -jar geyser.jar
