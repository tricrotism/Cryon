#!/usr/bin/env bash
# Places the mounted Geyser config, the Cryon extension config and the shared Floodgate key, then
# starts Geyser Standalone. The extension jar is baked into extensions/ by the image.
set -euo pipefail
cd /geyser
mkdir -p config extensions/cryon

if [ -f /geyser-config/config.yml ]; then
  cp /geyser-config/config.yml config/config.yml
fi

# Cryon extension config (redis/db/network) from a ConfigMap; inject the DB password Secret. The
# path is Geyser's per-extension data folder, the counterpart of plugins/cryon/ on the proxy.
if [ -f /cryon-config/config.yml ]; then
  cp /cryon-config/config.yml extensions/cryon/config.yml
  if [ -n "${CRYON_DB_PASSWORD:-}" ]; then
    sed -i "s|__DB_PASSWORD__|${CRYON_DB_PASSWORD}|g" extensions/cryon/config.yml
  fi
fi

# The Floodgate key must be identical across Geyser, the proxies, and every backend.
if [ -f /floodgate/key.pem ]; then
  cp /floodgate/key.pem config/key.pem
fi

exec java ${JAVA_OPTS:-} -jar geyser.jar
