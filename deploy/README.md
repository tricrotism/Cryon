# Cryon deployment (Kubernetes + Agones)

Runs the sharded network: many interchangeable Paper shards (persistent families like `hub`/`skyblock`
and ephemeral minigame families like `bedwars-mini`/`bedwars-mega`) autoscaled by Agones, behind
multiple Velocity proxies and a standalone Geyser for Bedrock. Game servers self-register into the
Redis-backed server registry; proxies register backends and route from it.

```
deploy/
  images/        Dockerfiles + entrypoints for the Paper family, Velocity, and Geyser images
  families/      per-family baked feature jars (api/ + modules/)
  helm/cryon/    the umbrella chart (Fleets, autoscalers, proxy + Geyser, config, RBAC)
```

## Prerequisites

1. A Kubernetes cluster.
2. Agones installed: `helm install agones agones/agones --namespace agones-system --create-namespace`.
   (For a future Counter autoscaler, enable the `CountsAndLists` feature gate.)
3. Redis and Postgres reachable from the cluster (managed services recommended for production). Point
   `values.yaml` `redis.uri` / `postgres.*` at them.
4. Your own jars placed in `deploy/`: `paper.jar` (your Paper 26.2 build), `velocity.jar`, `geyser.jar`,
   and for Bedrock `floodgate-velocity.jar`.

## Secrets

Create these in the `cryon` namespace (names are referenced from `values.yaml`, never committed):

```bash
kubectl create namespace cryon

# Postgres password
kubectl -n cryon create secret generic cryon-postgres --from-literal=password='<db-password>'

# Velocity modern-forwarding secret (shared by proxies and every backend)
kubectl -n cryon create secret generic cryon-forwarding --from-literal=secret="$(openssl rand -hex 24)"

# Floodgate key (Bedrock only). Generate once (Floodgate produces key.pem) and share it everywhere.
kubectl -n cryon create secret generic cryon-floodgate --from-file=key.pem=./key.pem
```

Use SealedSecrets/ExternalSecrets in production rather than raw `kubectl create secret`.

## Build and push images

```bash
# One image per family (bake that family's jars into deploy/families/<name>/ first).
docker build -f deploy/images/paper-family.Dockerfile --build-arg FAMILY=hub      -t <registry>/cryon-hub .
docker build -f deploy/images/paper-family.Dockerfile --build-arg FAMILY=skyblock -t <registry>/cryon-skyblock .
docker build -f deploy/images/velocity.Dockerfile -t <registry>/cryon-velocity .
docker build -f deploy/images/geyser.Dockerfile   -t <registry>/cryon-geyser .
# docker push each
```

## Install

Edit `helm/cryon/values.yaml` (registry, redis/postgres endpoints, families, resources, replicas),
then:

```bash
helm upgrade --install cryon deploy/helm/cryon -n cryon
```

## How it works operationally

- **Identity**: each pod gets `CRYON_SERVER_FAMILY` + downward-API `POD_NAME`/`POD_IP`; the plugin
  registers `POD_IP:25565` (in-cluster pod-IP dialing) with a heartbeat TTL. Confirm your proxies are
  in-cluster; if they are external, switch to an Agones host-port policy and register the assigned
  `status.address:port` instead.
- **Autoscaling**: a Buffer `FleetAutoscaler` keeps warm Ready servers per family and only ever trims
  Ready ones, so a populated (Allocated) shard is never killed. Persistent shards additionally set
  `CRYON_AGONES_SHUTDOWN_WHEN_EMPTY=true` to reclaim themselves once empty (guarded to never drop the
  last `min-instances`). Ephemeral matches self-`Shutdown()` on match end.
- **Routing + capacity**: `PlayerRouter.route(uuid, family)` reserves a slot atomically in Redis
  before transferring, so two proxies cannot overfill a shard. Every proxy converges to the same
  backend set independently.
- **Maintenance**: `/maintenance on|off [message]` on any proxy (permission `cryon.maintenance`) flips
  every proxy over Redis; players see the message and an unjoinable protocol, bypass via
  `cryon.maintenance.bypass`.
- **Bedrock**: Geyser terminates UDP and speaks Java to the Velocity Service; the shared Floodgate key
  gives each Bedrock player a stable UUID the registry/flags/locale already key on.

## Stand-up order

1. Cluster + Agones + Redis + Postgres, secrets created.
2. Build/push the `hub` family, Velocity, and (if Bedrock) Geyser images.
3. `helm install` with only `hub` in `persistentFamilies`; confirm a Java client connects through the
   proxy and lands on a hub shard, and that killing a shard drops its backend within ~3 heartbeats.
4. Add `skyblock` (persistent) and confirm least-loaded routing + empty reclaim.
5. Add ephemeral families once a matchmaker module is deployed (the `Matchmaker` seam).
6. Enable Geyser and verify a Bedrock client routes across families with a consistent identity.

## Decisions to make / risks

- Managed vs in-cluster Redis/Postgres (recommend managed).
- In-cluster pod-IP dialing (default here) vs Agones host ports for external proxies.
- Cloud LoadBalancer **UDP** support for Bedrock varies by provider; confirm before relying on it.
- Floodgate global account linking on/off (whether Bedrock maps to a linked Java UUID).
- Safe scale-down of populated persistent shards is the highest-risk area: test scale events with
  players present before going live.
- The `paper-global.yml` here is a minimal overlay; verify Paper accepts it (modern forwarding on,
  the same secret as the proxy) on your exact build.
