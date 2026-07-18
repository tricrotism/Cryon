# Cryon local test network

A self-contained Velocity proxy + two Paper backends + Redis + Postgres, wired for **instanced**
deployment so you can exercise the cross-server pieces (server registry, player routing, handoff
flush, maintenance mode, feature-flag sync) and module hot-swap end to end.

```
        Minecraft client
              |
              v  localhost:25565
        +--------------+
        |   Velocity   |  (proxy, reads the shared registry)
        +--------------+
           /        \
          v          v
   paper1:25566   paper2:25567     family "prison", instanced mode
          \          /
           v        v
        Redis 6379   Postgres 5433     shared transport + persistence
```

Everything here is disposable — the whole folder can be deleted and rebuilt (see *Rebuilding* below).
Nothing in it is committed to git.

## What's wired

| Piece    | Where                                 | Notes                                              |
|----------|---------------------------------------|----------------------------------------------------|
| Redis    | `redis/` (portable, port 6379)        | The shared transport. Start it first.              |
| Postgres | `postgres/` (portable, **port 5433**) | Superuser/db `cryon`, trust auth. 5432 left alone. |
| paper1   | `paper1/` (port 25566, id `paper1`)   | Instanced, family `prison`, 7 feature modules.     |
| paper2   | `paper2/` (port 25567, id `paper2`)   | Same family — an interchangeable instance.         |
| Velocity | `velocity/` (port 25565)              | Modern forwarding; backends via the registry.      |

The Paper instance ids (`paper1`/`paper2`) deliberately equal the Velocity server names — the handoff
and transfer listeners look backends up by instance id, so they must match.

## Running it

From a PowerShell prompt in this folder:

```powershell
.\start-all.ps1      # opens Postgres, Redis, both Papers, and Velocity each in its own window
.\stop-all.ps1       # stops everything this folder started (never touches a 5432 Postgres)
```

Or start pieces individually (each runs in the foreground; Ctrl+C stops it):

```powershell
.\start-postgres.ps1
.\start-redis.ps1
.\start-paper1.ps1
.\start-paper2.ps1
.\start-velocity.ps1
```

Order matters: **Postgres + Redis first**, then the Papers (they register into Redis on boot), then
Velocity (it seeds its backend list from the registry). `start-all.ps1` staggers this for you.

Then point a Minecraft client (offline/cracked is fine — the proxy runs `online-mode = false`) at
`localhost:25565`. You land on `paper1`.

## Things to try

- **Cross-server hop + handoff flush** — on paper1, run `/server paper2`. Velocity holds the connect
  open while paper1 flushes your state (watch the paper1 console for the handoff request on
  `cryon:handoff:paper1:req`), then moves you. `/cryon network` shows both live instances.
- **Feature-flag sync** — `/cryon flag disable SHOP_SELL` on paper1, then check `/cryon flag status
  SHOP_SELL` on paper2; the toggle crosses over Redis and (because Postgres is on) survives a restart.
- **Maintenance mode** — `/maintenance on Testing` on the **proxy** console. New logins are denied
  (except `cryon.maintenance.bypass`) and the server list shows the message. `/maintenance off` clears it.
- **Module hot-swap** — drop, replace, or delete a jar in `paper1/plugins/Cryon/modules/` while the
  server runs; the watcher loads/reloads/unloads it live (`modules.auto-reload` is on). Or use
  `/cryon load|unload|scan|reload-api`. Replacing anything in `paper1/plugins/Cryon/api/` triggers a
  full `reload-api` cascade.

## Optional: drop Postgres

Postgres only adds persistence (flags/language survive restarts) and clears the "instanced but
database.enabled is false" boot banner. To run Redis-only, set `database.enabled: false` in all three
configs (`paper1`, `paper2`, `velocity`) — routing, handoff, maintenance, and flag sync still work,
they just reset on restart.

## Config pointers

- Paper core: `paperN/plugins/Cryon/config.yml` (mode, family, instance-id, redis, database).
- Paper identity is also forced via env in `start-paperN.ps1` (`CRYON_INSTANCE_ID` etc.) so it can't
  collide with a stray `HOSTNAME`.
- Velocity forwarding: `velocity/velocity.toml` + `velocity/forwarding.secret`, mirrored into each
  `paperN/config/paper-global.yml` (`proxies.velocity`). All three share one secret.
- Velocity Cryon plugin: `velocity/plugins/cryon/config.yml`.

## Rebuilding after a code change

Rebuild the plugin jars and recopy them (world data and configs are preserved):

```powershell
cd D:\git\tricrotism\Cryon
.\gradlew :paper:shadowJar :velocity:shadowJar
Copy-Item paper\build\libs\paper-1.0-SNAPSHOT-all.jar   test-network\paper1\plugins\Cryon.jar -Force
Copy-Item paper\build\libs\paper-1.0-SNAPSHOT-all.jar   test-network\paper2\plugins\Cryon.jar -Force
Copy-Item velocity\build\libs\velocity-1.0-SNAPSHOT-all.jar test-network\velocity\plugins\cryon-velocity.jar -Force
```

Java is Amazon Corretto JDK 25 (`C:\Program Files\Amazon Corretto\jdk25.0.3_9`); the scripts point at
it explicitly since the JDK on `PATH` is 21.
