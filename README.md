# Cryon

A **Kotlin** **Velocity + Paper** Minecraft network built as a **feature-loader framework**. The Cryon
core is a Paper plugin (and a matching Velocity proxy plugin) that discovers and loads independent
**feature jars** at boot, each living in its own repo. Features stay isolated from one another and
intertwine only through shared API interfaces, so a single feature can be built, hot-swapped, or crash
without taking the rest of the server down.

Stack: **JDK 25**, **Kotlin 2.4.20-Beta1**, Paper dev bundle **26.2**, Velocity.

Feature modules live in a **separate repo** — see
[`../Cryon-Modules`](../Cryon-Modules) for a working example set (economy, skills, shop, spawn,
jumppads, visibility/vanish, and the Ashvale gamemode). This repo is the **core / loader** only.

---

## Modules in this repo

| Module              | What it is                                                                                                                                                                                                                                                                                                      | Published |
|---------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-----------|
| **`:common`**       | Platform-neutral framework: module system, `number` (`PackedDecimal`, …), `text` (`Mini`, palette, messages), `locale` (i18n), `data`/`net` (SQL + `Messenger`/`KeyValueStore` transport, Redis or in-process), `server` (deployment mode, server registry, player routing, handoff). No Bukkit/Velocity types. | ✅         |
| **`:paper-api`**    | What Paper feature repos compile against: `PaperModule`/`PaperModuleContext`, `ItemBuilder`, `Schedulers` (Folia-aware), `Events`, the `@Command` annotation framework. Bukkit `compileOnly`.                                                                                                                   | ✅         |
| **`:paper`**        | The core plugin / **loader** (`com.tricrotism.cryon.Cryon`). Bundles `:common` + `:paper-api` + kotlin-stdlib for loaded features. paperweight.userdev + Shadow + run-paper.                                                                                                                                    | —         |
| **`:velocity-api`** | What Velocity feature repos compile against: `VelocityModule`/`VelocityModuleContext`. Velocity `compileOnly`.                                                                                                                                                                                                  | ✅         |
| **`:velocity`**     | The proxy **loader** (`com.tricrotism.cryon.velocity.CryonVelocityPlugin`). Reads the shared server registry to route players and register backends live. Shades `:common` + `:velocity-api` + the cross-server client libs.                                                                                    | —         |

The published artifacts (`:common`, `:paper-api`, `:velocity-api`) are the contract feature repos
depend on; the two loaders (`:paper`, `:velocity`) are what you deploy.

---

## Architecture at a glance

- **Isolated feature jars.** On boot the loader loads every jar in `plugins/Cryon/api/` into one shared
  contract classloader, then loads each `modules/*.jar` in its **own** isolated classloader parented to
  it. Features can't see each other's classes; they expose behaviour only by registering an
  implementation of a shared API interface into the `ServiceRegistry`.
- **Two-phase lifecycle.** Every module's `onLoad(context)` (publish services) runs before any
  `onEnable()` (consume peers), so peer services are always available — no declared load order.
- **Failure isolation.** Every seam where the framework invokes feature code catches `Throwable`. A
  broken feature is marked `FAILED` and the server keeps running.
- **Hot-swap.** Jars are loaded from private cache copies (so the original is never file-locked), and
  can be added/removed at runtime: `/cryon load|unload|scan|reload-api`, plus an optional dev
  file-watcher that auto-reloads on change (gated by `modules.auto-reload`, default `!production`).
- **Cross-server.** `:common` ships a `Messenger` + `KeyValueStore` transport (Redis when configured,
  else in-process), a live server registry, player routing/reservation, maintenance mode, and player
  handoff — all with exactly one implementation each, so feature code never branches on deployment mode.

For the full design (module system, command registration, spark attribution, sharding, handoff,
deployment modes), read **[`CLAUDE.md`](CLAUDE.md)** — it is the authoritative developer guide.

---

## Build & run

Requires **JDK 25**.

```bash
# Build everything. :paper's build produces the shaded loader jar under paper/build/libs/
./gradlew build

# Run a local Paper 26.2 server with the core loaded.
# Drop feature jars into plugins/Cryon/modules/ (and any api/ contract jars into plugins/Cryon/api/).
./gradlew :paper:runServer

# Publish the API locally so feature repos can compile against it
./gradlew :common:publishToMavenLocal :paper-api:publishToMavenLocal :velocity-api:publishToMavenLocal
```

Production publishes/consumes the API from `repo.striveservices.org` rather than mavenLocal. There are
no unit tests — verify on a local server.

A ready-made local stack (Velocity + 2 Paper instances + Redis + Postgres, instanced mode) lives in
[`test-network/`](test-network).

---

## Authoring a feature

Features are separate repos that `compileOnly` the published API and ship a thin jar. The full example
set is [`Cryon-Modules`](../Cryon-Modules); the shape is:

1. New repo → `compileOnly` published `:common` + `:paper-api` (+ Paper API). The core provides them at
   runtime, so don't shade them.
2. `class Foo : PaperModule()` with a **no-arg constructor**.
3. Add the `META-INF/services/com.tricrotism.cryon.common.module.Module` entry naming your class
   (`ServiceLoader` discovery).
4. Register commands in `onLoad` (via `registerCommands(…)`), publish services in `onLoad`, consume
   peers in `onEnable`, tear down in `onDisable`.
5. Build the plain jar and drop it into the core server's `plugins/Cryon/modules/`.

```kotlin
class VisibilityModule : PaperModule() {
    override fun onLoad(context: PaperModuleContext) {
        super.onLoad(context)
        services.register(VisibilityService::class, engine)   // expose behaviour
        registerCommands(VanishCommands(engine))              // gated on isEnabled()
    }
    override fun onEnable() { /* consume peer services here */ }
    override fun onDisable() { super.onDisable() }
}
```

To expose an API to a feature in **another repo**, ship the interface in a thin `*-api` jar placed in
`plugins/Cryon/api/`; both provider and consumer depend on it `compileOnly`, and the consumer resolves
it with `services.find(...)` (the other repo may be absent). See the `*-api` pairs in `Cryon-Modules`.

Velocity feature repos are the same, against `:common` + `:velocity-api`, extending `VelocityModule`,
dropped into the proxy's `plugins/cryon/modules/`.

---

## Deployment

`deploy/` (outside the Gradle build) holds per-family Paper / Velocity / Geyser Dockerfiles, baked
family jar sets, and a Helm chart of Agones Fleets + autoscalers for a Kubernetes deployment. See
[`deploy/README.md`](deploy/README.md).

A deployment is one of two shapes, declared in `network.mode`:

- **`single`** — this server is the whole family (a proxy still fronts it). Redis optional.
- **`instanced`** — one of N interchangeable instances of `network.family`, players load-balanced onto
  the healthiest. Redis + Postgres required.

The mode declares intent only; whether state actually crosses processes is decided by `redis.enabled`.
Feature code is written once and never branches on the mode.

---

## Localization

Everything user-facing is localizable. A jar's `lang/<locale>.properties` auto-registers on load, and
`plugins/Cryon/lang/` (admin override) wins. See [`TRANSLATING.md`](TRANSLATING.md) and `crowdin.yml`.

---

## Repository layout

```
common/         platform-neutral framework (published)
paper-api/      Paper feature contract (published)
paper/          core Paper plugin / loader
velocity-api/   Velocity feature contract (published)
velocity/       proxy plugin / loader
deploy/         Dockerfiles, family jar sets, Helm/Agones chart
test-network/   local Velocity + Paper + Redis + Postgres stack
CLAUDE.md       authoritative developer guide
```
