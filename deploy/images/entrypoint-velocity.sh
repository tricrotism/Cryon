#!/usr/bin/env bash
# Renders the mounted proxy config, injects the DB password and the modern-forwarding secret, then
# starts Velocity. Backends are registered dynamically by the Cryon loader from the server registry,
# so velocity.toml ships with an empty [servers] table.
set -euo pipefail
cd /proxy
mkdir -p plugins/cryon

# Cryon proxy config (redis/db/network/maintenance) from a ConfigMap; inject the DB password Secret.
if [ -f /cryon-config/config.yml ]; then
  cp /cryon-config/config.yml plugins/cryon/config.yml
  if [ -n "${CRYON_DB_PASSWORD:-}" ]; then
    sed -i "s|__DB_PASSWORD__|${CRYON_DB_PASSWORD}|g" plugins/cryon/config.yml
  fi
fi

# velocity.toml (modern forwarding). It points at forwarding-secret-file, so write the shared secret
# to that file from its Secret env var (backends must carry the same secret).
if [ -f /cryon-config/velocity.toml ]; then
  cp /cryon-config/velocity.toml velocity.toml
fi
if [ -n "${CRYON_FORWARDING_SECRET:-}" ]; then
  printf '%s' "${CRYON_FORWARDING_SECRET}" > forwarding.secret
fi

# Floodgate key (Bedrock) if mounted from its Secret.
if [ -f /floodgate/key.pem ]; then
  mkdir -p plugins/floodgate
  cp /floodgate/key.pem plugins/floodgate/key.pem
fi

exec java ${JAVA_OPTS:-} -jar velocity.jar
