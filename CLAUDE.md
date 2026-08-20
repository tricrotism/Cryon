# Cryon Dev Guide

**Kotlin** **Velocity + Paper** network built as a **feature-loader framework**: the Cryon core is a
Paper plugin that discovers and loads independent **feature jars** (each its own repo) at boot.
Stack: **JDK 25**, **Kotlin 2.4.20-Beta1**, Paper dev bundle **26.2**.

Modules in this repo (core + published API):

- **`:common`**. Platform-neutral framework: module system (`Module`, `ModuleManager`,
  `ModuleContext`, `ServiceRegistry`), `number` (`PackedDecimal`, `LongUtils`, `BigDecimalUtils`,
  `NumberUtils`), `text` (`Mini`, `CryonPalette`, `CommonMessages`), `locale`
  (`MessageService` + sources), `data`/`net` (SQL + the `Messenger`/`KeyValueStore` transport, Redis or in-process),
  `server` (deployment mode, server registry, player routing, player handoff),
  `currency` (`CurrencyService`, the ledger), `concurrent` (`CryonIO`), `cooldown`, `lock`, `requirement`, primitive
  `extension`s. Adventure `compileOnly`; no Bukkit/Velocity types
  (the `:velocity` loader reuses it).
  **Published.**
- **`:paper-api`**. What feature repos compile against: `PaperModule`/`PaperModuleContext`,
  `CryonPaper`, `item.ItemBuilder`, `scheduler.Schedulers` + `scheduler.CryonDispatchers` (both Folia-aware),
  `event.Events`, `dialog.Dialogs`, `command` (annotation framework), Paper `extension`s. Bukkit `compileOnly`.
  **Published.**
- **`:paper`**. The core plugin / **loader** (`com.tricrotism.cryon.Cryon`). paperweight.userdev +
  Shadow + run-paper; bundles `:common` + `:paper-api` + kotlin-stdlib for loaded features.
- **`:velocity-api`**. What Velocity feature repos compile against: `VelocityModule`/
  `VelocityModuleContext`. Velocity `compileOnly`. **Published.**
- **`:velocity`**. The proxy **loader** (`com.tricrotism.cryon.velocity.CryonVelocityPlugin`). Shadow;
  shades `:common` + `:velocity-api` + kotlin-stdlib + the cross-server client libs (no Paper-style
  `libraries:` loader on Velocity). Reads the shared server registry to route players and register
  backends live.
- **`:geyser-api`**. What Geyser Standalone feature repos compile against: `GeyserModule`/
  `GeyserModuleContext`, `command` (a third annotation framework), and the `Component` to legacy string seam Geyser's
  `CommandSource` forces. Geyser API `compileOnly`. **Published.**
- **`:geyser`**. The Bedrock **loader** (`com.tricrotism.cryon.geyser.CryonGeyserExtension`), a Geyser Standalone
  *extension* rather than a plugin. Shadow; shades the same set `:velocity` does plus MiniMessage, which the Geyser jar
  does not carry. Gates Bedrock logins on maintenance and writes the Bedrock MOTD.

Features live in **separate repos** (e.g. `Cryon-Modules/cryon-example-feature/`), `compileOnly` the
API, and ship a thin jar dropped into `plugins/Cryon/modules/`.

**Async is kotlinx-coroutines** (see *Coroutines*), shaded into `:paper` unrelocated like kotlin-stdlib. **No** DI
container or codegen. Menus are **InvUI**, shaded into `:paper`, with Bedrock clients served native Cumulus forms (see
*Menus* and *Bedrock* under Utilities) and Java clients native **dialogs** for typed input (see *Dialogs*); packets are
**PacketEvents**, shaded the same
way (see *Packets*). **When you add infrastructure (DI, KSP), document it here in the same pass**. Keep this guide and
the code in
lockstep.

---

## Working Practices

Bias toward caution over speed; use judgment on trivial tasks.

- **Think first / ask.** State assumptions. Surface multiple interpretations. Don't pick silently.
  Unclear → stop and ask.
- **Simplicity first.** Minimum code that solves the problem. No speculative features, single-use
  abstractions, configurability, or error handling for impossible cases. 200 lines that could be 50 → rewrite.
- **Light logic first.** Lightest tool wins: one-line guard over helper, helper over class, typed
  local over field, existing extension over new one, `if`/`when` over strategy/registry until 3+
  branches truly vary independently. Escalate only when the lighter form provably fails.
- **Surgical changes.** Touch only what you must. Don't refactor or "improve" adjacent code. Match
  existing style. Remove only the symbols *your* change made unused; mention pre-existing dead code, don't delete it.
- **Update deprecations only on lines you already touch** (legacy `§`, untyped lambdas, `@Deprecated`
  Bukkit calls). Don't open a file just to chase them. Non-trivial migration (signature/callsite cascade) → ask first.
- **Wire new effects everywhere they apply.** A multiplier/buff/drop-chance/cost modifier must hook
  every relevant call site (sell, drop, currency, crafting). Firing in one of four paths is a bug.
  Grep the peers and mirror them; unsure → ask.

---

## Kotlin Style

- `val` over `var`. Data classes for models. Sealed interfaces/classes for result types. `object`
  singletons, `companion object` factories/constants.
- **Explicit types on public API** and where inference hurts readability. Null-safety (`?.`/`?:`)
  throughout. No Java-isms; `!!` only when truly invariant.
- **Prefer extension functions**. Check existing ones first; reusable helpers go in an `…extension`
  file, not a static util.
- **Schedule through `Schedulers`** (Folia-aware), never raw `Bukkit.getScheduler()`.

---

## Code Quality

Apply ordinary code-smell scrutiny:

- **Null-safety on external returns**. `Bukkit.getPlayer`, config reads, `event.item`,
  `inventory.getItem`, anything crossing the Bukkit boundary. Internal invariants you can trust.
- **Resource leaks**. Unregistered listeners, tasks left running on disable, `TextDisplay`/`ItemDisplay`
  not despawned, open inventories unhandled.
- **Mutable shared state without thread-safety** (see Thread Safety).
- **Accidentally quadratic loops**. Per-block work inside per-player work, etc.
- **Broad `catch (e: Exception)` that swallows context**. Rethrow, log with context, or scope it.
- **Misleading identifiers** (`get…` that mutates, `enabled` meaning the opposite).
- **Dead branches / unreachable returns** your change introduced.

Don't flag: many-param Bukkit event/command signatures, hardcoded dep versions, `TODO`s, wildcard Bukkit returns.

---

## Commit Messages

Single-line title: `[TICKET]` + short imperative. Ticket = Linear ID (`[DEV-915]`); none → scope tag
(`[Build]`, `[Fishing]`, `[Global]`).

- Non-trivial: blank line, then **Problem** (concrete: class/symptom/impact) then **Fix** (mechanism,
  key invariant, preserved overrides).
- Trivial commits (dep bumps, cosmetics, reverts) → title only.
- **Never `Co-Authored-By:` trailers (Claude/Anthropic/AI/anything) or emoji.**

```
[Fishing] Gate rare-catch bonus behind the fishing feature flag

The ranged-spear bonus fired even when fishing was disabled, so admins
couldn't kill it independently of the core sell loop.

Wraps the bonus award in isEnabled(FISHING_RARE) and short-circuits before
the BigDecimal multiply.
```

---

## Feature Flags

**Every feature gets one; every independently-meaningful sub-feature gets its own** (command,
broadcast, scheduler, payout path, animation phase, mode toggle). Umbrella flags force killing the
whole feature when one slice breaks.

**The system: `FeatureFlags`** (`…common.flag`), created by the core and shared via the
`ServiceRegistry`. Bare uppercase IDs (`FISHING`, `FISHING_RARE`, **no** gamemode prefixes; a gamemode-specific flag is
scoped via the scope argument, never the ID). **Layered. Most specific wins:** player
override → server override (this server's pool, `network.server`/legacy `server-name` in
`config.yml`) → global
override → default **enabled**. SQL-persisted (`cryon_feature_flags`, source of truth) and synced
across every server via a Redis broadcast when the infra is configured; without it the same API runs
in-memory per server (resets on restart).

Admin surface (`cryon.admin`): `/cryon flags [scope]`, per-scope listing with clickable toggles;
`/cryon flag enable|disable|clear <feature> [scope]`; `/cryon flag status <feature> [player]`, the layered breakdown;
`/cryon flag delete <feature>` (console only); `/cryon flag reload`. **A scope is
`global` (the default), `server` for this server's own pool, another server's name, or a bare player name**.
`player:<name>` is still accepted so existing scripts keep working, but nothing suggests it.

In a module: resolve once (`services.get<FeatureFlags>()`), `register("SHOP_SELL")` each ID in
`onEnable` (persists its default and lists it), then gate every entry point **inside** the handler, not at wiring time,
so a runtime toggle bites without re-enabling the module. Commands use the
one-line guard `flags.guard(player, FLAG)` (acks the localized "⟨Feature⟩ is currently disabled."
and returns false); silent paths (event handlers, sell modifiers, payout code) use
`isEnabled(FLAG, player.uniqueId)`, **pass the player whenever one is in context** so per-player
overrides (canary rollouts, support cases) apply. Reference: the survival gamemode modules in
`Cryon-Modules/` (`cryon-economy`/`cryon-skills`/`cryon-shop`).

---

## Messaging: Adventure + MiniMessage

Send `Component`s, **never legacy `§` strings, never string interpolation into messages.** Use the
helpers: `CommonMessages` acks, `audience.sendError("…")`, `Mini.format(...)`/`"…".mm()`. `Mini` is
the cached, palette-loaded MiniMessage; **never `MiniMessage.miniMessage()`**.

```kotlin
player.sendError("You don't have enough scales.")            // « Error » prefix
player.sendMessage("<emerald>Enchantment applied!".mm())
```

Dynamic content uses placeholders, **never `"...$value..."`**:

```kotlin
player.sendMessage(
    Mini.format(
        "<off_white>Caught a <highlight><rarity></highlight> fish! Earned <highlight><amount></highlight> scales.",
        Placeholder.unparsed("rarity", rarity.name),
        Placeholder.unparsed("amount", amount.toString()),
    )
)
```

`Placeholder.unparsed`/`.component`/`.parsed`. Palette tags (`<off_white>`, `<scarlet>`, semantic
`<error>`/`<success>`/…) resolve through `Mini`. Multi-language copy pulls from `MessageService` by
key (never hardcode English); localized + prefixed ack → `messages.send(player, "key", …)`.

---

## Item Lore

Prefer `ItemBuilder`. Auto-applies non-italic (`<!i>`) to name/lore and chains flags/glow/attributes/PDC:

```kotlin
val item = Material.TRIDENT.toItem()
    .name("<aqua>Fish Spear")
    .lore("<gray>Deals bonus damage to fish.")
    .build()
```

Hand-building meta is fine for one-offs, but every name/lore line needs `<!i>`, and lore lines are `Component`s, never
`§`-coded.

---

## Audio Cues

Silent feedback feels broken, play a `Sound.*` on every player-facing action (message, menu, toggle,
redeem, drop). Only vanilla sounds exist.

```kotlin
player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.5f)
```

- Personal feedback → `player.location`. Ambient/broadcast → a world `Location`.
- Pitch: `1.5f`-`2.0f` positive/rare, `1.0f` neutral, `0.5f`-`0.8f` warnings/heavy. Volume `1f`
  standard, `0.5f` incidental/repeating.
- Match existing feature sounds before inventing new ones.

---

## Giving Items

`inventory.addItem` and **handle overflow deliberately**. Never `dropItemNaturally` as the
"inventory full" fallback (anyone can grab it; it despawns).

```kotlin
val leftover = player.inventory.addItem(item) // slot -> what didn't fit; decide explicitly
```

`world.dropItemNaturally` only for genuine world drops (block breaks, deaths).

---

## Events & Listeners

Prefer the functional `Events` builder. No `Listener` class, returns a cancellable `Subscription`,
filters run before the handler:

```kotlin
Events.subscribe<PlayerInteractEvent>(EventPriority.HIGHEST)  // or Events.subscribe(type, priority) with a Class
    .ignoreCancelled()
    .filter { it.hand == EquipmentSlot.HAND }
    .handler { event -> /* … */ }
```

Inside a `PaperModule`, `listen(listener)` covers a classic `@EventHandler` `Listener` and
auto-unregisters on disable. Either way: filter cheaply first, tear down on disable.

---

## Coroutines

**Every async API in the core is `suspend`.** `Database`, `KeyValueStore`, `Messenger`, `CurrencyService`,
`ServerRegistry`, `PlayerRouter`, `LocaleStore`, `MaintenanceService` and `PlayerHandoff` return values, not
`CompletableFuture`s. There is no `.get()` to park a region thread on and no future to forget to consume.

**Launch from a scope that dies with you.** `PaperModule.scope` is cancelled on disable, so an unloaded module cannot
leave a coroutine suspended on a database call holding its classloader open, the suspending half of what `track(…)`
does for listeners. **Never `GlobalScope`, never an ad-hoc `CoroutineScope`.** The core has its own `scope` for work
that must outlive a module (the quit flush, the locale load, the leaderboard refresh); `:velocity` has the proxy twin.

**`CryonDispatchers` picks the thread** (`…paper.api.scheduler`), the suspending counterpart to `Schedulers`:

- `Global` / `region(loc)` / `entity(e)`, the Folia region schedulers.
- `Async`. Off-server I/O, no Bukkit API. Virtual threads (`CryonIO`), so blocking is what it is *for*; the ceiling on
  concurrent Redis/HTTP calls is not a pool size. SQL is the exception and keeps its own pool sized to the connection
  pool, because there the scarce resource is connections.

Two properties worth knowing. Each dispatcher **elides the hop when it is already on the right thread**, so
`withContext(Global)` from the global thread costs nothing, unlike `Schedulers.global`, which always defers a tick. And
they implement `Delay`, so `delay`/`withTimeout` schedule on the owning region rather than parking a thread elsewhere
and hopping back.

- **Cancellation is cooperative.** It unblocks a suspension point; a thread already inside a JDBC call runs to
  completion. Teardown that must *finish* goes in `onDisable` before the super call, not in a coroutine racing it.
- **`runBlocking` is for shutdown only**, and there are exactly three, all in `onDisable`/`stop`:
  `Cryon.flushOnlinePlayers`, the colony crown resign in `Cryon.onDisable`, and `NodeReporter.stop`. Each is bounded by
  a timeout, and each exists because there is no later tick to resume on and the work has to land before the pool
  closes. Never reachable from a tick, event or Netty thread.
- **A monitor cannot be held across a suspension point.** `@Synchronized` goes on a non-suspending helper
  (`MemoryCurrencyStore.moveLocked` is the worked example); for anything that suspends, use a `Mutex`.
- **Non-suspending seams stay non-suspending on purpose.** `Messenger.subscribe` handlers run on the transport's ordered
  delivery thread, that ordering *is* the guarantee, so launching each message would silently reorder them.
  `FeatureFlags`/`SettingsService`/`MaintenanceService` mutations apply in memory synchronously and launch the durable
  write behind, which is what their futures did before and is why `register(…)` still works from a plain `onEnable`.

**Binary compatibility broke here.** Suspend functions change signatures, so every feature jar must be rebuilt against
the new `:common`/`:paper-api`. Old jars link and then fail at the call with `NoSuchMethodError`.

---

## Thread Safety

Shared mutable state (`object`/`companion` fields touched by >1 thread/scheduler) must be thread-safe; lightest correct
type:

- **`ConcurrentHashMap`** for shared maps; **`Collections.newSetFromMap(ConcurrentHashMap())`** for sets.
- **`merge()` for counter increments** (`getOrDefault + 1 + put` is a TOCTOU race):
  `rarityCount.merge(rarity, 1) { a, b -> a + b }`
- **`computeIfAbsent`/`putIfAbsent`** for check-set, never `containsKey` + `put`.
- **No Bukkit API off the main thread**. Entities, inventories, particles, `TextDisplay.text(…)`.
  Do async work, then hop back. Folia: use region/entity schedulers and document it.
- **Locals that never escape a tick** can use plain `HashSet`/`ArrayList`.

### Folia

**`:paper` declares `folia-supported: true`, so there is no main thread.** Write every line as if two players' handlers
run at the same instant, because on Folia they do, each player is owned by their region's thread, and only console
commands and `Schedulers.global` land on the global region thread.

- **A plain `HashMap`/`mutableListOf` field on a manager or `object` is a race** even with no
  `async` anywhere in the file. This is the rule that costs people, not the scheduler ones.
- **A snapshot is not a licence to touch.** `server.onlinePlayers` is safe to *read* from anywhere, but `sendMessage`
  /inventory/`updateCommands` on each element needs a per-player
  `Schedulers.entity(player) { … }`. They don't share a thread. `CommandRegistry.refresh` and
  `DefaultInventorySearch` are the two worked examples.
- **`Schedulers.global` never runs inline**. It always defers to a later tick. So a check-then-act whose mutation is
  hopped reads state its own write hasn't applied yet; the burst races itself with no second actor involved. Perform the
  authoritative mutation inline and branch on what it returns.
- **Serializing writers does not protect readers.** The core funnels every `/cryon` hot-swap through the global region
  thread (`ModuleCommands.onLoaderThread`) so `ModuleLoader`/`ModuleManager`/
  `CommandRegistry` keep their single-writer invariant. The **runtime** Brigadier splice
  (`CommandRegistry.liveRegister`) still mutates the live dispatcher's plain child maps while region threads may be
  reading them, unavoidable, since Brigadier exposes no locking and Paper no API for it. It is a dev/admin path
  measured in microseconds; boot-time registration goes through Paper's registrar and is unaffected.

Two dependencies, both fine for what the core uses and both worth knowing:

- **InvUI 2.2.0 is Folia-ready**. `AbstractWindow`'s tick task runs on `Player.getScheduler()`. Its one legacy
  `Bukkit.getScheduler()` call is a fallback inside `AnimationImpl.start(…)` for a GUI with no viewer, so **InvUI
  animations can throw on Folia**. Nothing in the core or the feature repos uses them; if you add one, drive it from a
  viewer's entity scheduler.
- **PlaceholderAPI** is a `softdepend` and makes no Folia guarantee of its own. `PapiBridge` is best-effort either way,
  and `onRequest` must stay cheap, thread-safe, and free of Bukkit calls, which was already the rule.

---

## Performance

- **Cache lookups before hot loops**. Resolve services/config once above the loop.
- **`x shr 4` for block→chunk coords**, not `block.chunk.x` (allocates a `Chunk`).
- **Batch high-frequency work**. Accumulate deltas in a thread-safe per-player buffer, flush on
  interval or boundary (logout, level-up, sell-cycle end), not one DB write per event.
- **Hot-loop math.** `BigDecimal.valueOf(long)` over `BigDecimal(double)`; hoist invariant lookups;
  branch out no-op multiplies (`multiplier == 1.0`); keep per-rank multipliers as `BigDecimal` where a `long` could
  overflow.
- **No accidentally quadratic loops**. Don't iterate online players inside per-player/per-block work.

These target hot loops (per-tick/block/player). Cold paths (boot, admin commands, file events) follow **simplicity first
** instead.

---

## Comments

No decorative dividers (`// ── fishing ──`). Comment the *why* when non-obvious; don't narrate what the code says.

---

## Build & Run

JDK 25, Kotlin 2.4.20-Beta1, paperweight dev bundle 26.2. Gradle config-cache/parallel/build-cache on. **Verify on a
local server**. There are no tests in this repo, and `./gradlew test` therefore passes without running anything.

If one is ever worth adding, the case is the narrow one a running server cannot reach: a state that needs a *dependency*
to fail part-way through a sequence, which is not reachable by stopping a database from outside because the first
statement fails first. That would want a `src/test/kotlin` in the module it tests (Kotlin associates the two
compilations, so the test sees `internal` types and can substitute a fake `CurrencyStore` without widening any public
API), plus `kotlin("test")` on that module. **Don't grow it into a general suite**, anything observable from a running
server belongs on a running server.

- `./gradlew build`. All modules; `:paper`'s `build` runs `shadowJar` → `paper/build/libs/`. The shaded jar bundles
  `:common` + `:paper-api` + kotlin-stdlib, **don't relocate kotlin-stdlib**
  (features resolve `kotlin.*` through it).
- `./gradlew :paper:runServer`. Local Paper 26.2 with the core loaded; drop feature jars into `plugins/Cryon/modules/`.
- `./gradlew :common:publishToMavenLocal :paper-api:publishToMavenLocal`. Publish the API locally, which
  is how feature repos resolve it. No remote repository is configured, so this is the only publish
  target that does anything.
- **Every version lives in `gradle/libs.versions.toml`**. Dependencies use `module=` + `version.ref`,
  never inline coordinates; `bundles` group the adventure and SQL-driver sets. Three separate Paper
  coordinates, because they are three different artifacts: `paperDevBundle` (`26.2.build.+`, what
  `:paper` compiles against), `minecraft` (`26.2`, what `runServer` starts) and `paper`
  (`26.2.build.+`, the plain `paper-api` artifact `:paper-api` compiles against, and through which every Bukkit *and*
  Adventure type in that module arrives, break it and the whole module resolves to
  nothing). **Paper dropped the `-R0.1-SNAPSHOT` scheme at 26.x**; only `26.2.build.NN-{alpha,beta,stable}`
  is published, so the two build coordinates now share a range even though the artifacts differ.
  `paper/plugin.yml` `libraries:` is plain YAML and can't reference the catalog. Keep its versions in
  step by hand.
- **Shared build config is convention plugins in `build-logic/`** (an included build):
  `cryon.kotlin` (Kotlin JVM, toolchain 25, mavenCentral, kotlin-stdlib) and `cryon.publish`
  (`cryon.kotlin` + `maven-publish`, local only). Modules apply `id("cryon.kotlin")` or
  `id("cryon.publish")` and add only what is theirs. `build-logic/settings.gradle.kts` re-creates the
  `libs` catalog from `../gradle/libs.versions.toml`; the catalog reaches precompiled scripts via
  `implementation(files(libs.javaClass.superclass.protectionDomain.codeSource.location))` plus
  `the<LibrariesForLibs>()`.
- Plugins that aren't in a convention plugin (shadow, run-paper, paperweight) are declared once in
  root `build.gradle.kts` via `alias(libs.plugins.…) apply false`; subprojects apply them without versions. In `:paper`
  the order matters, **paperweight before run-paper**, or run-paper's
  `afterEvaluate` dev-bundle resolution fails. `group`/`version` from `gradle.properties`.
  `plugin.yml` `${version}` filled by `processResources`. Don't hardcode.

---

## Module System

Features are **modules**, each a jar (own repo) loaded by the core. Framework types in `:common`
(`…common.module`); Paper base in `:paper-api`.

**Loader (`Cryon.kt` → `ModuleLoader`, `…cryon.module`):** on **load**, loads every jar in
`plugins/Cryon/api/` into **one shared `URLClassLoader`** (the contract layer), then loads each
`modules/*.jar` in its **own isolated `URLClassLoader`** parented to that shared loader (→ core →
Paper + `:common`/`:paper-api` + kotlin-stdlib). Discovers `Module`s via `ServiceLoader` and runs
`preLoadAll(context)`; then on **enable**, after the infrastructure is up, `loadAll(context)` →
`enableAll()` → `postLoadAll()`. Broken jar logged and skipped (caught as `Throwable`,
`ServiceConfigurationError` is an `Error`). Loaders closed on disable (modules before parent). Empty `api/` → shared
layer skipped.

**Cache copies:** each feature jar is copied into a private `plugins/Cryon/.module-cache/` and loaded
from the copy, so the original in `modules/` is never file-locked (matters on Windows) and can be deleted/replaced while
running, the basis for hot-swap. Closing a loader frees its file handle and
drops its lang bundle/listeners; reclaiming classes still depends on the module not leaking refs (usual reload caveat).
Cache wiped on boot and disable.

**Core types:**

- **`Module`** (`:common`). `id` + two-phase lifecycle: `onLoad(context)` (publish services),
  `onEnable()` (consume peers), `onDisable()`. `ServiceLoader`-discovered → needs **no-arg ctor** +
  `META-INF/services/com.tricrotism.cryon.common.module.Module` entry. Plus `preLoad(context)` and
  `postLoad()`. See **Pre-enable phase** and **Post-enable phase** below; almost nothing should
  override either.
- **`ModuleContext`** (`:common`). `logger` + `services`; Paper's `PaperModuleContext` adds `plugin`/`server`.
- **`ServiceRegistry`** (`:common`). Intertwine seam. `register<Api>(impl)` / `get<Api>()` /
  `find<Api>()` (KClass overloads exist; **always spell out the type argument on `register`** or the impl's concrete
  class becomes the key), keyed by interface. `ModuleContext.service<T>()`/
  `serviceOrNull<T>()` extensions cover code holding a context.
- **`PaperModule`** (`:paper-api`). Base exposing `plugin`/`server`/`services`/`logger`, plus
  `service<T>()`/`serviceOrNull<T>()` sugar for `services.get`/`find`; `listen(listener)`
  auto-unregisters on disable. Override `onLoad` (call `super` first), `onEnable`, `onDisable` (call `super`).

**Isolation → intertwine only through shared interfaces.** Feature jars can't see each other's
classes. Expose behaviour by registering an impl of an **API interface in a shared artifact** (`:common`,
`:paper-api`, or an `api/` contract jar). Never reference another feature's concrete classes.

**Cross-module contracts, the `api/` layer.** To expose an API to a feature in *another repo*, the
contract type must load from the **shared parent**, not be bundled in either jar (two copies = two
`Class` objects = `ClassCastException`). Ship the interfaces in a thin **`*-api` jar** in
`plugins/Cryon/api/`; both provider and consumer depend on it **`compileOnly`**. Provider
`services.register<FooService>(impl)` in `onLoad`; consumer `services.find<FooService>()`
in `onEnable` (**`find`, not `get`**, the other repo may be absent). Reference: `cryon-visibility-api`
consumed by `cryon-visibility`. Reserve `api/` for genuinely cross-repo contracts; a contract the core ships can live in
`:paper-api`.

**Order-independence:** every module's `onLoad` runs before any `onEnable`, so peer services are always available in
`onEnable`. No declared load order.

**Pre-enable phase (`preLoad`).** Both `onLoad` and `onEnable` run from the core's **`onEnable`**, so
by then every other plugin on the server has already enabled. That is too late for a third-party registry that seals
itself on enable. WorldGuard's flag registry is the motivating case: it locks at
the end of `WorldGuardPlugin.onEnable` and has no unregister at all. So `Module.preLoad(context)` runs
from the core's **`onLoad`**, before *any* plugin has enabled, and that is the only thing it is for.
Constraints, all deliberate:

- `context.services` is **empty**. Cryon's own infrastructure does not exist yet. Touch nothing but
  the platform and the registry you came for. Add a `softdepend` on that plugin in the core's
  `plugin.yml` so its classes are reachable, and guard on `Bukkit.getPluginManager().getPlugin(...)`
  so a missing soft dependency logs instead of throwing `NoClassDefFoundError`.
- It does **not** run on a runtime jar load (`/cryon load`, `scan`, `reload-api`, the watchers), by
  then the same registries are shut. A module that needs this phase must be present at boot to work at
  all, and should fail loudly rather than half-enforce when it is not.
- It does not touch `ModuleState`; a module that throws here is marked `FAILED` and never reaches
  `onLoad`.
- Whatever you register in a foreign registry, **do not subclass its types**. A module hot-swap would
  strand your classloader behind a reference you can never remove. Register instances of the *host
  plugin's* own classes, and re-fetch them by name in `onEnable`.

Reference implementation: `Cryon-WorldGuardAddon`.

**Post-enable phase (`postLoad`).** The mirror at the far end: `Module.postLoad()` runs once **every**
module has finished `onEnable`, so it can rely on peers being *live*, listeners registered, tasks running, where
`onEnable` can only rely on them having published services. No context parameter,
like `onEnable()`: `onLoad` has already run, so a module holds its own. Differences from `preLoad`,
also deliberate:

- It **does** run on the runtime paths (`/cryon load|enable|reload`, `reload-api`, the watchers), its
  precondition is trivially met there, so a hot-swapped module reaches the same state a booted one
  does. Callers enable a whole jar's modules *before* any of their `postLoad`s, via
  `ModuleManager.postLoad(id)` kept separate from `enable(id)`.
- A module that throws is logged and left **`ENABLED`**, not `FAILED`, `onEnable` already succeeded
  and its listeners are live, so `FAILED` ("left out of the live set") would be a lie.
- At boot `postLoadAll()` runs immediately after `enableAll()`, deliberately *ahead* of the lang seed, the boot command
  flush and `announceReady`, so postLoad contributions still reach the reference
  lang file and the command tree, and nothing is announced ready early.

**Runtime lifecycle.** `ModuleManager` tracks `ModuleState` (`REGISTERED`/`LOADED`/`ENABLED`/`DISABLED`/`FAILED`)
and supports `enable`/`disable`/`reload(id)` (re-enable reuses the load-time context), plus
`load(id, context)` and `unregister(id)` for single-module hot-swap churn. Surfaced via `/cryon
modules|info|enable|disable|reload <id>`. Registered into `ServiceRegistry` so a module reads its own
state via `PaperModule.isEnabled()`. **Main-thread only.**

**Failure isolation. A feature must never crash the server.** Every seam where the framework invokes feature code
catches **`Throwable`** (not just `Exception`, a stale/mislinked jar throws `Error`s
like `NoSuchMethodError`): module `preLoad`/`onLoad`/`onEnable`/`onDisable` (failure → `FAILED`, server
continues) and `postLoad` (failure → logged, module stays `ENABLED`), jar reads, command registration (the core's single
COMMANDS lifecycle handler flushes
every contribution and guards each one, since Paper rethrows lifecycle exceptions fatally; the live
runtime path in `CommandRegistry` is likewise guarded), `Events` handlers, and the watcher thread. A `FAILED` module is
in-memory only and
doesn't auto-retry; it clears on a successful `/cryon enable|reload <id>`, a reload of its jar, or
restart. The one thing outside our control is a feature that bypasses these helpers and registers its own raw Paper
lifecycle handler. Route command registration through `registerCommands`, and keep the
published `:paper-api` binary-compatible (`@JvmOverloads` on defaulted params) so old feature jars still link.

**Hot-swap (jar level).** `ModuleLoader` adds/removes whole jars at runtime: `/cryon load <jar>`
(load + enable a jar in `modules/`), `/cryon unload <id>` (disable + unregister every module in that jar, close its
loader; the jar file stays. Delete to remove permanently), `/cryon scan` (load newly
dropped jars). A hot-loaded jar's modules all `onLoad` before any `onEnable`. Unload is whole-jar
(can't partially close a loader). On unload the loader also calls `ServiceRegistry.unregisterByClassLoader`
to drop services the jar published (so a reload re-registers cleanly and peers can't resolve a
dead-loader instance). These call `Player.updateCommands()` to resync command trees.

**Remote modules, Maven delivery (`…common.module.remote` + `RemoteModuleConfig` in `:paper`).**
Optional (`remote.enabled`, default **false**), and off means nothing is parsed and no poller starts. A timer asks a
Maven repository for new feature jars and writes them into `modules/`. **That is the entire job.** It never loads
anything, because `modules.auto-reload` already decides that: with the watcher running a replaced jar hot-swaps exactly
as if an admin had dropped it in, and with the watcher off the build waits on disk for the next restart or an explicit
`/cryon load`. A second switch deciding when a *remote* update may apply would let remote builds do what local ones
could not, which is the surprise this design refuses.

**Maven has no branches, so a branch is spliced into the version**: `version: "1.0.0"` +
`branch: "main"` resolves to `1.0.0-main-SNAPSHOT`, which is what CI publishes, so moving a server onto a feature branch
is a one-word config edit rather than a second repository. A blank branch is plain `-SNAPSHOT`; a version with **no**
`-SNAPSHOT` anywhere is a **pin**, fetched once and never changed again, which is the right shape for production. Each
artifact lands on a **stable filename**
(`cryon-economy.jar`) so a new build *replaces* the old one; a versioned name would leave both in
`modules/` and the loader would discover the module twice.

Snapshot resolution reads `maven-metadata.xml` and prefers its `snapshotVersions` block, the only authoritative source
for the published filename (a repository may use unique timestamped names or a single overwritten `-SNAPSHOT` one, and
guessing is a 404). Downloads are staged to `.remote-tmp/`, verified against the `.sha1` published beside the jar, and
only then moved into place atomically, so a truncated transfer or a proxy error page can never land where the loader
will open it. Installed revisions are recorded in `plugins/Cryon/remote-modules.properties`, and the revision carries
the resolved version, so a branch switch re-downloads even when the new branch's newest build is older.

**Credentials are env-first and have no default**: `CRYON_MAVEN_<NAME>_USERNAME` /
`CRYON_MAVEN_<NAME>_PASSWORD` (repository name uppercased, non-alphanumerics to `_`) beat anything in
`config.yml`. No username resolved means no `Authorization` header at all, so a public repository works and a private
one 401s honestly instead of falling back to somebody else's account. The first poll is **delayed, not run at boot**:
blocking startup on a network fetch would make an unreachable repository an unbootable server. Surfaced as
`/cryon remote` (what is tracked, what has been fetched) and `/cryon remote check` (poll now).

**`api/` reload (cascade).** The `api/` contract layer parents every module loader, so it can't be
swapped alone (running modules stay linked to the old contract classes). `/cryon reload-api` does the
only coherent thing: unload **all** modules → close + reload the `api/` loader → reload every module
that was loaded, preserving the global two-phase order. Briefly takes all features down. Use it after
replacing an `api/` jar.

**Auto hot-reload (dev).** Two daemon `WatchService`s (`ModuleWatcher`), one on `modules/` (per-jar load/reload/unload
on file events) and one on `api/` (any change → a full `reload-api` cascade),
debounced and hopped to the main thread. **Config-gated** by `modules.auto-reload` in the core
`config.yml`, **defaulting to `!production`** (`production: true` by default): a `production: false`
dev server hot-reloads automatically, production doesn't, either overridable. The `/cryon
load|unload|scan|reload-api` commands work regardless.

**spark profiler attribution (`SparkSupport`).** spark attributes a sampled frame in two steps: a
`ClassFinder` resolves the frame's class *name* to a `Class`, then a `ClassSourceLookup` maps it to
a source by walking its classloader chain for a Bukkit/Paper plugin loader. Both are blind to our module
`URLClassLoader`s, Paper's finder (`Class.forName` + the Paper plugin loader group) can't
even *find* module classes, so their frames are dropped before the lookup runs. spark exposes no
API to extend either, so `SparkSupport.install` (run one tick after enable; every attribution call
resolves through `SparkPlatform.plugin` live at export time, so post-hoc splicing works)
reflectively swaps that field for a `Proxy` overriding three methods: `createClassFinder()` (real finder, then each
module loader. Read off spark's export thread via a concurrent map),
`createClassSourceLookup()` (module classes resolve via `ModuleLoader.sourceName`, everything else
falls through), and `getKnownSources()` (appends per-jar `SourceMetadata` so the viewer lists modules with versions.
Sources are named `Cryon-Module-<id>`, authored by the core plugin's
`plugin.yml` authors). It reaches the `SparkPlatform` through spark's **registered API**
(`SparkProvider.get()`, then the services registry as a fallback), so it works whether spark is a standalone plugin **or
bundled into Paper**. Paper 26.x ships spark as a bundled *library*
(`SparksFly` + `spark-paper.jar`), not a Bukkit plugin, so `getPlugin("spark")` is null. spark's
internal types (`SparkPlugin`/`ClassSourceLookup`/`ClassFinder`/`SourceMetadata`) are taken from
reflection metadata (field/method/generic types), never named, since Paper relocates spark's packages when it bundles
them (`me.lucko.spark.paper.…`). **Best-effort**, spark absent or its
internals shifted → a console warning and spark's default (module frames unattributed) stands.

**Command registration (the core-owned `CommandService`).** Paper only hands out its `Commands`
registrar inside a COMMANDS lifecycle handler, and only accepts that handler during the
bootstrap/enable window. So the core owns command registration through one shared service
(`…paper.api.command.CommandService`, impl `CommandRegistry` in `:paper`, registered into the
`ServiceRegistry` before any module loads). `PaperModule.registerCommands` no longer registers its
own lifecycle handler; it contributes its `@Command` handlers to the registry keyed by module id,
gated on `isEnabled`. At boot the core's **single** COMMANDS handler flushes every queued
contribution (core + all modules) through Paper's registrar. **A module loaded or reloaded at
runtime** (hot-swap, `/cryon load`, `reload-api`) is past that window, so the registry splices its
built Brigadier tree **straight into the live dispatcher** (reached via
`CraftServer.getServer().getCommands().getDispatcher()`) and removes stale nodes reflectively
(Brigadier exposes no public child removal), then `updateCommands()`s online players. So its commands
appear immediately, no restart. On unload, `ModuleLoader` calls `CommandService.unregister(id)` to
drop that jar's nodes. Both live paths are **best-effort**: if the server internals shift, runtime
(un)registration logs and no-ops, while boot-time registration (the common path, pure Paper API) is
unaffected. The registry also reflects each owner's commands (`describe(id)`), which is what `/cryon
info <id>` lists (name, aliases, permission, per-subcommand usages).

**Commands track module state.** A `@Command` class registered via `PaperModule.registerCommands(…)`
is gated on `isEnabled()` (passed as `available` to `AnnotationCommands`), so it can't run or
tab-complete while disabled and reappears on re-enable without re-registering (guard re-evaluated per
dispatch). `/cryon enable|disable|reload` calls `Player.updateCommands()`. A disabled command shows vanilla "unknown
command", the trade-off for gating at the Brigadier layer.

**Authoring a feature:** new repo → `compileOnly` published `:common` + `:paper-api` (+ Paper API) →
`class Foo : PaperModule()` no-arg ctor → add `META-INF/services` entry → build the thin jar → drop into `modules/`.

**Velocity loader (`:velocity`).** The proxy runs `CryonVelocityPlugin`, which builds its own
`ModuleManager` + `VelocityModuleContext` (adds `proxy`/`plugin`) over the same `:common` module
system, with a `VelocityModuleLoader` mirroring the Paper one (isolated `URLClassLoader` per jar,
shared `api/` parent, `ServiceLoader` discovery). Velocity's `@Inject` appears **only on the
`CryonVelocityPlugin` entrypoint**; feature modules stay no-arg-ctor `ServiceLoader`-discovered exactly
like Paper, so the DI and the module system don't collide. Config is read from `plugins/cryon/config.yml`
via (relocated) SnakeYAML with the same keys as Paper. It also **bootstraps the shared i18n on the
proxy** (`Messages.install` + a `plugins/cryon/lang/` override dir + the bundle in its own jar), so proxy commands
localize by client locale exactly like Paper. Never hardcode English in `:velocity`.
Runtime hot-swap parity (a Velocity watcher and `/cryon`-style commands) is the documented next step.
Velocity feature repos `compileOnly` `:common` +
`:velocity-api`, `class Foo : VelocityModule()` no-arg ctor, add the `META-INF/services` entry, and
drop the jar into the proxy's `plugins/cryon/modules/`.

**Geyser loader (`:geyser`), the third platform.** Bedrock players reach the network through a **standalone Geyser**:
its own process, not a plugin, which terminates Bedrock UDP and opens an ordinary Java connection to Velocity.
GeyserMC's own advice for a proxy network is the Geyser *proxy plugin*; this deploys the standalone because it scales
apart from the proxies, which is what
`deploy/` already assumes. Geyser hosts **extensions**, so `CryonGeyserExtension` is the twin of
`CryonVelocityPlugin`: same config keys, same infrastructure, same i18n bootstrap, and a
`GeyserModuleLoader` mirroring the proxy's. Extension id **`cryon`**, which fixes the data folder at
`extensions/cryon/` (read via `Extension.dataFolder()`, never a literal path). `PlayerRouter` and the handoff pause are
deliberately absent: Geyser is not what connects a player to a backend, and it is not a place a player can be.
`ServerRegistry` is read-only; Geyser registers no node of its own.

**Everything is set up on `GeyserPreInitializeEvent`, and that is load-bearing.**
`GeyserDefineCommandsEvent` fires from `CommandRegistry`'s constructor later in the same startup, so a module that
contributes a command must already be loaded and subscribed. `GeyserPostInitializeEvent`
would be too late for exactly the modules that most want it.

**The maintenance gate is the reason the module exists.** `MaintenanceGate` cancels
`SessionLoginEvent` with the message rendered to legacy, so a Bedrock player in maintenance gets a Bedrock disconnect
screen instead of travelling to the proxy for a Java-shaped kick. **Its bypass is the name allowlist only, which is a
real difference from Velocity rather than an approximation:**
`SessionLoginEvent` fires before the player exists to any permission backend, so
`cryon.maintenance.bypass` cannot be evaluated there. The allowlist needs no backend. The permission case still works
one step later, because a player Geyser admits is a player the proxy runs
`MaintenanceListener` on. Geyser is the earlier, friendlier half; the proxy stays the backstop.

**The Bedrock MOTD is not a port of `Motd`.** `BedrockMotd` reads the same `motd.*` block and the same MiniMessage
source, and drops the alignment: the proxy measures segments with `FontWidth` to anchor left/center/right in the Java
server list, while a Bedrock ping is two short plain strings with no font metrics, so padding would only insert runs of
spaces. `motd.width` is unread here.

**Two platform facts worth knowing before writing a Geyser command.** `CommandSource.sendMessage`
takes a **plain String**, so `:geyser-api` owns the one `Component` to legacy seam and everything above it stays
Components. And Geyser namespaces every extension command under the extension root, so the proxy's
`/maintenance on <msg>` is **`/cryon maintenance on <msg>`** here. Same handlers, same permissions, different root.
`Command.Builder.subCommands(...)` is `@Deprecated(forRemoval = true)`
on 2.11.2 and documented as having no effect, so there is **no tab completion** for extension commands at all;
`AnnotationCommands` answers a mismatch with a filtered candidate list instead.

Geyser feature repos `compileOnly` `:common` + `:geyser-api`, `class Foo : GeyserModule()` no-arg ctor, add the
`META-INF/services` entry, and drop the jar into `extensions/cryon/modules/`.

**Velocity commands, annotation framework over Velocity Brigadier (`…velocity.api.command`).** A
proxy-side twin of the Paper `AnnotationCommands`: the **same** `@Command`/`@Subcommand`/`@Permission`/
`@Arg`/`@Greedy` model, reflected onto Velocity's native Brigadier (`BrigadierCommand`, source
`CommandSource`) instead of Paper's. Register with `AnnotationCommands.register(proxy.commandManager,
handler)`. The annotations are **duplicated**, not shared, because each platform's Brigadier is a
different type (`CommandSourceStack` vs `CommandSource`) and `:paper-api` must stay Bukkit-free for
`:velocity`. Keep the two models in step by hand. An optional trailing arg is two same-path methods
(one with the `@Arg`, one without); Brigadier merges the same-named literals. Core proxy commands
(`/maintenance`, `/motd`) use this; **don't hand-roll `SimpleCommand` `when(args)` parsing.**

**MOTD (`…velocity.motd`).** The server-list MOTD, set on `ProxyPingEvent` when maintenance is off.
Two lines (top/bottom), each three MiniMessage segments anchored **left/center/right**: `Motd` measures
each segment's pixel width (`FontWidth`, the vanilla default-font table) and pads with spaces (4px
granularity) so center is centered and right ends at `motd.width`. Config `motd.*` in the proxy
`config.yml`; `/motd reload` (perm `cryon.motd`) re-reads it live. `MotdListener` runs at
`PostOrder.EARLY` and skips when maintenance is on; the maintenance ping (LATE) overrides it. Bold text
isn't width-counted, so keep segments mostly unbolded for tight alignment.

---

## Command Surface

Two rules cover everything the core exposes to a human.

**Every menu action has a command, and neither is the primary one.** `AdminMenu` (`…cryon.menu`) is
`/cryon` as a menu: modules, feature flags and the network summary, an InvUI window on Java and Cumulus forms on
Bedrock. `commands.menu` in the core `config.yml` (default `true`) decides which of the two a bare `/cryon` gives a
*player*; `/cryon menu` and `/cryon help` reach the other one whichever way it is set, and the console always gets the
help, having nowhere to put a window. A menu answers "which of these is off?" faster because the state is the layout; a
command wins once you know the id, and it is the only one that works from a script. Menu pages are read at open time and
an action re-opens rather than editing the clicked slot, so a button can never describe a module that has since failed.
The core owns its sessions and closes them on disable.

**An unknown id is answered with the nearest real one, not just a rejection.**
`CommandUi.unknown(sender, noun, input, candidates) { retry }` (`…cryon.command`) prints the rejection plus a clickable
correction when something is close enough to be worth offering: a prefix match wins outright, otherwise the edit
distance has to be within a third of the input. Nothing is offered when nothing is close, because a wrong guess invites
a click that fails twice. Route every
`no such module / jar / currency / player / locale` path through it. `CommandUi` also owns the shared
`button`/`suggestButton`/`usage` look, so clickable output is the same wherever it appears.

`/cryon help [page]` is grouped by what an operator is trying to do (Modules, Jars, Flags, Server)
and paginated, Brigadier already tab-completes the tree, so what help adds is which commands belong together and what
each is for. Add an entry to `ModuleCommands.HELP` in the same pass as a new subcommand.

---

## Utilities

Shared helpers, **check these before writing your own.** Reach a peer feature's behaviour through `ServiceRegistry`,
not these.

**Numbers (`…common.number`/`…extension`):**

- `PackedDecimal` is the effectively-unbounded scaling value for currencies/idle math: a base-10
  number (14-digit signed mantissa + power-of-ten exponent) packed into a single `Long`, so as a
  `@JvmInline value class` it is **zero-allocation** and ~5x the throughput of a boxed mantissa/exponent
  class. ~14 significant figures, range ~10^±32767 (the exponent saturates). Operators,
  `pow`/`sqrt`/`cbrt`/`log*`, `of(...)`/`tenPow(...)`, `magnitude` (decimal order of magnitude). Use it
  (not `BigDecimal`) for anything past `~1e15` on a hot path.
- `LongUtils`, `BigDecimalUtils` (`magnitude`/`log10` past `Double` range), `NumberUtils`
  (`formatBalance`/`formatCommas`/`roman`/`parseBalance`, thread-safe), primitive extensions (`1500L.formatBalance()`,
  `5.pd`, `90L.formatDuration()` → compact two-unit `"1m 30s"`, `90L.formatDurationFull()` → all units
  `"1m 30s"`/`"1d 2h 3m 4s"` like `%statistic_time_played%`, both from seconds).

**Text (`…common.text`):**

- `Mini`. Non-strict MiniMessage, palette-preloaded, ~15s Caffeine cache, legacy interop. Use
  `Mini.format(...)`/`"…".mm()`, **never `MiniMessage.miniMessage()`**.
- `CryonPalette`. Named colours as `TextColor`s **and** tags (`<off_white>`, semantic `<error>`/`<success>`/…). Tune
  hexes / extend `RESOLVER` here.
- `CommonMessages`. Acks (`error`/`success`/`info`/`warn`, `notOnline`, `notEnoughCurrency`,
  `noPermission`, …) returning `Component`s. All share **one** lang-driven base prefix
  (`cryon.common.prefix`, resolved in the default locale, **blank by default** so there is no glyph);
  it is not per-type, and `error`/`success`/… are untyped aliases that render identically. Set `cryon.common.prefix` in
  a
  `lang/<locale>.properties` to give every ack a shared prefix. Canned bodies localized via `Messages`
  by `cryon.common.*` key; Paper extensions (`player.sendNoPermission()`, …) pass `resolvedLocale()`.

**Collections & randomness (`…common.bucket`/`…common.random`):**

- `Bucket<E>` (`Buckets.concurrent`/`hashSet`, `PartitioningStrategies.lowestSize`/`random`). A
  `MutableSet` that also splits its elements across a fixed number of partitions, so per-element work
  can be spread over ticks: `bucket.cycle().next()` each tick walks one partition (`Cycle` is an
  atomic rotating cursor). Used by `DefaultInventorySearch` to sweep online inventories a slice per
  tick, each read on the player's own entity scheduler (Folia-safe).
- `RandomSelector<E>` (`RandomSelector.uniform`/`weighted`, `Weighted`/`Weigher`/`WeightedObject`).
  uniform or weighted picks; weighted builds Vose's alias table (O(n) setup, O(1) `pick`). Immutable
  and thread-safe once built; `pick()` uses `ThreadLocalRandom`, `stream()` is an infinite sequence.

**i18n (`…common.locale`): everything user-facing is localizable.** `MessageService` resolves
`(locale, key) → Component` across `MessageSource`s with a fallback chain, `renderPlural`, hot
`reload()`. `Messages` is the static facade. **Auto-scanned. Don't register by hand:** a jar's
`lang/<locale>.properties` registers on load; `plugins/Cryon/lang/` (admin override) registers first
and wins. Send via `messages.send(player, key, …)` (localized, wrapped in the shared base prefix) or
`messages.render(player, key, …)` for a raw `Component`. Missing keys render `⟨key⟩`. **On boot the core seeds the admin
override file** for the default locale
(`exportMissing`, after every module loads): keys the core + module bundles define but
`plugins/Cryon/lang/en_US.properties` lacks are appended to it, so admins get a complete editable reference and new keys
surface there automatically. **Existing entries are never rewritten**, an
admin override always survives a restart.

**Module files (`PaperModule.dataFolder` / `config()`).** A module's own directory is
`plugins/Cryon/data/<id>/`, created on first use, and `config(name = "config.yml")` extracts the copy bundled in **that
module's jar** on first run and returns a fresh read each call (reload = call again and swap your field; there is no
hidden cached instance). Never `plugin.dataFolder`. That is the *core's* folder, one flat namespace where two modules
wanting a `config.yml` silently share one file. It sits beside `modules/` rather than inside it, so clearing the jar
drop-folder cannot take a module's saved state with it. The default is read straight from the jar's code source, **not**
through
`getResourceAsStream`: that delegates parent-first, and the core jar also has a `config.yml`, so a module asking for its
own default would silently be handed the core's. `VelocityModule.dataFolder` is the proxy twin, under
`plugins/cryon/data/<id>/`.

**Items (`…paper.api.item`/`…extension`):** `ItemBuilder`. Name/lore (auto `<!i>`, palette-parsed),
flags, glow, `enchant`, attributes, PDC `tag`s, `meta {}`. Extensions: `Material.toItem()`,
`ItemStack.toBuilder()`/`modify {}`, `get/set/has/removeTag` (PDC), `isEmpty()`, `withAmount()`. The PDC helpers (and
`ItemBuilder.tag`/`InventorySearch.byTag`) have reified forms that infer the
`PersistentDataType`, `item.getTag<String>(key)`, `item.setTag(key, 5)`, `.tag(key, 3)`, covering the primitive-backed
types plus `UUID` (16-byte array) and `PackedDecimal` (its packed long). Lists and custom types still pass the
`PersistentDataType` explicitly (erasure hides a list's element type).

**Menus, InvUI (`xyz.xenondevs.invui`, shaded into `:paper`).** `:paper` bundles `invui:2.2.0`
**unrelocated**, exactly like kotlin-stdlib, so module classloaders resolve `xyz.xenondevs.invui.*`
through the core. `InvUI.setPlugin` runs **once**, in `Cryon.initMenus`. It throws if called twice, so
a module must never call it. The core also routes InvUI's exception handler into the Cryon logger, so a
module's broken menu doesn't read as a core fault.

Features `compileOnly("xyz.xenondevs.invui:invui:2.2.0")` (repo `https://repo.xenondevs.xyz/releases`)
and **never shade it**, two copies across the loader boundary is the usual `ClassCastException`.

- **Build stacks with Cryon's `ItemBuilder`**, then `Item.builder().setItemProvider(stack)`. InvUI 2.x
  takes Adventure `Component`s directly (`Window.Builder.setTitle(Component)`), so nothing needs
  wrapping.
- **`MenuPalette` registers global ingredients**, so a `Structure` string reads as the menu's picture without per-menu
  boilerplate: a legacy colour code (`0`-`f`) is a hidden-tooltip filler pane of that
  colour and `.` is an empty slot. Only characters carrying real content need `addIngredient`.
- **`ConfirmMenu.open(player, bedrock, …)`** is the shared yes/no dialog. It picks a Cumulus modal for
  Bedrock and an InvUI window for Java, and treats *closing* as declining, so its callback always fires exactly once.
  **Callable from any thread**. It hops to the player, and the callback comes back on the
  player's own scheduler a tick later rather than in the caller's frame. That deferral is load-bearing,
  not cosmetic: the decline path answers from inside InvUI's close handler, and `AbstractWindow.open()`
  hard-throws if you open a window there, so a "cancel goes back to the previous menu" callback would
  otherwise blow up.
- **A module must close its own windows in `onDisable`.** InvUI holds strong references to your `Item`s,
  so a hot-unload with a menu open leaks the module classloader and leaves clicks dispatching into
  unloaded code. Track them and close them all.
- **A branch's entries come from a `MenuContent`, and only the visible window is ever fetched.**
  `branch { }` wraps a fixed list (right for a settings screen); a shop, auction house or leaderboard passes its own and
  pages in SQL rather than materializing every row. It is a single suspending
  `page(viewer, offset, limit)`. **there is deliberately no `size`**: the menu asks for one entry more than fits and
  uses the overflow to light the next arrow, so a source never owes a `COUNT`.
  `MenuTree.open(player, root, scope)` takes the module's scope, since a page may be a query.

InvUI 2.x is a single mojang-mapped jar with no per-version NMS bridge, so, unlike the 1.x line, it
needs no relocation and no bridge selection. It does track the Minecraft version closely: verify after
any Paper bump that the shaded jar still has zero `craftbukkit/v1*` references and that InvUI's NMS
members still resolve against the new dev bundle.

**Dialogs cover four shapes.** `text` (one field), `form` (several, build inputs with
`textInput`/`boolInput`/`numberInput`/`optionInput` and read them back by key), `confirm` (yes/no),
`notice` (acknowledge only, its own call because a notice returning `Boolean` invites branching on an answer nobody
asked for), and `choose(options)` which returns the chosen **value**, not an index into a list the caller then has to
keep in step. All share one implementation: the type shapes differ only in how many resolving buttons they have, so the
latch, the quit listener and the cancellation handling are written once.

**Bedrock (`…paper.api.bedrock`).** `BedrockService`: `isBedrock`, `inputMode`, and `sendSimpleForm`/
`sendModalForm`/`sendCustomForm`. **Always registered** (`services.get<BedrockService>()`, or
`PaperModule.bedrock`): with Floodgate installed it is the real bridge, without it every player reports
as Java and every send is a no-op, so features never branch on whether Geyser exists. All Floodgate and
Cumulus types are confined to `:paper`'s `FloodgateBedrockService`, which is only classloaded after the plugin-presence
check, the same discipline `PapiBridge` uses for PlaceholderAPI.

**Why forms rather than the translated container:** Geyser *will* render an InvUI window, but everything the layout
leans on, filler panes, control rows, hover lore, is meaningless on a touchscreen. A form
is the native idiom and scrolls, so it needs no paging. A menu worth building is worth giving a form
fallback; `ConfirmMenu` is the reference. Three behaviours the bridge handles for you: a form sent while
the player has a real container open is silently dropped, so it closes and delays first; Floodgate
delivers responses on its own thread, so callbacks are hopped to the player's scheduler and may touch
the Bukkit API directly; and **exactly one callback fires per send**.

That last one is not free, and it is why every send goes through a private `FormSession`. Floodgate
reports a tap and a dismissal, but says nothing when the send never lands (the player leaves inside the
5-tick close-and-delay), when the connection dies with the form on screen, or when a later form replaces this one, and
`player.openInventory` cannot see a Cumulus form, so nothing else can notice either. The
session supplies the missing outcomes (a `PlayerQuitEvent` at `MONITOR` is the only reliable
death signal) behind an `AtomicBoolean` latch, so duplicates collapse and anything escrowed behind a
callback can't hang. `sendSimpleForm`/`sendCustomForm` therefore take an `onClose`, and **a send returns true for any
Bedrock player**. Delivery is reported through the callback, never the boolean, so a
caller can't fall back to a Java menu for a client that can't read one.

**A second form replaces the first and resolves it as dismissed**, deliberately matching what InvUI does
on Java, where `WindowManager` is one-window-per-player and a new window evicts the old (firing its close
handler). One rule on both platforms. Note the consequence on Java: opening any menu over a pending
`ConfirmMenu` answers it `false`. Nothing tracks menu state beyond that. There is no per-player UI
registry, and a menu opening over a *foreign* container (a chest, another plugin's GUI) is unguarded on
both sides.

**Bedrock on the proxy, identity only (`…velocity.api.bedrock` + `…velocity.bedrock`).** The twin of Paper's
`BedrockService`, and deliberately the narrow half of it. A Bedrock player reaches the network through a standalone
Geyser that speaks Java to Velocity, so the proxy sees an ordinary connection whose `username` carries Floodgate's
prefix and whose `uniqueId` is Floodgate-generated unless the account is linked. `BedrockService` answers what that
connection actually is: `isBedrock(player)`,
`inputMode(player)` (a proxy-side copy of `BedrockInput`), `xuid`, `gamertag` (the real Bedrock name, unprefixed and
unshortened), `linkedJavaId` (non-null exactly when the player is linked, which is the fact worth asking for), and
`usernamePrefix`. **Always registered** (`services.get<BedrockService>()`, or `VelocityModule.bedrock`): without
Floodgate every player reports as Java and every accessor answers null or `UNKNOWN`, so proxy features never branch on
whether Geyser exists, the same rule
`Messenger`/`KeyValueStore` follow. All Floodgate types are confined to
`FloodgateBedrockService`, classloaded only after `proxy.pluginManager.getPlugin("floodgate")`, and
`floodgate` is an optional dependency in `velocity-plugin.json` so its API holder is filled before ours runs.

**No forms here, on purpose.** The Cumulus machinery on Paper exists because a chest menu is unreadable on a
touchscreen; the proxy has no inventories and nothing on it asks a player a question, so a form layer would be
speculative. The enum and the interface are **duplicated** from
`:paper-api` rather than shared, exactly like the `@Command` model, because `:paper-api` carries Bukkit types and
`:velocity` must stay Bukkit-free. Keep the two copies in step by hand. One Floodgate quirk carries over: `InputMode`
and `LinkedPlayer` ship in Floodgate's core rather than its
`api` artifact, so the input mode is read reflectively by name and the linked id is taken as
`isLinked() ? correctUniqueId : null` instead of through `LinkedPlayer`.

**Cooldowns (`…common.cooldown`).** `CooldownService` (impl `MemoryCooldowns`), always registered. **
`trigger(subject, id, duration)` is the whole API**. It decides *and* records in one atomic step, because
`remaining()` then `mark()` is a check-then-act race that on Folia fires twice for one player. `remaining` exists to
*describe* a refusal, never to make one, the same split currency draws between `withdraw` and `cachedBalance`. Paper
sugar in `…paper.api.extension`: `cooldowns.guard(player, "kit.daily", 24.hours)` claims it or sends the localized
"you must wait" and returns false. In-process and non-persistent: right for pacing a button, wrong for a daily reward
gate. Back those with stored state.

**Requirements (`…common.requirement`).** `Requirement<S>`, a `fun interface` with short-circuiting `and`/`or`/`not`
and `all`/`any`. Composition allocates once at declaration; `test` allocates nothing, which matters because the call
sites are warm (a menu re-resolves every node's visibility per draw). Implementations must be pure and cheap. Resolve
anything needing I/O before building the tree.

**Distributed locks (`…common.lock`).** `DistributedLock` (impl `StoreDistributedLock` over `KeyValueStore`), always
registered. **Read its KDoc before using it**. Almost nothing should. One key decided by its own value is a CAS
(`withdraw`, `setIfAbsent`, `delete`'s boolean, `tryHold`); one process is `AccountLocks`/`Mutex`; this is only for a
sequence spanning *several* keys that must not interleave across nodes. It is a lease, not a guarantee: it renews at
half the TTL and **cancels the body** if a renewal is refused (`LockLostException`), releases via an owner-token
compare-and-delete so a lapsed holder cannot release somebody else's claim, and queues same-node contenders on a local
mutex so they never round-trip Redis. Keep the work under it idempotent.

**Dialogs (`…paper.api.dialog`).** `Dialogs.text/confirm/form`, native client dialogs, the way to ask a player for a
*value*. They **suspend and return the answer** (`val name = Dialogs.text(player, title, label) ?: return`), so there is
no callback to thread state through. Exactly one outcome always fires: button, disconnect, or caller cancellation, all
collapsed onto one latch, the same rule `FormSession` follows, for the same reason: anything escrowed behind an
unanswered prompt hangs. Escape is disabled and cancelling is a button, deliberately: Paper fires no event for a
dismissed dialog, so an escape-closable one is indistinguishable from a player still typing. Prefer these over an anvil
rename (durability cost, one field) or a chat prompt (hijacks chat, loses the message).

**Bars (`…paper.api.bar`).** Two APIs, installed by the core, for the two pieces of screen furniture every feature
eventually wants.

- **`BossBars.create(name, progress, color, overlay)`** returns an `AutoCloseable` bar, so
  `track(BossBars.create(…))` and it dies with the module. **This matters more than it looks:** a bar lives on *other
  people's connections*, so one left open through a hot-unload keeps rendering with nothing owning it and a reload
  stacks a second beside it. Mutating a bar updates every viewer at once, which is the reason to share one across a
  group rather than give each player their own, a raid timer is one fact, so it should be one packet per change rather
  than one per player per change. The core keeps a registry solely to prune a disconnecting player from every bar's
  viewer set, which Adventure does not do on this side.
- **`ActionBars.send(player, message, duration, priority, key)`**. Not a wrapper over
  `sendActionBar`, an arbiter. The action bar fades after ~3s so anything persistent must be re-sent, and there is only
  *one* of it, so two features both sending flicker at whatever rate they tick with the winner decided by scheduling
  order. This owns the re-send and settles the contest by priority:
  a transient alert interrupts a persistent readout and the readout returns when the alert expires.
  `key` makes an update an update, re-sending under the same key replaces rather than accumulates. Costs nothing when
  unused: the ticker walks only players who have an entry.

**Scheduling (`…paper.api.scheduler`):** `Schedulers`, `global`/`region(loc)`/`entity(e)`/`async`, each with `*Later`/
`*Timer`. Pick the scope owning the data; no Bukkit API in `async`. For suspending code use
`CryonDispatchers` instead (see *Coroutines*).

**Events (`…paper.api.event`):** `Events.subscribe<Type>(priority)` (or
`subscribe(Type::class.java, priority)`)`.filter{…}.handler{…}` → cancellable `Subscription`; `expireAfter(n)`
self-unregisters. Handler
exceptions logged, not propagated.

**Packets. PacketEvents (`…paper.api.packet`).** `Packets` is the `Events` builder's twin one layer down, for what
Bukkit never surfaces. `Packets.onReceive(type…)` (client → server) and
`Packets.onSend(type…)` (server → client) take `PacketType.Play.…` constants, then
`.filter{…}.priority(…).expireAfter(n).handler{…}` → a `PacketSubscription` that is `AutoCloseable`, so it goes straight
into `track(…)`. Cancel or rewrite in the handler (`event.isCancelled = true`); send with the
`Player.sendPacket(wrapper)` extension.

- **Handlers run on a Netty I/O thread. No Bukkit API, on Paper or Folia.** No entities, inventories, or `sendMessage`.
  Read what you need off the event, then `Schedulers.entity(player) { … }`. Handlers are deliberately *not* hopped for
  you: cancellation has to be decided before the packet moves on, and a scheduled handler always runs too late. Handler
  exceptions are logged, never propagated, one escaping onto a Netty thread can drop the connection.
- `:paper` shades **PacketEvents unrelocated**, exactly like InvUI and kotlin-stdlib, so module classloaders resolve
  `com.github.retrooper.*` through the core. Features
  `compileOnly("com.github.retrooper:packetevents-spigot:2.13.0")` (repo
  `https://repo.codemc.io/repository/maven-releases/`) and **never shade it**. The consequence of unrelocated shading:
  **do not also install the standalone PacketEvents plugin**, two copies both inject the pipeline.
- The core owns the lifecycle in `Cryon.initPackets` (`setAPI` + `load()` in `onLoad`, `init()` in
  `onEnable` ahead of the modules that subscribe, `terminate()` after they disable). Its API is a static singleton, so
  **a module must never call `setAPI`/`load`/`init`**, the same rule as
  `InvUI.setPlugin`. Best-effort like spark: a failure logs and leaves `Packets.isReady` false rather than taking the
  server down.
- One PacketEvents listener is registered per subscription (mirroring `Events`), and every listener is walked for every
  packet. Fine for the handful of subscriptions a feature set needs; the ceiling and the upgrade path (one shared
  listener per priority, dispatching through a type map) are marked in
  `Packets.kt`. Version pinned in `libs.versions.toml`; 2.13.0 is the first release supporting 26.2.

**PlaceholderAPI (`…paper.api.placeholder`, bridge in `…cryon.papi`).** Optional integration, structured
like the module system itself: the **core** owns the single PAPI dependency and each module gets its **own**
`%<identifier>_…%` namespace without ever touching PAPI classes (they can't, isolated
classloaders). A module implements `PlaceholderProvider` (`identifier` + `onRequest(player, params)`)
and publishes it with **`PaperModule.registerPlaceholders(provider)`** in `onEnable` (auto-unregistered
on disable). The core `PlaceholderService` impl (`PapiBridge`) wraps each provider in a `CryonExpansion`
(`PlaceholderExpansion`, `persist()=true`, provider throws → swallowed so a feature bug can't break PAPI
server-wide) and registers it, keyed by the owning module id so **`/cryon info <id>` lists that module's
`%<namespace>_…%`** alongside its commands. **Best-effort like spark:** PAPI is a `softdepend` +
`compileOnly`; absent → the namespace is still recorded (so info stays honest) but no expansion installs, and features
never branch on it. `onRequest` runs on PAPI's thread (maybe async, maybe hot). Keep it
cheap, thread-safe, no Bukkit API off-main. Built-in namespace `%cryon_…%` (`CorePlaceholders`):
`server`/`node`/`expect`/`max_players` off the immutable `NodeIdentity`.

**Commands, annotation framework over Paper Brigadier (`…paper.api.command`).** **Cloud is broken on
26.2** (cloud-bukkit's `ItemStackParser` reflects a missing method). Use the `@Command` layer
registered via `LifecycleEvents.COMMANDS`. **Never `plugin.yml commands:` / `CommandMap` / `Commands.create()` / Cloud.
**

```kotlin
@Command("cryon", "Module manager")
@Permission("cryon.admin")
class ModuleCommands(private val modules: ModuleManager) {
    @Subcommand
    fun overview(sender: CommandSender) = list(sender)
    @Subcommand("enable")
    fun enable(sender: CommandSender, @Arg("id", suggests = "ids") id: String) {
        …
    }
    fun ids(): Collection<String> = modules.ids()   // suggester
}
```

`@Command`/`@Subcommand`/`@Permission`/`@Arg(name, suggests)`/`@Greedy`. `CommandSender` injected;
`@Arg` types `String`/`Int`/`Boolean`. Built-ins: `/cryon` (`cryon.admin`), `/language` (`/lang`).
**Feature modules register via `PaperModule.registerCommands(…)` in `onLoad`** (gated on `isEnabled`);
the core contributes its own commands the same way. Both go through the core-owned `CommandService`,
which flushes at boot and splices runtime-loaded modules into the live dispatcher (see *Command
registration* above). Registering from `onEnable` instead of `onLoad` double-contributes on every
reload, so always register in `onLoad`.

**Shared command roots. `registerBranchCommands`.** `registerCommands` gives an owner *exclusive*
title to a root literal: the registry drops whatever held that name, and `unregister` takes the whole root back. Right
for a module's own command, wrong for a namespace several modules share, the second
module to claim it silently evicts the first, with no error and no log line. For those, use
**`PaperModule.registerBranchCommands(…)`** (→ `CommandService.registerBranch`): the `@Command` name
picks a *shared* root, the `@Subcommand` methods become branches of it, and the root is created on first use and dropped
with its last branch. `/int` is the standing example, `@Command("int")` +
`@Subcommand("drills", "give")` in Cryon-Drills contributes `/int drills give <ign> <type>` without
touching anyone else's branch. Two consequences of the root being co-owned: a **root `@Subcommand`
(empty path) and any aliases are ignored**, since neither belongs to a single contributor. Permission
still comes from each handler's own `@Permission`. Same `onLoad` rule as `registerCommands`.

**Schema migrations (`…common.data.SchemaMigrator`).** `database.migrate(namespace, migrations, logger)`:
forward-only, one shared `cryon_schema_version` table keyed by namespace, so a new module is a row rather than a
schema change of its own. The whole run sits in one transaction holding
`SELECT … FOR UPDATE` on that namespace's row, so N nodes booting together produce one migration and N-1 no-ops. **No
down migrations**: a rollback that discards columns is a data-loss decision nobody should make by editing a list. Fix a
bad step by adding another. **One caveat:** MySQL commits implicitly on DDL, so there a create/alter cannot roll back
and does not hold its lock for the rest of the transaction; Postgres and H2 do transactional DDL and behave as
described. Keep each migration to one coherent change so the MySQL failure mode stays diagnosable. Reference:
`cryon-core-servers` in
`SharedServerRegistry`, which renames a table and carries its rows over.

**Currency (`…common.currency`).** The ledger. `CurrencyService` (impl `Currencies`) holds any number of currencies,
each `SERVER`- or `GLOBAL`-scoped, in one `cryon_currency` table keyed by `(scope, currency, uuid)`, so a new currency
is a `register(Currency(...))` call in `onEnable`, never a migration. Without SQL it runs in memory and says so at boot.

**Off by default, and genuinely optional like `Database`. Resolve it with `find<CurrencyService>()` and handle null.**
`currency.enabled` in the core `config.yml` (default **`false`**) decides whether the service is registered at all; off,
the `/balance`, `/pay` and `/currency` commands are not contributed either. Only the servers running an economy turn it
on, and the rows stay untouched while it is off. A feature that hard-requires money should say so in its own log line
and disable its slice, not `get` and take the server down.

Four rules, and the first is the one that matters:

- **The mutation is the answer.** `withdraw` is a compare-and-set with a retry, not a balance read followed by a
  subtract: it writes only if the stored value has not moved. **Gate the reward on the returned `Boolean` and on nothing
  else.** `transfer` moves both sides in one transaction (`COMPLETED`/`INSUFFICIENT`/`FAILED`, branch on all three;
  "you were short" and "the store is down" are different facts). Same-process callers are serialised per account by
  `AccountLocks`, which chains futures instead of holding a monitor across I/O, so nothing parks a region thread.
- **`cachedBalance` is display only.** Synchronous and safe on a tick thread, for HUDs, scoreboards and placeholders;
  null means "not known here", never zero. Deciding anything with it reintroduces exactly the race `withdraw` removes.
  The authoritative read is `balance`/`balances`, and it is async.
- **The ledger is exact; only the presentation is packed.** The API speaks `PackedDecimal` (14 significant figures)
  because that is the project number type, but the stored value and the arithmetic are `BigDecimal`. A 100 deposit onto
  a 1e20 balance would otherwise vanish and the matching purchase be free. Amounts, displays and multipliers round; the
  running total never does. Prices set more than ~14 orders of magnitude below a balance still charge nothing. That is
  inherent to the number type.
- **A write broadcasts.** Every mutation publishes the touched account so other nodes drop their cached copy, and fires
  a `CurrencyChange` (`before`/`after`/exact `delta`/reason) to `onChange` listeners, which is the hook for audit logs,
  quests and achievements.

Core commands: `/balance [player]` (`cryon.currency.balance.others`), `/pay <player> <currency> <amount>`
(`cryon.currency.pay`), `/currency list|top|give|take|set` (`cryon.currency.admin`). Leaderboards are a cached snapshot
refreshed by an async timer (`currency.leaderboard-refresh-seconds`, default 300) and read synchronously.

**Storage + transport (`…common.data`/`…common.net`).**

- `Database` (`SqlDatabase`, HikariCP). Async SQL: `query`/`update`/`transaction` are `suspend` and run on the query
  pool. No ORM. The `transaction` body is deliberately **not** suspending: a suspension inside it would let the
  coroutine resume on another thread while the connection and its row locks stayed bound to the first. Genuinely
  optional, `find<Database>()`,
  may be null. Client libs load at runtime via `plugin.yml` `libraries:` (Velocity shades them). Backend is
  `database.type` = `postgresql` (default), `mysql`, or embedded `h2` (a local file, host/port ignored, zero-setup but
  not shared across processes), keyed by the `SqlDialect` enum.
  **Build one with `SqlDatabase.connect(config, logger)`, never the constructor:** it creates the
  database when that is the only thing wrong, so a fresh deployment does not come up with no flags,
  no locale store and no metrics over a missing `CREATE DATABASE`. Postgres connects to its
  `postgres` database to issue the DDL (it has no `IF NOT EXISTS` here); MySQL uses
  `CREATE DATABASE IF NOT EXISTS`; H2 needs nothing, its file is made on first connect. Narrow by design,
  `SqlDialect.isMissingDatabase` matches only Postgres `3D000` and MySQL `1049`, so a
  refused connection, a bad password or a missing `CREATEDB` grant still fail with their own error
  instead of being papered over. One retry, then the original exception. The name is validated by
  `SqlDialect.identifier` before it is concatenated into DDL, because DDL takes no bind parameters. **Write upserts
  through `database.upsert(table, keys, columns, …params)` / `insertIfAbsent(…)`, never by hand**, params bind in
  `columns` order. It is the one statement that is *not* portable: Postgres spells it `ON CONFLICT`, MySQL
  `ON DUPLICATE KEY UPDATE`, and H2 accepts neither in any mode (`MODE=PostgreSQL` does not extend this far, its upsert
  is `MERGE … KEY`, and leaving a row alone needs `MERGE … USING … WHEN NOT MATCHED`). So a hand-written upsert works on
  exactly the backend it was written against and throws a syntax error on the other two, at runtime, inside a
  fire-and-forget future with nothing waiting to notice. `SqlDialect.upsert`/`insertIfAbsent` own the three spellings.
- **`Repository<T>` (`SqlRepository`). The write-behind keyed store**, for the shape five features had each hand-rolled:
  a map, a dirty flag, a periodic save, a load on join. `cached(id)` is synchronous and tick-safe (null means *not
  loaded*, never *absent*, the same distinction
  `cachedBalance` draws); `stage(id, value)` updates memory and marks it; `flush()` writes everything staged in **one
  transaction and one batched statement per operation** (`SqlSession.batch`). **Single-owner only**. Writes are
  last-write-wins, which is correct precisely because
  `PlayerHandoff` guarantees one server owns a player's state at a time. Anything several servers write at once wants
  `CurrencyService`'s compare-and-set instead. The `version` column exists to *detect* a violation (a missed guard is
  logged as a handoff bug), not to merge one. The feature still owns its table: create it in your own `migrate` with
  `Repository.BASE_COLUMNS_DDL`, and describe the columns with a `RowCodec<T>`. It is not an ORM and should not grow
  into one.
- `Messenger`. `publish`/`subscribe` + `request`/`handle`. String payloads. **Always registered**
  (`get<Messenger>()`): `RedisMessenger` when `redis.enabled`, else `LocalMessenger`.
- `KeyValueStore`. Suspending KV with TTL (`set`/`get`/`delete`/`keys`/`mget`/`tryHold`), for state that must expire on
  its own (server liveness). **Always registered** (`get<KeyValueStore>()`):
  `RedisKeyValueStore` when `redis.enabled`, else `MemoryKeyValueStore`. Its **claim primitives** are the ones to reach
  for before inventing a lock: `setIfAbsent` (one winner per key),
  `deleteIfEqual`/`refreshIfEqual` (owner-checked release and renewal, a bare `delete` releases a lock somebody else may
  already hold), `hsetIfAbsent` (one winner per *field*, so an entry or a claim is one round trip and not a `hgetAll`
  -then-`hset` check-then-act), and `tryHold` for capacity. All are atomic on both transports.
- `config.yml` holds `network.expect`, `database.*`, `redis.*` (both `enabled: false` by default),
  `currency.*` (`enabled: false`), `commands.menu` (default `true`), `production` (default `true`) and
  `modules.auto-reload` (defaults to `!production`).

**Player locale, persistent & cross-server.** `Player.resolvedLocale()` = stored override ?: client
`locale()`; all helpers use it. A chosen override (`player.setLanguage(de)`) persists to SQL +
broadcasts an invalidation; `PlayerLocaleStore` caches it in memory for sync reads. The core installs
`PlayerLocaleStore` whenever **SQL** is configured (the broadcast reaches whoever is listening, which
on a single server is just this process), else `MemoryLocaleStore` (overrides work but reset on
restart). A store is always installed; falls back to client locale without an override.

**Two words, used everywhere.** A **server** is what a player joins and picks from a hub menu (`prison`, `skyblock`); a
**node** is one process running it, and a node id *is* the name the proxy registers it under. In Kotlin the pool is
`serverId`, not `server`, because `server` is Bukkit's
`Server` and the collision would be constant.

**How many nodes you mean to run** is declared in `network.expect` (or `CRYON_EXPECT`) and exposed as
`NodeIdentity.expectation`:

- **`one-node`**. This process is the whole server. A Velocity proxy still fronts it, with a static
  backend in `velocity.toml`. Redis optional.
- **`many-nodes`**. One of N interchangeable nodes of `network.server` (10 prison shards), players
  load-balanced onto the healthiest. Redis + Postgres **required**.

**Mode and transport are orthogonal, and this is the load-bearing idea.** The mode declares *intent*;
what actually decides whether state crosses processes is `redis.enabled`. **The mode never switches behaviour**, a
second code path keyed on the mode is precisely what this design exists to delete.
It exists so the core can check intent against reality and be loud when they disagree; without it,
"I meant one server" and "I meant a pool and my Redis URI is wrong" look identical at boot.

**So `Messenger` + `KeyValueStore` are always registered** (Redis impls, else in-process ones), and
everything above them has **exactly one implementation, always present**: `ServerRegistry`,
`FeatureFlags`, `MaintenanceService`, `PlayerHandoff`. Over the in-process transport the registry simply holds this one
instance, which is what a single server *is*, not a degraded pool, so
`bestNode(serverId)` answering "you" needs no special case. **Write feature code once; never branch
on the mode.** `LocalMessenger` echoes to the publisher and delivers on its own thread precisely so that code behaves
identically on both transports (see its KDoc, both are load-bearing).

**Two deliberate carve-outs**, both because they are *inherently* cross-process rather than merely
usually so:

- **`PlayerRouter`**. Registered only with a shared transport. A transfer is performed by a proxy in
  another JVM; over loopback it could only publish into a void and report `Sent`. `find` returning
  null honestly means "there is nowhere to route", which is the truth of a single server.
- **`MaintenanceService`**. **Enforced where logins arrive**, on either transport: the proxy, and now Geyser ahead of it
  (see the `:geyser` loader). A single-server deployment still has exactly one proxy, so its in-process state is already
  network-wide truth. Nothing on Paper reads it.

**The rule: more than one process that must share state ⇒ Redis.** Loopback does not bridge JVMs, so several *different*
one-node servers behind one proxy is a shared-transport deployment, not
a single-mode one.

Validation is **loud but non-fatal** (`NetworkStatus`, `:paper`): `many-nodes` without Redis or without a database, or
`one-node` while >1 live node serves your server, prints a banner at boot and shows in **`/cryon network`** (server,
node, expect, transport, database, live nodes, warnings).

The old spellings, `network.family`, `network.instance-id`, `network.mode`, and the values
`single`/`instanced`. Are still read for one release. Each logs a rename warning once at boot. The alias deliberately
checks `isSet` rather than `getString`: a `FileConfiguration` falls through to the jar's defaults, so reading the new
key first would always answer *something* and silently discard the operator's declared intent.

**Network / sharding (`…common.server`).** Each process resolves a `NodeIdentity`
(env-first: `CRYON_EXPECT`, `CRYON_SERVER`, `CRYON_NODE`/`HOSTNAME`, `CRYON_NODE_ADDRESS`,
`CRYON_NODE_PORT`, else `config.yml` `network.*`, else Paper's own values), generalizing the old static `server-name`
into a `serverId` (the pool, and the FeatureFlags server scope) plus a per-process `nodeId`. Registered into the
`ServiceRegistry`.

- `ServerRegistry` (`SharedServerRegistry`). The directory of live instances. Liveness is **KV with a
  TTL** (a crashed pod's key expires; each node also runs a local reaper), synced over
  `cryon:registry:events` pub/sub into an in-memory replica so queries are non-blocking; the slow-changing server
  catalog (`cryon_servers`) lives in Postgres (optional). **Always registered**,
  `services.get<ServerRegistry>()`. Only ever populates its replica **from the pub/sub echo**, so
  a `Messenger` that didn't echo to itself would leave it permanently empty.
- `NodeReporter` (`:paper`). `register()` publishes this node as STARTING **before** modules
  load; `ready()` flips it to READY and starts heartbeating **after** they enable, so proxies never
  route into a half-loaded server. Player count rides an `AtomicInteger` fed by join/quit, so the async
  heartbeat never touches Bukkit off-thread; sets DRAINING then deregisters on disable. Proxies never
  register themselves; they only read.
- `PlayerRouter` (`DefaultPlayerRouter`, in `:common`, runs on Paper and Velocity). `route(uuid, serverId)`
  picks the least-loaded candidates and **reserves a slot atomically** (`ServerRegistry.tryReserve` →
  `KeyValueStore.tryHold`, a Lua/zset hold on Redis) before broadcasting on `cryon:routing:transfer`, so
  two proxies can't overfill one shard. The owning proxy connects the player, others no-op. A Paper feature never needs
  a proxy handle: `services.find<PlayerRouter>()?.route(...)`.
- On Velocity, `BackendSynchronizer` registers/unregisters proxy servers off registry events and
  `TransferListener` performs the connects. Ephemeral minigame families fall back to the `Matchmaker`
  seam (interface only until a matchmaker module ships). `config.yml` adds a `network.*` block;
  `server-name` remains a legacy alias for `network.server`.
- **Who may enter a server is decided on the proxy (`ServerAccessListener`, `:velocity`).** A switch can start from a
  feature module, `/server`, a forced host or Velocity's fallback-on-kick, and none of those callers can weigh the
  target's state against the player's permissions. `PlayerRouter` lives in
  `:common` and never sees a player. `ServerPreConnectEvent` is the one point they all pass through, so the listener
  denies there, at `PostOrder.FIRST` (ahead of `HandoffListener`, so a refused move never makes the source node flush).
  Three rules: maintenance is on and the player has neither
  `cryon.maintenance.bypass` nor an allowlist entry; the target node is registered but not `READY`; or its `serverId` is
  listed in `network.restricted-servers` and the player lacks `cryon.server.<serverId>`. A name the registry doesn't
  know is a static backend and is only maintenance-gated. **Denying costs the player nothing**. Velocity leaves them on
  their current backend; on the initial connect there is none, so a denial disconnects them, which is what a closed
  server means. `PlayerRouter` still answers `Sent`
  for a move the proxy then refuses: it reports that the request was broadcast, not that it was admitted.
- `AgonesLifecycle` (`:paper`, `…network.agones`). Active only under an Agones sidecar (detected via
  `AGONES_SDK_HTTP_PORT`). Talks to the sidecar over REST (`AgonesClient`, JDK `HttpClient`, no gRPC):
  marks `Ready` after registration, pings `Health`, mirrors the player count to an annotation, and
  optionally reclaims an empty **persistent** shard (`shutdown-when-empty`, env-first via
  `CRYON_AGONES_SHUTDOWN_WHEN_EMPTY`, guarded to never kill the last `min-instances`). Registered into
  the `ServiceRegistry` so a matchmaker/match-end handler can call `requestShutdown()`.
- **Maintenance mode** (`…common.maintenance` `MaintenanceService`/`SharedMaintenanceService`, synced
  like FeatureFlags). Proxy-side only. `/maintenance on|off [message]` (permission `cryon.maintenance`)
  flips every proxy; `MaintenanceListener` shows the message with an unjoinable ping protocol and denies
  logins without `cryon.maintenance.bypass`. The message is a **MiniMessage** template (rendered via
  `Mini`), so it colours/formats. A command-managed **bypass allowlist** (`/maintenance add|remove|list`, name-based,
  case-insensitive) lets named players in without the permission node, persisted to
  `cryon_maintenance_allow` and synced over `cryon:maintenance:allow`, mirroring the toggle's write-through + broadcast.
  **A broadcast only reaches a process that was listening, so with a database the state is also re-pulled on an
  interval** (`maintenance.refresh-seconds`, default 30, 0 disables). That repairs a process which started before the
  database was reachable, or missed a toggle while disconnected: without it, `init` logs its failure and the process
  holds "maintenance off" for its whole life while the network is closed. The re-pull applies a removal only to a name
  it already knew before the read, so a name added locally while the query was in flight is never wiped by its result.

**Signals. The in-process bus for the project's own vocabulary (`…common.signal`).** `Events`
carries Bukkit events and `Packets` carries wire packets; both are *the platform's* nouns, both are notifications about
something that already happened, and neither can carry a type a feature invented.
`Signals.dispatch(signal)` hands a value to every subscriber **and gives it back**, so a listener can raise a payout,
apply a discount, veto a purchase or attach a tag, and the emitter uses what comes out.

That inverts the problem this guide already warns about. *"a multiplier must hook every relevant call site; firing in
one of four paths is a bug"*. Today the author of a sell path has to know every feature that might modify it, and a
feature cannot register interest in a path written later. With a dispatch point the sell path emits one signal and is
done, and the fifth feature added next month touches nothing. Implement `Signal` (or `Cancellable`, and emit through
`signals.allows(…)`), subscribe with `track(signals.on<T> { … })`, and order with `priority`, a cap, a rounding step or
an audit registers late. **In-process only**: handlers mutate in place, which cannot survive a network hop, so a
cross-server broadcast is still `Messenger`.

**Retention. Did that actually get collected? (`…common.diagnostic`).** Hand it something that ought to become garbage
and it holds a *phantom* reference; `report()` says how many were reclaimed and how many are still live. The core tracks
every unloaded module classloader, and `/cryon retention` prints it. **This is the one measurement that says whether a
hot-swap really worked**, everything else the loader does (dropping services, unregistering commands, closing windows,
cancelling tasks) is it *trying* to remove the last references, and only the collector can confirm it succeeded. Nothing
outside can see this: `LagFinder` attributes heap by package prefix and every module is
`com.tricrotism.cryon.*`, so its histogram credits them all to the core plugin. **Read the trend, not one number**, a
live count right after an unload usually just means no GC has run; one that climbs across reloads of the same jar is the
leak.

**Provisioning, find a node that fits, make one if none does (`…common.server`).** The rung between
`ServerRegistry` (which nodes exist) and `PlayerRouter` (send a player to one): `provision(request)`
matches a `NodeSelector` against the live replica and, with `createIfMissing`, asks a `NodeAllocator`
for another node and waits for it to report `READY`. Selectors are predicates (`Available`, `Empty`,
`tagged(k, v)`) because callers want "one with room", not a name. Four outcomes, and the split between them is
load-bearing: `Ready`, `Unavailable` (nothing matched, nothing created), **`Pending`** (created but not ready yet: tell
the player to try shortly, *not* that it is broken), and `Failed`. `NodeAllocator` is the seam to Agones or k8s and is
genuinely optional, without one this is a pure query, which on a static pool is the truth rather than a degraded mode.

**The colony, one elected queen per cluster service (`…common.colony`).** For work that must happen **once across a
pool** rather than once per node: a market tick, an auction sweep, a scheduled event, a leaderboard rebuild. A feature
declares a `ClusterService`, registers it with a `ColonyListener`, and gates its repeating task on
`colony.isQueen(service)`; `colony.route(service, strategy)` answers which node to send a request to
(`ShardingStrategy.Queen` by default, `byId` to pin a subject to a shard,
`roundRobin` for stateless fan-out).

**The election is a hash, not a consensus protocol**, and that is the whole trick. Every node writes a heartbeat
advertisement into one Redis hash listing what it claims, reads the set back, and computes the winner with **rendezvous
hashing**, `min by fnv1a(serviceId, nodeId)`. Given the same view every node independently reaches the same answer, so
there is no lock to take, no CAS to lose, and no leader needed to elect the leader. A node claiming a crown the hash
says belongs elsewhere demotes on its next tick, which makes a split brain self-healing rather than something to page
about. Rendezvous rather than modulo because losing a node should re-elect only what *that* node held, not reshuffle
every service in the pool.

`ColonyElector` is deliberately pure, no I/O, no clock, so the algorithm can be exercised by handing it advertisements
and reading back its decisions. `SharedColony` is the transport half. One hash rather than a key per node, because
reading a key-per-node scheme means `keys("prefix*")`, which walks the whole keyspace; each advertisement carries its
own heartbeat and stale ones are dropped on read.

**Two honest limits.** The view is only as fresh as the heartbeat, so a failover has a window where the old queen is
gone and the new one has not noticed, **work under a crown must be idempotent or resumable**, the same rule as
`DistributedLock`. And nothing hands state over: a promoted queen loads what it needs from SQL in `onPromote`, because
the previous one may have died rather than resigned. Without Redis this is one node that is trivially queen of
everything, same code path. Ticked by the core on the `network.heartbeat-seconds` interval.

**Player handoff. Saving on quit is a bug with more than one node (`PlayerHandoff`, `…common.server`).** A
proxy moves a player A→B by connecting B **first** and dropping A **after**
(`createConnectionRequest(…).connect()`). So B's login, and every feature load behind it, happens
*before* A's `PlayerQuitEvent` runs. A feature that saves on quit is therefore always one step behind:
B reads the previous save. **No amount of care inside the feature fixes this**. The ordering is imposed from outside,
which is why the core owns it.

The fix inverts the order: **flush before the transfer, not on quit.** Velocity's `HandoffListener`
catches `ServerPreConnectEvent`, asks the source instance to flush over
`messenger.request(HandoffCoordinator.channel(from), uuid)`. A channel named per instance, so only the one holding the
player answers, and returns an `EventTask` so the connect resumes only once it
acks. `HandoffCoordinator` (`:common`) then runs every registered flush, marks the player, and skips
the quit flush that follows moments later (writing our now-stale copy again would undo what they did
on B). The mark **expires** rather than clearing on quit, because a transfer can fail and the player's
real quit must still save. Fails open: a timeout moves the player anyway and logs it.

**Modules register a flush instead of saving on quit:**

```kotlin
override fun onEnable() {
    onFlush("balances") { uuid -> repository.save(uuid, cache[uuid]) }   // PaperModule helper
}
```

Auto-unregisters on disable. The callback **runs off the main thread, must not touch the Bukkit API**
(it writes state you already hold; it does not go and collect it), and must be safe **while the player is still
online**, during a handoff that is exactly the case. Return a future that completes when the
write lands: the transfer waits on it, so one that never completes stalls the player and one that
completes early defeats the point. The quit path runs at `MONITOR`, so your own quit handler has
already updated your state before the core writes it down. The **same callback** runs on quit and on
shutdown (`Cryon.flushOnlinePlayers`, before modules disable and the DB pool closes), so a single server, where no
transfer ever happens, exercises the identical code.

**Deployment** lives in `deploy/` (outside the Gradle build): per-family Paper / Velocity / Geyser
Dockerfiles + entrypoints (`images/`), baked family jar sets (`families/`), and a Helm chart
(`helm/cryon/`) of Agones Fleets + Buffer FleetAutoscalers, the proxy Deployment/Service, standalone
Geyser (UDP) + Floodgate, ConfigMaps, and allocator RBAC. See `deploy/README.md`.

**Not yet:** DI container, codegen, coroutine bridge; the ephemeral `Matchmaker`
implementation + a k8s allocator, the Agones Counter (player-count) autoscaler, and safe drain/transfer
of populated shards on node upgrades. Add infrastructure **and document it here in the same pass.**

---

## Summary: Do / Don't

| Do                                                                | Don't                                                           |
|-------------------------------------------------------------------|-----------------------------------------------------------------|
| `CommonMessages.error(…)` / `audience.sendError(…)`               | `player.sendMessage("§c§lError §7> …")`                         |
| `Mini.format("…", Placeholder…)` / `"…".mm()`                     | `MiniMessage.miniMessage()`; `"…$value…"`                       |
| `MessageService` keys for multi-language copy                     | Hardcoded English strings in features                           |
| `<!i>` in lore (or `ItemBuilder`, which does it)                  | Raw `§o` italic prefix in lore                                  |
| `ItemBuilder` / `Material.toItem()`                               | Hand-rolled `editMeta` for every item                           |
| Colour-code chars in a `Structure` (see `MenuPalette`)            | Declaring a filler-pane ingredient in every menu                |
| `bedrock.sendSimpleForm(...)` first, InvUI window as the fallback | Assuming a Bedrock player can read a chest menu's layout        |
| `get<BedrockService>()` on the proxy for Bedrock identity         | Reading a Floodgate prefix off `player.username` by hand        |
| Close your module's windows in `onDisable`                        | Leaving them open (leaks the module classloader on hot-unload)  |
| `Schedulers.async/global/region/entity`                           | raw `Bukkit.getScheduler()`                                     |
| `Schedulers.entity(p)` per player when iterating online players   | Touching each `onlinePlayers` element from one thread           |
| Mutate authoritatively inline, branch on the result               | Guard read + hopped mutation (`global` never runs inline)       |
| `timer`/`asyncTimer`/`track(…)` on `PaperModule`                  | Raw `Schedulers.*Timer` in a module with no cancel on disable   |
| `Events.subscribe(...).filter{}.handler{}`                        | ad-hoc `Listener` plumbing for one handler                      |
| `Packets.onReceive/onSend(...).handler{}` + `track(…)`            | Registering a raw PacketEvents listener with no teardown        |
| Hop out of a packet handler before any Bukkit call                | Touching entities/inventories on the Netty thread               |
| `PackedDecimal` for values that grow past ~1e15                   | `BigDecimal` on hot incremental-math paths                      |
| `services.find<CurrencyService>()` (optional, like SQL)           | `get<CurrencyService>()` assuming an economy is configured      |
| Branch the reward on `withdraw`'s returned `Boolean`              | Reading a balance to decide, then taking it separately          |
| `cachedBalance` for HUDs/placeholders only                        | Deciding a purchase from `cachedBalance`                        |
| Branch all three `TransferResult` values                          | Collapsing `INSUFFICIENT` and `FAILED` into "not completed"     |
| `@Command`/`@Subcommand` + `AnnotationCommands.register`          | `plugin.yml commands:` / `CommandMap` / Cloud (broken on 26.2)  |
| `CommandUi.unknown(...)` for a bad id                             | Rejecting the input without offering the nearest match          |
| A command for every menu action                                   | A menu as the only way to do something (console can't click)    |
| `branch { leaf(...) }` from `…api.menu` to build a tree           | Hand-building `MenuBranch`/`MenuLeaf` node lists                |
| `PlaceholderProvider` + `registerPlaceholders(...)`               | Extending `PlaceholderExpansion` in a module (can't see PAPI)   |
| `player.resolvedLocale()` for messages                            | `player.locale()` directly (ignores overrides)                  |
| `services.find<Database>()` (genuinely optional)                  | `get<Database>()` assuming SQL is enabled                       |
| `get<Messenger>()`/`get<KeyValueStore>()`/`get<ServerRegistry>()` | Null-checking them, or branching on the deployment mode         |
| `onFlush("…") { uuid -> … }` for player state                     | Saving player state in a quit handler (too late on a transfer)  |
| `PlayerRouter.route(uuid, serverId)` to move players              | Hardcoding a backend server name to connect to                  |
| `find<PlayerRouter>()`. Null means nowhere to route               | Assuming a route is always possible                             |
| `bestNode(serverId)`                                              | Assuming a fixed server list; picking a full/STARTING node      |
| `signals.dispatch(…)` at one emit point                           | Hooking every call site by hand and missing the fifth           |
| `signals.allows(cancellable)` to gate                             | Dispatching and forgetting to read `cancelled`                  |
| `/cryon retention` trend across reloads                           | Reading one live count as proof of a leak                       |
| `remote.enabled` + let `modules.auto-reload` gate applying        | A second switch letting remote builds swap when local can't     |
| A stable jar filename per remote artifact                         | A versioned filename (the loader then sees the module twice)    |
| `Provisioner` + a `NodeSelector`                                  | Hand-rolling a scan-then-scale loop over the registry           |
| Branching `Pending` apart from `Unavailable`                      | Telling a player "broken" while a node is still booting         |
| `colony.isQueen(service)` to gate pool-wide repeating work        | Running a market tick on every node of a pool                   |
| Idempotent/resumable work under a crown                           | Assuming the queen never changes mid-job                        |
| Load queen state from SQL in `onPromote`                          | Expecting the previous queen to hand state over                 |
| `launch` into `PaperModule.scope`                                 | `GlobalScope` / an ad-hoc `CoroutineScope` in a module          |
| `withContext(CryonDispatchers.Async)` for I/O                     | Blocking I/O on a region thread; `runBlocking` outside shutdown |
| `@Synchronized` on a non-suspending helper                        | Holding a monitor across a suspension point                     |
| `cooldowns.trigger(...)` / `guard(...)` as the gate               | `remaining()` then `mark()` (check-then-act)                    |
| A CAS for one key; `DistributedLock` only across several          | A distributed lock where `withdraw`/`setIfAbsent` would do      |
| `Dialogs.text(...)` for typed input                               | Anvil renames or chat prompts to collect a value                |
| `Dialogs.choose(options)` returning the value                     | Returning an index the caller has to keep in step               |
| `track(BossBars.create(...))`                                     | A bar left open through a hot-unload (renders with no owner)    |
| `ActionBars.send(..., priority, key)`                             | Raw `sendActionBar` for anything persistent or contended        |
| `Repository.stage(...)` + a flush timer for player state          | A hand-rolled map + dirty flag + full-file rewrite              |
| `session.batch(sql, rows)` for a checkpoint                       | A loop of `update()` per row                                    |
| `hsetIfAbsent` / `setIfAbsent` to claim                           | `hgetAll` then `hset` (check-then-act)                          |
| A `MenuContent` for anything longer than a page                   | Materializing every node to show the first twenty-eight         |
| `database.upsert(table, keys, columns, …)`                        | Hand-written `ON CONFLICT` / `ON DUPLICATE KEY` SQL             |
| Play a `Sound.*` on player-facing actions                         | Silent state changes / redeems                                  |
| `inventory.addItem` + handle overflow deliberately                | `dropItemNaturally` as the "didn't fit" path                    |
| Explicit types; `val` over `var`                                  | Java-isms; needless `var`; gratuitous `!!`                      |
| Typed lambda params (`{ event: PlayerInteractEvent ->`)           | Untyped params relying on inference where it hurts              |
| `ConcurrentHashMap` for shared static state                       | `HashMap`/`HashSet` for shared static state                     |
| `merge()` for shared-map counter increments                       | `getOrDefault + 1 + put`                                        |
| `computeIfAbsent` / `putIfAbsent`                                 | `containsKey` + `put`                                           |
| Bukkit API only on the server thread                              | Bukkit API in `runTaskAsynchronously`                           |
| Cache service/config lookups before hot loops                     | Re-resolving them inside loops                                  |
| `x shr 4` for block→chunk coords                                  | `block.chunk.x` / `block.chunk.z`                               |
| A guard/flag per distinct slice of behavior                       | One umbrella guard covering many slices                         |
| Bare flag IDs (`FISHING`)                                         | Gamemode-prefixed flag IDs (`A_FISHING`)                        |
| `[TICKET]` imperative commit titles                               | `Co-Authored-By:` trailers / emoji in commits                   |
