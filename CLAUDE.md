# Cryon Dev Guide

**Kotlin** **Velocity + Paper** network built as a **feature-loader framework**: the Cryon core is a
Paper plugin that discovers and loads independent **feature jars** (each its own repo) at boot.
Stack: **JDK 25**, **Kotlin 2.4.20-Beta1**, Paper dev bundle **26.2**.

Modules in this repo (core + published API):

- **`:common`** — platform-neutral framework: module system (`Module`, `ModuleManager`,
  `ModuleContext`, `ServiceRegistry`), `number` (`PackedDecimal`, `LongUtils`, `BigDecimalUtils`,
  `NumberUtils`), `text` (`Mini`, `CryonPalette`, `MessageType`, `CommonMessages`), `locale`
  (`MessageService` + sources), `data`/`net` (SQL + the `Messenger`/`KeyValueStore` transport, Redis or
  in-process), `server` (deployment mode, server registry, player routing, player handoff), primitive
  `extension`s. Adventure `compileOnly`; no Bukkit/Velocity types (the `:velocity` loader reuses it).
  **Published.**
- **`:paper-api`** — what feature repos compile against: `PaperModule`/`PaperModuleContext`,
  `CryonPaper`, `item.ItemBuilder`, `scheduler.Schedulers` (Folia-aware), `event.Events`,
  `command` (annotation framework), Paper `extension`s. Bukkit `compileOnly`. **Published.**
- **`:paper`** — the core plugin / **loader** (`com.tricrotism.cryon.Cryon`). paperweight.userdev +
  Shadow + run-paper; bundles `:common` + `:paper-api` + kotlin-stdlib for loaded features.
- **`:velocity-api`** — what Velocity feature repos compile against: `VelocityModule`/
  `VelocityModuleContext`. Velocity `compileOnly`. **Published.**
- **`:velocity`** — the proxy **loader** (`com.tricrotism.cryon.velocity.CryonVelocityPlugin`). Shadow;
  shades `:common` + `:velocity-api` + kotlin-stdlib + the cross-server client libs (no Paper-style
  `libraries:` loader on Velocity). Reads the shared server registry to route players and register
  backends live.

Features live in **separate repos** (e.g. `Cryon-Modules/cryon-example-feature/`), `compileOnly` the
API, and ship a thin jar dropped into `plugins/Cryon/modules/`.

**No** DI container, codegen, menu framework, or coroutine bridge yet. **When you add infrastructure
(DI, KSP, InvUI, Folia), document it here in the same pass** — keep this
guide and the code in lockstep.

---

## Working Practices

Bias toward caution over speed; use judgment on trivial tasks.

- **Think first / ask.** State assumptions. Surface multiple interpretations — don't pick silently.
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
  throughout — no Java-isms; `!!` only when truly invariant.
- **Prefer extension functions** — check existing ones first; reusable helpers go in an `…extension`
  file, not a static util.
- **Schedule through `Schedulers`** (Folia-aware), never raw `Bukkit.getScheduler()`.

---

## Code Quality

Apply ordinary code-smell scrutiny:

- **Null-safety on external returns** — `Bukkit.getPlayer`, config reads, `event.item`,
  `inventory.getItem`, anything crossing the Bukkit boundary. Internal invariants you can trust.
- **Resource leaks** — unregistered listeners, tasks left running on disable, `TextDisplay`/`ItemDisplay`
  not despawned, open inventories unhandled.
- **Mutable shared state without thread-safety** (see Thread Safety).
- **Accidentally quadratic loops** — per-block work inside per-player work, etc.
- **Broad `catch (e: Exception)` that swallows context** — rethrow, log with context, or scope it.
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
`ServiceRegistry`. Bare uppercase IDs (`FISHING`, `FISHING_RARE` — **no** gamemode prefixes; a
gamemode-specific flag is scoped via the scope argument, never the ID). **Layered — most specific
wins:** player override → server override (this server's family, `network.family`/legacy `server-name` in
`config.yml`) → global
override → default **enabled**. SQL-persisted (`cryon_feature_flags`, source of truth) and synced
across every server via a Redis broadcast when the infra is configured; without it the same API runs
in-memory per server (resets on restart).

Admin surface (`cryon.admin`): `/cryon flags [scope]` — per-scope listing with clickable toggles;
`/cryon flag enable|disable|clear <feature> [scope]` where scope is `global` (default), a server
name, or `player:<name>`; `/cryon flag status <feature> [player]` — the layered breakdown; `/cryon
flag delete <feature>` (hardcoded-authorized account only); `/cryon flag reload`.

In a module: resolve once (`services.get(FeatureFlags::class)`), `register("SHOP_SELL")` each ID in
`onEnable` (persists its default and lists it), then gate every entry point **inside** the handler —
not at wiring time — so a runtime toggle bites without re-enabling the module. Commands use the
one-line guard `flags.guard(player, FLAG)` (acks the localized "⟨Feature⟩ is currently disabled."
and returns false); silent paths (event handlers, sell modifiers, payout code) use
`isEnabled(FLAG, player.uniqueId)` — **pass the player whenever one is in context** so per-player
overrides (canary rollouts, support cases) apply. Reference: the survival gamemode modules in
`Cryon-Modules/` (`cryon-economy`/`cryon-skills`/`cryon-shop`).

---

## Messaging — Adventure + MiniMessage

Send `Component`s — **never legacy `§` strings, never string interpolation into messages.** Use the
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
key (never hardcode English); localized + prefixed ack → `messages.send(player, MessageType.ERROR, "key", …)`.

---

## Item Lore

Prefer `ItemBuilder` — auto-applies non-italic (`<!i>`) to name/lore and chains flags/glow/attributes/PDC:

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

Silent feedback feels broken — play a `Sound.*` on every player-facing action (message, menu, toggle,
redeem, drop). Only vanilla sounds exist.

```kotlin
player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.5f)
```

- Personal feedback → `player.location`. Ambient/broadcast → a world `Location`.
- Pitch: `1.5f`–`2.0f` positive/rare, `1.0f` neutral, `0.5f`–`0.8f` warnings/heavy. Volume `1f`
  standard, `0.5f` incidental/repeating.
- Match existing feature sounds before inventing new ones.

---

## Giving Items

`inventory.addItem` and **handle overflow deliberately** — never `dropItemNaturally` as the
"inventory full" fallback (anyone can grab it; it despawns).

```kotlin
val leftover = player.inventory.addItem(item) // slot -> what didn't fit; decide explicitly
```

`world.dropItemNaturally` only for genuine world drops (block breaks, deaths).

---

## Events & Listeners

Prefer the functional `Events` builder — no `Listener` class, returns a cancellable `Subscription`,
filters run before the handler:

```kotlin
Events.subscribe(PlayerInteractEvent::class.java, EventPriority.HIGHEST)  // or Events.subscribe<PlayerInteractEvent>(…)
    .ignoreCancelled()
    .filter { it.hand == EquipmentSlot.HAND }
    .handler { event -> /* … */ }
```

Inside a `PaperModule`, `listen(listener)` covers a classic `@EventHandler` `Listener` and
auto-unregisters on disable. Either way: filter cheaply first, tear down on disable.

---

## Thread Safety

Shared mutable state (`object`/`companion` fields touched by >1 thread/scheduler) must be thread-safe; lightest correct
type:

- **`ConcurrentHashMap`** for shared maps; **`Collections.newSetFromMap(ConcurrentHashMap())`** for sets.
- **`merge()` for counter increments** (`getOrDefault + 1 + put` is a TOCTOU race):
  `rarityCount.merge(rarity, 1) { a, b -> a + b }`
- **`computeIfAbsent`/`putIfAbsent`** for check-set, never `containsKey` + `put`.
- **No Bukkit API off the main thread** — entities, inventories, particles, `TextDisplay.text(…)`.
  Do async work, then hop back. Folia: use region/entity schedulers and document it.
- **Locals that never escape a tick** can use plain `HashSet`/`ArrayList`.

---

## Performance

- **Cache lookups before hot loops** — resolve services/config once above the loop.
- **`x shr 4` for block→chunk coords**, not `block.chunk.x` (allocates a `Chunk`).
- **Batch high-frequency work** — accumulate deltas in a thread-safe per-player buffer, flush on
  interval or boundary (logout, level-up, sell-cycle end), not one DB write per event.
- **Hot-loop math:** `BigDecimal.valueOf(long)` over `BigDecimal(double)`; hoist invariant lookups;
  branch out no-op multiplies (`multiplier == 1.0`); keep per-rank multipliers as `BigDecimal` where a `long` could
  overflow.
- **No accidentally quadratic loops** — don't iterate online players inside per-player/per-block work.

These target hot loops (per-tick/block/player). Cold paths (boot, admin commands, file events) follow **simplicity first
** instead.

---

## Comments

No decorative dividers (`// ── fishing ──`). Comment the *why* when non-obvious; don't narrate what the code says.

---

## Build & Run

JDK 25, Kotlin 2.4.20-Beta1, paperweight dev bundle 26.2. Gradle config-cache/parallel/build-cache on. No unit tests —
verify on a local server.

- `./gradlew build` — all modules; `:paper`'s `build` runs `shadowJar` → `paper/build/libs/`. The
  shaded jar bundles `:common` + `:paper-api` + kotlin-stdlib — **don't relocate kotlin-stdlib**
  (features resolve `kotlin.*` through it).
- `./gradlew :paper:runServer` — local Paper 26.2 with the core loaded; drop feature jars into `plugins/Cryon/modules/`.
- `./gradlew :common:publishToMavenLocal :paper-api:publishToMavenLocal` — publish the API locally
  (production: `repo.striveservices.org`).
- Versions declared once in root `build.gradle.kts` (`apply false`); subprojects apply without
  versions. `group`/`version` from `gradle.properties`. `plugin.yml` `${version}` filled by `processResources` — don't
  hardcode.

---

## Module System

Features are **modules**, each a jar (own repo) loaded by the core. Framework types in `:common`
(`…common.module`); Paper base in `:paper-api`.

**Loader (`Cryon.kt` → `ModuleLoader`, `…cryon.module`):** on enable, loads every jar in
`plugins/Cryon/api/` into **one shared `URLClassLoader`** (the contract layer), then loads each
`modules/*.jar` in its **own isolated `URLClassLoader`** parented to that shared loader (→ core →
Paper + `:common`/`:paper-api` + kotlin-stdlib). Discovers `Module`s via `ServiceLoader`, then
`loadAll(context)` → `enableAll()`. Broken jar logged and skipped (caught as `Throwable` —
`ServiceConfigurationError` is an `Error`). Loaders closed on disable (modules before parent). Empty `api/` → shared
layer skipped.

**Cache copies:** each feature jar is copied into a private `plugins/Cryon/.module-cache/` and loaded
from the copy, so the original in `modules/` is never file-locked (matters on Windows) and can be
deleted/replaced while running — the basis for hot-swap. Closing a loader frees its file handle and
drops its lang bundle/listeners; reclaiming classes still depends on the module not leaking refs (usual reload caveat).
Cache wiped on boot and disable.

**Core types:**

- **`Module`** (`:common`) — `id` + two-phase lifecycle: `onLoad(context)` (publish services),
  `onEnable()` (consume peers), `onDisable()`. `ServiceLoader`-discovered → needs **no-arg ctor** +
  `META-INF/services/com.tricrotism.cryon.common.module.Module` entry.
- **`ModuleContext`** (`:common`) — `logger` + `services`; Paper's `PaperModuleContext` adds `plugin`/`server`.
- **`ServiceRegistry`** (`:common`) — intertwine seam. `register(Api::class, impl)` / `get<Api>()` /
  `find<Api>()` (reified variants exist), keyed by interface.
- **`PaperModule`** (`:paper-api`) — base exposing `plugin`/`server`/`services`/`logger`; `listen(listener)`
  auto-unregisters on disable. Override `onLoad` (call `super` first), `onEnable`, `onDisable` (call `super`).

**Isolation → intertwine only through shared interfaces.** Feature jars can't see each other's
classes. Expose behaviour by registering an impl of an **API interface in a shared artifact** (`:common`,
`:paper-api`, or an `api/` contract jar). Never reference another feature's concrete classes.

**Cross-module contracts — the `api/` layer.** To expose an API to a feature in *another repo*, the
contract type must load from the **shared parent**, not be bundled in either jar (two copies = two
`Class` objects = `ClassCastException`). Ship the interfaces in a thin **`*-api` jar** in
`plugins/Cryon/api/`; both provider and consumer depend on it **`compileOnly`**. Provider
`services.register(FooService::class, impl)` in `onLoad`; consumer `services.find(FooService::class)`
in `onEnable` (**`find`, not `get`** — the other repo may be absent). Reference: `cryon-visibility-api`
consumed by `cryon-visibility`. Reserve `api/` for genuinely cross-repo contracts; a contract the core ships can live in
`:paper-api`.

**Order-independence:** every module's `onLoad` runs before any `onEnable`, so peer services are always available in
`onEnable`. No declared load order.

**Runtime lifecycle.** `ModuleManager` tracks `ModuleState` (`REGISTERED`/`LOADED`/`ENABLED`/`DISABLED`/`FAILED`)
and supports `enable`/`disable`/`reload(id)` (re-enable reuses the load-time context), plus
`load(id, context)` and `unregister(id)` for single-module hot-swap churn. Surfaced via `/cryon
modules|info|enable|disable|reload <id>`. Registered into `ServiceRegistry` so a module reads its own
state via `PaperModule.isEnabled()`. **Main-thread only.**

**Failure isolation — a feature must never crash the server.** Every seam where the framework invokes
feature code catches **`Throwable`** (not just `Exception` — a stale/mislinked jar throws `Error`s
like `NoSuchMethodError`): module `onLoad`/`onEnable`/`onDisable` (failure → `FAILED`, server
continues), jar reads, command registration (the core's single COMMANDS lifecycle handler flushes
every contribution and guards each one, since Paper rethrows lifecycle exceptions fatally; the live
runtime path in `CommandRegistry` is likewise guarded), `Events` handlers, and the watcher thread. A `FAILED` module is
in-memory only and
doesn't auto-retry; it clears on a successful `/cryon enable|reload <id>`, a reload of its jar, or
restart. The one thing outside our control is a feature that bypasses these helpers and registers its
own raw Paper lifecycle handler — route command registration through `registerCommands`, and keep the
published `:paper-api` binary-compatible (`@JvmOverloads` on defaulted params) so old feature jars still link.

**Hot-swap (jar level).** `ModuleLoader` adds/removes whole jars at runtime: `/cryon load <jar>`
(load + enable a jar in `modules/`), `/cryon unload <id>` (disable + unregister every module in that
jar, close its loader; the jar file stays — delete to remove permanently), `/cryon scan` (load newly
dropped jars). A hot-loaded jar's modules all `onLoad` before any `onEnable`. Unload is whole-jar
(can't partially close a loader). On unload the loader also calls `ServiceRegistry.unregisterByClassLoader`
to drop services the jar published (so a reload re-registers cleanly and peers can't resolve a
dead-loader instance). These call `Player.updateCommands()` to resync command trees.

**`api/` reload (cascade).** The `api/` contract layer parents every module loader, so it can't be
swapped alone (running modules stay linked to the old contract classes). `/cryon reload-api` does the
only coherent thing: unload **all** modules → close + reload the `api/` loader → reload every module
that was loaded, preserving the global two-phase order. Briefly takes all features down. Use it after
replacing an `api/` jar.

**Auto hot-reload (dev).** Two daemon `WatchService`s (`ModuleWatcher`) — one on `modules/` (per-jar
load/reload/unload on file events) and one on `api/` (any change → a full `reload-api` cascade) —
debounced and hopped to the main thread. **Config-gated** by `modules.auto-reload` in the core
`config.yml`, **defaulting to `!production`** (`production: true` by default): a `production: false`
dev server hot-reloads automatically, production doesn't, either overridable. The `/cryon
load|unload|scan|reload-api` commands work regardless.

**spark profiler attribution (`SparkSupport`).** spark attributes a sampled frame in two steps: a
`ClassFinder` resolves the frame's class *name* to a `Class`, then a `ClassSourceLookup` maps it to
a source by walking its classloader chain for a Bukkit/Paper plugin loader. Both are blind to our
module `URLClassLoader`s — Paper's finder (`Class.forName` + the Paper plugin loader group) can't
even *find* module classes, so their frames are dropped before the lookup runs. spark exposes no
API to extend either, so `SparkSupport.install` (run one tick after enable; every attribution call
resolves through `SparkPlatform.plugin` live at export time, so post-hoc splicing works)
reflectively swaps that field for a `Proxy` overriding three methods: `createClassFinder()` (real
finder, then each module loader — read off spark's export thread via a concurrent map),
`createClassSourceLookup()` (module classes resolve via `ModuleLoader.sourceName`, everything else
falls through), and `getKnownSources()` (appends per-jar `SourceMetadata` so the viewer lists
modules with versions — sources are named `Cryon-Module-<id>`, authored by the core plugin's
`plugin.yml` authors). It reaches the `SparkPlatform` through spark's **registered API**
(`SparkProvider.get()`, then the services registry as a fallback), so it works whether spark is a
standalone plugin **or bundled into Paper** — Paper 26.x ships spark as a bundled *library*
(`SparksFly` + `spark-paper.jar`), not a Bukkit plugin, so `getPlugin("spark")` is null. spark's
internal types (`SparkPlugin`/`ClassSourceLookup`/`ClassFinder`/`SourceMetadata`) are taken from
reflection metadata (field/method/generic types), never named, since Paper relocates spark's
packages when it bundles them (`me.lucko.spark.paper.…`). **Best-effort** — spark absent or its
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
dispatch). `/cryon enable|disable|reload` calls `Player.updateCommands()`. A disabled command shows
vanilla "unknown command" — the trade-off for gating at the Brigadier layer.

**Authoring a feature:** new repo → `compileOnly` published `:common` + `:paper-api` (+ Paper API) →
`class Foo : PaperModule()` no-arg ctor → add `META-INF/services` entry → build the thin jar → drop into `modules/`.

**Velocity loader (`:velocity`).** The proxy runs `CryonVelocityPlugin`, which builds its own
`ModuleManager` + `VelocityModuleContext` (adds `proxy`/`plugin`) over the same `:common` module
system, with a `VelocityModuleLoader` mirroring the Paper one (isolated `URLClassLoader` per jar,
shared `api/` parent, `ServiceLoader` discovery). Velocity's `@Inject` appears **only on the
`CryonVelocityPlugin` entrypoint**; feature modules stay no-arg-ctor `ServiceLoader`-discovered exactly
like Paper, so the DI and the module system don't collide. Config is read from `plugins/cryon/config.yml`
via (relocated) SnakeYAML with the same keys as Paper. It also **bootstraps the shared i18n on the
proxy** (`Messages.install` + a `plugins/cryon/lang/` override dir + the bundle in its own jar), so
proxy commands localize by client locale exactly like Paper — never hardcode English in `:velocity`.
Runtime hot-swap parity (a Velocity watcher and `/cryon`-style commands) is the documented next step.
Velocity feature repos `compileOnly` `:common` +
`:velocity-api`, `class Foo : VelocityModule()` no-arg ctor, add the `META-INF/services` entry, and
drop the jar into the proxy's `plugins/cryon/modules/`.

**Velocity commands — annotation framework over Velocity Brigadier (`…velocity.api.command`).** A
proxy-side twin of the Paper `AnnotationCommands`: the **same** `@Command`/`@Subcommand`/`@Permission`/
`@Arg`/`@Greedy` model, reflected onto Velocity's native Brigadier (`BrigadierCommand`, source
`CommandSource`) instead of Paper's. Register with `AnnotationCommands.register(proxy.commandManager,
handler)`. The annotations are **duplicated**, not shared, because each platform's Brigadier is a
different type (`CommandSourceStack` vs `CommandSource`) and `:paper-api` must stay Bukkit-free for
`:velocity` — keep the two models in step by hand. An optional trailing arg is two same-path methods
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

## Utilities

Shared helpers — **check these before writing your own.** Reach a peer feature's behaviour through `ServiceRegistry`,
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
  `5.pd`).

**Text (`…common.text`):**

- `Mini` — non-strict MiniMessage, palette-preloaded, ~15s Caffeine cache, legacy interop. Use
  `Mini.format(...)`/`"…".mm()`, **never `MiniMessage.miniMessage()`**.
- `CryonPalette` — named colours as `TextColor`s **and** tags (`<off_white>`, semantic `<error>`/`<success>`/…). Tune
  hexes / extend `RESOLVER` here.
- `CommonMessages` — prefixed acks (`error`/`success`/`info`/`warn`, `notOnline`, `notEnoughCurrency`,
  `noPermission`, …) returning `Component`s. Prefix is a bold icon per `MessageType` (`✖`/`✔`/`✦`/`⚠`,
  language-neutral). Canned bodies localized via `Messages` by `cryon.common.*` key; Paper extensions
  (`player.sendNoPermission()`, …) pass `resolvedLocale()`.

**Collections & randomness (`…common.bucket`/`…common.random`):**

- `Bucket<E>` (`Buckets.concurrent`/`hashSet`, `PartitioningStrategies.lowestSize`/`random`) — a
  `MutableSet` that also splits its elements across a fixed number of partitions, so per-element work
  can be spread over ticks: `bucket.cycle().next()` each tick walks one partition (`Cycle` is an
  atomic rotating cursor). Used by `DefaultInventorySearch` to sweep online inventories a slice per
  tick, each read on the player's own entity scheduler (Folia-safe).
- `RandomSelector<E>` (`RandomSelector.uniform`/`weighted`, `Weighted`/`Weigher`/`WeightedObject`) —
  uniform or weighted picks; weighted builds Vose's alias table (O(n) setup, O(1) `pick`). Immutable
  and thread-safe once built; `pick()` uses `ThreadLocalRandom`, `stream()` is an infinite sequence.

**i18n (`…common.locale`): everything user-facing is localizable.** `MessageService` resolves
`(locale, key) → Component` across `MessageSource`s with a fallback chain, `renderPlural`, hot
`reload()`. `Messages` is the static facade. **Auto-scanned — don't register by hand:** a jar's
`lang/<locale>.properties` registers on load; `plugins/Cryon/lang/` (admin override) registers first
and wins. Send via `messages.send(player, key, …)` or `messages.send(player, MessageType.ERROR, key, …)`. Missing keys
render `⟨key⟩`.

**Items (`…paper.api.item`/`…extension`):** `ItemBuilder` — name/lore (auto `<!i>`, palette-parsed),
flags, glow, `enchant`, attributes, PDC `tag`s, `meta {}`. Extensions: `Material.toItem()`,
`ItemStack.toBuilder()`/`modify {}`, `get/set/has/removeTag` (PDC), `isEmpty()`, `withAmount()`.

**Scheduling (`…paper.api.scheduler`):** `Schedulers` — `global`/`region(loc)`/`entity(e)`/`async`,
each with `*Later`/`*Timer`. Pick the scope owning the data; no Bukkit API in `async`.

**Events (`…paper.api.event`):** `Events.subscribe(Type::class.java, priority)` (or reified
`subscribe<Type>()`)`.filter{…}.handler{…}` → cancellable `Subscription`; `expireAfter(n)` self-unregisters. Handler
exceptions logged, not propagated.

**Commands — annotation framework over Paper Brigadier (`…paper.api.command`).** **Cloud is broken on
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

**Storage + transport (`…common.data`/`…common.net`).**

- `Database` (`SqlDatabase`, HikariCP) — async SQL: `query`/`update` return `CompletableFuture`s
  off-thread. No ORM. **Never `.get()` on the main thread.** Genuinely optional — `find<Database>()`,
  may be null. Client libs load at runtime via `plugin.yml` `libraries:` (Velocity shades them). Backend
  is `database.type` = `postgresql` (default), `mysql`, or embedded `h2` (a local file, host/port ignored
  — zero-setup but not shared across processes), keyed by the `SqlDialect` enum.
- `Messenger` — `publish`/`subscribe` + `request`/`handle`. String payloads. **Always registered**
  (`get<Messenger>()`): `RedisMessenger` when `redis.enabled`, else `LocalMessenger`.
- `KeyValueStore` — async KV with TTL (`set`/`get`/`delete`/`keys`/`mget`/`tryHold`), for state that
  must expire on its own (server liveness). **Always registered** (`get<KeyValueStore>()`):
  `RedisKeyValueStore` when `redis.enabled`, else `MemoryKeyValueStore`.
- `config.yml` holds `network.mode`, `database.*`, `redis.*` (both `enabled: false` by default),
  `production` (default `true`) and `modules.auto-reload` (defaults to `!production`).

**Player locale — persistent & cross-server.** `Player.resolvedLocale()` = stored override ?: client
`locale()`; all helpers use it. A chosen override (`player.setLanguage(de)`) persists to SQL +
broadcasts an invalidation; `PlayerLocaleStore` caches it in memory for sync reads. The core installs
`PlayerLocaleStore` whenever **SQL** is configured (the broadcast reaches whoever is listening, which
on a single server is just this process), else `MemoryLocaleStore` (overrides work but reset on
restart). A store is always installed; falls back to client locale without an override.

**Deployment modes — `single` and `instanced`.** A deployment is one of two shapes, declared in
`network.mode` (or `CRYON_NETWORK_MODE`) and exposed as `InstanceIdentity.mode`:

- **`single`** — this server is the whole family. A Velocity proxy still fronts it, with a static
  backend in `velocity.toml`. Redis optional.
- **`instanced`** — one of N interchangeable instances of `network.family` (10 prison shards), players
  load-balanced onto the healthiest. Redis + Postgres **required**.

**Mode and transport are orthogonal, and this is the load-bearing idea.** The mode declares *intent*;
what actually decides whether state crosses processes is `redis.enabled`. **The mode never switches
behaviour** — a second code path keyed on the mode is precisely what this design exists to delete.
It exists so the core can check intent against reality and be loud when they disagree; without it,
"I meant one server" and "I meant a pool and my Redis URI is wrong" look identical at boot.

**So `Messenger` + `KeyValueStore` are always registered** (Redis impls, else in-process ones), and
everything above them has **exactly one implementation, always present**: `ServerRegistry`,
`FeatureFlags`, `MaintenanceService`, `PlayerHandoff`. Over the in-process transport the registry
simply holds one instance — this one — which is what a single server *is*, not a degraded pool, so
`bestInstance(family)` answering "you" needs no special case. **Write feature code once; never branch
on the mode.** `LocalMessenger` echoes to the publisher and delivers on its own thread precisely so
that code behaves identically on both transports (see its KDoc — both are load-bearing).

**Two deliberate carve-outs**, both because they are *inherently* cross-process rather than merely
usually so:

- **`PlayerRouter`** — registered only with a shared transport. A transfer is performed by a proxy in
  another JVM; over loopback it could only publish into a void and report `Sent`. `find` returning
  null honestly means "there is nowhere to route", which is the truth of a single server.
- **`MaintenanceService`** — **proxy-side only**, on either transport. It is enforced where logins
  arrive, and a single-server deployment still has exactly one proxy, so its in-process state is
  already network-wide truth. Nothing on Paper reads it.

**The rule: more than one process that must share state ⇒ Redis.** Loopback does not bridge JVMs, so
several *different* `single`-mode families behind one proxy is an instanced-transport deployment, not
a single-mode one.

Validation is **loud but non-fatal** (`NetworkStatus`, `:paper`): `instanced` without Redis or without
a database, or `single` while >1 live instance shares your family, prints a banner at boot and shows
in **`/cryon network`** (mode, family, instance, transport, database, live instances, warnings).

**Network / sharding (`…common.server`).** Each running server resolves an `InstanceIdentity`
(env-first: `CRYON_NETWORK_MODE`, `CRYON_SERVER_FAMILY`, `CRYON_INSTANCE_ID`/`HOSTNAME`,
`CRYON_INSTANCE_ADDRESS`, `CRYON_INSTANCE_PORT`, else `config.yml` `network.*`, else Paper's own
values), generalizing the old static `server-name` into a `family` (the pool, and the FeatureFlags
server-scope) plus a per-process `instanceId`. Registered into the `ServiceRegistry`.

- `ServerRegistry` (`SharedServerRegistry`) — the directory of live instances. Liveness is **KV with a
  TTL** (a crashed pod's key expires; each node also runs a local reaper), synced over
  `cryon:registry:events` pub/sub into an in-memory replica so queries are non-blocking; the
  slow-changing family catalog lives in Postgres (optional). **Always registered** —
  `services.get(ServerRegistry::class)`. Only ever populates its replica **from the pub/sub echo**, so
  a `Messenger` that didn't echo to itself would leave it permanently empty.
- `InstanceReporter` (`:paper`) — `register()` publishes this server as STARTING **before** modules
  load; `ready()` flips it to READY and starts heartbeating **after** they enable, so proxies never
  route into a half-loaded server. Player count rides an `AtomicInteger` fed by join/quit, so the async
  heartbeat never touches Bukkit off-thread; sets DRAINING then deregisters on disable. Proxies never
  register themselves; they only read.
- `PlayerRouter` (`DefaultPlayerRouter`, in `:common`, runs on Paper and Velocity) — `route(uuid, family)`
  picks the least-loaded candidates and **reserves a slot atomically** (`ServerRegistry.tryReserve` →
  `KeyValueStore.tryHold`, a Lua/zset hold on Redis) before broadcasting on `cryon:routing:transfer`, so
  two proxies can't overfill one shard. The owning proxy connects the player, others no-op. A Paper
  feature never needs a proxy handle: `services.find(PlayerRouter::class)?.route(...)`.
- On Velocity, `BackendSynchronizer` registers/unregisters proxy servers off registry events and
  `TransferListener` performs the connects. Ephemeral minigame families fall back to the `Matchmaker`
  seam (interface only until a matchmaker module ships). `config.yml` adds a `network.*` block;
  `server-name` remains a legacy alias for `network.family`.
- `AgonesLifecycle` (`:paper`, `…network.agones`) — active only under an Agones sidecar (detected via
  `AGONES_SDK_HTTP_PORT`). Talks to the sidecar over REST (`AgonesClient`, JDK `HttpClient`, no gRPC):
  marks `Ready` after registration, pings `Health`, mirrors the player count to an annotation, and
  optionally reclaims an empty **persistent** shard (`shutdown-when-empty`, env-first via
  `CRYON_AGONES_SHUTDOWN_WHEN_EMPTY`, guarded to never kill the last `min-instances`). Registered into
  the `ServiceRegistry` so a matchmaker/match-end handler can call `requestShutdown()`.
- **Maintenance mode** (`…common.maintenance` `MaintenanceService`/`SharedMaintenanceService`, synced
  like FeatureFlags). Proxy-side only. `/maintenance on|off [message]` (permission `cryon.maintenance`)
  flips every proxy; `MaintenanceListener` shows the message with an unjoinable ping protocol and denies
  logins without `cryon.maintenance.bypass`. The message is a **MiniMessage** template (rendered via
  `Mini`), so it colours/formats. A command-managed **bypass allowlist** (`/maintenance add|remove|list`,
  name-based, case-insensitive) lets named players in without the permission node — persisted to
  `cryon_maintenance_allow` and synced over `cryon:maintenance:allow`, mirroring the toggle's
  write-through + broadcast.

**Player handoff — saving on quit is a bug in `instanced` (`PlayerHandoff`, `…common.server`).** A
proxy moves a player A→B by connecting B **first** and dropping A **after**
(`createConnectionRequest(…).connect()`). So B's login, and every feature load behind it, happens
*before* A's `PlayerQuitEvent` runs. A feature that saves on quit is therefore always one step behind:
B reads the previous save. **No amount of care inside the feature fixes this** — the ordering is
imposed from outside — which is why the core owns it.

The fix inverts the order: **flush before the transfer, not on quit.** Velocity's `HandoffListener`
catches `ServerPreConnectEvent`, asks the source instance to flush over
`messenger.request(HandoffCoordinator.channel(from), uuid)` — a channel named per instance, so only
the one holding the player answers — and returns an `EventTask` so the connect resumes only once it
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
(it writes state you already hold; it does not go and collect it), and must be safe **while the player
is still online** — during a handoff that is exactly the case. Return a future that completes when the
write lands: the transfer waits on it, so one that never completes stalls the player and one that
completes early defeats the point. The quit path runs at `MONITOR`, so your own quit handler has
already updated your state before the core writes it down. The **same callback** runs on quit and on
shutdown (`Cryon.flushOnlinePlayers`, before modules disable and the DB pool closes), so a single
server — where no transfer ever happens — exercises the identical code.

**Deployment** lives in `deploy/` (outside the Gradle build): per-family Paper / Velocity / Geyser
Dockerfiles + entrypoints (`images/`), baked family jar sets (`families/`), and a Helm chart
(`helm/cryon/`) of Agones Fleets + Buffer FleetAutoscalers, the proxy Deployment/Service, standalone
Geyser (UDP) + Floodgate, ConfigMaps, and allocator RBAC. See `deploy/README.md`.

**Not yet:** DI container, codegen, menu framework, coroutine bridge; the ephemeral `Matchmaker`
implementation + a k8s allocator, the Agones Counter (player-count) autoscaler, and safe drain/transfer
of populated shards on node upgrades. Add infrastructure **and document it here in the same pass.**

---

## Summary — Do / Don't

| Do                                                                | Don't                                                          |
|-------------------------------------------------------------------|----------------------------------------------------------------|
| `CommonMessages.error(…)` / `audience.sendError(…)`               | `player.sendMessage("§c§lError §7> …")`                        |
| `Mini.format("…", Placeholder…)` / `"…".mm()`                     | `MiniMessage.miniMessage()`; `"…$value…"`                      |
| `MessageService` keys for multi-language copy                     | Hardcoded English strings in features                          |
| `<!i>` in lore (or `ItemBuilder`, which does it)                  | Raw `§o` italic prefix in lore                                 |
| `ItemBuilder` / `Material.toItem()`                               | Hand-rolled `editMeta` for every item                          |
| `Schedulers.async/global/region/entity`                           | raw `Bukkit.getScheduler()`                                    |
| `Events.subscribe(...).filter{}.handler{}`                        | ad-hoc `Listener` plumbing for one handler                     |
| `PackedDecimal` for values that grow past ~1e15                   | `BigDecimal` on hot incremental-math paths                     |
| `@Command`/`@Subcommand` + `AnnotationCommands.register`          | `plugin.yml commands:` / `CommandMap` / Cloud (broken on 26.2) |
| `player.resolvedLocale()` for messages                            | `player.locale()` directly (ignores overrides)                 |
| `services.find<Database>()` (genuinely optional)                  | `get<Database>()` assuming SQL is enabled                      |
| `get<Messenger>()`/`get<KeyValueStore>()`/`get<ServerRegistry>()` | Null-checking them, or branching on the deployment mode        |
| `onFlush("…") { uuid -> … }` for player state                     | Saving player state in a quit handler (too late on a transfer) |
| `PlayerRouter.route(uuid, family)` to move players                | Hardcoding a backend server name to connect to                 |
| `find<PlayerRouter>()` — null means nowhere to route              | Assuming a route is always possible                            |
| `bestInstance(family)`                                            | Assuming a fixed server list; picking a full/STARTING instance |
| Consume the DB/Redis `CompletableFuture` async                    | `.get()` on a DB future on the main thread                     |
| Play a `Sound.*` on player-facing actions                         | Silent state changes / redeems                                 |
| `inventory.addItem` + handle overflow deliberately                | `dropItemNaturally` as the "didn't fit" path                   |
| Explicit types; `val` over `var`                                  | Java-isms; needless `var`; gratuitous `!!`                     |
| Typed lambda params (`{ event: PlayerInteractEvent ->`)           | Untyped params relying on inference where it hurts             |
| `ConcurrentHashMap` for shared static state                       | `HashMap`/`HashSet` for shared static state                    |
| `merge()` for shared-map counter increments                       | `getOrDefault + 1 + put`                                       |
| `computeIfAbsent` / `putIfAbsent`                                 | `containsKey` + `put`                                          |
| Bukkit API only on the server thread                              | Bukkit API in `runTaskAsynchronously`                          |
| Cache service/config lookups before hot loops                     | Re-resolving them inside loops                                 |
| `x shr 4` for block→chunk coords                                  | `block.chunk.x` / `block.chunk.z`                              |
| A guard/flag per distinct slice of behavior                       | One umbrella guard covering many slices                        |
| Bare flag IDs (`FISHING`)                                         | Gamemode-prefixed flag IDs (`A_FISHING`)                       |
| `[TICKET]` imperative commit titles                               | `Co-Authored-By:` trailers / emoji in commits                  |
