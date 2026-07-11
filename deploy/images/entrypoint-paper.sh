#!/usr/bin/env bash
# Renders the mounted core config, injects secrets, accepts the EULA, and starts Paper. Per-pod
# network identity (family/instance/address/port) is read straight from CRYON_* env by the plugin.
set -euo pipefail
cd /server

echo "eula=${EULA:-false}" > eula.txt
mkdir -p plugins/Cryon config

# Core config (production, redis, db) is mounted read-only from a ConfigMap; copy it in and inject
# the DB password from its Secret env var (never store it in the ConfigMap).
if [ -f /cryon-config/config.yml ]; then
  cp /cryon-config/config.yml plugins/Cryon/config.yml
  if [ -n "${CRYON_DB_PASSWORD:-}" ]; then
    sed -i "s|__DB_PASSWORD__|${CRYON_DB_PASSWORD}|g" plugins/Cryon/config.yml
  fi
fi

# Paper global config carries the Velocity modern-forwarding secret; inject it from its Secret.
if [ -f /cryon-config/paper-global.yml ]; then
  cp /cryon-config/paper-global.yml config/paper-global.yml
  if [ -n "${CRYON_FORWARDING_SECRET:-}" ]; then
    sed -i "s|__FORWARDING_SECRET__|${CRYON_FORWARDING_SECRET}|g" config/paper-global.yml
  fi
fi

exec java ${JAVA_OPTS:-} -jar paper.jar --nogui
