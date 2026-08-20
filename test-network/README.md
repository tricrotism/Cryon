# Cryon local test network

A self-contained Velocity proxy + two Paper backends + standalone Geyser + Redis + Postgres, wired for **instanced**
deployment so you can exercise the cross-server pieces (server registry, player routing, handoff
flush, maintenance mode, feature-flag sync) and module hot-swap end to end.

```
   Java client        Bedrock client
        |                   |
        |                   v  localhost:19132/udp
        |            +--------------+
        |            |    Geyser    |  (standalone, Floodgate auth)
        |            +--------------+
        |                   |
        v  localhost:25565  v
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

| Piece    | Where                                 | Notes                                               |
|----------|---------------------------------------|-----------------------------------------------------|
| Redis    | `redis/` (portable, port 6379)        | The shared transport. Start it first.               |
| Postgres | `postgres/` (portable, **port 5433**) | Superuser/db `cryon`, trust auth. 5432 left alone.  |
| paper1   | `paper1/` (port 25566, id `paper1`)   | Instanced, family `prison`, 7 feature modules.      |
| paper2   | `paper2/` (port 25567, id `paper2`)   | Same family — an interchangeable instance.          |
| Velocity | `velocity/` (port 25565)              | Modern forwarding; backends via the registry.       |
| Geyser   | `geyser/` (**UDP** port 19132)        | Standalone, translates Bedrock into a Java connect. |

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
.\start-geyser.ps1
```

Order matters: **Postgres + Redis first**, then the Papers (they register into Redis on boot), then Velocity (it seeds
its backend list from the registry), then Geyser (it pings the proxy for its MOTD).
`start-all.ps1` staggers this for you.

Then point a Minecraft client (offline/cracked is fine, the proxy runs `online-mode = false`) at
`localhost:25565`. You land on `paper1`. A Bedrock client (Windows/mobile/console) adds the same host on port `19132`
instead and arrives through Geyser as a Floodgate player named `.<gamertag>`.

## Things to try

- **Cross-server hop + handoff flush** — on paper1, run `/server paper2`. Velocity holds the connect
  open while paper1 flushes your state (watch the paper1 console for the handoff request on
  `cryon:handoff:paper1:req`), then moves you. `/cryon network` shows both live instances.
- **Feature-flag sync** — `/cryon flag disable SHOP_SELL` on paper1, then check `/cryon flag status
  SHOP_SELL` on paper2; the toggle crosses over Redis and (because Postgres is on) survives a restart.
- **Maintenance mode** — `/maintenance on Testing` on the **proxy** console. New logins are denied
  (except `cryon.maintenance.bypass`) and the server list shows the message. `/maintenance off` clears it.
- **Bedrock forms** — join on 19132 and run something that opens a menu (`/cryon`, or any
  `ConfirmMenu`). `BedrockService` reports you as Bedrock, so you get a native Cumulus form rather than the InvUI
  window. Note what proves what: paper1's boot line `Floodgate detected. Bedrock forms
  enabled` only says the plugin is installed. Whether a real Bedrock player is *recognised* depends on the key matching
  everywhere and on `send-floodgate-data` (see below), and the only way to know is to join on 19132 and look.
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
- Cryon Geyser extension: `geyser/extensions/cryon-geyser.jar`, with its own config under
  `geyser/extensions/cryon/` once it has run once. The folder is the Geyser side of the same drop pattern as
  `velocity/plugins/` and `paperN/plugins/Cryon/modules/`.
- Geyser: `geyser/config.yml`. `java.address`/`java.port` point at the proxy and `java.auth-type` is
  `floodgate`, which is what makes Bedrock players land with a stable Floodgate identity instead of a fresh offline UUID
  per join.
- **The proxy must forward Floodgate data or the backends learn nothing.**
  `send-floodgate-data: true` in `velocity/plugins/floodgate/config.yml`. It defaults to **false**, and with it off a
  Bedrock player still joins fine but arrives on paper1/paper2 looking like an ordinary Java player, so
  `BedrockService.isBedrock` answers false and every menu renders as InvUI. This is set here. Floodgate has to stay
  installed on the backends for it, which is why the jar is in all three.
- **The Floodgate key is shared by four processes** and must be byte-identical in all of them:
  `geyser/key.pem`, `velocity/plugins/floodgate/key.pem`, and `paperN/plugins/floodgate/key.pem`. One is generated here
  already. If you ever regenerate it (delete one and let Floodgate make a new one), copy that file over the other three,
  or Bedrock logins fail the encryption check.

## Rebuilding after a code change

Rebuild the plugin jars and recopy them (world data and configs are preserved):

```powershell
cd D:\git\tricrotism\Cryon
.\gradlew :paper:shadowJar :velocity:shadowJar :geyser:shadowJar
Copy-Item paper\build\libs\paper-1.0-SNAPSHOT-all.jar   test-network\paper1\plugins\paper-1.0-SNAPSHOT-all.jar -Force
Copy-Item paper\build\libs\paper-1.0-SNAPSHOT-all.jar   test-network\paper2\plugins\paper-1.0-SNAPSHOT-all.jar -Force
Copy-Item velocity\build\libs\velocity-1.0-SNAPSHOT-all.jar test-network\velocity\plugins\cryon-velocity.jar -Force
Copy-Item geyser\build\libs\geyser-1.0-SNAPSHOT-all.jar     test-network\geyser\extensions\cryon-geyser.jar -Force
```

Paper picks a replaced module jar up live and Velocity needs a proxy restart, but the Geyser extension needs **Geyser
restarted**: it scans `extensions/` once at startup, and nothing here has been tested to reload it in place. Close the
`cryon-geyser` window and run `.\start-geyser.ps1`
again. Nothing else has to come down with it.

**Keep the deployed filename.** The core plugin is `paper-1.0-SNAPSHOT-all.jar` in both `plugins/`
folders. Copying the same build in under a second name (`Cryon.jar`) leaves two jars declaring
`name: Cryon`, and Paper refuses both with `Ambiguous plugin name 'Cryon'`. Overwrite in place rather than adding a
copy.

Geyser and Floodgate are plain downloads, not built here. To refresh them (jars land in
`downloads/`, then copy into `geyser/`, `velocity/plugins/`, and both `paperN/plugins/`):

```
https://download.geysermc.org/v2/projects/geyser/versions/latest/builds/latest/downloads/standalone
https://download.geysermc.org/v2/projects/floodgate/versions/latest/builds/latest/downloads/velocity
https://download.geysermc.org/v2/projects/floodgate/versions/latest/builds/latest/downloads/spigot
```

Currently pinned: Geyser 2.11.2-b1227, Floodgate 2.2.5-b140. Keep `key.pem` when you replace a jar.

Java is Amazon Corretto JDK 25 (`C:\Program Files\Amazon Corretto\jdk25.0.3_9`); the scripts point at
it explicitly since the JDK on `PATH` is 21.
